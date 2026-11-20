package com.project.devsphere.identity.infrastructure.mail;

import com.project.devsphere.identity.domain.VerificationCodeCrypto;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
@EnableScheduling
public class VerificationMailWorker {

    private static final String CLEANUP_SQL = """
        UPDATE background_jobs
        SET status = 'FAILED',
            finished_at = clock_timestamp(),
            last_error = 'EXPIRED_OR_EXHAUSTED',
            payload = jsonb_build_object('schemaVersion', 1),
            locked_by = NULL,
            locked_until = NULL
        WHERE job_kind = 'AUTH_VERIFY_EMAIL'
          AND finished_at IS NULL
          AND (locked_until IS NULL OR locked_until < clock_timestamp())
          AND (attempts >= 5 OR (expires_at IS NOT NULL AND expires_at <= clock_timestamp()))
        """;

    private static final String CLAIM_SQL = """
        WITH next_job AS (
            SELECT id FROM background_jobs
            WHERE job_kind = 'AUTH_VERIFY_EMAIL'
              AND finished_at IS NULL
              AND attempts < 5
              AND scheduled_at <= clock_timestamp()
              AND (expires_at IS NULL OR expires_at > clock_timestamp())
              AND (status IN ('QUEUED', 'FAILED') OR (status = 'RUNNING' AND (locked_until IS NULL OR locked_until < clock_timestamp())))
            ORDER BY scheduled_at ASC, id
            FOR UPDATE SKIP LOCKED
            LIMIT 1
        )
        UPDATE background_jobs j
        SET status = 'RUNNING',
            attempts = j.attempts + 1,
            locked_by = ?,
            locked_until = clock_timestamp() + INTERVAL '90 seconds',
            started_at = clock_timestamp()
        FROM next_job
        WHERE j.id = next_job.id
        RETURNING j.id, j.attempts, j.payload->>'challengeId' AS challenge, j.payload->>'ciphertext' AS ciphertext, j.idempotency_key
        """;

    private static final String VALIDATE_SQL = """
        SELECT t.email
        FROM auth_tokens t
        JOIN users u ON u.id = t.user_id
        JOIN background_jobs j ON j.id = ? AND j.requested_by = t.user_id
        WHERE t.id = ?
          AND t.purpose = 'VERIFY_EMAIL'
          AND t.used_at IS NULL
          AND t.expires_at > clock_timestamp()
          AND t.attempts < 5
          AND u.status = 'PENDING'
          AND u.email = t.email
          AND j.job_kind = 'AUTH_VERIFY_EMAIL'
          AND j.status = 'RUNNING'
          AND j.locked_by = ?
          AND j.locked_until > clock_timestamp()
        """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectProvider<VerificationCodeCrypto> cryptoProvider;
    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final String fromAddress;
    private final String smtpHost;

    public VerificationMailWorker(
            JdbcTemplate jdbcTemplate,
            ObjectProvider<VerificationCodeCrypto> cryptoProvider,
            ObjectProvider<JavaMailSender> mailSenderProvider,
            @Value("${devsphere.auth.mail.from:}") String fromAddress,
            @Value("${spring.mail.host:}") String smtpHost) {
        this.jdbcTemplate = jdbcTemplate;
        this.cryptoProvider = cryptoProvider;
        this.mailSenderProvider = mailSenderProvider;
        this.fromAddress = fromAddress;
        this.smtpHost = smtpHost;
    }

    @Scheduled(fixedDelayString = "${devsphere.auth.mail.poll-delay-ms:3000}",
            initialDelayString = "${devsphere.auth.mail.initial-delay-ms:3000}")
    public void runJobCycle() {
        jdbcTemplate.update(CLEANUP_SQL);

        JavaMailSender sender = mailSenderProvider.getIfAvailable();
        VerificationCodeCrypto crypto = cryptoProvider.getIfAvailable();
        if (sender == null || crypto == null || fromAddress == null || fromAddress.isBlank()
                || smtpHost == null || smtpHost.isBlank()) {
            return;
        }

        String owner = UUID.randomUUID().toString();
        List<ClaimedJob> claimed = jdbcTemplate.query(CLAIM_SQL, (rs, rowNum) -> new ClaimedJob(
                rs.getObject("id", UUID.class),
                rs.getInt("attempts"),
                rs.getString("challenge"),
                rs.getString("ciphertext"),
                rs.getString("idempotency_key")
        ), owner);

        if (claimed.isEmpty()) {
            return;
        }

        ClaimedJob job = claimed.get(0);
        if (job.challenge() == null || !job.challenge().equals(job.idempotencyKey())) {
            finishTerminal(job.id(), owner, "INVALID_CHALLENGE");
            return;
        }

        UUID challengeId;
        try {
            challengeId = UUID.fromString(job.challenge());
        } catch (IllegalArgumentException ex) {
            finishTerminal(job.id(), owner, "MALFORMED_CHALLENGE");
            return;
        }

        List<String> emails = jdbcTemplate.query(VALIDATE_SQL, (rs, rowNum) -> rs.getString("email"), job.id(), challengeId, owner);
        if (emails.isEmpty()) {
            finishTerminal(job.id(), owner, "INVALID_TOKEN");
            return;
        }

        String otp;
        try {
            otp = crypto.decrypt(challengeId, job.ciphertext());
            if (otp == null || otp.isBlank()) {
                finishTerminal(job.id(), owner, "EMPTY_DECRYPT");
                return;
            }
        } catch (IllegalArgumentException ex) {
            finishTerminal(job.id(), owner, "DECRYPT_FAILED");
            return;
        }

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(emails.get(0));
        message.setSubject("Verification Code");
        message.setText("Your verification code is: " + otp);

        try {
            sender.send(message);
            jdbcTemplate.update("""
                UPDATE background_jobs
                SET status = 'SUCCESS',
                    finished_at = clock_timestamp(),
                    payload = payload - 'ciphertext',
                    last_error = NULL,
                    locked_by = NULL,
                    locked_until = NULL
                WHERE id = ? AND status = 'RUNNING' AND job_kind = 'AUTH_VERIFY_EMAIL'
                  AND locked_by = ? AND locked_until > clock_timestamp()
                """, job.id(), owner);
        } catch (MailException ex) {
            if (job.attempts() < 5) {
                int retryDelaySec = Math.min(60, job.attempts() * 10);
                jdbcTemplate.update("""
                    UPDATE background_jobs
                    SET status = 'FAILED',
                        scheduled_at = clock_timestamp() + (? * INTERVAL '1 second'),
                        last_error = 'MAIL_SEND_ERROR',
                        locked_by = NULL,
                        locked_until = NULL
                    WHERE id = ? AND status = 'RUNNING' AND job_kind = 'AUTH_VERIFY_EMAIL'
                      AND locked_by = ? AND locked_until > clock_timestamp()
                    """, retryDelaySec, job.id(), owner);
            } else {
                finishTerminal(job.id(), owner, "MAIL_EXHAUSTED");
            }
        }
    }

    private void finishTerminal(UUID jobId, String owner, String errorCode) {
        jdbcTemplate.update("""
            UPDATE background_jobs
            SET status = 'FAILED',
                finished_at = clock_timestamp(),
                last_error = ?,
                payload = payload - 'ciphertext',
                locked_by = NULL,
                locked_until = NULL
            WHERE id = ? AND status = 'RUNNING' AND job_kind = 'AUTH_VERIFY_EMAIL'
              AND locked_by = ? AND locked_until > clock_timestamp()
            """, errorCode, jobId, owner);
    }

    private record ClaimedJob(UUID id, int attempts, String challenge, String ciphertext, String idempotencyKey) {}
}

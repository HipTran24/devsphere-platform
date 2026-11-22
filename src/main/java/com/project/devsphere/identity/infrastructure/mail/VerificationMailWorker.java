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
        WHERE job_kind IN ('AUTH_VERIFY_EMAIL', 'AUTH_RESET_PASSWORD')
          AND finished_at IS NULL
          AND (locked_until IS NULL OR locked_until < clock_timestamp())
          AND (attempts >= 5 OR (expires_at IS NOT NULL AND expires_at <= clock_timestamp()))
        """;

    private static final String CLAIM_SQL = """
        WITH next_job AS (
            SELECT id FROM background_jobs
            WHERE job_kind IN ('AUTH_VERIFY_EMAIL', 'AUTH_RESET_PASSWORD')
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
        RETURNING j.id, j.job_kind, j.attempts, j.payload->>'challengeId' AS challenge, j.payload->>'ciphertext' AS ciphertext, j.idempotency_key, j.payload::text AS raw_payload
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
    private final RecoveryMailEligibility recoveryMailEligibility;
    private final String fromAddress;
    private final String smtpHost;

    public VerificationMailWorker(
            JdbcTemplate jdbcTemplate,
            ObjectProvider<VerificationCodeCrypto> cryptoProvider,
            ObjectProvider<JavaMailSender> mailSenderProvider,
            RecoveryMailEligibility recoveryMailEligibility,
            @Value("${devsphere.auth.mail.from:}") String fromAddress,
            @Value("${spring.mail.host:}") String smtpHost) {
        this.jdbcTemplate = jdbcTemplate;
        this.cryptoProvider = cryptoProvider;
        this.mailSenderProvider = mailSenderProvider;
        this.recoveryMailEligibility = recoveryMailEligibility;
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
                rs.getString("job_kind"),
                rs.getInt("attempts"),
                rs.getString("challenge"),
                rs.getString("ciphertext"),
                rs.getString("idempotency_key"),
                rs.getString("raw_payload")
        ), owner);

        if (claimed.isEmpty()) {
            return;
        }

        ClaimedJob job = claimed.get(0);
        if ("AUTH_VERIFY_EMAIL".equals(job.jobKind())) {
            handleVerifyEmailJob(job, owner, sender, crypto);
        } else if ("AUTH_RESET_PASSWORD".equals(job.jobKind())) {
            handleResetPasswordJob(job, owner, sender, crypto);
        }
    }

    private void handleVerifyEmailJob(ClaimedJob job, String owner, JavaMailSender sender, VerificationCodeCrypto crypto) {
        if (job.challenge() == null || !job.challenge().equals(job.idempotencyKey())) {
            finishTerminal(job.id(), owner, "INVALID_CHALLENGE", "AUTH_VERIFY_EMAIL");
            return;
        }

        UUID challengeId;
        try {
            challengeId = parseCanonicalUuid(job.challenge());
        } catch (IllegalArgumentException ex) {
            finishTerminal(job.id(), owner, "MALFORMED_CHALLENGE", "AUTH_VERIFY_EMAIL");
            return;
        }

        List<String> emails = jdbcTemplate.query(VALIDATE_SQL, (rs, rowNum) -> rs.getString("email"), job.id(), challengeId, owner);
        if (emails.isEmpty()) {
            finishTerminal(job.id(), owner, "INVALID_TOKEN", "AUTH_VERIFY_EMAIL");
            return;
        }

        String otp;
        try {
            otp = crypto.decrypt(challengeId, job.ciphertext(), VerificationCodeCrypto.Purpose.VERIFY_EMAIL);
            if (otp == null || otp.isBlank()) {
                finishTerminal(job.id(), owner, "EMPTY_DECRYPT", "AUTH_VERIFY_EMAIL");
                return;
            }
        } catch (IllegalArgumentException ex) {
            finishTerminal(job.id(), owner, "DECRYPT_FAILED", "AUTH_VERIFY_EMAIL");
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
                finishTerminal(job.id(), owner, "MAIL_EXHAUSTED", "AUTH_VERIFY_EMAIL");
            }
        }
    }

    private void handleResetPasswordJob(ClaimedJob job, String owner, JavaMailSender sender, VerificationCodeCrypto crypto) {
        RecoveryMailPayload payload;
        try {
            payload = RecoveryMailPayload.parse(job.rawPayload(), job.idempotencyKey());
        } catch (IllegalArgumentException ex) {
            finishTerminal(job.id(), owner, "INVALID_PAYLOAD", "AUTH_RESET_PASSWORD");
            return;
        }

        String otp;
        try {
            otp = crypto.decrypt(payload.challengeId(), payload.ciphertext(), VerificationCodeCrypto.Purpose.RESET_PASSWORD);
            if (otp == null || otp.isBlank()) {
                finishTerminal(job.id(), owner, "EMPTY_DECRYPT", "AUTH_RESET_PASSWORD");
                return;
            }
        } catch (IllegalArgumentException ex) {
            finishTerminal(job.id(), owner, "DECRYPT_FAILED", "AUTH_RESET_PASSWORD");
            return;
        }

        RecoveryMailEligibility.BindingResult bindingResult = recoveryMailEligibility.bindAndValidate(
                job.id(),
                payload,
                owner,
                job.attempts(),
                otp
        );

        if (bindingResult.status() != RecoveryMailEligibility.Status.BOUND) {
            return;
        }

        // Immediately before send, re-check eligibility, lease, and high-water mark
        if (!recoveryMailEligibility.isEligibleToSend(job.id(), payload, owner, job.attempts(), otp)) {
            finishTerminal(job.id(), owner, "STALE_OR_INELIGIBLE", "AUTH_RESET_PASSWORD");
            return;
        }

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(bindingResult.email());
        message.setSubject("Password Recovery Code");
        message.setText("Your password recovery code is: " + otp);

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
                WHERE id = ? AND status = 'RUNNING' AND job_kind = 'AUTH_RESET_PASSWORD'
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
                    WHERE id = ? AND status = 'RUNNING' AND job_kind = 'AUTH_RESET_PASSWORD'
                      AND locked_by = ? AND locked_until > clock_timestamp()
                    """, retryDelaySec, job.id(), owner);
            } else {
                finishTerminal(job.id(), owner, "MAIL_EXHAUSTED", "AUTH_RESET_PASSWORD");
            }
        }
    }

    private void finishTerminal(UUID jobId, String owner, String errorCode, String jobKind) {
        jdbcTemplate.update("""
            UPDATE background_jobs
            SET status = 'FAILED',
                finished_at = clock_timestamp(),
                last_error = ?,
                payload = payload - 'ciphertext',
                locked_by = NULL,
                locked_until = NULL
            WHERE id = ? AND status = 'RUNNING' AND job_kind = ?
              AND locked_by = ? AND locked_until > clock_timestamp()
            """, errorCode, jobId, jobKind, owner);
    }

    private static UUID parseCanonicalUuid(String s) {
        if (s == null || s.length() != 36) {
            throw new IllegalArgumentException("Invalid UUID length");
        }
        UUID u = UUID.fromString(s);
        if (!u.toString().equalsIgnoreCase(s)) {
            throw new IllegalArgumentException("Non-canonical UUID representation");
        }
        return u;
    }

    private record ClaimedJob(
            UUID id,
            String jobKind,
            int attempts,
            String challenge,
            String ciphertext,
            String idempotencyKey,
            String rawPayload
    ) {}
}

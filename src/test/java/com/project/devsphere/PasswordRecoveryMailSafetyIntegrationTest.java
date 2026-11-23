package com.project.devsphere;

import com.project.devsphere.identity.domain.VerificationCodeCrypto;
import com.project.devsphere.identity.infrastructure.mail.RecoveryMailEligibility;
import com.project.devsphere.identity.infrastructure.mail.RecoveryMailPayload;
import com.project.devsphere.identity.infrastructure.mail.VerificationMailWorker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "devsphere.database.manage-runtime-privileges=false",
        "devsphere.auth.mail.from=devsphere@example.test",
        "devsphere.auth.mail.initial-delay-ms=600000",
        "spring.mail.host=localhost",
        "spring.mail.port=2525"
})
@Import(PasswordRecoveryIntegrationTest.TestMailConfig.class)
class PasswordRecoveryMailSafetyIntegrationTest {

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        PasswordRecoveryIntegrationTest.configure(registry);
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private JavaMailSender mailSender;
    @Autowired private VerificationCodeCrypto crypto;
    @Autowired private VerificationMailWorker worker;
    @Autowired private RecoveryMailEligibility eligibility;

    @BeforeEach
    void setup() {
        if (mailSender instanceof PasswordRecoveryIntegrationTest.CapturingMailSender capturer) {
            capturer.sent.clear();
            capturer.sendAttempts.set(0);
            capturer.lastAttempted = null;
            capturer.sendLatch = null;
            capturer.unblockLatch = null;
            capturer.failNext.set(false);
        }
        jdbc.execute("DELETE FROM background_jobs WHERE job_kind IN ('AUTH_VERIFY_EMAIL', 'AUTH_RESET_PASSWORD')");
        jdbc.execute("DELETE FROM auth_tokens WHERE purpose IN ('RESET_PASSWORD', 'RESET_PASSWORD_GRANT', 'VERIFY_EMAIL')");
    }

    private UUID addActiveVerifiedUser(String email) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users(id, email, display_name, status, email_verified_at, password_hash)
                VALUES (?, cast(? as citext), 'Active Safety User', 'ACTIVE', clock_timestamp() - INTERVAL '1 day', 'dummyArgon2idHash123')
                """, id, email);
        return id;
    }

    private record RecoveryFixture(UUID challengeId, String rawOtp, String tokenHash, String ciphertext, UUID jobId) {}

    private RecoveryFixture createRecoveryFixture(String email, String rawPayload) {
        UUID challengeId = UUID.randomUUID();
        String rawOtp = "654321";
        String tokenHash = crypto.hash(challengeId, rawOtp, VerificationCodeCrypto.Purpose.RESET_PASSWORD);
        String ciphertext = crypto.encrypt(challengeId, rawOtp, VerificationCodeCrypto.Purpose.RESET_PASSWORD);

        OffsetDateTime now = jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class);
        OffsetDateTime expiry = now.plusMinutes(10);

        jdbc.update("""
                INSERT INTO auth_tokens(id, user_id, email, purpose, token_hash, created_at, expires_at, used_at, attempts)
                VALUES (?, NULL, cast(? as citext), 'RESET_PASSWORD', ?, ?, ?, NULL, 0)
                """, challengeId, email, tokenHash, now, expiry);

        String payloadToUse = rawPayload != null ? rawPayload :
                String.format("{\"schemaVersion\":1,\"challengeId\":\"%s\",\"ciphertext\":\"%s\"}", challengeId, ciphertext);

        UUID jobId = jdbc.queryForObject("""
                INSERT INTO background_jobs(job_kind, idempotency_key, payload, expires_at, scheduled_at, status, attempts)
                VALUES ('AUTH_RESET_PASSWORD', ?, ?::jsonb, ?, ?, 'QUEUED', 0)
                RETURNING id
                """, UUID.class, challengeId.toString(), payloadToUse, expiry, now);

        return new RecoveryFixture(challengeId, rawOtp, tokenHash, ciphertext, jobId);
    }

    @Test
    void numericSchema1SendsOnce() {
        String email = "schema1-send@example.test";
        UUID userId = addActiveVerifiedUser(email);
        RecoveryFixture fixture = createRecoveryFixture(email, null);

        PasswordRecoveryIntegrationTest.CapturingMailSender capturer =
                (PasswordRecoveryIntegrationTest.CapturingMailSender) mailSender;

        worker.runJobCycle();

        assertThat(capturer.sent).hasSize(1);
        SimpleMailMessage msg = capturer.sent.get(0);
        assertThat(msg.getTo()).containsExactly(email);
        assertThat(msg.getSubject()).isEqualTo("Password Recovery Code");
        assertThat(msg.getText()).contains("Your password recovery code is: " + fixture.rawOtp());

        // Assert job completed and payload ciphertext scrubbed
        Map<String, Object> jobRow = jdbc.queryForMap(
                "SELECT status, payload, requested_by FROM background_jobs WHERE id = ?", fixture.jobId()
        );
        assertThat(jobRow.get("status")).isEqualTo("SUCCESS");
        assertThat(jobRow.get("payload").toString()).doesNotContain("ciphertext");
        assertThat(jobRow.get("requested_by")).isEqualTo(userId);

        // Assert token bound
        UUID boundUser = jdbc.queryForObject(
                "SELECT user_id FROM auth_tokens WHERE id = ?", UUID.class, fixture.challengeId()
        );
        assertThat(boundUser).isEqualTo(userId);

        // Second cycle does not send a duplicate email
        worker.runJobCycle();
        assertThat(capturer.sent).hasSize(1);
    }

    @Test
    void preDecryptShapeErrorsRejectWithoutTransportAndSetInvalidPayloadReason() {
        PasswordRecoveryIntegrationTest.CapturingMailSender capturer =
                (PasswordRecoveryIntegrationTest.CapturingMailSender) mailSender;

        String[] malformedPayloads = {
                // String schemaVersion
                "{\"schemaVersion\":\"1\",\"challengeId\":\"%s\",\"ciphertext\":\"%s\"}",
                // Null schemaVersion
                "{\"schemaVersion\":null,\"challengeId\":\"%s\",\"ciphertext\":\"%s\"}",
                // Boolean schemaVersion
                "{\"schemaVersion\":true,\"challengeId\":\"%s\",\"ciphertext\":\"%s\"}",
                // Fractional schemaVersion
                "{\"schemaVersion\":1.5,\"challengeId\":\"%s\",\"ciphertext\":\"%s\"}",
                // Extra field
                "{\"schemaVersion\":1,\"challengeId\":\"%s\",\"ciphertext\":\"%s\",\"extra\":123}",
                // Missing ciphertext
                "{\"schemaVersion\":1,\"challengeId\":\"%s\"}",
                // Uppercase challengeId
                "{\"schemaVersion\":1,\"challengeId\":\"UPPERCASE\",\"ciphertext\":\"%s\"}",
                // Malformed ciphertext characters
                "{\"schemaVersion\":1,\"challengeId\":\"%s\",\"ciphertext\":\"!@#$%%^&*()_+\"}"
        };

        for (int i = 0; i < malformedPayloads.length; i++) {
            String email = "shape-err-" + i + "@example.test";
            addActiveVerifiedUser(email);

            UUID challengeId = UUID.randomUUID();
            String ciphertext = crypto.encrypt(challengeId, "123456", VerificationCodeCrypto.Purpose.RESET_PASSWORD);
            OffsetDateTime now = jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class);

            jdbc.update("""
                    INSERT INTO auth_tokens(id, user_id, email, purpose, token_hash, created_at, expires_at, used_at, attempts)
                    VALUES (?, NULL, cast(? as citext), 'RESET_PASSWORD', 'dummyhash', ?, ? + INTERVAL '10 minutes', NULL, 0)
                    """, challengeId, email, now, now);

            String rawTemplate = malformedPayloads[i];
            String payloadJson;
            if (rawTemplate.contains("UPPERCASE")) {
                payloadJson = String.format(rawTemplate.replace("UPPERCASE", challengeId.toString().toUpperCase()), ciphertext);
            } else if (rawTemplate.contains("%s") && rawTemplate.indexOf("%s") == rawTemplate.lastIndexOf("%s")) {
                payloadJson = String.format(rawTemplate, challengeId);
            } else {
                payloadJson = String.format(rawTemplate, challengeId, ciphertext);
            }

            UUID jobId = jdbc.queryForObject("""
                    INSERT INTO background_jobs(job_kind, idempotency_key, payload, expires_at, scheduled_at, status, attempts)
                    VALUES ('AUTH_RESET_PASSWORD', ?, ?::jsonb, ? + INTERVAL '10 minutes', ?, 'QUEUED', 0)
                    RETURNING id
                    """, UUID.class, challengeId.toString(), payloadJson, now, now);

            worker.runJobCycle();

            // Zero transport calls
            assertThat(capturer.sendAttempts.get()).isZero();
            assertThat(capturer.sent).isEmpty();

            // Sanitize terminal failure
            Map<String, Object> jobRow = jdbc.queryForMap(
                    "SELECT status, last_error, payload, finished_at FROM background_jobs WHERE id = ?", jobId
            );
            assertThat(jobRow.get("status")).isEqualTo("FAILED");
            assertThat(jobRow.get("last_error")).isEqualTo("INVALID_PAYLOAD");
            assertThat(jobRow.get("finished_at")).isNotNull();
            assertThat(jobRow.get("payload").toString()).doesNotContain("ciphertext");
        }
    }

    @Test
    void distinguishesPreDecryptShapeErrorFromDecryptError() {
        PasswordRecoveryIntegrationTest.CapturingMailSender capturer =
                (PasswordRecoveryIntegrationTest.CapturingMailSender) mailSender;

        // Shape error -> INVALID_PAYLOAD
        String email1 = "distinguish-shape@example.test";
        addActiveVerifiedUser(email1);
        UUID challenge1 = UUID.randomUUID();
        OffsetDateTime now = jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class);
        jdbc.update("""
                INSERT INTO auth_tokens(id, user_id, email, purpose, token_hash, created_at, expires_at)
                VALUES (?, NULL, cast(? as citext), 'RESET_PASSWORD', 'hash1', ?, ? + INTERVAL '10 minutes')
                """, challenge1, email1, now, now);

        String shapeBadPayload = String.format("{\"schemaVersion\":1,\"challengeId\":\"%s\",\"ciphertext\":\"ABC\",\"extra\":\"bad\"}", challenge1);
        UUID job1 = jdbc.queryForObject("""
                INSERT INTO background_jobs(job_kind, idempotency_key, payload, expires_at, scheduled_at, status, attempts)
                VALUES ('AUTH_RESET_PASSWORD', ?, ?::jsonb, ? + INTERVAL '10 minutes', ?, 'QUEUED', 0)
                RETURNING id
                """, UUID.class, challenge1.toString(), shapeBadPayload, now, now);

        worker.runJobCycle();

        assertThat(capturer.sendAttempts.get()).isZero();
        Map<String, Object> jobRow1 = jdbc.queryForMap("SELECT status, last_error FROM background_jobs WHERE id = ?", job1);
        assertThat(jobRow1.get("status")).isEqualTo("FAILED");
        assertThat(jobRow1.get("last_error")).isEqualTo("INVALID_PAYLOAD");

        // Decrypt error -> DECRYPT_FAILED
        // Valid 46-char Base64URL string (34 bytes), valid shape, but corrupted GCM tag
        String email2 = "distinguish-decrypt@example.test";
        addActiveVerifiedUser(email2);
        UUID challenge2 = UUID.randomUUID();
        String validEncrypted = crypto.encrypt(challenge2, "123456", VerificationCodeCrypto.Purpose.RESET_PASSWORD);
        byte[] corruptedBytes = Base64.getUrlDecoder().decode(validEncrypted);
        corruptedBytes[corruptedBytes.length - 1] ^= 0x55;
        String corruptedCiphertext = Base64.getUrlEncoder().withoutPadding().encodeToString(corruptedBytes);

        jdbc.update("""
                INSERT INTO auth_tokens(id, user_id, email, purpose, token_hash, created_at, expires_at)
                VALUES (?, NULL, cast(? as citext), 'RESET_PASSWORD', 'hash2', ?, ? + INTERVAL '10 minutes')
                """, challenge2, email2, now, now);

        String decryptBadPayload = String.format("{\"schemaVersion\":1,\"challengeId\":\"%s\",\"ciphertext\":\"%s\"}",
                challenge2, corruptedCiphertext);
        UUID job2 = jdbc.queryForObject("""
                INSERT INTO background_jobs(job_kind, idempotency_key, payload, expires_at, scheduled_at, status, attempts)
                VALUES ('AUTH_RESET_PASSWORD', ?, ?::jsonb, ? + INTERVAL '10 minutes', ?, 'QUEUED', 0)
                RETURNING id
                """, UUID.class, challenge2.toString(), decryptBadPayload, now, now);

        worker.runJobCycle();

        assertThat(capturer.sendAttempts.get()).isZero();
        Map<String, Object> jobRow2 = jdbc.queryForMap("SELECT status, last_error FROM background_jobs WHERE id = ?", job2);
        assertThat(jobRow2.get("status")).isEqualTo("FAILED");
        assertThat(jobRow2.get("last_error")).isEqualTo("DECRYPT_FAILED");
    }

    @Test
    void persistedPayloadMutationBetweenClaimAndBindingOrPreSendRejectsStaleSend() {
        String email = "mutate-payload@example.test";
        UUID userId = addActiveVerifiedUser(email);
        RecoveryFixture fixture = createRecoveryFixture(email, null);

        // Parse valid claimed payload
        String rawClaimed = jdbc.queryForObject(
                "SELECT payload::text FROM background_jobs WHERE id = ?", String.class, fixture.jobId()
        );
        RecoveryMailPayload claimedPayload = RecoveryMailPayload.parse(rawClaimed, fixture.challengeId().toString());

        // Mutate payload in DB before binding
        String owner = UUID.randomUUID().toString();
        jdbc.update("""
                UPDATE background_jobs
                SET status = 'RUNNING', locked_by = ?, locked_until = clock_timestamp() + INTERVAL '90 seconds', attempts = 1,
                    payload = jsonb_build_object('schemaVersion', 1, 'challengeId', cast(? as text), 'ciphertext', 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrsg')
                WHERE id = ?
                """, owner, fixture.challengeId().toString(), fixture.jobId());

        // bindAndValidate detects mutation
        RecoveryMailEligibility.BindingResult res = eligibility.bindAndValidate(
                fixture.jobId(),
                claimedPayload,
                owner,
                1,
                fixture.rawOtp()
        );
        assertThat(res.status()).isEqualTo(RecoveryMailEligibility.Status.TERMINAL);
        assertThat(res.errorCode()).isEqualTo("INVALID_PAYLOAD");

        // Now test mutation between binding and pre-send
        // Re-create pristine fixture
        setup();
        fixture = createRecoveryFixture(email, null);
        rawClaimed = jdbc.queryForObject(
                "SELECT payload::text FROM background_jobs WHERE id = ?", String.class, fixture.jobId()
        );
        claimedPayload = RecoveryMailPayload.parse(rawClaimed, fixture.challengeId().toString());

        String owner2 = UUID.randomUUID().toString();
        jdbc.update("""
                UPDATE background_jobs
                SET status = 'RUNNING', locked_by = ?, locked_until = clock_timestamp() + INTERVAL '90 seconds', attempts = 1
                WHERE id = ?
                """, owner2, fixture.jobId());

        // Successfully bind
        RecoveryMailEligibility.BindingResult bindRes = eligibility.bindAndValidate(
                fixture.jobId(),
                claimedPayload,
                owner2,
                1,
                fixture.rawOtp()
        );
        assertThat(bindRes.status()).isEqualTo(RecoveryMailEligibility.Status.BOUND);

        // Before pre-send, mutate persisted payload
        jdbc.update("""
                UPDATE background_jobs
                SET payload = jsonb_build_object('schemaVersion', 1, 'challengeId', cast(? as text), 'ciphertext', 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrsg')
                WHERE id = ?
                """, fixture.challengeId().toString(), fixture.jobId());

        // isEligibleToSend returns false
        boolean canSend = eligibility.isEligibleToSend(
                fixture.jobId(),
                claimedPayload,
                owner2,
                1,
                fixture.rawOtp()
        );
        assertThat(canSend).isFalse();
    }

    @Test
    void tokenJobMismatchedExpiryRejectsBothBindingAndPreSendPreservingLeaseFence() {
        String email = "expiry-mismatch@example.test";
        UUID userId = addActiveVerifiedUser(email);

        UUID challengeId = UUID.randomUUID();
        String rawOtp = "123456";
        String tokenHash = crypto.hash(challengeId, rawOtp, VerificationCodeCrypto.Purpose.RESET_PASSWORD);
        String ciphertext = crypto.encrypt(challengeId, rawOtp, VerificationCodeCrypto.Purpose.RESET_PASSWORD);

        OffsetDateTime now = jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class);
        OffsetDateTime tokenExpiry = now.plusMinutes(10);
        OffsetDateTime jobExpiry = now.plusMinutes(8); // Mismatched!

        jdbc.update("""
                INSERT INTO auth_tokens(id, user_id, email, purpose, token_hash, created_at, expires_at)
                VALUES (?, NULL, cast(? as citext), 'RESET_PASSWORD', ?, ?, ?)
                """, challengeId, email, tokenHash, now, tokenExpiry);

        String payloadJson = String.format("{\"schemaVersion\":1,\"challengeId\":\"%s\",\"ciphertext\":\"%s\"}",
                challengeId, ciphertext);

        UUID jobId = jdbc.queryForObject("""
                INSERT INTO background_jobs(job_kind, idempotency_key, payload, expires_at, scheduled_at, status, attempts)
                VALUES ('AUTH_RESET_PASSWORD', ?, ?::jsonb, ?, ?, 'QUEUED', 0)
                RETURNING id
                """, UUID.class, challengeId.toString(), payloadJson, jobExpiry, now);

        String owner = UUID.randomUUID().toString();
        jdbc.update("""
                UPDATE background_jobs
                SET status = 'RUNNING', locked_by = ?, locked_until = clock_timestamp() + INTERVAL '90 seconds', attempts = 1
                WHERE id = ?
                """, owner, jobId);

        String rawClaimed = jdbc.queryForObject(
                "SELECT payload::text FROM background_jobs WHERE id = ?", String.class, jobId
        );
        RecoveryMailPayload payload = RecoveryMailPayload.parse(rawClaimed, challengeId.toString());

        // Binding fails with EXPIRY_MISMATCH
        RecoveryMailEligibility.BindingResult res = eligibility.bindAndValidate(
                jobId,
                payload,
                owner,
                1,
                rawOtp
        );
        assertThat(res.status()).isEqualTo(RecoveryMailEligibility.Status.TERMINAL);
        assertThat(res.errorCode()).isEqualTo("EXPIRY_MISMATCH");

        // Job marked FAILED and ciphertext scrubbed
        Map<String, Object> jobRow = jdbc.queryForMap("SELECT status, last_error, payload FROM background_jobs WHERE id = ?", jobId);
        assertThat(jobRow.get("status")).isEqualTo("FAILED");
        assertThat(jobRow.get("last_error")).isEqualTo("EXPIRY_MISMATCH");
        assertThat(jobRow.get("payload").toString()).doesNotContain("ciphertext");

        // Isolated pre-send fixture: bound + RUNNING with matching expiry control
        PasswordRecoveryIntegrationTest.CapturingMailSender capturer =
                (PasswordRecoveryIntegrationTest.CapturingMailSender) mailSender;
        capturer.sent.clear();
        capturer.sendAttempts.set(0);

        String preSendEmail = "expiry-presend@example.test";
        UUID preSendUserId = addActiveVerifiedUser(preSendEmail);
        UUID preSendChallengeId = UUID.randomUUID();
        String preSendOtp = "654321";
        String preSendTokenHash = crypto.hash(preSendChallengeId, preSendOtp, VerificationCodeCrypto.Purpose.RESET_PASSWORD);
        String preSendCiphertext = crypto.encrypt(preSendChallengeId, preSendOtp, VerificationCodeCrypto.Purpose.RESET_PASSWORD);

        OffsetDateTime preSendNow = jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class);
        OffsetDateTime matchingExpiry = preSendNow.plusMinutes(10);

        jdbc.update("""
                INSERT INTO auth_tokens(id, user_id, email, purpose, token_hash, created_at, expires_at, used_at, attempts)
                VALUES (?, ?, cast(? as citext), 'RESET_PASSWORD', ?, ?, ?, NULL, 0)
                """, preSendChallengeId, preSendUserId, preSendEmail, preSendTokenHash, preSendNow, matchingExpiry);

        String preSendPayloadJson = String.format("{\"schemaVersion\":1,\"challengeId\":\"%s\",\"ciphertext\":\"%s\"}",
                preSendChallengeId, preSendCiphertext);

        String preSendOwner = UUID.randomUUID().toString();
        UUID preSendJobId = jdbc.queryForObject("""
                INSERT INTO background_jobs(job_kind, idempotency_key, payload, expires_at, scheduled_at, status, attempts, locked_by, locked_until, requested_by)
                VALUES ('AUTH_RESET_PASSWORD', ?, ?::jsonb, ?, ?, 'RUNNING', 1, ?, clock_timestamp() + INTERVAL '90 seconds', ?)
                RETURNING id
                """, UUID.class, preSendChallengeId.toString(), preSendPayloadJson, matchingExpiry, preSendNow, preSendOwner, preSendUserId);

        String rawPreSendClaimed = jdbc.queryForObject(
                "SELECT payload::text FROM background_jobs WHERE id = ?", String.class, preSendJobId
        );
        RecoveryMailPayload preSendPayload = RecoveryMailPayload.parse(rawPreSendClaimed, preSendChallengeId.toString());

        // Establish matching-expiry positive pre-send control
        boolean positivePreSend = eligibility.isEligibleToSend(
                preSendJobId,
                preSendPayload,
                preSendOwner,
                1,
                preSendOtp
        );
        assertThat(positivePreSend).isTrue();

        // Mismatched expiry rejected
        jdbc.update("UPDATE background_jobs SET expires_at = ? WHERE id = ?", matchingExpiry.minusMinutes(2), preSendJobId);

        boolean mismatchedPreSend = eligibility.isEligibleToSend(
                preSendJobId,
                preSendPayload,
                preSendOwner,
                1,
                preSendOtp
        );
        assertThat(mismatchedPreSend).isFalse();

        // No transport sent and job/token/lease unchanged
        assertThat(capturer.sendAttempts.get()).isZero();
        assertThat(capturer.sent).isEmpty();

        Map<String, Object> jobAfter = jdbc.queryForMap(
                "SELECT status, locked_by, attempts, requested_by FROM background_jobs WHERE id = ?", preSendJobId
        );
        assertThat(jobAfter.get("status")).isEqualTo("RUNNING");
        assertThat(jobAfter.get("locked_by")).isEqualTo(preSendOwner);
        assertThat(((Number) jobAfter.get("attempts")).intValue()).isEqualTo(1);
        assertThat(jobAfter.get("requested_by")).isEqualTo(preSendUserId);

        Map<String, Object> tokenAfter = jdbc.queryForMap(
                "SELECT used_at, attempts, user_id FROM auth_tokens WHERE id = ?", preSendChallengeId
        );
        assertThat(tokenAfter.get("used_at")).isNull();
        assertThat(((Number) tokenAfter.get("attempts")).intValue()).isEqualTo(0);
        assertThat(tokenAfter.get("user_id")).isEqualTo(preSendUserId);
    }

    @Test
    void fiveSmtpFailuresExhaustAttemptsAndScrubCiphertextWithIncreasingBackoff() {
        String email = "five-failures@example.test";
        addActiveVerifiedUser(email);
        RecoveryFixture fixture = createRecoveryFixture(email, null);

        PasswordRecoveryIntegrationTest.CapturingMailSender capturer =
                (PasswordRecoveryIntegrationTest.CapturingMailSender) mailSender;

        int[] expectedDelays = {10, 20, 30, 40};

        for (int i = 0; i < 4; i++) {
            capturer.failNext.set(true);
            worker.runJobCycle();

            assertThat(capturer.sendAttempts.get()).isEqualTo(i + 1);

            Map<String, Object> job = jdbc.queryForMap(
                    "SELECT status, attempts, last_error, scheduled_at FROM background_jobs WHERE id = ?", fixture.jobId()
            );
            assertThat(job.get("status")).isEqualTo("FAILED");
            assertThat(((Number) job.get("attempts")).intValue()).isEqualTo(i + 1);
            assertThat(job.get("last_error")).isEqualTo("MAIL_SEND_ERROR");

            // Assert retry backoff is increasing and respects cap (<= 60s)
            Double delaySec = jdbc.queryForObject(
                    "SELECT EXTRACT(EPOCH FROM (scheduled_at - clock_timestamp())) FROM background_jobs WHERE id = ?",
                    Double.class, fixture.jobId()
            );
            assertThat(delaySec).isNotNull();
            assertThat(delaySec).isBetween(expectedDelays[i] - 5.0, expectedDelays[i] + 5.0);
            assertThat(expectedDelays[i]).isLessThanOrEqualTo(60);
            if (i > 0) {
                assertThat(expectedDelays[i]).isGreaterThan(expectedDelays[i - 1]);
            }

            // Advance scheduled_at for next cycle
            jdbc.update("UPDATE background_jobs SET scheduled_at = clock_timestamp() - INTERVAL '1 second' WHERE id = ?", fixture.jobId());
        }

        // 5th attempt fails -> terminal MAIL_EXHAUSTED and ciphertext scrubbed
        capturer.failNext.set(true);
        worker.runJobCycle();
        assertThat(capturer.sendAttempts.get()).isEqualTo(5);

        Map<String, Object> finalJob = jdbc.queryForMap(
                "SELECT status, attempts, last_error, finished_at, payload FROM background_jobs WHERE id = ?", fixture.jobId()
        );
        assertThat(finalJob.get("status")).isEqualTo("FAILED");
        assertThat(((Number) finalJob.get("attempts")).intValue()).isEqualTo(5);
        assertThat(finalJob.get("last_error")).isEqualTo("MAIL_EXHAUSTED");
        assertThat(finalJob.get("finished_at")).isNotNull();
        assertThat(finalJob.get("payload").toString()).doesNotContain("ciphertext");

        // Advance scheduled_at again to prove terminal cycle sends no 6th attempt
        jdbc.update("UPDATE background_jobs SET scheduled_at = clock_timestamp() - INTERVAL '1 second' WHERE id = ?", fixture.jobId());
        worker.runJobCycle();
        assertThat(capturer.sendAttempts.get()).isEqualTo(5);
    }

    @Test
    void staleLeaseOwnerCannotMutateNewerOwnersJobOrSend() {
        String email = "stale-lease@example.test";
        UUID userId = addActiveVerifiedUser(email);
        RecoveryFixture fixture = createRecoveryFixture(email, null);

        // Stale owner claims job
        String staleOwner = UUID.randomUUID().toString();
        jdbc.update("""
                UPDATE background_jobs
                SET status = 'RUNNING', locked_by = ?, locked_until = clock_timestamp() - INTERVAL '1 second', attempts = 1
                WHERE id = ?
                """, staleOwner, fixture.jobId());

        // Newer owner steals/claims expired lease
        String newerOwner = UUID.randomUUID().toString();
        jdbc.update("""
                UPDATE background_jobs
                SET status = 'RUNNING', locked_by = ?, locked_until = clock_timestamp() + INTERVAL '90 seconds', attempts = 2
                WHERE id = ?
                """, newerOwner, fixture.jobId());

        String rawClaimed = jdbc.queryForObject(
                "SELECT payload::text FROM background_jobs WHERE id = ?", String.class, fixture.jobId()
        );
        RecoveryMailPayload payload = RecoveryMailPayload.parse(rawClaimed, fixture.challengeId().toString());

        // Stale owner attempts binding -> ABORT LOST_LEASE
        RecoveryMailEligibility.BindingResult res = eligibility.bindAndValidate(
                fixture.jobId(),
                payload,
                staleOwner,
                1,
                fixture.rawOtp()
        );
        assertThat(res.status()).isEqualTo(RecoveryMailEligibility.Status.ABORT);
        assertThat(res.errorCode()).isEqualTo("LOST_LEASE");

        // Stale owner's pre-send check also returns false
        boolean canSend = eligibility.isEligibleToSend(
                fixture.jobId(),
                payload,
                staleOwner,
                1,
                fixture.rawOtp()
        );
        assertThat(canSend).isFalse();

        // Verify newer owner's job state remained completely unmutated
        Map<String, Object> job = jdbc.queryForMap(
                "SELECT status, locked_by, attempts, requested_by FROM background_jobs WHERE id = ?", fixture.jobId()
        );
        assertThat(job.get("status")).isEqualTo("RUNNING");
        assertThat(job.get("locked_by")).isEqualTo(newerOwner);
        assertThat(((Number) job.get("attempts")).intValue()).isEqualTo(2);
        assertThat(job.get("requested_by")).isNull();
    }

    @Test
    void highWaterMarkProvesNewerUnboundDoesNotDisplaceOlderEligibleBinding() {
        String email = "unbound-highwater@example.test";
        UUID userId = addActiveVerifiedUser(email);

        // 1. Older challenge (2 minutes ago)
        UUID olderChallengeId = UUID.randomUUID();
        String olderOtp = "111111";
        String olderHash = crypto.hash(olderChallengeId, olderOtp, VerificationCodeCrypto.Purpose.RESET_PASSWORD);
        String olderCipher = crypto.encrypt(olderChallengeId, olderOtp, VerificationCodeCrypto.Purpose.RESET_PASSWORD);
        OffsetDateTime now = jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class);
        OffsetDateTime olderTime = now.minusMinutes(2);

        jdbc.update("""
                INSERT INTO auth_tokens(id, user_id, email, purpose, token_hash, created_at, expires_at)
                VALUES (?, NULL, cast(? as citext), 'RESET_PASSWORD', ?, ?, ? + INTERVAL '10 minutes')
                """, olderChallengeId, email, olderHash, olderTime, olderTime);

        String olderPayload = String.format("{\"schemaVersion\":1,\"challengeId\":\"%s\",\"ciphertext\":\"%s\"}",
                olderChallengeId, olderCipher);

        UUID olderJobId = jdbc.queryForObject("""
                INSERT INTO background_jobs(job_kind, idempotency_key, payload, expires_at, scheduled_at, status, attempts)
                VALUES ('AUTH_RESET_PASSWORD', ?, ?::jsonb, ? + INTERVAL '10 minutes', ?, 'QUEUED', 0)
                RETURNING id
                """, UUID.class, olderChallengeId.toString(), olderPayload, olderTime, olderTime);

        // 2. Newer challenge (1 minute ago), strictly UNBOUND (user_id IS NULL)
        UUID newerChallengeId = UUID.randomUUID();
        String newerOtp = "222222";
        String newerHash = crypto.hash(newerChallengeId, newerOtp, VerificationCodeCrypto.Purpose.RESET_PASSWORD);
        OffsetDateTime newerTime = now.minusMinutes(1);

        jdbc.update("""
                INSERT INTO auth_tokens(id, user_id, email, purpose, token_hash, created_at, expires_at)
                VALUES (?, NULL, cast(? as citext), 'RESET_PASSWORD', ?, ?, ? + INTERVAL '10 minutes')
                """, newerChallengeId, email, newerHash, newerTime, newerTime);

        // Run worker cycle: older job is claimed (scheduled earlier) and MUST successfully bind and send
        PasswordRecoveryIntegrationTest.CapturingMailSender capturer =
                (PasswordRecoveryIntegrationTest.CapturingMailSender) mailSender;

        worker.runJobCycle();

        assertThat(capturer.sent).hasSize(1);
        SimpleMailMessage sentMsg = capturer.sent.get(0);
        assertThat(sentMsg.getText()).contains("Your password recovery code is: " + olderOtp);

        // Older challenge was bound to user; newer unbound token did NOT supersede it
        UUID olderBoundUser = jdbc.queryForObject(
                "SELECT user_id FROM auth_tokens WHERE id = ?", UUID.class, olderChallengeId
        );
        assertThat(olderBoundUser).isEqualTo(userId);

        UUID newerBoundUser = jdbc.queryForObject(
                "SELECT user_id FROM auth_tokens WHERE id = ?", UUID.class, newerChallengeId
        );
        assertThat(newerBoundUser).isNull();
    }

    @Test
    void highWaterMarkProvesNewerBoundTokenPreventsOlderJobFromRevivingOrSending() {
        String email = "bound-highwater@example.test";
        UUID userId = addActiveVerifiedUser(email);

        OffsetDateTime now = jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class);
        OffsetDateTime olderTime = now.minusMinutes(3);
        OffsetDateTime newerTime = now.minusMinutes(1);

        // 1. Older challenge (3 minutes ago), unbound with delayed/queued job
        UUID olderChallengeId = UUID.randomUUID();
        String olderOtp = "333333";
        String olderHash = crypto.hash(olderChallengeId, olderOtp, VerificationCodeCrypto.Purpose.RESET_PASSWORD);
        String olderCipher = crypto.encrypt(olderChallengeId, olderOtp, VerificationCodeCrypto.Purpose.RESET_PASSWORD);

        jdbc.update("""
                INSERT INTO auth_tokens(id, user_id, email, purpose, token_hash, created_at, expires_at)
                VALUES (?, NULL, cast(? as citext), 'RESET_PASSWORD', ?, ?, ? + INTERVAL '10 minutes')
                """, olderChallengeId, email, olderHash, olderTime, olderTime);

        String olderPayload = String.format("{\"schemaVersion\":1,\"challengeId\":\"%s\",\"ciphertext\":\"%s\"}",
                olderChallengeId, olderCipher);

        UUID olderJobId = jdbc.queryForObject("""
                INSERT INTO background_jobs(job_kind, idempotency_key, payload, expires_at, scheduled_at, status, attempts)
                VALUES ('AUTH_RESET_PASSWORD', ?, ?::jsonb, ? + INTERVAL '10 minutes', ?, 'QUEUED', 0)
                RETURNING id
                """, UUID.class, olderChallengeId.toString(), olderPayload, olderTime, olderTime);

        // 2. Newer challenge (1 minute ago), already BOUND to userId and CONSUMED
        UUID newerChallengeId = UUID.randomUUID();
        String newerHash = crypto.hash(newerChallengeId, "444444", VerificationCodeCrypto.Purpose.RESET_PASSWORD);

        jdbc.update("""
                INSERT INTO auth_tokens(id, user_id, email, purpose, token_hash, created_at, expires_at, used_at)
                VALUES (?, ?, cast(? as citext), 'RESET_PASSWORD', ?, ?, ? + INTERVAL '10 minutes', clock_timestamp())
                """, newerChallengeId, userId, email, newerHash, newerTime, newerTime);

        // Process older job: high-water mark detects newer bound token for user -> rejects
        PasswordRecoveryIntegrationTest.CapturingMailSender capturer =
                (PasswordRecoveryIntegrationTest.CapturingMailSender) mailSender;

        worker.runJobCycle();

        assertThat(capturer.sent).isEmpty();
        assertThat(capturer.sendAttempts.get()).isZero();

        Map<String, Object> job = jdbc.queryForMap(
                "SELECT status, last_error, payload FROM background_jobs WHERE id = ?", olderJobId
        );
        assertThat(job.get("status")).isEqualTo("FAILED");
        assertThat(job.get("last_error")).isEqualTo("SUPERSEDED_BY_NEWER_CHALLENGE");
        assertThat(job.get("payload").toString()).doesNotContain("ciphertext");
    }

    @Test
    void newerExpiredTokenPreservesTtlOrderAndAssertsNoRevival() {
        String email = "ttl-order@example.test";
        UUID userId = addActiveVerifiedUser(email);

        OffsetDateTime now = jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class);
        // With equal 10-minute TTL, when newer token (issued 11 min ago) is expired,
        // older token (issued 14 min ago) is also expired.
        OffsetDateTime olderCreated = now.minusMinutes(14);
        OffsetDateTime olderExpiry = olderCreated.plusMinutes(10); // Expired 4 min ago

        OffsetDateTime newerCreated = now.minusMinutes(11);
        OffsetDateTime newerExpiry = newerCreated.plusMinutes(10); // Expired 1 min ago

        UUID olderChallengeId = UUID.randomUUID();
        String olderOtp = "555555";
        String olderHash = crypto.hash(olderChallengeId, olderOtp, VerificationCodeCrypto.Purpose.RESET_PASSWORD);
        String olderCipher = crypto.encrypt(olderChallengeId, olderOtp, VerificationCodeCrypto.Purpose.RESET_PASSWORD);

        jdbc.update("""
                INSERT INTO auth_tokens(id, user_id, email, purpose, token_hash, created_at, expires_at)
                VALUES (?, NULL, cast(? as citext), 'RESET_PASSWORD', ?, ?, ?)
                """, olderChallengeId, email, olderHash, olderCreated, olderExpiry);

        String olderPayload = String.format("{\"schemaVersion\":1,\"challengeId\":\"%s\",\"ciphertext\":\"%s\"}",
                olderChallengeId, olderCipher);

        UUID olderJobId = jdbc.queryForObject("""
                INSERT INTO background_jobs(job_kind, idempotency_key, payload, expires_at, scheduled_at, status, attempts)
                VALUES ('AUTH_RESET_PASSWORD', ?, ?::jsonb, ?, ?, 'QUEUED', 0)
                RETURNING id
                """, UUID.class, olderChallengeId.toString(), olderPayload, olderExpiry, olderCreated);

        // Newer expired bound token
        UUID newerChallengeId = UUID.randomUUID();
        String newerHash = crypto.hash(newerChallengeId, "666666", VerificationCodeCrypto.Purpose.RESET_PASSWORD);
        jdbc.update("""
                INSERT INTO auth_tokens(id, user_id, email, purpose, token_hash, created_at, expires_at)
                VALUES (?, ?, cast(? as citext), 'RESET_PASSWORD', ?, ?, ?)
                """, newerChallengeId, userId, email, newerHash, newerCreated, newerExpiry);

        PasswordRecoveryIntegrationTest.CapturingMailSender capturer =
                (PasswordRecoveryIntegrationTest.CapturingMailSender) mailSender;

        worker.runJobCycle();

        assertThat(capturer.sent).isEmpty();
        assertThat(capturer.sendAttempts.get()).isZero();

        Map<String, Object> job = jdbc.queryForMap(
                "SELECT status, last_error, payload FROM background_jobs WHERE id = ?", olderJobId
        );
        assertThat(job.get("status")).isEqualTo("FAILED");
        assertThat(job.get("last_error")).isIn("EXPIRED_OR_EXHAUSTED", "JOB_EXPIRED", "TOKEN_UNUSABLE");
        assertThat(job.get("payload").toString()).doesNotContain("ciphertext");
    }

    @Test
    void nullKeyJobTerminatesInvalidPayloadWithoutTransportOrBinding() {
        String email = "null-key-job@example.test";
        UUID userId = addActiveVerifiedUser(email);

        UUID challengeId = UUID.randomUUID();
        String rawOtp = "123456";
        String tokenHash = crypto.hash(challengeId, rawOtp, VerificationCodeCrypto.Purpose.RESET_PASSWORD);
        String ciphertext = crypto.encrypt(challengeId, rawOtp, VerificationCodeCrypto.Purpose.RESET_PASSWORD);

        OffsetDateTime now = jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class);
        OffsetDateTime expiry = now.plusMinutes(10);

        jdbc.update("""
                INSERT INTO auth_tokens(id, user_id, email, purpose, token_hash, created_at, expires_at, used_at, attempts)
                VALUES (?, NULL, cast(? as citext), 'RESET_PASSWORD', ?, ?, ?, NULL, 0)
                """, challengeId, email, tokenHash, now, expiry);

        String payloadJson = String.format("{\"schemaVersion\":1,\"challengeId\":\"%s\",\"ciphertext\":\"%s\"}",
                challengeId, ciphertext);

        UUID jobId = jdbc.queryForObject("""
                INSERT INTO background_jobs(job_kind, idempotency_key, payload, expires_at, scheduled_at, status, attempts)
                VALUES ('AUTH_RESET_PASSWORD', NULL, ?::jsonb, ?, ?, 'QUEUED', 0)
                RETURNING id
                """, UUID.class, payloadJson, expiry, now);

        PasswordRecoveryIntegrationTest.CapturingMailSender capturer =
                (PasswordRecoveryIntegrationTest.CapturingMailSender) mailSender;

        worker.runJobCycle();

        assertThat(capturer.sendAttempts.get()).isZero();
        assertThat(capturer.sent).isEmpty();

        Map<String, Object> jobRow = jdbc.queryForMap(
                "SELECT status, last_error, payload, finished_at FROM background_jobs WHERE id = ?", jobId
        );
        assertThat(jobRow.get("status")).isEqualTo("FAILED");
        assertThat(jobRow.get("last_error")).isEqualTo("INVALID_PAYLOAD");
        assertThat(jobRow.get("finished_at")).isNotNull();
        assertThat(jobRow.get("payload").toString()).doesNotContain("ciphertext");

        UUID boundUser = jdbc.queryForObject(
                "SELECT user_id FROM auth_tokens WHERE id = ?", UUID.class, challengeId
        );
        assertThat(boundUser).isNull();
    }

    @Test
    void strictAttemptAndOtpFenceRejectsInvalidInputsPreservingPositiveControlWithoutMutation() {
        PasswordRecoveryIntegrationTest.CapturingMailSender capturer =
                (PasswordRecoveryIntegrationTest.CapturingMailSender) mailSender;
        capturer.sent.clear();
        capturer.sendAttempts.set(0);

        String email = "strict-fence@example.test";
        UUID userId = addActiveVerifiedUser(email);

        UUID challengeId = UUID.randomUUID();
        String rawOtp = "123456";
        String tokenHash = crypto.hash(challengeId, rawOtp, VerificationCodeCrypto.Purpose.RESET_PASSWORD);
        String ciphertext = crypto.encrypt(challengeId, rawOtp, VerificationCodeCrypto.Purpose.RESET_PASSWORD);

        OffsetDateTime now = jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class);
        OffsetDateTime expiry = now.plusMinutes(10);

        jdbc.update("""
                INSERT INTO auth_tokens(id, user_id, email, purpose, token_hash, created_at, expires_at, used_at, attempts)
                VALUES (?, ?, cast(? as citext), 'RESET_PASSWORD', ?, ?, ?, NULL, 0)
                """, challengeId, userId, email, tokenHash, now, expiry);

        String payloadJson = String.format("{\"schemaVersion\":1,\"challengeId\":\"%s\",\"ciphertext\":\"%s\"}",
                challengeId, ciphertext);

        String owner = UUID.randomUUID().toString();
        UUID jobId = jdbc.queryForObject("""
                INSERT INTO background_jobs(job_kind, idempotency_key, payload, expires_at, scheduled_at, status, attempts, locked_by, locked_until, requested_by)
                VALUES ('AUTH_RESET_PASSWORD', ?, ?::jsonb, ?, ?, 'RUNNING', 1, ?, clock_timestamp() + INTERVAL '90 seconds', ?)
                RETURNING id
                """, UUID.class, challengeId.toString(), payloadJson, expiry, now, owner, userId);

        String rawClaimed = jdbc.queryForObject(
                "SELECT payload::text FROM background_jobs WHERE id = ?", String.class, jobId
        );
        RecoveryMailPayload payload = RecoveryMailPayload.parse(rawClaimed, challengeId.toString());

        // 1. Same-current owner with mismatched / invalid claimed attempt fails closed
        int[] invalidAttempts = {-1, 0, 2, 6};
        for (int invalidAttempt : invalidAttempts) {
            assertThat(eligibility.isEligibleToSend(jobId, payload, owner, invalidAttempt, rawOtp)).isFalse();
            RecoveryMailEligibility.BindingResult res = eligibility.bindAndValidate(jobId, payload, owner, invalidAttempt, rawOtp);
            assertThat(res.status()).isEqualTo(RecoveryMailEligibility.Status.ABORT);
            assertThat(res.errorCode()).isEqualTo("LOST_LEASE");
        }

        // 2. Same-current owner with null / blank / wrong OTP rejects pre-send
        assertThat(eligibility.isEligibleToSend(jobId, payload, owner, 1, null)).isFalse();
        assertThat(eligibility.isEligibleToSend(jobId, payload, owner, 1, "")).isFalse();
        assertThat(eligibility.isEligibleToSend(jobId, payload, owner, 1, "   ")).isFalse();
        assertThat(eligibility.isEligibleToSend(jobId, payload, owner, 1, "999999")).isFalse();

        // Assert zero DB mutations and zero transport attempts across all invalid checks
        assertThat(capturer.sendAttempts.get()).isZero();
        assertThat(capturer.sent).isEmpty();

        Map<String, Object> jobRow = jdbc.queryForMap(
                "SELECT status, attempts, locked_by, requested_by, last_error FROM background_jobs WHERE id = ?", jobId
        );
        assertThat(jobRow.get("status")).isEqualTo("RUNNING");
        assertThat(((Number) jobRow.get("attempts")).intValue()).isEqualTo(1);
        assertThat(jobRow.get("locked_by")).isEqualTo(owner);
        assertThat(jobRow.get("requested_by")).isEqualTo(userId);
        assertThat(jobRow.get("last_error")).isNull();

        Map<String, Object> tokenRow = jdbc.queryForMap(
                "SELECT used_at, attempts, user_id FROM auth_tokens WHERE id = ?", challengeId
        );
        assertThat(tokenRow.get("used_at")).isNull();
        assertThat(((Number) tokenRow.get("attempts")).intValue()).isEqualTo(0);
        assertThat(tokenRow.get("user_id")).isEqualTo(userId);

        // 3. Strict normal attempt + correct OTP positive control remains functional
        boolean preSendOk = eligibility.isEligibleToSend(jobId, payload, owner, 1, rawOtp);
        assertThat(preSendOk).isTrue();

        RecoveryMailEligibility.BindingResult boundRes = eligibility.bindAndValidate(jobId, payload, owner, 1, rawOtp);
        assertThat(boundRes.status()).isEqualTo(RecoveryMailEligibility.Status.BOUND);
        assertThat(boundRes.userId()).isEqualTo(userId);
        assertThat(boundRes.email()).isEqualTo(email);
    }
}

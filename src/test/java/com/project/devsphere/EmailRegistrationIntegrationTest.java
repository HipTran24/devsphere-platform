package com.project.devsphere;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.devsphere.identity.domain.VerificationCodeCrypto;
import com.project.devsphere.identity.infrastructure.mail.VerificationMailWorker;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "devsphere.database.manage-runtime-privileges=false",
        "devsphere.auth.mail.from=devsphere@example.test",
        "spring.mail.host=mail.example.test",
        "devsphere.auth.mail.initial-delay-ms=600000"
})
@AutoConfigureMockMvc
class EmailRegistrationIntegrationTest {
    private final ObjectMapper json = new ObjectMapper();
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired VerificationCodeCrypto crypto;
    @Autowired VerificationMailWorker worker;
    @MockitoBean JavaMailSender mailSender;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        DevsphereApplicationTests.database(registry);
        registry.add("devsphere.auth.otp.hmac-key", () -> testKey(1));
        registry.add("devsphere.auth.otp.encryption-key", () -> testKey(2));
    }

    @Test
    void queuesEncryptedMailAndActivatesExactlyOnce() throws Exception {
        String email = UUID.randomUUID() + "@example.test";
        JsonNode receipt = register(email);
        UUID userId = id(receipt, "userId");
        UUID challenge = id(receipt, "challengeId");
        String ciphertext = jdbc.queryForObject("""
                SELECT payload->>'ciphertext' FROM background_jobs
                WHERE job_kind='AUTH_VERIFY_EMAIL' AND idempotency_key=? AND requested_by=?
                """, String.class, challenge.toString(), userId);
        String code = crypto.decrypt(challenge, ciphertext);

        assertThat(receipt.get("status").asText()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select password_hash from users where id=?", String.class, userId))
                .startsWith("$argon2id$");
        assertThat(jdbc.queryForObject("select token_hash from auth_tokens where id=?", String.class, challenge))
                .isEqualTo(crypto.hash(challenge, code));
        submitCode(challenge, code).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
        submitCode(challenge, code).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("OTP_INVALID"));
        assertThat(jdbc.queryForObject("select email_verified_at is not null from users where id=?", Boolean.class, userId))
                .isTrue();

        request("/api/v1/auth/register", Map.of("email", email.toUpperCase(java.util.Locale.ROOT),
                        "password", "a long test password", "displayName", "Duplicate"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_EXISTS"));
        assertThat(jdbc.queryForObject("select count(*) from background_jobs where requested_by=?", Integer.class, userId))
                .isEqualTo(1);
    }

    @Test
    void fifthFailureCommitsAndBlocksCorrectCodeAndPrematureResend() throws Exception {
        JsonNode receipt = register(UUID.randomUUID() + "@example.test");
        UUID challenge = id(receipt, "challengeId");
        String ciphertext = jdbc.queryForObject("""
                select payload->>'ciphertext' from background_jobs
                where job_kind='AUTH_VERIFY_EMAIL' and idempotency_key=?
                """, String.class, challenge.toString());
        String code = crypto.decrypt(challenge, ciphertext);
        String wrong = "000000".equals(code) ? "000001" : "000000";
        for (int attempt = 0; attempt < 5; attempt++) {
            submitCode(challenge, wrong).andExpect(status().isBadRequest());
        }
        submitCode(challenge, code).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select attempts from auth_tokens where id=?", Integer.class, challenge)).isEqualTo(5);
        assertThat(jdbc.queryForObject("select status from users where id=?", String.class, id(receipt, "userId")))
                .isEqualTo("PENDING");

        request("/api/v1/auth/verification/resend", Map.of("challengeId", challenge))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    void mailFailureRetriesThenSuccessRemovesCiphertext() throws Exception {
        // This suite uses an isolated Testcontainers database and no live mail server.
        jdbc.update("delete from background_jobs where job_kind='AUTH_VERIFY_EMAIL'");
        UUID challenge = id(register(UUID.randomUUID() + "@example.test"), "challengeId");
        String jobKey = challenge.toString();
        String recipient = jdbc.queryForObject("select email from auth_tokens where id=?", String.class, challenge);
        String encryptedCode = jdbc.queryForObject("""
                select payload->>'ciphertext' from background_jobs
                where job_kind='AUTH_VERIFY_EMAIL' and idempotency_key=?
                """, String.class, jobKey);
        String expectedCode = crypto.decrypt(challenge, encryptedCode);
        doThrow(new MailSendException("test transport failure")).doNothing()
                .when(mailSender).send(any(SimpleMailMessage.class));

        worker.runJobCycle();
        assertThat(jdbc.queryForObject("""
                select status from background_jobs where job_kind='AUTH_VERIFY_EMAIL' and idempotency_key=?
                """, String.class, jobKey)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("""
                select attempts from background_jobs where job_kind='AUTH_VERIFY_EMAIL' and idempotency_key=?
                """, Integer.class, jobKey)).isEqualTo(1);
        worker.runJobCycle();
        verify(mailSender, times(1)).send(any(SimpleMailMessage.class));

        jdbc.update("""
                update background_jobs set scheduled_at=clock_timestamp() - interval '1 second'
                where job_kind='AUTH_VERIFY_EMAIL' and idempotency_key=?
                """, jobKey);
        worker.runJobCycle();
        verify(mailSender, times(2)).send(any(SimpleMailMessage.class));
        var messages = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender, times(2)).send(messages.capture());
        assertThat(messages.getAllValues()).allSatisfy(message -> {
            assertThat(message.getTo()).containsExactly(recipient);
            assertThat(message.getFrom()).isEqualTo("devsphere@example.test");
            assertThat(message.getText()).isEqualTo("Your verification code is: " + expectedCode);
        });
        assertThat(jdbc.queryForObject("""
                select status='SUCCESS' and payload->>'ciphertext' is null and last_error is null
                from background_jobs where job_kind='AUTH_VERIFY_EMAIL' and idempotency_key=?
                """, Boolean.class, jobKey)).isTrue();
    }

    @Test
    void resendReplacesChallengeAndHonorsHourlyCap() throws Exception {
        String email = "test-" + UUID.randomUUID() + "@example.com";
        JsonNode reg = register(email);
        UUID userId = id(reg, "userId");
        UUID firstChallengeId = id(reg, "challengeId");

        jdbc.update("UPDATE auth_tokens SET created_at = clock_timestamp() - interval '60 seconds' WHERE id = ?", firstChallengeId);

        String resendResponse = request("/api/v1/auth/verification/resend", Map.of("challengeId", firstChallengeId.toString()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.challengeId").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        UUID secondChallengeId = id(json.readTree(resendResponse), "challengeId");
        assertThat(secondChallengeId).isNotEqualTo(firstChallengeId);

        Boolean used = jdbc.queryForObject("SELECT (used_at IS NOT NULL) FROM auth_tokens WHERE id = ?", Boolean.class, firstChallengeId);
        assertThat(used).isTrue();

        request("/api/v1/auth/verification/resend", Map.of("challengeId", firstChallengeId.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("OTP_INVALID"));

        String fixtureHash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        for (int minutes : new int[]{10, 11, 12}) {
            jdbc.update(
                    "INSERT INTO auth_tokens (user_id, email, purpose, token_hash, expires_at, used_at, created_at) " +
                    "VALUES (?, cast(? as citext), 'VERIFY_EMAIL', ?, clock_timestamp() + interval '15 minutes', " +
                    "statement_timestamp() - make_interval(mins => ?), statement_timestamp() - make_interval(mins => ?))",
                    userId, email, fixtureHash, minutes, minutes
            );
        }

        jdbc.update("UPDATE auth_tokens SET created_at = clock_timestamp() - interval '60 seconds' WHERE id = ?", secondChallengeId);

        request("/api/v1/auth/verification/resend", Map.of("challengeId", secondChallengeId.toString()))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    void concurrentValidOtpSubmissionsActivateOnlyOnce() throws Exception {
        UUID challenge = id(register(UUID.randomUUID() + "@example.test"), "challengeId");
        String ciphertext = jdbc.queryForObject("""
                select payload->>'ciphertext' from background_jobs
                where job_kind='AUTH_VERIFY_EMAIL' and idempotency_key=?
                """, String.class, challenge.toString());
        String code = crypto.decrypt(challenge, ciphertext);
        Callable<Integer> submit = () -> submitCode(challenge, code).andReturn().getResponse().getStatus();

        assertThat(simultaneously(submit, submit)).containsExactlyInAnyOrder(200, 400);
    }

    @Test
    void concurrentRegistrationsForSameEmailCreateOneUserAndOneJob() throws Exception {
        String email = UUID.randomUUID() + "@example.test";
        assertThat(simultaneously(
                () -> registerStatus(email), () -> registerStatus(email.toUpperCase(Locale.ROOT))))
                .containsExactlyInAnyOrder(201, 409);
        UUID userId = jdbc.queryForObject("select id from users where email=cast(? as citext)", UUID.class, email);
        assertThat(jdbc.queryForObject("""
                select count(*) from background_jobs where job_kind='AUTH_VERIFY_EMAIL' and requested_by=?
                """, Integer.class, userId)).isEqualTo(1);
    }

    @Test
    void expiredLockedAndWrongPurposeChallengesAreRejectedWithoutSideEffects() throws Exception {
        String email = "invalid-challenge-" + UUID.randomUUID() + "@example.test";
        JsonNode receipt = register(email);
        UUID user = id(receipt, "userId");
        String code = crypto.generateCode();
        var now = OffsetDateTime.now(ZoneOffset.UTC);

        UUID expired = UUID.randomUUID();
        insertChallengeFixture(expired, user, email, "VERIFY_EMAIL", code, now.minusMinutes(1));
        assertRejectedChallenge(expired, code, user, "PENDING");

        UUID wrongPurpose = UUID.randomUUID();
        insertChallengeFixture(wrongPurpose, user, email, "RESET_PASSWORD", code, now.plusMinutes(5));
        assertRejectedChallenge(wrongPurpose, code, user, "PENDING");

        UUID lockedChallenge = id(receipt, "challengeId");
        String cipher = jdbc.queryForObject("""
                select payload->>'ciphertext' from background_jobs
                where job_kind='AUTH_VERIFY_EMAIL' and idempotency_key=?
                """, String.class, lockedChallenge.toString());
        jdbc.update("update users set status='LOCKED' where id=?", user);
        assertRejectedChallenge(lockedChallenge, crypto.decrypt(lockedChallenge, cipher), user, "LOCKED");
    }

    private void insertChallengeFixture(UUID challenge, UUID user, String email, String purpose,
                                        String code, OffsetDateTime expiresAt) {
        jdbc.update("""
                insert into auth_tokens(id,user_id,email,purpose,token_hash,created_at,expires_at)
                values (?,?,cast(? as citext),?,?,?,?)
                """, challenge, user, email, purpose, crypto.hash(challenge, code),
                expiresAt.minusMinutes(10), expiresAt);
    }

    private void assertRejectedChallenge(UUID challenge, String code, UUID user, String expectedStatus) throws Exception {
        submitCode(challenge, code).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("OTP_INVALID"));
        assertThat(jdbc.queryForObject("select status from users where id=?", String.class, user)).isEqualTo(expectedStatus);
        assertThat(jdbc.queryForObject("select attempts from auth_tokens where id=?", Integer.class, challenge)).isZero();
    }

    @Test
    void concurrentResendsReplaceTheChallengeOnlyOnce() throws Exception {
        String email = "resend-simultaneous-" + UUID.randomUUID() + "@example.com";
        JsonNode reg = register(email);
        UUID firstChallengeId = id(reg, "challengeId");
        UUID userId = jdbc.queryForObject("SELECT user_id FROM auth_tokens WHERE id = ?", UUID.class, firstChallengeId);

        jdbc.update(
                "UPDATE auth_tokens SET created_at = NOW() - INTERVAL '60 seconds' WHERE id = ?",
                firstChallengeId
        );

        Map<String, Object> payload = Map.of("challengeId", firstChallengeId);

        List<Integer> statuses = simultaneously(
                () -> request("/api/v1/auth/verification/resend", payload).andReturn().getResponse().getStatus(),
                () -> request("/api/v1/auth/verification/resend", payload).andReturn().getResponse().getStatus()
        );

        assertThat(statuses).containsExactlyInAnyOrder(202, 400);

        Integer liveChallenges = jdbc.queryForObject(
                "SELECT COUNT(*) FROM auth_tokens WHERE user_id = ? AND used_at IS NULL AND expires_at > NOW()",
                Integer.class,
                userId
        );
        assertThat(liveChallenges).isEqualTo(1);

        Integer predecessorChallenges = jdbc.queryForObject(
                "SELECT COUNT(*) FROM auth_tokens WHERE user_id = ? AND (used_at IS NOT NULL OR expires_at <= NOW())",
                Integer.class,
                userId
        );
        assertThat(predecessorChallenges).isEqualTo(1);

        Integer totalJobs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM background_jobs WHERE idempotency_key IN (SELECT id::text FROM auth_tokens WHERE user_id = ?)",
                Integer.class,
                userId
        );
        assertThat(totalJobs).isEqualTo(2);
    }

    @Test
    void invalidRegistrationDoesNotPersistAnAccount() throws Exception {
        String uniqueEmail = "invalid-reg-" + UUID.randomUUID() + "@example.com";
        Map<String, Object> registrationRequest = Map.of(
                "email", uniqueEmail,
                "password", "short",
                "displayName", "   "
        );

        request("/api/v1/auth/register", registrationRequest)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        Integer userCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE email = cast(? as citext)",
                Integer.class,
                uniqueEmail
        );
        assertThat(userCount).isZero();
    }

    @Test
    void suppressedVerificationJobsAreTerminalAndLeaveOtherKindsQueued() throws Exception {
        jdbc.update("DELETE FROM background_jobs WHERE job_kind = 'AUTH_VERIFY_EMAIL'");

        UUID c1 = id(register("used_" + UUID.randomUUID() + "@example.com"), "challengeId");
        String expiredEmail = "expired-" + UUID.randomUUID() + "@example.test";
        JsonNode expiredReceipt = register(expiredEmail);
        UUID c2 = id(expiredReceipt, "challengeId");
        UUID c3 = id(register("malformed_" + UUID.randomUUID() + "@example.com"), "challengeId");
        UUID c4 = id(register("job_expired_" + UUID.randomUUID() + "@example.com"), "challengeId");

        jdbc.update("UPDATE auth_tokens SET used_at = clock_timestamp() WHERE id = ?", c1);
        // Seed the expired token rather than modifying immutable token expiry.
        String expiredCipher = jdbc.queryForObject(
                "select payload->>'ciphertext' from background_jobs where idempotency_key=?", String.class, c2.toString());
        jdbc.update("delete from auth_tokens where id=?", c2);
        insertChallengeFixture(c2, id(expiredReceipt, "userId"), expiredEmail, "VERIFY_EMAIL",
                crypto.decrypt(c2, expiredCipher), OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        jdbc.update("update background_jobs set payload=jsonb_set(payload, '{ciphertext}', to_jsonb('short'::text)) "
                + "where idempotency_key=?", c3.toString());
        jdbc.update("UPDATE background_jobs SET expires_at = clock_timestamp() - interval '1 minute' WHERE idempotency_key = ?", c4.toString());

        UUID outboxId = jdbc.queryForObject(
                "INSERT INTO background_jobs (job_kind, payload, status, attempts) VALUES ('OUTBOX_EVENT', '{}'::jsonb, 'QUEUED', 0) RETURNING id",
                UUID.class
        );

        for (int i = 0; i < 4; i++) {
            worker.runJobCycle();
        }

        verifyNoInteractions(mailSender);

        record TargetExpectation(UUID challengeId, String expectedError) {}
        for (var target : List.of(
                new TargetExpectation(c1, "INVALID_TOKEN"),
                new TargetExpectation(c2, "INVALID_TOKEN"),
                new TargetExpectation(c3, "DECRYPT_FAILED"),
                new TargetExpectation(c4, "EXPIRED_OR_EXHAUSTED"))) {
            var job = jdbc.queryForMap("SELECT * FROM background_jobs WHERE idempotency_key = ?", target.challengeId().toString());
            assertThat(job.get("status")).isEqualTo("FAILED");
            assertThat(job.get("finished_at")).isNotNull();
            assertThat(String.valueOf(job.get("payload"))).doesNotContain("ciphertext");
            assertThat(String.valueOf(job.get("last_error"))).isEqualTo(target.expectedError());
        }

        var outbox = jdbc.queryForMap("SELECT * FROM background_jobs WHERE id = ?", outboxId);
        assertThat(outbox.get("status")).isEqualTo("QUEUED");
        assertThat(((Number) outbox.get("attempts")).intValue()).isEqualTo(0);
    }

    @Test
    void smtpRetryExhaustionClosesTheJobAndScrubsTheCode() throws Exception {
        jdbc.update("DELETE FROM background_jobs WHERE job_kind = 'AUTH_VERIFY_EMAIL'");
        UUID challengeId = id(register("retry_" + UUID.randomUUID() + "@example.com"), "challengeId");

        doThrow(new MailSendException("SMTP delivery failed")).when(mailSender).send(any(SimpleMailMessage.class));

        for (int i = 0; i < 4; i++) {
            worker.runJobCycle();
            jdbc.update("UPDATE background_jobs SET scheduled_at = clock_timestamp() - interval '1 second' WHERE idempotency_key = ?", challengeId.toString());
        }
        worker.runJobCycle();

        var job = jdbc.queryForMap("SELECT * FROM background_jobs WHERE idempotency_key = ?", challengeId.toString());
        assertThat(job.get("status")).isEqualTo("FAILED");
        assertThat(((Number) job.get("attempts")).intValue()).isEqualTo(5);
        assertThat(job.get("finished_at")).isNotNull();
        assertThat(String.valueOf(job.get("payload"))).doesNotContain("ciphertext");
        assertThat(String.valueOf(job.get("last_error"))).isEqualTo("MAIL_EXHAUSTED");
        assertThat(job.get("locked_by")).isNull();
        assertThat(job.get("locked_until")).isNull();

        worker.runJobCycle();
        verify(mailSender, times(5)).send(any(SimpleMailMessage.class));
    }

    @Test
    void displayNameControlCharactersAreRejectedBeforeDatabaseWrite() throws Exception {
        for (String name : new String[]{
                "User" + (char) 0 + "Name", "\tAlice", "Alice\t", "\nAlice", "Alice\n"}) {
            String email = UUID.randomUUID() + "@example.test";
            request("/api/v1/auth/register", Map.of(
                            "email", email, "password", "a long test password", "displayName", name))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
            assertThat(jdbc.queryForObject("select count(*) from users where email=cast(? as citext)",
                    Integer.class, email)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from auth_tokens where email=cast(? as citext)",
                    Integer.class, email)).isZero();
        }
        String email = UUID.randomUUID() + "@example.test";
        request("/api/v1/auth/register", Map.of(
                        "email", email, "password", "a long test password", "displayName", "  Alice  "))
                .andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("select display_name from users where email=cast(? as citext)",
                String.class, email)).isEqualTo("Alice");
    }

    @Test
    void resendDuringSmtpDeliverySuppressesTheRetriedOldChallenge() throws Exception {
        jdbc.update("DELETE FROM background_jobs WHERE job_kind = 'AUTH_VERIFY_EMAIL'");
        String email = "race-" + UUID.randomUUID() + "@example.com";
        UUID firstChallenge = id(register(email), "challengeId");
        String cipher = jdbc.queryForObject(
            "SELECT payload->>'ciphertext' FROM background_jobs WHERE job_kind = 'AUTH_VERIFY_EMAIL' AND idempotency_key = ?",
            String.class, firstChallenge.toString());
        String code = crypto.decrypt(firstChallenge, cipher);
        jdbc.update("UPDATE auth_tokens SET created_at = created_at - INTERVAL '60 seconds' WHERE id = ?", firstChallenge);

        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(inv -> {
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for the resend transaction");
            }
            throw new MailSendException("smtp failure");
        }).when(mailSender).send(any(SimpleMailMessage.class));

        try (var exec = Executors.newSingleThreadExecutor()) {
            var future = exec.submit(worker::runJobCycle);
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                request("/api/v1/auth/verification/resend", Map.of("challengeId", firstChallenge.toString()))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.challengeId").isNotEmpty());
            } finally {
                release.countDown();
                future.get(10, TimeUnit.SECONDS);
            }
        }

        verify(mailSender, times(1)).send(any(SimpleMailMessage.class));

        jdbc.update(
            "UPDATE background_jobs SET scheduled_at = clock_timestamp() - INTERVAL '1 minute' WHERE job_kind = 'AUTH_VERIFY_EMAIL' AND idempotency_key = ?",
            firstChallenge.toString());
        worker.runJobCycle();

        verify(mailSender, times(1)).send(any(SimpleMailMessage.class));
        var oldJob = jdbc.queryForMap(
            "SELECT * FROM background_jobs WHERE job_kind = 'AUTH_VERIFY_EMAIL' AND idempotency_key = ?",
            firstChallenge.toString());
        assertThat(oldJob.get("status")).isEqualTo("FAILED");
        assertThat(oldJob.get("finished_at")).isNotNull();
        assertThat(oldJob.get("last_error")).isEqualTo("INVALID_TOKEN");
        assertThat(oldJob.get("payload").toString()).doesNotContain("ciphertext");
        submitCode(firstChallenge, code).andExpect(status().isBadRequest());
    }

    @Test
    void codeForThePreviousEmailCannotVerifyANewAddress() throws Exception {
        String oldEmail = "\"prior@private\"@example.test";
        JsonNode receipt = register(oldEmail);
        assertThat(receipt.get("emailMasked").asText()).doesNotContain("private", "prior");
        UUID user = id(receipt, "userId");
        UUID challenge = id(receipt, "challengeId");
        String encryptedCode = jdbc.queryForObject("""
                select payload->>'ciphertext' from background_jobs
                where job_kind='AUTH_VERIFY_EMAIL' and idempotency_key=?
                """, String.class, challenge.toString());
        String newEmail = UUID.randomUUID() + "@example.test";
        jdbc.update("update users set email=cast(? as citext) where id=?", newEmail, user);
        submitCode(challenge, crypto.decrypt(challenge, encryptedCode))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("OTP_INVALID"));
        assertThat(jdbc.queryForObject("select status from users where id=?", String.class, user)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select email_verified_at is null from users where id=?", Boolean.class, user)).isTrue();
        assertThat(jdbc.queryForObject("select attempts from auth_tokens where id=?", Integer.class, challenge)).isZero();
    }

    @Test
    void nonStringJsonScalarsAreRejectedWithoutPersistenceOrOtpAttempts() throws Exception {
        String email = UUID.randomUUID() + "@example.test";
        for (Map<String, ?> body : List.of(
                Map.of("email", email, "password", 1234567890123L, "displayName", "New User"),
                Map.of("email", email, "password", "a long test password", "displayName", true))) {
            request("/api/v1/auth/register", body).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
            assertThat(jdbc.queryForObject("select count(*) from users where email=cast(? as citext)",
                    Long.class, email)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from auth_tokens where email=cast(? as citext)",
                    Long.class, email)).isZero();
        }
        UUID challenge = id(register(email), "challengeId");
        request("/api/v1/auth/verify-email", Map.of("challengeId", challenge, "code", 123456))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(jdbc.queryForObject("select attempts from auth_tokens where id=?", Integer.class, challenge)).isZero();
        assertThat(jdbc.queryForObject("select used_at is null from auth_tokens where id=?", Boolean.class, challenge)).isTrue();
    }

    @Test
    void supplementaryUnicodeLengthLimitsMatchThePublishedCharacterCounts() throws Exception {
        String symbol = new String(Character.toChars(0x1F9D1));
        for (String[] rejected : List.of(
                new String[]{symbol.repeat(6), "New User"},
                new String[]{symbol.repeat(129), "New User"},
                new String[]{"a long test password", symbol.repeat(121)})) {
            String email = UUID.randomUUID() + "@example.test";
            request("/api/v1/auth/register", Map.of(
                            "email", email, "password", rejected[0], "displayName", rejected[1]))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
            assertThat(jdbc.queryForObject("select count(*) from users where email=cast(? as citext)",
                    Integer.class, email)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from auth_tokens where email=cast(? as citext)",
                    Integer.class, email)).isZero();
        }
        for (int count : new int[]{12, 128}) {
            String email = UUID.randomUUID() + "@example.test";
            String password = symbol.repeat(count);
            request("/api/v1/auth/register", Map.of(
                            "email", email, "password", password, "displayName", symbol.repeat(120)))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING"));
            assertThat(jdbc.queryForObject("select length(display_name) from users where email=cast(? as citext)",
                    Integer.class, email)).isEqualTo(120);
        }
    }

    private int registerStatus(String email) throws Exception {
        return request("/api/v1/auth/register", Map.of("email", email,
                        "password", "a long test password", "displayName", "New User"))
                .andReturn().getResponse().getStatus();
    }

    private static List<Integer> simultaneously(Callable<Integer> first, Callable<Integer> second) throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var a = pool.submit(() -> { start.await(); return first.call(); });
            var b = pool.submit(() -> { start.await(); return second.call(); });
            start.countDown();
            try {
                return List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
            } finally {
                a.cancel(true);
                b.cancel(true);
            }
        }
    }

    private JsonNode register(String email) throws Exception {
        return json.readTree(request("/api/v1/auth/register", Map.of("email", email,
                        "password", "a long test password", "displayName", "New User"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private ResultActions submitCode(UUID challenge, String code) throws Exception {
        return request("/api/v1/auth/verify-email", Map.of("challengeId", challenge, "code", code));
    }

    private ResultActions request(String path, Map<String, ?> body) throws Exception {
        return mvc.perform(post(path).contentType("application/json").content(json.writeValueAsString(body)));
    }

    private static String testKey(int index) {
        byte[] key = new byte[32];
        key[0] = (byte) index;
        return Base64.getEncoder().encodeToString(key);
    }

    private static UUID id(JsonNode node, String name) {
        return UUID.fromString(node.get(name).asText());
    }
}

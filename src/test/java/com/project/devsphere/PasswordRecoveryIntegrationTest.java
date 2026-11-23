package com.project.devsphere;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.devsphere.identity.application.SessionService;
import com.project.devsphere.identity.domain.PasswordResetGrantToken;
import com.project.devsphere.identity.domain.VerificationCodeCrypto;
import com.project.devsphere.identity.infrastructure.mail.VerificationMailWorker;
import com.project.devsphere.operations.infrastructure.persistence.JobQueue;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "devsphere.database.manage-runtime-privileges=false",
        "devsphere.auth.mail.from=devsphere@example.test",
        "devsphere.auth.mail.initial-delay-ms=600000",
        "spring.mail.host=localhost",
        "spring.mail.port=2525"
})
@AutoConfigureMockMvc
class PasswordRecoveryIntegrationTest {

    @TestConfiguration
    static class TestMailConfig {
        @Bean
        @Primary
        public JavaMailSender testMailSender() {
            return new CapturingMailSender();
        }
    }

    static class CapturingMailSender implements JavaMailSender {
        final List<SimpleMailMessage> sent = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger sendAttempts = new AtomicInteger(0);
        volatile SimpleMailMessage lastAttempted = null;
        volatile CountDownLatch sendLatch = null;
        volatile CountDownLatch unblockLatch = null;
        final AtomicBoolean failNext = new AtomicBoolean(false);

        @Override
        public void send(SimpleMailMessage simpleMessage) throws MailException {
            sendAttempts.incrementAndGet();
            lastAttempted = simpleMessage;
            if (sendLatch != null) {
                sendLatch.countDown();
            }
            if (unblockLatch != null) {
                try {
                    if (!unblockLatch.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timeout waiting for test unblock");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new MailSendException("Interrupted", e);
                }
            }
            if (failNext.getAndSet(false)) {
                throw new MailSendException("Injected SMTP delivery error");
            }
            sent.add(simpleMessage);
        }

        @Override public void send(SimpleMailMessage... simpleMessages) throws MailException { for (var m : simpleMessages) send(m); }
        @Override public jakarta.mail.internet.MimeMessage createMimeMessage() { throw new UnsupportedOperationException(); }
        @Override public jakarta.mail.internet.MimeMessage createMimeMessage(InputStream contentStream) { throw new UnsupportedOperationException(); }
        @Override public void send(jakarta.mail.internet.MimeMessage mimeMessage) throws MailException { throw new UnsupportedOperationException(); }
        @Override public void send(jakarta.mail.internet.MimeMessage... mimeMessages) throws MailException { throw new UnsupportedOperationException(); }
        @Override public void send(org.springframework.mail.javamail.MimeMessagePreparator mimeMessagePreparator) throws MailException { throw new UnsupportedOperationException(); }
        @Override public void send(org.springframework.mail.javamail.MimeMessagePreparator... mimeMessagePreparators) throws MailException { throw new UnsupportedOperationException(); }
    }

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        DevsphereApplicationTests.database(registry);
        byte[] hmac = new byte[32];
        byte[] encryption = new byte[32];
        hmac[0] = 11;
        encryption[0] = 22;
        registry.add("devsphere.auth.otp.hmac-key", () -> Base64.getEncoder().encodeToString(hmac));
        registry.add("devsphere.auth.otp.encryption-key", () -> Base64.getEncoder().encodeToString(encryption));
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired JavaMailSender mailSender;
    @Autowired VerificationCodeCrypto crypto;
    @Autowired VerificationMailWorker worker;
    @Autowired SessionService sessionService;
    @Autowired Flyway flyway;

    private final ObjectMapper json = new ObjectMapper();
    private final Argon2PasswordEncoder encoder = new Argon2PasswordEncoder(16, 32, 1, 19456, 2);

    @BeforeEach
    void setup() {
        if (mailSender instanceof CapturingMailSender capturer) {
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

    @Test
    void v008ToV009MigrationPreservesExistingDataAndSchemaInvariants() {
        String tempDb = "upgrade_test_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("create database " + tempDb);
        String tempUrl = DevsphereApplicationTests.POSTGRES.getJdbcUrl().replaceFirst("/[^/?]+(?=\\?|$)", "/" + tempDb);
        try {
            // Target V8 schema
            var flywayV8 = Flyway.configure()
                    .dataSource(tempUrl, DevsphereApplicationTests.POSTGRES.getUsername(), DevsphereApplicationTests.POSTGRES.getPassword())
                    .target("8")
                    .load();
            flywayV8.migrate();
            flywayV8.validate();

            var tempDs = new DriverManagerDataSource(
                    tempUrl, DevsphereApplicationTests.POSTGRES.getUsername(), DevsphereApplicationTests.POSTGRES.getPassword()
            );
            JdbcTemplate tempJdbc = new JdbcTemplate(tempDs);

            // Insert representative V8 data:
            // 1. ACTIVE verified user
            UUID activeUserId = UUID.randomUUID();
            String activeEmail = "v8-active@example.test";
            String encodedPwd = encoder.encode("password123456");
            tempJdbc.update("""
                    insert into users(id, email, display_name, status, email_verified_at, password_hash)
                    values (?, cast(? as citext), 'V8 Active User', 'ACTIVE', clock_timestamp() - interval '1 day', ?)
                    """, activeUserId, activeEmail, encodedPwd);

            // 2. Rotated/revoked session for active user
            UUID familyId = UUID.randomUUID();
            UUID revokedSessionId = UUID.randomUUID();
            UUID activeSessionId = UUID.randomUUID();
            String refreshHash1 = "11".repeat(32);
            String refreshHash2 = "22".repeat(32);

            tempJdbc.update("""
                    insert into auth_sessions(id, user_id, refresh_hash, issued_at, expires_at, revoked_at, session_family_id)
                    values (?, ?, ?, clock_timestamp() - interval '2 hours', clock_timestamp() + interval '1 day', clock_timestamp() - interval '1 hour', ?)
                    """, revokedSessionId, activeUserId, refreshHash1, familyId);

            tempJdbc.update("""
                    insert into auth_sessions(id, user_id, refresh_hash, issued_at, expires_at, revoked_at, session_family_id)
                    values (?, ?, ?, clock_timestamp() - interval '1 hour', clock_timestamp() + interval '1 day', NULL, ?)
                    """, activeSessionId, activeUserId, refreshHash2, familyId);

            // 3. Representative PENDING verification challenge + encrypted queued AUTH_VERIFY_EMAIL job
            UUID pendingUserId = UUID.randomUUID();
            String pendingEmail = "v8-pending@example.test";
            tempJdbc.update("""
                    insert into users(id, email, display_name, status)
                    values (?, cast(? as citext), 'V8 Pending User', 'PENDING')
                    """, pendingUserId, pendingEmail);

            UUID challengeId = UUID.randomUUID();
            String rawOtp = "123456";
            String tokenHash = crypto.hash(challengeId, rawOtp, VerificationCodeCrypto.Purpose.VERIFY_EMAIL);
            String ciphertext = crypto.encrypt(challengeId, rawOtp, VerificationCodeCrypto.Purpose.VERIFY_EMAIL);

            tempJdbc.update("""
                    insert into auth_tokens(id, user_id, email, purpose, token_hash, created_at, expires_at)
                    values (?, ?, cast(? as citext), 'VERIFY_EMAIL', ?, clock_timestamp(), clock_timestamp() + interval '10 minutes')
                    """, challengeId, pendingUserId, pendingEmail, tokenHash);

            UUID queuedJobId = tempJdbc.queryForObject("""
                    insert into background_jobs(job_kind, idempotency_key, payload, requested_by, status, attempts, scheduled_at, expires_at)
                    values ('AUTH_VERIFY_EMAIL', cast(? as text),
                            jsonb_build_object('schemaVersion', 1, 'challengeId', cast(? as text), 'ciphertext', cast(? as text)),
                            ?, 'QUEUED', 0, clock_timestamp(), clock_timestamp() + interval '10 minutes')
                    returning id
                    """, UUID.class, challengeId.toString(), challengeId.toString(), ciphertext, pendingUserId);

            // Snapshot old row bytes/fields before migrating to V9
            Map<String, Object> userBefore = tempJdbc.queryForMap(
                    "select id, email, display_name, status, email_verified_at, password_hash from users where id = ?", activeUserId
            );
            Map<String, Object> tokenBefore = tempJdbc.queryForMap(
                    "select id, user_id, email, purpose, token_hash, created_at, expires_at from auth_tokens where id = ?", challengeId
            );
            Map<String, Object> jobBefore = tempJdbc.queryForMap(
                    "select id, job_kind, idempotency_key, payload, requested_by, status, attempts from background_jobs where id = ?", queuedJobId
            );
            Map<String, Object> revokedSessionBefore = tempJdbc.queryForMap(
                    "select id, user_id, refresh_hash, issued_at, expires_at, revoked_at, session_family_id from auth_sessions where id = ?", revokedSessionId
            );
            Map<String, Object> activeSessionBefore = tempJdbc.queryForMap(
                    "select id, user_id, refresh_hash, issued_at, expires_at, revoked_at, session_family_id from auth_sessions where id = ?", activeSessionId
            );

            // Migrate to V9 (one migration)
            var flywayV9 = Flyway.configure()
                    .dataSource(tempUrl, DevsphereApplicationTests.POSTGRES.getUsername(), DevsphereApplicationTests.POSTGRES.getPassword())
                    .target("9")
                    .load();
            var migrateV9Result = flywayV9.migrate();
            assertThat(migrateV9Result.migrationsExecuted).isEqualTo(1);
            flywayV9.validate();

            // Assert 36 business tables
            int tables = tempJdbc.queryForObject(
                    "select count(*) from information_schema.tables where table_schema='public' and table_type='BASE TABLE' and table_name<>'flyway_schema_history'",
                    Integer.class
            );
            assertThat(tables).isEqualTo(36);

            // Assert unchanged old data
            Map<String, Object> userAfter = tempJdbc.queryForMap(
                    "select id, email, display_name, status, email_verified_at, password_hash from users where id = ?", activeUserId
            );
            Map<String, Object> tokenAfter = tempJdbc.queryForMap(
                    "select id, user_id, email, purpose, token_hash, created_at, expires_at from auth_tokens where id = ?", challengeId
            );
            Map<String, Object> jobAfter = tempJdbc.queryForMap(
                    "select id, job_kind, idempotency_key, payload, requested_by, status, attempts from background_jobs where id = ?", queuedJobId
            );
            Map<String, Object> revokedSessionAfter = tempJdbc.queryForMap(
                    "select id, user_id, refresh_hash, issued_at, expires_at, revoked_at, session_family_id from auth_sessions where id = ?", revokedSessionId
            );
            Map<String, Object> activeSessionAfter = tempJdbc.queryForMap(
                    "select id, user_id, refresh_hash, issued_at, expires_at, revoked_at, session_family_id from auth_sessions where id = ?", activeSessionId
            );

            assertThat(userAfter).isEqualTo(userBefore);
            assertThat(tokenAfter).isEqualTo(tokenBefore);
            assertThat(jobAfter).isEqualTo(jobBefore);
            assertThat(revokedSessionAfter).isEqualTo(revokedSessionBefore);
            assertThat(activeSessionAfter).isEqualTo(activeSessionBefore);

            // Assert crypto compatibility
            assertThat(crypto.matches(challengeId, rawOtp, (String) tokenAfter.get("token_hash"), VerificationCodeCrypto.Purpose.VERIFY_EMAIL)).isTrue();
            String decrypted = crypto.decrypt(challengeId, ciphertext, VerificationCodeCrypto.Purpose.VERIFY_EMAIL);
            assertThat(decrypted).isEqualTo(rawOtp);

            // Assert new grant/job constraints still work
            UUID token1 = UUID.randomUUID();
            UUID token2 = UUID.randomUUID();

            tempJdbc.update("""
                    insert into auth_tokens(id, user_id, email, purpose, token_hash, expires_at)
                    values (?, ?, 'constraint-test@example.test', 'RESET_PASSWORD', 'hash1', clock_timestamp() + interval '10 min')
                    """, token1, activeUserId);

            tempJdbc.update("""
                    insert into auth_tokens(id, user_id, email, purpose, token_hash, expires_at, attempts)
                    values (?, ?, 'constraint-test@example.test', 'RESET_PASSWORD_GRANT', 'hash2', clock_timestamp() + interval '10 min', 0)
                    """, token2, activeUserId);

            // Reject RESET_PASSWORD_GRANT without user_id
            assertThatThrownBy(() -> tempJdbc.update("""
                    insert into auth_tokens(id, user_id, email, purpose, token_hash, expires_at, attempts)
                    values (?, NULL, 'constraint-test@example.test', 'RESET_PASSWORD_GRANT', 'hash3', clock_timestamp() + interval '10 min', 0)
                    """, UUID.randomUUID()))
                    .isInstanceOf(DataIntegrityViolationException.class);

            // Reject RESET_PASSWORD_GRANT with attempts > 0
            assertThatThrownBy(() -> tempJdbc.update("""
                    insert into auth_tokens(id, user_id, email, purpose, token_hash, expires_at, attempts)
                    values (?, ?, 'constraint-test@example.test', 'RESET_PASSWORD_GRANT', 'hash4', clock_timestamp() + interval '10 min', 1)
                    """, UUID.randomUUID(), activeUserId))
                    .isInstanceOf(DataIntegrityViolationException.class);

            // Reject duplicate grant digest
            assertThatThrownBy(() -> tempJdbc.update("""
                    insert into auth_tokens(id, user_id, email, purpose, token_hash, expires_at, attempts)
                    values (?, ?, 'constraint-test@example.test', 'RESET_PASSWORD_GRANT', 'hash2', clock_timestamp() + interval '10 min', 0)
                    """, UUID.randomUUID(), activeUserId))
                    .isInstanceOf(DataIntegrityViolationException.class);

            // Assert job_kind AUTH_RESET_PASSWORD allowed
            UUID newJobId = tempJdbc.queryForObject("""
                    insert into background_jobs(job_kind, payload)
                    values ('AUTH_RESET_PASSWORD', '{"schemaVersion":1}')
                    returning id
                    """, UUID.class);
            assertThat(newJobId).isNotNull();

            // Generic JobQueue routing: demonstrate neither identity kind is claimed even when unrelated valid jobs exist
            UUID unrelatedJobId = tempJdbc.queryForObject("""
                    insert into background_jobs(job_kind, payload)
                    values ('OUTBOX_EVENT', '{"schemaVersion":1}')
                    returning id
                    """, UUID.class);

            JobQueue queue = new JobQueue(tempJdbc);
            var firstClaim = queue.claim("worker-queue-test-1", 60);
            assertThat(firstClaim).contains(unrelatedJobId);

            var secondClaim = queue.claim("worker-queue-test-2", 60);
            assertThat(secondClaim).isEmpty();

            Map<String, Object> verifyEmailJob = tempJdbc.queryForMap(
                    "select status, attempts, locked_by from background_jobs where id = ?", queuedJobId
            );
            assertThat(verifyEmailJob.get("status")).isEqualTo("QUEUED");
            assertThat(((Number) verifyEmailJob.get("attempts")).intValue()).isEqualTo(0);
            assertThat(verifyEmailJob.get("locked_by")).isNull();

            Map<String, Object> resetPasswordJob = tempJdbc.queryForMap(
                    "select status, attempts, locked_by from background_jobs where id = ?", newJobId
            );
            assertThat(resetPasswordJob.get("status")).isEqualTo("QUEUED");
            assertThat(((Number) resetPasswordJob.get("attempts")).intValue()).isEqualTo(0);
            assertThat(resetPasswordJob.get("locked_by")).isNull();

        } finally {
            jdbc.execute("drop database " + tempDb + " with (force)");
        }
    }

    @Test
    void intakeReturnsByteEquivalent202ForActivePendingLockedAndUnknownAddresses() throws Exception {
        String active = "active-intake@example.test";
        String pending = "pending-intake@example.test";
        String locked = "locked-intake@example.test";
        String unknown = "unknown-intake@example.test";

        UUID activeId = addActiveVerifiedUser(active, "initialPassword123");
        UUID pendingId = addPendingUser(pending);
        UUID lockedId = addLockedUser(locked);

        // Snapshot existing user row states before intake
        Map<String, Object> activeBefore = jdbc.queryForMap("SELECT updated_at, version, password_hash, status FROM users WHERE id=?", activeId);
        Map<String, Object> pendingBefore = jdbc.queryForMap("SELECT updated_at, version, status FROM users WHERE id=?", pendingId);
        Map<String, Object> lockedBefore = jdbc.queryForMap("SELECT updated_at, version, status FROM users WHERE id=?", lockedId);

        List<MvcResult> results = new ArrayList<>();
        String[] emails = {active, pending, locked, unknown};
        for (int i = 0; i < emails.length; i++) {
            String email = emails[i];
            String peer = "10.0.0." + (i + 1);
            MvcResult res = mvc.perform(post("/api/v1/auth/forgot-password")
                            .with(req -> { req.setRemoteAddr(peer); return req; })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"" + email + "\"}"))
                    .andExpect(status().isAccepted())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                    .andReturn();
            results.add(res);
        }

        String expectedBody = "{\"status\":\"ACCEPTED\"}";
        for (MvcResult res : results) {
            assertThat(res.getResponse().getContentAsString()).isEqualTo(expectedBody);
        }

        // Intake persists unbound token (null user_id) and AUTH_RESET_PASSWORD job with null requested_by
        for (String email : emails) {
            int tokenCount = jdbc.queryForObject(
                    "select count(*) from auth_tokens where email=cast(? as citext) and purpose='RESET_PASSWORD' and user_id is null",
                    Integer.class, email
            );
            assertThat(tokenCount).isEqualTo(1);
        }

        // Verify users table was completely unmodified during intake (exact before/after snapshot)
        Map<String, Object> activeAfter = jdbc.queryForMap("SELECT updated_at, version, password_hash, status FROM users WHERE id=?", activeId);
        Map<String, Object> pendingAfter = jdbc.queryForMap("SELECT updated_at, version, status FROM users WHERE id=?", pendingId);
        Map<String, Object> lockedAfter = jdbc.queryForMap("SELECT updated_at, version, status FROM users WHERE id=?", lockedId);

        assertThat(activeAfter).isEqualTo(activeBefore);
        assertThat(pendingAfter).isEqualTo(pendingBefore);
        assertThat(lockedAfter).isEqualTo(lockedBefore);
    }

    @Test
    void intakeEnforces60SecondsCooldownAndRollingHourlyLimit() throws Exception {
        String email = "rate-intake@example.test";
        addActiveVerifiedUser(email, "initialPassword123");

        // 1st request -> accepted and persists token
        mvc.perform(post("/api/v1/auth/forgot-password")
                        .with(req -> { req.setRemoteAddr("10.0.1.1"); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));

        long tokensAfter1 = jdbc.queryForObject(
                "select count(*) from auth_tokens where email=cast(? as citext) and purpose='RESET_PASSWORD'",
                Long.class, email
        );
        assertThat(tokensAfter1).isEqualTo(1);

        // 2nd request within 60s -> returns 202 accepted silently, persists NOTHING
        mvc.perform(post("/api/v1/auth/forgot-password")
                        .with(req -> { req.setRemoteAddr("10.0.1.2"); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));

        long tokensAfter2 = jdbc.queryForObject(
                "select count(*) from auth_tokens where email=cast(? as citext) and purpose='RESET_PASSWORD'",
                Long.class, email
        );
        assertThat(tokensAfter2).isEqualTo(1);

        // Simulate 5 issues in preceding hour
        jdbc.execute("delete from auth_tokens where email=cast('" + email + "' as citext)");
        for (int i = 0; i < 5; i++) {
            jdbc.update("""
                    insert into auth_tokens(id, email, purpose, token_hash, created_at, expires_at)
                    values (?, cast(? as citext), 'RESET_PASSWORD', 'h', clock_timestamp() - (? * interval '5 minutes'), clock_timestamp() + interval '5 minutes')
                    """, UUID.randomUUID(), email, i + 1);
        }

        // 6th request within hour -> capped, persists nothing
        mvc.perform(post("/api/v1/auth/forgot-password")
                        .with(req -> { req.setRemoteAddr("10.0.1.3"); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isAccepted());

        long tokensAfterHourCap = jdbc.queryForObject(
                "select count(*) from auth_tokens where email=cast(? as citext) and purpose='RESET_PASSWORD'",
                Long.class, email
        );
        assertThat(tokensAfterHourCap).isEqualTo(5);
    }

    @Test
    void concurrentSameEmailIntakeYieldsExactlyOneIssueInCooldown() throws Exception {
        String email = "concurrent-intake@example.test";
        addActiveVerifiedUser(email, "pass12345678");

        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(4)) {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    return mvc.perform(post("/api/v1/auth/forgot-password")
                                    .with(req -> { req.setRemoteAddr("10.0.10." + idx); return req; })
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"email\":\"" + email + "\"}"))
                            .andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            for (var f : futures) {
                assertThat(f.get(10, TimeUnit.SECONDS)).isEqualTo(202);
            }
        }

        // Exactly one challenge token and background job created during cooldown
        int tokens = jdbc.queryForObject(
                "select count(*) from auth_tokens where email=cast(? as citext) and purpose='RESET_PASSWORD'",
                Integer.class, email
        );
        assertThat(tokens).isEqualTo(1);
    }

    @Test
    void routePeerCapsAndForwardedHeaderSpoofIsolation() throws Exception {
        final String email = "peer-cap@example.test";
        addActiveVerifiedUser(email, "pass12345678");

        final String peer1 = "192.168.10.100";
        // Spoofed X-Forwarded-For should be ignored; remote address is used.
        // Send requests until email is exhausted (8 allowed, 9th rate-limited).
        for (int i = 0; i < 8; i++) {
            final String spoofed = "10.0." + i + ".1, 10.0." + i + ".2";
            mvc.perform(post("/api/v1/auth/forgot-password/verify")
                            .with(req -> {
                                req.setRemoteAddr(peer1);
                                return req;
                            })
                            .header("X-Forwarded-For", spoofed)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"" + email + "\",\"code\":\"123456\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("RESET_CODE_INVALID"));
        }

        // Capped with 429 RATE_LIMITED and integral Retry-After
        MvcResult emailRateLimitedResult = mvc.perform(post("/api/v1/auth/forgot-password/verify")
                        .with(req -> {
                            req.setRemoteAddr(peer1);
                            return req;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"code\":\"123456\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andReturn();

        String emailRetryAfter = emailRateLimitedResult.getResponse().getHeader(HttpHeaders.RETRY_AFTER);
        assertThat(Integer.parseInt(emailRetryAfter)).isPositive();

        // Assert same exhausted email remains 429 under a fresh peer
        final String freshPeer1 = "192.168.10.101";
        MvcResult freshPeerExhaustedEmailResult = mvc.perform(post("/api/v1/auth/forgot-password/verify")
                        .with(req -> {
                            req.setRemoteAddr(freshPeer1);
                            return req;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"code\":\"123456\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andReturn();

        String freshPeerRetryAfter = freshPeerExhaustedEmailResult.getResponse().getHeader(HttpHeaders.RETRY_AFTER);
        assertThat(Integer.parseInt(freshPeerRetryAfter)).isPositive();

        // Separately show a fresh peer plus fresh email is allowed (well-formed unknown recovery code 400)
        final String freshEmail1 = "peer-cap-fresh-1@example.test";
        mvc.perform(post("/api/v1/auth/forgot-password/verify")
                        .with(req -> {
                            req.setRemoteAddr(freshPeer1);
                            return req;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + freshEmail1 + "\",\"code\":\"123456\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RESET_CODE_INVALID"));

        // HTTP peer-cap check using distinct email keys on one actual peer with rotating spoofed forwarding headers
        final String peerCapTarget = "192.168.10.200";
        for (int i = 0; i < 80; i++) {
            final String distinctEmail = "peer-cap-distinct-" + i + "@example.test";
            final String spoofedForwarded = "172.16." + (i / 250) + "." + (i % 250);
            mvc.perform(post("/api/v1/auth/forgot-password/verify")
                            .with(req -> {
                                req.setRemoteAddr(peerCapTarget);
                                return req;
                            })
                            .header("X-Forwarded-For", spoofedForwarded)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"" + distinctEmail + "\",\"code\":\"123456\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("RESET_CODE_INVALID"));
        }

        // 81st request on peerCapTarget is 429 with RATE_LIMITED and a positive integral Retry-After
        final String email81 = "peer-cap-distinct-80@example.test";
        final String spoofed81 = "172.16.1.80";
        MvcResult peerCapResult = mvc.perform(post("/api/v1/auth/forgot-password/verify")
                        .with(req -> {
                            req.setRemoteAddr(peerCapTarget);
                            return req;
                        })
                        .header("X-Forwarded-For", spoofed81)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email81 + "\",\"code\":\"123456\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andReturn();

        String peerRetryAfter = peerCapResult.getResponse().getHeader(HttpHeaders.RETRY_AFTER);
        assertThat(Integer.parseInt(peerRetryAfter)).isPositive();

        // Fresh distinct email on a fresh peer remains 400
        final String freshPeer2 = "192.168.10.201";
        final String freshEmail2 = "peer-cap-fresh-2@example.test";
        mvc.perform(post("/api/v1/auth/forgot-password/verify")
                        .with(req -> {
                            req.setRemoteAddr(freshPeer2);
                            return req;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + freshEmail2 + "\",\"code\":\"123456\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RESET_CODE_INVALID"));
    }

    @Test
    void httpContractValidatesInputAndMediaType() throws Exception {
        // Malformed JSON
        mvc.perform(post("/api/v1/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{malformed"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));

        // Non-JSON media type -> 415
        mvc.perform(post("/api/v1/auth/forgot-password")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("email=test@example.test"))
                .andExpect(status().isUnsupportedMediaType());

        // Invalid fields for verify
        mvc.perform(post("/api/v1/auth/forgot-password/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"invalid-email\",\"code\":\"123\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // Null resetGrant -> 400 VALIDATION_FAILED
        mvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resetGrant\":null,\"newPassword\":\"aValidPassword123\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // Non-string field -> 400 VALIDATION_FAILED
        mvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resetGrant\":12345,\"newPassword\":\"aValidPassword123\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // Empty string resetGrant -> reaches credential validation and produces RESET_GRANT_INVALID (Finding 3!)
        mvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resetGrant\":\"\",\"newPassword\":\"aValidPassword123\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RESET_GRANT_INVALID"));

        // Whitespace string resetGrant -> RESET_GRANT_INVALID
        mvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resetGrant\":\"   \",\"newPassword\":\"aValidPassword123\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RESET_GRANT_INVALID"));
    }

    @Test
    void unicodeCodePointBoundariesAndPasswordWhitespacePreservation() throws Exception {
        String email = "unicode-pw@example.test";
        UUID userId = addActiveVerifiedUser(email, "initialPassword123");

        // Obtain valid grant
        mvc.perform(post("/api/v1/auth/forgot-password")
                        .with(req -> { req.setRemoteAddr("10.0.12.1"); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isAccepted());

        CapturingMailSender capturer = (CapturingMailSender) mailSender;
        worker.runJobCycle();
        String text = capturer.sent.get(0).getText();
        String code = text.substring(text.lastIndexOf(':') + 1).trim();

        MvcResult verifyRes = mvc.perform(post("/api/v1/auth/forgot-password/verify")
                        .with(req -> { req.setRemoteAddr("10.0.12.2"); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andReturn();

        String grant = json.readTree(verifyRes.getResponse().getContentAsString()).get("resetGrant").asText();

        // 11 characters fails validation (min 12)
        mvc.perform(post("/api/v1/auth/reset-password")
                        .with(req -> { req.setRemoteAddr("10.0.12.3"); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "resetGrant", grant,
                                "newPassword", "12345678901"
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // 12 characters with leading/trailing spaces preserved
        String passwordWithSpaces = "  password12  ";
        mvc.perform(post("/api/v1/auth/reset-password")
                        .with(req -> { req.setRemoteAddr("10.0.12.4"); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "resetGrant", grant,
                                "newPassword", passwordWithSpaces
                        ))))
                .andExpect(status().isOk());

        // Login fails with trimmed password; succeeds with exact whitespace password
        assertThat(sessionService.login(email, "password12").outcome())
                .isEqualTo(SessionService.Outcome.INVALID_CREDENTIALS);
        assertThat(sessionService.login(email, passwordWithSpaces).outcome())
                .isEqualTo(SessionService.Outcome.SUCCESS);
    }

    @Test
    void workerBindsEligibleRecipientAndSendsOtpMail() throws Exception {
        String email = "worker-send@example.test";
        UUID userId = addActiveVerifiedUser(email, "strongPassword123");

        // Request recovery
        mvc.perform(post("/api/v1/auth/forgot-password")
                        .with(req -> { req.setRemoteAddr("10.0.2.1"); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isAccepted());

        CapturingMailSender capturer = (CapturingMailSender) mailSender;
        assertThat(capturer.sent).isEmpty();

        // Run worker cycle
        worker.runJobCycle();

        assertThat(capturer.sent).hasSize(1);
        SimpleMailMessage msg = capturer.sent.get(0);
        assertThat(msg.getTo()).containsExactly(email);
        assertThat(msg.getSubject()).isEqualTo("Password Recovery Code");
        assertThat(msg.getText()).contains("Your password recovery code is: ");

        // Job status SUCCESS and ciphertext scrubbed
        var jobRow = jdbc.queryForMap(
                "select status, payload from background_jobs where job_kind='AUTH_RESET_PASSWORD' and requested_by=?",
                userId
        );
        assertThat(jobRow.get("status")).isEqualTo("SUCCESS");
        assertThat(jobRow.get("payload").toString()).doesNotContain("ciphertext");

        // Challenge token is now bound to userId
        UUID boundUser = jdbc.queryForObject(
                "select user_id from auth_tokens where email=cast(? as citext) and purpose='RESET_PASSWORD'",
                UUID.class, email
        );
        assertThat(boundUser).isEqualTo(userId);
    }

    @Test
    void workerTerminatesJobForIneligibleRecipientAndConsumesNeverBoundChallenge() throws Exception {
        String email = "ineligible-user@example.test";
        addPendingUser(email);

        mvc.perform(post("/api/v1/auth/forgot-password")
                        .with(req -> { req.setRemoteAddr("10.0.3.1"); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isAccepted());

        worker.runJobCycle();

        CapturingMailSender capturer = (CapturingMailSender) mailSender;
        assertThat(capturer.sent).isEmpty();

        // Token consumed
        Instant usedAt = jdbc.queryForObject(
                "select used_at from auth_tokens where email=cast(? as citext) and purpose='RESET_PASSWORD'",
                Instant.class, email
        );
        assertThat(usedAt).isNotNull();

        // Job marked FAILED
        String status = jdbc.queryForObject(
                "select status from background_jobs where job_kind='AUTH_RESET_PASSWORD' and idempotency_key in (select cast(id as text) from auth_tokens where email=cast(? as citext))",
                String.class, email
        );
        assertThat(status).isEqualTo("FAILED");
    }

    @Test
    void blockedSmtpProvesNoDbLocksAndConcurrentRetirementOrConsumption() throws Exception {
        String email = "blocked-smtp@example.test";
        UUID userId = addActiveVerifiedUser(email, "strongPassword123");

        mvc.perform(post("/api/v1/auth/forgot-password")
                        .with(req -> { req.setRemoteAddr("10.0.4.1"); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isAccepted());

        CapturingMailSender capturer = (CapturingMailSender) mailSender;
        CountDownLatch sendEntered = new CountDownLatch(1);
        CountDownLatch unblockLatch = new CountDownLatch(1);
        capturer.sendLatch = sendEntered;
        capturer.unblockLatch = unblockLatch;
        capturer.failNext.set(true); // SMTP delivery will fail

        var pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> workerFuture = pool.submit(() -> worker.runJobCycle());

            // Wait until worker enters send (outside DB transactions/locks!)
            assertThat(sendEntered.await(5, TimeUnit.SECONDS)).isTrue();

            // Prove no DB locks across SMTP: concurrent consumption and user lock commit without waiting
            int consumed = jdbc.update(
                    "UPDATE auth_tokens SET used_at = clock_timestamp() WHERE email=cast(? as citext) AND purpose='RESET_PASSWORD'",
                    email
            );
            assertThat(consumed).isEqualTo(1);

            // Unblock sender
            unblockLatch.countDown();
            workerFuture.get(5, TimeUnit.SECONDS);

            // Worker recorded failure due to failNext
            String statusAfterFailure = jdbc.queryForObject(
                    "select status from background_jobs where job_kind='AUTH_RESET_PASSWORD' and requested_by=?",
                    String.class, userId
            );
            assertThat(statusAfterFailure).isEqualTo("FAILED");
            assertThat(capturer.sendAttempts.get()).isEqualTo(1);
            assertThat(capturer.sent).isEmpty();

            // Advance scheduled_at to make it eligible for retry poll
            jdbc.update("update background_jobs set scheduled_at = clock_timestamp() - interval '1 second' where job_kind='AUTH_RESET_PASSWORD' and requested_by=?", userId);

            // Next worker poll suppresses stale retry and scrubs ciphertext because token was consumed!
            worker.runJobCycle();

            // Failed send retry does NOT call transport again!
            assertThat(capturer.sendAttempts.get()).isEqualTo(1);

            var jobRow = jdbc.queryForMap(
                    "select status, last_error, payload, finished_at from background_jobs where job_kind='AUTH_RESET_PASSWORD' and requested_by=?",
                    userId
            );
            assertThat(jobRow.get("status")).isEqualTo("FAILED");
            assertThat(jobRow.get("finished_at")).isNotNull();
            assertThat(jobRow.get("last_error")).isIn("TOKEN_UNUSABLE", "STALE_OR_INELIGIBLE");
            assertThat(jobRow.get("payload").toString()).doesNotContain("ciphertext");

            // Consumed code cannot verify
            assertThat(capturer.lastAttempted).isNotNull();
            String mailText = capturer.lastAttempted.getText();
            String code = mailText.substring(mailText.lastIndexOf(':') + 1).trim();

            mvc.perform(post("/api/v1/auth/forgot-password/verify")
                            .with(req -> { req.setRemoteAddr("10.0.4.2"); return req; })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"" + email + "\",\"code\":\"" + code + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("RESET_CODE_INVALID"));

        } finally {
            unblockLatch.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void codeVerificationIssuesResetGrantAndEnforcesAttemptLimit() throws Exception {
        String email = "verify-flow@example.test";
        UUID userId = addActiveVerifiedUser(email, "initialPassword123");

        mvc.perform(post("/api/v1/auth/forgot-password")
                        .with(req -> { req.setRemoteAddr("10.0.5.1"); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isAccepted());

        CapturingMailSender capturer = (CapturingMailSender) mailSender;
        worker.runJobCycle();
        assertThat(capturer.sent).hasSize(1);
        String text = capturer.sent.get(0).getText();
        String code = text.substring(text.lastIndexOf(':') + 1).trim();

        // 4 failed attempts with wrong code
        String wrongCode = String.format("%06d", (Integer.parseInt(code) + 1) % 1_000_000);
        for (int i = 1; i <= 4; i++) {
            final String peer = "10.0.5." + (i + 1);
            mvc.perform(post("/api/v1/auth/forgot-password/verify")
                            .with(req -> { req.setRemoteAddr(peer); return req; })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"" + email + "\",\"code\":\"" + wrongCode + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("RESET_CODE_INVALID"));

            int attempts = jdbc.queryForObject(
                    "select attempts from auth_tokens where user_id=? and purpose='RESET_PASSWORD'",
                    Integer.class, userId
            );
            assertThat(attempts).isEqualTo(i);
        }

        // 5th attempt with valid code succeeds
        MvcResult verifyRes = mvc.perform(post("/api/v1/auth/forgot-password/verify")
                        .with(req -> { req.setRemoteAddr("10.0.5.10"); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.resetGrant").isString())
                .andExpect(jsonPath("$.expiresAt").isString())
                .andReturn();

        String rawGrant = new ObjectMapper().readTree(verifyRes.getResponse().getContentAsString()).get("resetGrant").asText();
        assertThat(rawGrant).hasSize(43);

        // Recovery token is consumed
        Instant usedAt = jdbc.queryForObject(
                "select used_at from auth_tokens where user_id=? and purpose='RESET_PASSWORD'",
                Instant.class, userId
        );
        assertThat(usedAt).isNotNull();

        // Reset grant token persisted with purpose RESET_PASSWORD_GRANT, attempts 0
        String grantDigest = PasswordResetGrantToken.digest(rawGrant);
        int grantCount = jdbc.queryForObject(
                "select count(*) from auth_tokens where token_hash=? and purpose='RESET_PASSWORD_GRANT' and attempts=0 and user_id=?",
                Integer.class, grantDigest, userId
        );
        assertThat(grantCount).isEqualTo(1);
    }

    @Test
    void unusableOtpUniformityAcrossExpiredExhaustedUnboundAndWrongPurpose() throws Exception {
        // 1. Wrong guess on live token -> 400 RESET_CODE_INVALID
        String wrongGuessEmail = "unusable-wrong-guess@example.test";
        UUID user1 = addActiveVerifiedUser(wrongGuessEmail, "pass12345678");

        mvc.perform(post("/api/v1/auth/forgot-password")
                        .with(req -> { req.setRemoteAddr("10.0.14.1"); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + wrongGuessEmail + "\"}"))
                .andExpect(status().isAccepted());
        worker.runJobCycle();

        CapturingMailSender capturer = (CapturingMailSender) mailSender;
        assertThat(capturer.sent).hasSize(1);
        String mailText = capturer.sent.get(0).getText();
        String realCode = mailText.substring(mailText.lastIndexOf(':') + 1).trim();

        // Wrong guess deterministically different from real captured code
        String wrongGuess = String.format("%06d", (Integer.parseInt(realCode) + 1) % 1_000_000);

        MvcResult wrongGuessResult = mvc.perform(post("/api/v1/auth/forgot-password/verify")
                        .with(req -> { req.setRemoteAddr("10.0.14.2"); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + wrongGuessEmail + "\",\"code\":\"" + wrongGuess + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RESET_CODE_INVALID"))
                .andReturn();

        String uniformRejectionBody = wrongGuessResult.getResponse().getContentAsString();

        // 2. Unbound token -> 400 RESET_CODE_INVALID
        String unboundEmail = "unusable-unbound@example.test";
        addActiveVerifiedUser(unboundEmail, "pass12345678");
        mvc.perform(post("/api/v1/auth/forgot-password")
                        .with(req -> { req.setRemoteAddr("10.0.14.3"); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + unboundEmail + "\"}"))
                .andExpect(status().isAccepted());

        MvcResult unboundResult = mvc.perform(post("/api/v1/auth/forgot-password/verify")
                        .with(req -> { req.setRemoteAddr("10.0.14.4"); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + unboundEmail + "\",\"code\":\"123456\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RESET_CODE_INVALID"))
                .andReturn();
        assertThat(unboundResult.getResponse().getContentAsString()).isEqualTo(uniformRejectionBody);

        // 3. Expired token (historical created_at and expiry without altering triggers)
        String expiredEmail = "unusable-expired@example.test";
        UUID user3 = addActiveVerifiedUser(expiredEmail, "pass12345678");
        UUID token3 = UUID.randomUUID();
        String code3 = "234567";
        String hash3 = crypto.hash(token3, code3, VerificationCodeCrypto.Purpose.RESET_PASSWORD);
        jdbc.update("""
                insert into auth_tokens(id, user_id, email, purpose, token_hash, created_at, expires_at, attempts)
                values (?, ?, cast(? as citext), 'RESET_PASSWORD', ?, clock_timestamp() - interval '20 minutes', clock_timestamp() - interval '10 minutes', 0)
                """, token3, user3, expiredEmail, hash3);

        MvcResult expiredResult = mvc.perform(post("/api/v1/auth/forgot-password/verify")
                        .with(req -> { req.setRemoteAddr("10.0.14.5"); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + expiredEmail + "\",\"code\":\"" + code3 + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RESET_CODE_INVALID"))
                .andReturn();
        assertThat(expiredResult.getResponse().getContentAsString()).isEqualTo(uniformRejectionBody);

        // 4. Exhausted attempts -> 400 RESET_CODE_INVALID
        String exhaustedEmail = "unusable-exhausted@example.test";
        UUID user4 = addActiveVerifiedUser(exhaustedEmail, "pass12345678");
        UUID token4 = UUID.randomUUID();
        String code4 = "345678";
        String hash4 = crypto.hash(token4, code4, VerificationCodeCrypto.Purpose.RESET_PASSWORD);
        jdbc.update("""
                insert into auth_tokens(id, user_id, email, purpose, token_hash, created_at, expires_at, attempts)
                values (?, ?, cast(? as citext), 'RESET_PASSWORD', ?, clock_timestamp(), clock_timestamp() + interval '10 minutes', 5)
                """, token4, user4, exhaustedEmail, hash4);

        MvcResult exhaustedResult = mvc.perform(post("/api/v1/auth/forgot-password/verify")
                        .with(req -> { req.setRemoteAddr("10.0.14.6"); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + exhaustedEmail + "\",\"code\":\"" + code4 + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RESET_CODE_INVALID"))
                .andReturn();
        assertThat(exhaustedResult.getResponse().getContentAsString()).isEqualTo(uniformRejectionBody);

        // 5. Used token -> 400 RESET_CODE_INVALID
        String usedEmail = "unusable-used@example.test";
        UUID user5 = addActiveVerifiedUser(usedEmail, "pass12345678");
        UUID token5 = UUID.randomUUID();
        String code5 = "456789";
        String hash5 = crypto.hash(token5, code5, VerificationCodeCrypto.Purpose.RESET_PASSWORD);
        jdbc.update("""
                insert into auth_tokens(id, user_id, email, purpose, token_hash, created_at, expires_at, used_at, attempts)
                values (?, ?, cast(? as citext), 'RESET_PASSWORD', ?, clock_timestamp() - interval '5 minutes', clock_timestamp() + interval '5 minutes', clock_timestamp() - interval '2 minutes', 0)
                """, token5, user5, usedEmail, hash5);

        MvcResult usedResult = mvc.perform(post("/api/v1/auth/forgot-password/verify")
                        .with(req -> { req.setRemoteAddr("10.0.14.7"); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + usedEmail + "\",\"code\":\"" + code5 + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RESET_CODE_INVALID"))
                .andReturn();
        assertThat(usedResult.getResponse().getContentAsString()).isEqualTo(uniformRejectionBody);

        // 6. Wrong-purpose token (VERIFY_EMAIL token with valid VERIFY_EMAIL HMAC) -> 400 RESET_CODE_INVALID
        String wrongPurposeEmail = "unusable-wrong-purpose@example.test";
        UUID user6 = addActiveVerifiedUser(wrongPurposeEmail, "pass12345678");
        UUID token6 = UUID.randomUUID();
        String code6 = "567890";
        String hash6 = crypto.hash(token6, code6, VerificationCodeCrypto.Purpose.VERIFY_EMAIL);
        jdbc.update("""
                insert into auth_tokens(id, user_id, email, purpose, token_hash, created_at, expires_at, attempts)
                values (?, ?, cast(? as citext), 'VERIFY_EMAIL', ?, clock_timestamp(), clock_timestamp() + interval '10 minutes', 0)
                """, token6, user6, wrongPurposeEmail, hash6);

        MvcResult wrongPurposeResult = mvc.perform(post("/api/v1/auth/forgot-password/verify")
                        .with(req -> { req.setRemoteAddr("10.0.14.8"); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + wrongPurposeEmail + "\",\"code\":\"" + code6 + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RESET_CODE_INVALID"))
                .andReturn();
        assertThat(wrongPurposeResult.getResponse().getContentAsString()).isEqualTo(uniformRejectionBody);
    }

    @Test
    void passwordResetUpdatesPasswordAndRevokesEverySessionRow() throws Exception {
        String email = "reset-sessions@example.test";
        String oldPassword = "oldPassword123456";
        String newPassword = "newPasswordUnicode! 🌟 128Chars" + "a".repeat(80);
        UUID userId = addActiveVerifiedUser(email, oldPassword);

        // Create 3 sessions across 2 families using SessionService
        var session1 = sessionService.login(email, oldPassword);
        assertThat(session1.outcome()).isEqualTo(SessionService.Outcome.SUCCESS);
        var session2 = sessionService.login(email, oldPassword);
        assertThat(session2.outcome()).isEqualTo(SessionService.Outcome.SUCCESS);
        var rotated1 = sessionService.refresh(session1.refresh());
        assertThat(rotated1.outcome()).isEqualTo(SessionService.Outcome.SUCCESS);

        int totalSessionsBefore = jdbc.queryForObject(
                "select count(*) from auth_sessions where user_id=? and revoked_at is null",
                Integer.class, userId
        );
        assertThat(totalSessionsBefore).isGreaterThanOrEqualTo(2);

        // Obtain valid reset grant
        mvc.perform(post("/api/v1/auth/forgot-password")
                        .with(req -> { req.setRemoteAddr("10.0.6.1"); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isAccepted());

        CapturingMailSender capturer = (CapturingMailSender) mailSender;
        worker.runJobCycle();
        String text = capturer.sent.get(0).getText();
        String code = text.substring(text.lastIndexOf(':') + 1).trim();

        MvcResult verifyRes = mvc.perform(post("/api/v1/auth/forgot-password/verify")
                        .with(req -> { req.setRemoteAddr("10.0.6.2"); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andReturn();

        String grant = json.readTree(verifyRes.getResponse().getContentAsString()).get("resetGrant").asText();

        // Complete reset
        MvcResult resetRes = mvc.perform(post("/api/v1/auth/reset-password")
                        .with(req -> { req.setRemoteAddr("10.0.6.3"); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "resetGrant", grant,
                                "newPassword", newPassword
                        ))))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.revokedSessions").isNumber())
                .andReturn();

        int reportedRevoked = json.readTree(resetRes.getResponse().getContentAsString()).get("revokedSessions").asInt();
        assertThat(reportedRevoked).isEqualTo(totalSessionsBefore);

        // All sessions for this user are now revoked
        int unrevokedSessions = jdbc.queryForObject(
                "select count(*) from auth_sessions where user_id=? and revoked_at is null",
                Integer.class, userId
        );
        assertThat(unrevokedSessions).isZero();

        // Old access and refresh tokens now fail
        assertThat(sessionService.authenticate(rotated1.access().value())).isEmpty();
        assertThat(sessionService.authenticate(session2.access().value())).isEmpty();
        assertThat(sessionService.refresh(rotated1.refresh()).outcome()).isEqualTo(SessionService.Outcome.INVALID_SESSION);

        // Old password fails login; new password succeeds
        assertThat(sessionService.login(email, oldPassword).outcome()).isEqualTo(SessionService.Outcome.INVALID_CREDENTIALS);
        var newLogin = sessionService.login(email, newPassword);
        assertThat(newLogin.outcome()).isEqualTo(SessionService.Outcome.SUCCESS);

        // Used grant cannot be reused
        mvc.perform(post("/api/v1/auth/reset-password")
                        .with(req -> { req.setRemoteAddr("10.0.6.4"); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "resetGrant", grant,
                                "newPassword", "anotherNewPassword123"
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RESET_GRANT_INVALID"));
    }

    @Test
    void concurrentResetPasswordExecutesExactlyOnce() throws Exception {
        String email = "race-reset@example.test";
        UUID userId = addActiveVerifiedUser(email, "pass12345678");

        mvc.perform(post("/api/v1/auth/forgot-password")
                        .with(req -> { req.setRemoteAddr("10.0.7.1"); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isAccepted());

        CapturingMailSender capturer = (CapturingMailSender) mailSender;
        worker.runJobCycle();
        String text = capturer.sent.get(0).getText();
        String code = text.substring(text.lastIndexOf(':') + 1).trim();

        MvcResult verifyRes = mvc.perform(post("/api/v1/auth/forgot-password/verify")
                        .with(req -> { req.setRemoteAddr("10.0.7.2"); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andReturn();

        String grant = json.readTree(verifyRes.getResponse().getContentAsString()).get("resetGrant").asText();

        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Integer> call = () -> {
                start.await();
                return mvc.perform(post("/api/v1/auth/reset-password")
                                .with(req -> { req.setRemoteAddr("10.0.7." + Thread.currentThread().getId()); return req; })
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(Map.of(
                                        "resetGrant", grant,
                                        "newPassword", "newPassword123456"
                                ))))
                        .andReturn().getResponse().getStatus();
            };

            var f1 = pool.submit(call);
            var f2 = pool.submit(call);
            start.countDown();

            List<Integer> statuses = List.of(f1.get(10, TimeUnit.SECONDS), f2.get(10, TimeUnit.SECONDS));
            assertThat(statuses).containsExactlyInAnyOrder(200, 400);
        }
    }

    @Test
    void rollbackFixtureLeavesOldHashAndGrantIntactOnWriteFailure() throws Exception {
        String email = "rollback-fixture@example.test";
        String initialPassword = "oldInitialPassword123";
        UUID userId = addActiveVerifiedUser(email, initialPassword);

        // Log in to create a session
        var login = sessionService.login(email, initialPassword);
        assertThat(login.outcome()).isEqualTo(SessionService.Outcome.SUCCESS);

        mvc.perform(post("/api/v1/auth/forgot-password")
                        .with(req -> { req.setRemoteAddr("10.0.8.1"); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isAccepted());

        CapturingMailSender capturer = (CapturingMailSender) mailSender;
        worker.runJobCycle();
        String text = capturer.sent.get(0).getText();
        String code = text.substring(text.lastIndexOf(':') + 1).trim();

        MvcResult verifyRes = mvc.perform(post("/api/v1/auth/forgot-password/verify")
                        .with(req -> { req.setRemoteAddr("10.0.8.2"); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andReturn();

        String grant = json.readTree(verifyRes.getResponse().getContentAsString()).get("resetGrant").asText();

        // Create temporary trigger that raises exception on auth_sessions update
        jdbc.execute("""
                create or replace function fail_session_update() returns trigger as $$
                begin
                    raise exception 'Test injected DB failure';
                end;
                $$ language plpgsql;
                """);
        jdbc.execute("""
                create trigger trg_fail_session_update
                before update on auth_sessions
                for each row execute function fail_session_update();
                """);

        try {
            // Reset should fail due to DB trigger
            assertThatThrownBy(() -> mvc.perform(post("/api/v1/auth/reset-password")
                            .with(req -> { req.setRemoteAddr("10.0.8.3"); return req; })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of(
                                    "resetGrant", grant,
                                    "newPassword", "newPasswordFailedRollback"
                              )))))
                    .isNotNull();

            // Verify rollback: password hash unchanged, grant unused, sessions not revoked
            assertThat(sessionService.login(email, initialPassword).outcome()).isEqualTo(SessionService.Outcome.SUCCESS);
            String grantDigest = PasswordResetGrantToken.digest(grant);
            Instant grantUsedAt = jdbc.queryForObject(
                    "select used_at from auth_tokens where token_hash=?",
                    Instant.class, grantDigest
            );
            assertThat(grantUsedAt).isNull();

            int unrevoked = jdbc.queryForObject(
                    "select count(*) from auth_sessions where user_id=? and revoked_at is null",
                    Integer.class, userId
            );
            assertThat(unrevoked).isGreaterThanOrEqualTo(1);

        } finally {
            // Drop temporary failure fixture
            jdbc.execute("drop trigger if exists trg_fail_session_update on auth_sessions");
            jdbc.execute("drop function if exists fail_session_update");
        }

        // Now retry reset -> succeeds
        mvc.perform(post("/api/v1/auth/reset-password")
                        .with(req -> { req.setRemoteAddr("10.0.8.4"); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "resetGrant", grant,
                                "newPassword", "newPasswordRetrySuccess123"
                        ))))
                .andExpect(status().isOk());

        assertThat(sessionService.login(email, "newPasswordRetrySuccess123").outcome())
                .isEqualTo(SessionService.Outcome.SUCCESS);
    }

    private UUID addActiveVerifiedUser(String email, String password) {
        UUID id = UUID.randomUUID();
        String hash = encoder.encode(password);
        jdbc.update("""
                insert into users(id, email, display_name, status, email_verified_at, password_hash)
                values (?, cast(? as citext), 'Active User', 'ACTIVE', clock_timestamp() - interval '1 day', ?)
                """, id, email, hash);
        return id;
    }

    private UUID addPendingUser(String email) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into users(id, email, display_name, status)
                values (?, cast(? as citext), 'Pending User', 'PENDING')
                """, id, email);
        return id;
    }

    private UUID addLockedUser(String email) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into users(id, email, display_name, status, email_verified_at)
                values (?, cast(? as citext), 'Locked User', 'LOCKED', clock_timestamp() - interval '1 day')
                """, id, email);
        return id;
    }
}

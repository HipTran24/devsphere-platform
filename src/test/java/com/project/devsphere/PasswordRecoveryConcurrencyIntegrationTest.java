package com.project.devsphere;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.devsphere.identity.domain.PasswordResetGrantToken;
import com.project.devsphere.identity.domain.SessionTokens;
import com.project.devsphere.identity.domain.VerificationCodeCrypto;
import com.project.devsphere.identity.infrastructure.mail.VerificationMailWorker;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
@Import(PasswordRecoveryIntegrationTest.TestMailConfig.class)
@AutoConfigureMockMvc
class PasswordRecoveryConcurrencyIntegrationTest {

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        PasswordRecoveryIntegrationTest.configure(registry);
    }

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private JavaMailSender mailSender;
    @Autowired private VerificationMailWorker worker;
    @Autowired private VerificationCodeCrypto crypto;
    @Autowired private SessionTokens sessionTokens;

    private final ObjectMapper json = new ObjectMapper();
    private final Argon2PasswordEncoder encoder = new Argon2PasswordEncoder(16, 32, 1, 19456, 2);
    private static final AtomicInteger peerCounter = new AtomicInteger(5000);

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

    private String nextPeer() {
        int seq = peerCounter.incrementAndGet();
        return "10.99." + ((seq / 250) % 250) + "." + (seq % 250);
    }

    private UUID addActiveVerifiedUser(String email, String rawPassword) {
        UUID id = UUID.randomUUID();
        String hash = encoder.encode(rawPassword);
        jdbc.update("""
                INSERT INTO users(id, email, display_name, status, email_verified_at, password_hash)
                VALUES (?, cast(? as citext), 'Concurrency User', 'ACTIVE', clock_timestamp() - INTERVAL '1 day', ?)
                """, id, email, hash);
        return id;
    }

    private record BrowserCsrf(Cookie cookie, String headerName, String token) {}

    private BrowserCsrf getBootCsrf() throws Exception {
        MvcResult res = mvc.perform(get("/api/v1/auth/csrf").secure(true))
                .andExpect(status().isOk())
                .andReturn();
        Cookie cookie = res.getResponse().getCookie("__Host-devsphere-csrf");
        assertThat(cookie).isNotNull();
        JsonNode body = json.readTree(res.getResponse().getContentAsString());
        return new BrowserCsrf(cookie, body.get("headerName").asText(), body.get("token").asText());
    }

    private String extractDeliveredOtp(String recipient, String expectedSubject) {
        if (!(mailSender instanceof PasswordRecoveryIntegrationTest.CapturingMailSender capturer)) {
            throw new IllegalStateException("mailSender is not CapturingMailSender");
        }
        synchronized (capturer.sent) {
            for (SimpleMailMessage msg : capturer.sent) {
                if (msg.getTo() != null && List.of(msg.getTo()).contains(recipient)) {
                    if (expectedSubject == null || expectedSubject.equals(msg.getSubject())) {
                        String text = msg.getText();
                        if (text != null && text.contains(": ")) {
                            return text.substring(text.lastIndexOf(':') + 1).trim();
                        }
                    }
                }
            }
        }
        throw new AssertionError("No captured email found for recipient: " + recipient + ", subject: " + expectedSubject);
    }

    private long getBackendPid(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT pg_backend_pid()")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private Long waitForBlockedPid(long blockerPid, Duration timeout) throws Exception {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            List<Long> blocked = jdbc.query(
                    "SELECT pid FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))",
                    (rs, rowNum) -> rs.getLong("pid"),
                    blockerPid
            );
            if (!blocked.isEmpty()) {
                return blocked.get(0);
            }
            Thread.sleep(20);
        }
        throw new TimeoutException("Timed out waiting for process blocked by PID " + blockerPid);
    }

    @Test
    void twoSimultaneousCorrectOtpExchangesProduceExactlyOneGrantAndOneInvalidCodeError() throws Exception {
        String email = "simultaneous-otp-" + UUID.randomUUID() + "@example.test";
        String password = "SimultaneousUserPassword123";
        UUID userId = addActiveVerifiedUser(email, password);

        // 1. Request recovery OTP
        mvc.perform(post("/api/v1/auth/forgot-password")
                        .with(req -> { req.setRemoteAddr(nextPeer()); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email))))
                .andExpect(status().isAccepted());

        // 2. Deliver OTP via worker and captured mail
        worker.runJobCycle();
        String validOtp = extractDeliveredOtp(email, "Password Recovery Code");
        assertThat(validOtp).matches("^[0-9]{6}$");

        // 3. Two simultaneous contenders exchange the same valid OTP
        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        try {
            Callable<MvcResult> contender1 = () -> {
                startLatch.await();
                return mvc.perform(post("/api/v1/auth/forgot-password/verify")
                                .with(req -> { req.setRemoteAddr(nextPeer()); return req; })
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(Map.of("email", email, "code", validOtp))))
                        .andReturn();
            };

            Callable<MvcResult> contender2 = () -> {
                startLatch.await();
                return mvc.perform(post("/api/v1/auth/forgot-password/verify")
                                .with(req -> { req.setRemoteAddr(nextPeer()); return req; })
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(Map.of("email", email, "code", validOtp))))
                        .andReturn();
            };

            Future<MvcResult> f1 = pool.submit(contender1);
            Future<MvcResult> f2 = pool.submit(contender2);

            startLatch.countDown();

            MvcResult r1 = f1.get(10, TimeUnit.SECONDS);
            MvcResult r2 = f2.get(10, TimeUnit.SECONDS);

            List<MvcResult> responses = List.of(r1, r2);
            List<Integer> statuses = responses.stream().map(r -> r.getResponse().getStatus()).toList();
            assertThat(statuses).containsExactlyInAnyOrder(200, 400);

            MvcResult successResult = responses.stream().filter(r -> r.getResponse().getStatus() == 200).findFirst().orElseThrow();
            MvcResult failureResult = responses.stream().filter(r -> r.getResponse().getStatus() == 400).findFirst().orElseThrow();

            // Verify successful response exposes valid raw grant
            assertThat(successResult.getResponse().getHeaderNames()).noneMatch(h -> h.equalsIgnoreCase(HttpHeaders.SET_COOKIE));
            assertThat(successResult.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
            JsonNode successBody = json.readTree(successResult.getResponse().getContentAsString());
            String rawGrant = successBody.get("resetGrant").asText();
            assertThat(rawGrant).hasSize(43);
            assertThat(successBody.get("expiresAt").asText()).isNotBlank();

            // Verify rejected contender receives uniform RESET_CODE_INVALID and never exposes the raw grant
            assertThat(failureResult.getResponse().getHeaderNames()).noneMatch(h -> h.equalsIgnoreCase(HttpHeaders.SET_COOKIE));
            assertThat(failureResult.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
            String failureBodyStr = failureResult.getResponse().getContentAsString();
            JsonNode failureBody = json.readTree(failureBodyStr);
            assertThat(failureBody.get("code").asText()).isEqualTo("RESET_CODE_INVALID");
            assertThat(failureBodyStr).doesNotContain(rawGrant);

            // DB assertions: exactly one new grant exists, OTP is consumed once, wrong attempts remain 0
            int grantCount = jdbc.queryForObject(
                    "SELECT count(*) FROM auth_tokens WHERE user_id = ? AND purpose = 'RESET_PASSWORD_GRANT' AND used_at IS NULL",
                    Integer.class, userId
            );
            assertThat(grantCount).isEqualTo(1);

            Map<String, Object> otpTokenRow = jdbc.queryForMap(
                    "SELECT used_at, attempts FROM auth_tokens WHERE user_id = ? AND purpose = 'RESET_PASSWORD'",
                    userId
            );
            assertThat(otpTokenRow.get("used_at")).isNotNull();
            assertThat(((Number) otpTokenRow.get("attempts")).intValue()).isEqualTo(0);

            // The issued grant successfully resets password
            mvc.perform(post("/api/v1/auth/reset-password")
                            .with(req -> { req.setRemoteAddr(nextPeer()); return req; })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of(
                                    "resetGrant", rawGrant,
                                    "newPassword", "NewSimultaneousPassword123"
                            ))))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));

        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void subsequentValidOtpExchangeRetiresPriorUnusedGrantAtomically() throws Exception {
        String userAEmail = "retire-grant-a-" + UUID.randomUUID() + "@example.test";
        String userAPassword = "UserAInitialPassword123";
        UUID userAId = addActiveVerifiedUser(userAEmail, userAPassword);

        // Separate user B with their own grant and separate purpose token
        String userBEmail = "retire-grant-b-" + UUID.randomUUID() + "@example.test";
        String userBPassword = "UserBInitialPassword123";
        UUID userBId = addActiveVerifiedUser(userBEmail, userBPassword);

        PasswordResetGrantToken userBGrant = PasswordResetGrantToken.generate();
        UUID userBGrantId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO auth_tokens(id, user_id, email, purpose, token_hash, created_at, expires_at, used_at, attempts)
                VALUES (?, ?, cast(? as citext), 'RESET_PASSWORD_GRANT', ?, clock_timestamp(), clock_timestamp() + INTERVAL '10 minutes', NULL, 0)
                """, userBGrantId, userBId, userBEmail, userBGrant.digest());

        UUID userBVerifyTokenId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO auth_tokens(id, user_id, email, purpose, token_hash, created_at, expires_at, used_at, attempts)
                VALUES (?, ?, cast(? as citext), 'VERIFY_EMAIL', 'dummyVerifyB', clock_timestamp(), clock_timestamp() + INTERVAL '1 hour', NULL, 0)
                """, userBVerifyTokenId, userBId, userBEmail);

        // User A also has a separate VERIFY_EMAIL token
        UUID userAVerifyTokenId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO auth_tokens(id, user_id, email, purpose, token_hash, created_at, expires_at, used_at, attempts)
                VALUES (?, ?, cast(? as citext), 'VERIFY_EMAIL', 'dummyVerifyA', clock_timestamp(), clock_timestamp() + INTERVAL '1 hour', NULL, 0)
                """, userAVerifyTokenId, userAId, userAEmail);

        // Legitimate older recovery token #1 for User A created 70 seconds ago (satisfies 60s cooldown for subsequent intake)
        UUID challenge1 = UUID.randomUUID();
        String otp1 = "112233";
        String tokenHash1 = crypto.hash(challenge1, otp1, VerificationCodeCrypto.Purpose.RESET_PASSWORD);
        OffsetDateTime pastCreated = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(70);
        OffsetDateTime pastExpires = pastCreated.plusMinutes(10);
        jdbc.update("""
                INSERT INTO auth_tokens(id, user_id, email, purpose, token_hash, created_at, expires_at, used_at, attempts)
                VALUES (?, ?, cast(? as citext), 'RESET_PASSWORD', ?, ?, ?, NULL, 0)
                """, challenge1, userAId, userAEmail, tokenHash1, pastCreated, pastExpires);

        // Exchange OTP 1 for Grant 1
        MvcResult grant1Res = mvc.perform(post("/api/v1/auth/forgot-password/verify")
                        .with(req -> { req.setRemoteAddr(nextPeer()); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", userAEmail, "code", otp1))))
                .andExpect(status().isOk())
                .andReturn();

        String rawGrant1 = json.readTree(grant1Res.getResponse().getContentAsString()).get("resetGrant").asText();
        assertThat(rawGrant1).hasSize(43);

        // Verify Grant 1 is currently active and unused
        Instant grant1UsedAtBefore = jdbc.queryForObject(
                "SELECT used_at FROM auth_tokens WHERE token_hash = ?", Instant.class, PasswordResetGrantToken.digest(rawGrant1)
        );
        assertThat(grant1UsedAtBefore).isNull();

        // Request second OTP via HTTP intake: previous token creation was 70s ago, so 60s cooldown is satisfied
        mvc.perform(post("/api/v1/auth/forgot-password")
                        .with(req -> { req.setRemoteAddr(nextPeer()); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", userAEmail))))
                .andExpect(status().isAccepted());

        worker.runJobCycle();
        String otp2 = extractDeliveredOtp(userAEmail, "Password Recovery Code");
        assertThat(otp2).matches("^[0-9]{6}$");

        // Exchange OTP 2 for Grant 2
        MvcResult grant2Res = mvc.perform(post("/api/v1/auth/forgot-password/verify")
                        .with(req -> { req.setRemoteAddr(nextPeer()); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", userAEmail, "code", otp2))))
                .andExpect(status().isOk())
                .andReturn();

        String rawGrant2 = json.readTree(grant2Res.getResponse().getContentAsString()).get("resetGrant").asText();
        assertThat(rawGrant2).hasSize(43);
        assertThat(rawGrant2).isNotEqualTo(rawGrant1);

        // Grant 1 has been retired atomically upon issuance of Grant 2
        Instant grant1UsedAtAfter = jdbc.queryForObject(
                "SELECT used_at FROM auth_tokens WHERE token_hash = ?", Instant.class, PasswordResetGrantToken.digest(rawGrant1)
        );
        assertThat(grant1UsedAtAfter).isNotNull();

        // Grant 2 is active and unused
        Instant grant2UsedAt = jdbc.queryForObject(
                "SELECT used_at FROM auth_tokens WHERE token_hash = ?", Instant.class, PasswordResetGrantToken.digest(rawGrant2)
        );
        assertThat(grant2UsedAt).isNull();

        // Other users' grants and other purpose tokens remain completely untouched
        Instant userBGrantUsedAt = jdbc.queryForObject(
                "SELECT used_at FROM auth_tokens WHERE id = ?", Instant.class, userBGrantId
        );
        assertThat(userBGrantUsedAt).isNull();

        Instant userBVerifyUsedAt = jdbc.queryForObject(
                "SELECT used_at FROM auth_tokens WHERE id = ?", Instant.class, userBVerifyTokenId
        );
        assertThat(userBVerifyUsedAt).isNull();

        Instant userAVerifyUsedAt = jdbc.queryForObject(
                "SELECT used_at FROM auth_tokens WHERE id = ?", Instant.class, userAVerifyTokenId
        );
        assertThat(userAVerifyUsedAt).isNull();

        // Old Grant 1 fails reset with RESET_GRANT_INVALID
        mvc.perform(post("/api/v1/auth/reset-password")
                        .with(req -> { req.setRemoteAddr(nextPeer()); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "resetGrant", rawGrant1,
                                "newPassword", "FailedGrant1Password123"
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("RESET_GRANT_INVALID"));

        // New Grant 2 succeeds
        mvc.perform(post("/api/v1/auth/reset-password")
                        .with(req -> { req.setRemoteAddr(nextPeer()); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "resetGrant", rawGrant2,
                                "newPassword", "SuccessGrant2Password123"
                        ))))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));

        // Password for User A is updated
        BrowserCsrf csrf = getBootCsrf();
        mvc.perform(post("/api/v1/auth/login")
                        .secure(true)
                        .cookie(csrf.cookie())
                        .header(csrf.headerName(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", userAEmail, "password", "SuccessGrant2Password123"))))
                .andExpect(status().isOk());
    }

    @Test
    void refreshCommittedFirstIsSubsequentlyIncludedInResetRevocation() throws Exception {
        String email = "refresh-first-" + UUID.randomUUID() + "@example.test";
        String initialPassword = "InitialRefreshPassword123";
        String newPassword = "NewResetPassword456";
        UUID userId = addActiveVerifiedUser(email, initialPassword);

        // User logs in via HTTP to create session 1
        BrowserCsrf bootCsrf = getBootCsrf();
        MvcResult loginRes = mvc.perform(post("/api/v1/auth/login")
                        .secure(true)
                        .cookie(bootCsrf.cookie())
                        .header(bootCsrf.headerName(), bootCsrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", initialPassword))))
                .andExpect(status().isOk())
                .andReturn();

        String oldAccessToken = json.readTree(loginRes.getResponse().getContentAsString()).get("accessToken").asText();
        Cookie oldRefreshCookie = loginRes.getResponse().getCookie("__Secure-devsphere-refresh");
        assertThat(oldRefreshCookie).isNotNull();

        UUID sessionId1 = jdbc.queryForObject(
                "SELECT id FROM auth_sessions WHERE user_id = ? AND revoked_at IS NULL", UUID.class, userId
        );
        assertThat(sessionId1).isNotNull();

        // Pre-create legitimate reset grant for this user
        PasswordResetGrantToken resetGrant = PasswordResetGrantToken.generate();
        jdbc.update("""
                INSERT INTO auth_tokens(id, user_id, email, purpose, token_hash, created_at, expires_at, used_at, attempts)
                VALUES (?, ?, cast(? as citext), 'RESET_PASSWORD_GRANT', ?, clock_timestamp(), clock_timestamp() + INTERVAL '10 minutes', NULL, 0)
                """, UUID.randomUUID(), userId, email, resetGrant.digest());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        Connection fixtureConn = dataSource.getConnection();
        fixtureConn.setAutoCommit(false);

        try {
            long fixturePid = getBackendPid(fixtureConn);

            // Fixture Tx locks the target session row for update (without locking users)
            try (PreparedStatement ps = fixtureConn.prepareStatement("SELECT id FROM auth_sessions WHERE id = ? FOR UPDATE")) {
                ps.setObject(1, sessionId1);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                }
            }

            // Thread A: HTTP refresh (locks user row, then blocks on sessionId1 held by fixtureConn)
            BrowserCsrf refreshCsrf = getBootCsrf();
            Callable<MvcResult> refreshCall = () -> mvc.perform(post("/api/v1/auth/refresh")
                            .secure(true)
                            .cookie(refreshCsrf.cookie(), oldRefreshCookie)
                            .header(refreshCsrf.headerName(), refreshCsrf.token()))
                    .andReturn();

            Future<MvcResult> refreshFuture = pool.submit(refreshCall);

            // Observe Thread A reached lock wait on fixturePid
            Long refreshPid = waitForBlockedPid(fixturePid, Duration.ofSeconds(5));
            assertThat(refreshPid).isNotNull();

            // Thread B: HTTP reset-password (hashes password, enters tx, blocks on user row held by refreshPid)
            Callable<MvcResult> resetCall = () -> mvc.perform(post("/api/v1/auth/reset-password")
                            .with(req -> { req.setRemoteAddr(nextPeer()); return req; })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of(
                                    "resetGrant", resetGrant.rawGrant(),
                                    "newPassword", newPassword
                            ))))
                    .andReturn();

            Future<MvcResult> resetFuture = pool.submit(resetCall);

            // Observe Thread B reached lock wait on refreshPid (deterministic proof of refresh-first serialization)
            Long resetPid = waitForBlockedPid(refreshPid, Duration.ofSeconds(5));
            assertThat(resetPid).isNotNull();

            // Release fixture lock: Thread A completes first (refresh commits), then Thread B unblocks (reset commits)
            fixtureConn.rollback();

            MvcResult refreshResult = refreshFuture.get(10, TimeUnit.SECONDS);
            MvcResult resetResult = resetFuture.get(10, TimeUnit.SECONDS);

            // Refresh succeeded and issued new rotated credentials
            assertThat(refreshResult.getResponse().getStatus()).isEqualTo(200);
            String rotatedAccessToken = json.readTree(refreshResult.getResponse().getContentAsString()).get("accessToken").asText();
            Cookie rotatedRefreshCookie = refreshResult.getResponse().getCookie("__Secure-devsphere-refresh");
            assertThat(rotatedRefreshCookie).isNotNull();

            // Reset succeeded and reported revocation of all sessions
            assertThat(resetResult.getResponse().getStatus()).isEqualTo(200);
            int revokedCount = json.readTree(resetResult.getResponse().getContentAsString()).get("revokedSessions").asInt();
            assertThat(revokedCount).isEqualTo(1);

            // In DB: ALL session rows for this user are now revoked
            int unrevokedSessions = jdbc.queryForObject(
                    "SELECT count(*) FROM auth_sessions WHERE user_id = ? AND revoked_at IS NULL", Integer.class, userId
            );
            assertThat(unrevokedSessions).isZero();

            // The newly rotated session row was revoked by reset; its rotated_at remains unchanged (NULL)
            String successorDigest = sessionTokens.digestRefresh(rotatedRefreshCookie.getValue());
            Map<String, Object> successorSession = jdbc.queryForMap(
                    "SELECT revoked_at, rotated_at FROM auth_sessions WHERE refresh_hash = ?", successorDigest
            );
            assertThat(successorSession.get("revoked_at")).isNotNull();
            assertThat(successorSession.get("rotated_at")).isNull();

            // Post-commit token invalidity: both old and newly rotated credentials are dead
            mvc.perform(get("/api/v1/auth/me")
                            .secure(true)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + oldAccessToken))
                    .andExpect(status().isUnauthorized());

            mvc.perform(get("/api/v1/auth/me")
                            .secure(true)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + rotatedAccessToken))
                    .andExpect(status().isUnauthorized());

            BrowserCsrf checkCsrf = getBootCsrf();
            mvc.perform(post("/api/v1/auth/refresh")
                            .secure(true)
                            .cookie(checkCsrf.cookie(), oldRefreshCookie)
                            .header(checkCsrf.headerName(), checkCsrf.token()))
                    .andExpect(status().isUnauthorized());

            mvc.perform(post("/api/v1/auth/refresh")
                            .secure(true)
                            .cookie(checkCsrf.cookie(), rotatedRefreshCookie)
                            .header(checkCsrf.headerName(), checkCsrf.token()))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_SESSION"));

            // Old password rejected; new password authenticates
            BrowserCsrf loginCsrf = getBootCsrf();
            mvc.perform(post("/api/v1/auth/login")
                            .secure(true)
                            .cookie(loginCsrf.cookie())
                            .header(loginCsrf.headerName(), loginCsrf.token())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("email", email, "password", initialPassword))))
                    .andExpect(status().isUnauthorized());

            mvc.perform(post("/api/v1/auth/login")
                            .secure(true)
                            .cookie(loginCsrf.cookie())
                            .header(loginCsrf.headerName(), loginCsrf.token())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("email", email, "password", newPassword))))
                    .andExpect(status().isOk());

        } finally {
            try { fixtureConn.rollback(); } catch (Exception ignored) {}
            try { fixtureConn.close(); } catch (Exception ignored) {}
            pool.shutdownNow();
        }
    }

    @Test
    void resetCommittedFirstRejectsConcurrentRefreshWithoutSurvivingSession() throws Exception {
        String email = "reset-first-" + UUID.randomUUID() + "@example.test";
        String initialPassword = "ResetFirstInitialPassword123";
        String newPassword = "ResetFirstNewPassword456";
        UUID userId = addActiveVerifiedUser(email, initialPassword);

        // User logs in via HTTP to create session
        BrowserCsrf bootCsrf = getBootCsrf();
        MvcResult loginRes = mvc.perform(post("/api/v1/auth/login")
                        .secure(true)
                        .cookie(bootCsrf.cookie())
                        .header(bootCsrf.headerName(), bootCsrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", initialPassword))))
                .andExpect(status().isOk())
                .andReturn();

        String oldAccessToken = json.readTree(loginRes.getResponse().getContentAsString()).get("accessToken").asText();
        Cookie oldRefreshCookie = loginRes.getResponse().getCookie("__Secure-devsphere-refresh");
        assertThat(oldRefreshCookie).isNotNull();

        UUID sessionId = jdbc.queryForObject(
                "SELECT id FROM auth_sessions WHERE user_id = ? AND revoked_at IS NULL", UUID.class, userId
        );
        assertThat(sessionId).isNotNull();

        // Pre-create legitimate reset grant for this user
        PasswordResetGrantToken resetGrant = PasswordResetGrantToken.generate();
        jdbc.update("""
                INSERT INTO auth_tokens(id, user_id, email, purpose, token_hash, created_at, expires_at, used_at, attempts)
                VALUES (?, ?, cast(? as citext), 'RESET_PASSWORD_GRANT', ?, clock_timestamp(), clock_timestamp() + INTERVAL '10 minutes', NULL, 0)
                """, UUID.randomUUID(), userId, email, resetGrant.digest());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        Connection fixtureConn = dataSource.getConnection();
        fixtureConn.setAutoCommit(false);

        try {
            long fixturePid = getBackendPid(fixtureConn);

            // Fixture Tx locks the target session row for update
            try (PreparedStatement ps = fixtureConn.prepareStatement("SELECT id FROM auth_sessions WHERE id = ? FOR UPDATE")) {
                ps.setObject(1, sessionId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                }
            }

            // Thread B: HTTP reset-password (locks user row, hashes password, updates grant, blocks on updating sessionId)
            Callable<MvcResult> resetCall = () -> mvc.perform(post("/api/v1/auth/reset-password")
                            .with(req -> { req.setRemoteAddr(nextPeer()); return req; })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of(
                                    "resetGrant", resetGrant.rawGrant(),
                                    "newPassword", newPassword
                            ))))
                    .andReturn();

            Future<MvcResult> resetFuture = pool.submit(resetCall);

            // Observe Thread B reached lock wait on fixturePid
            Long resetPid = waitForBlockedPid(fixturePid, Duration.ofSeconds(5));
            assertThat(resetPid).isNotNull();

            // Thread A: HTTP refresh (attempts to lock user row, blocks on resetPid)
            BrowserCsrf refreshCsrf = getBootCsrf();
            Callable<MvcResult> refreshCall = () -> mvc.perform(post("/api/v1/auth/refresh")
                            .secure(true)
                            .cookie(refreshCsrf.cookie(), oldRefreshCookie)
                            .header(refreshCsrf.headerName(), refreshCsrf.token()))
                    .andReturn();

            Future<MvcResult> refreshFuture = pool.submit(refreshCall);

            // Observe Thread A reached lock wait on resetPid (deterministic proof of reset-first serialization)
            Long refreshPid = waitForBlockedPid(resetPid, Duration.ofSeconds(5));
            assertThat(refreshPid).isNotNull();

            // Release fixture lock: reset commits first, then refresh unblocks
            fixtureConn.rollback();

            MvcResult resetResult = resetFuture.get(10, TimeUnit.SECONDS);
            MvcResult refreshResult = refreshFuture.get(10, TimeUnit.SECONDS);

            // Reset succeeded
            assertThat(resetResult.getResponse().getStatus()).isEqualTo(200);

            // Refresh rejected because session was revoked by reset; no surviving session issued
            assertThat(refreshResult.getResponse().getStatus()).isEqualTo(401);
            assertThat(refreshResult.getResponse().getCookie("devsphere_session")).isNull();
            List<String> setCookies = refreshResult.getResponse().getHeaders(HttpHeaders.SET_COOKIE);
            assertThat(setCookies).allMatch(cookie -> cookie.contains("Max-Age=0") || cookie.contains("max-age=0"));
            JsonNode refreshBody = json.readTree(refreshResult.getResponse().getContentAsString());
            assertThat(refreshBody.get("code").asText()).isEqualTo("INVALID_SESSION");

            // In DB: ALL sessions revoked, total count remains 1 (no successor session escaped)
            int totalSessions = jdbc.queryForObject(
                    "SELECT count(*) FROM auth_sessions WHERE user_id = ?", Integer.class, userId
            );
            assertThat(totalSessions).isEqualTo(1);

            int unrevokedSessions = jdbc.queryForObject(
                    "SELECT count(*) FROM auth_sessions WHERE user_id = ? AND revoked_at IS NULL", Integer.class, userId
            );
            assertThat(unrevokedSessions).isZero();

            // Target session rotated_at remains unchanged (NULL)
            Map<String, Object> targetSession = jdbc.queryForMap(
                    "SELECT revoked_at, rotated_at FROM auth_sessions WHERE id = ?", sessionId
            );
            assertThat(targetSession.get("revoked_at")).isNotNull();
            assertThat(targetSession.get("rotated_at")).isNull();

            // Credentials unusable
            mvc.perform(get("/api/v1/auth/me")
                            .secure(true)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + oldAccessToken))
                    .andExpect(status().isUnauthorized());

            BrowserCsrf checkCsrf = getBootCsrf();
            mvc.perform(post("/api/v1/auth/refresh")
                            .secure(true)
                            .cookie(checkCsrf.cookie(), oldRefreshCookie)
                            .header(checkCsrf.headerName(), checkCsrf.token()))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_SESSION"));

            // Old password fails; new password authenticates
            BrowserCsrf loginCsrf = getBootCsrf();
            mvc.perform(post("/api/v1/auth/login")
                            .secure(true)
                            .cookie(loginCsrf.cookie())
                            .header(loginCsrf.headerName(), loginCsrf.token())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("email", email, "password", initialPassword))))
                    .andExpect(status().isUnauthorized());

            mvc.perform(post("/api/v1/auth/login")
                            .secure(true)
                            .cookie(loginCsrf.cookie())
                            .header(loginCsrf.headerName(), loginCsrf.token())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("email", email, "password", newPassword))))
                    .andExpect(status().isOk());

        } finally {
            try { fixtureConn.rollback(); } catch (Exception ignored) {}
            try { fixtureConn.close(); } catch (Exception ignored) {}
            pool.shutdownNow();
        }
    }

    @Test
    void unboundRecoveryRequestQueuedBeforeResetIsCancelledAndPostResetRequestSucceeds() throws Exception {
        String userAEmail = "unbound-cancel-a-" + UUID.randomUUID() + "@example.test";
        String userAOldPassword = "UserAOldPassword123";
        String userANewPassword = "UserANewPassword456";
        UUID userAId = addActiveVerifiedUser(userAEmail, userAOldPassword);

        String userBEmail = "unbound-cancel-b-" + UUID.randomUUID() + "@example.test";
        String userBPassword = "UserBPassword123";
        UUID userBId = addActiveVerifiedUser(userBEmail, userBPassword);

        // Pre-reset legitimate older fixture for User A created 70 seconds ago (allows valid grant acquisition)
        UUID eligibleChallenge = UUID.randomUUID();
        String eligibleOtp = "654321";
        String eligibleHash = crypto.hash(eligibleChallenge, eligibleOtp, VerificationCodeCrypto.Purpose.RESET_PASSWORD);
        OffsetDateTime pastCreated = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(70);
        OffsetDateTime pastExpires = pastCreated.plusMinutes(10);
        jdbc.update("""
                INSERT INTO auth_tokens(id, user_id, email, purpose, token_hash, created_at, expires_at, used_at, attempts)
                VALUES (?, ?, cast(? as citext), 'RESET_PASSWORD', ?, ?, ?, NULL, 0)
                """, eligibleChallenge, userAId, userAEmail, eligibleHash, pastCreated, pastExpires);

        // Also add an older bound recovery OTP and a prior grant for User A to prove they cannot produce a post-reset grant
        UUID oldBoundChallenge = UUID.randomUUID();
        String oldBoundOtp = "778899";
        String oldBoundHash = crypto.hash(oldBoundChallenge, oldBoundOtp, VerificationCodeCrypto.Purpose.RESET_PASSWORD);
        jdbc.update("""
                INSERT INTO auth_tokens(id, user_id, email, purpose, token_hash, created_at, expires_at, used_at, attempts)
                VALUES (?, ?, cast(? as citext), 'RESET_PASSWORD', ?, ?, ?, NULL, 0)
                """, oldBoundChallenge, userAId, userAEmail, oldBoundHash, pastCreated.minusMinutes(5), pastExpires.minusMinutes(5));

        PasswordResetGrantToken priorGrant = PasswordResetGrantToken.generate();
        jdbc.update("""
                INSERT INTO auth_tokens(id, user_id, email, purpose, token_hash, created_at, expires_at, used_at, attempts)
                VALUES (?, ?, cast(? as citext), 'RESET_PASSWORD_GRANT', ?, ?, ?, NULL, 0)
                """, UUID.randomUUID(), userAId, userAEmail, priorGrant.digest(), pastCreated.minusMinutes(2), pastExpires.minusMinutes(2));

        // Exchange eligible OTP for a valid reset grant
        MvcResult grantRes = mvc.perform(post("/api/v1/auth/forgot-password/verify")
                        .with(req -> { req.setRemoteAddr(nextPeer()); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", userAEmail, "code", eligibleOtp))))
                .andExpect(status().isOk())
                .andReturn();

        String validGrant = json.readTree(grantRes.getResponse().getContentAsString()).get("resetGrant").asText();

        // Queue an unbound recovery request for User A BEFORE reset
        UUID unboundChallengeA = UUID.randomUUID();
        String unboundOtpA = "123123";
        String unboundHashA = crypto.hash(unboundChallengeA, unboundOtpA, VerificationCodeCrypto.Purpose.RESET_PASSWORD);
        String unboundCipherA = crypto.encrypt(unboundChallengeA, unboundOtpA, VerificationCodeCrypto.Purpose.RESET_PASSWORD);

        OffsetDateTime unboundNowA = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(90);
        OffsetDateTime unboundExpiresA = unboundNowA.plusMinutes(10);
        jdbc.update("""
                INSERT INTO auth_tokens(id, user_id, email, purpose, token_hash, created_at, expires_at, used_at, attempts)
                VALUES (?, NULL, cast(? as citext), 'RESET_PASSWORD', ?, ?, ?, NULL, 0)
                """, unboundChallengeA, userAEmail, unboundHashA, unboundNowA, unboundExpiresA);

        jdbc.update("""
                INSERT INTO background_jobs(job_kind, idempotency_key, payload, expires_at, scheduled_at)
                VALUES ('AUTH_RESET_PASSWORD', cast(? as text),
                        jsonb_build_object('schemaVersion', 1, 'challengeId', cast(? as text), 'ciphertext', cast(? as text)),
                        ?, ?)
                """, unboundChallengeA.toString(), unboundChallengeA.toString(), unboundCipherA, unboundExpiresA, unboundNowA);

        // Queue an unbound recovery request for User B
        UUID challengeB = UUID.randomUUID();
        String otpB = "456456";
        String hashB = crypto.hash(challengeB, otpB, VerificationCodeCrypto.Purpose.RESET_PASSWORD);
        String cipherB = crypto.encrypt(challengeB, otpB, VerificationCodeCrypto.Purpose.RESET_PASSWORD);

        OffsetDateTime nowB = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime expiresB = nowB.plusMinutes(10);
        jdbc.update("""
                INSERT INTO auth_tokens(id, user_id, email, purpose, token_hash, created_at, expires_at, used_at, attempts)
                VALUES (?, NULL, cast(? as citext), 'RESET_PASSWORD', ?, ?, ?, NULL, 0)
                """, challengeB, userBEmail, hashB, nowB, expiresB);

        jdbc.update("""
                INSERT INTO background_jobs(job_kind, idempotency_key, payload, expires_at, scheduled_at)
                VALUES ('AUTH_RESET_PASSWORD', cast(? as text),
                        jsonb_build_object('schemaVersion', 1, 'challengeId', cast(? as text), 'ciphertext', cast(? as text)),
                        ?, ?)
                """, challengeB.toString(), challengeB.toString(), cipherB, expiresB, nowB);

        // User A completes password reset using validGrant
        mvc.perform(post("/api/v1/auth/reset-password")
                        .with(req -> { req.setRemoteAddr(nextPeer()); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "resetGrant", validGrant,
                                "newPassword", userANewPassword
                        ))))
                .andExpect(status().isOk());

        // In DB: User A's unbound pre-reset token is now consumed/cancelled
        Instant unboundAUsedAt = jdbc.queryForObject(
                "SELECT used_at FROM auth_tokens WHERE id = ?", Instant.class, unboundChallengeA
        );
        assertThat(unboundAUsedAt).isNotNull();

        // Old bound OTP and prior grant are consumed
        Instant oldBoundUsedAt = jdbc.queryForObject(
                "SELECT used_at FROM auth_tokens WHERE id = ?", Instant.class, oldBoundChallenge
        );
        assertThat(oldBoundUsedAt).isNotNull();

        Instant priorGrantUsedAt = jdbc.queryForObject(
                "SELECT used_at FROM auth_tokens WHERE token_hash = ?", Instant.class, priorGrant.digest()
        );
        assertThat(priorGrantUsedAt).isNotNull();

        // User B's token is untouched
        Instant bUsedAt = jdbc.queryForObject(
                "SELECT used_at FROM auth_tokens WHERE id = ?", Instant.class, challengeB
        );
        assertThat(bUsedAt).isNull();

        // Run worker cycle: processes jobs
        worker.runJobCycle();
        worker.runJobCycle();

        // User A's pre-reset job was cancelled: status FAILED, last_error set, ciphertext scrubbed
        Map<String, Object> jobARow = jdbc.queryForMap(
                "SELECT status, last_error, payload::text AS raw_payload FROM background_jobs WHERE idempotency_key = cast(? as text)",
                unboundChallengeA.toString()
        );
        assertThat(jobARow.get("status")).isEqualTo("FAILED");
        assertThat((String) jobARow.get("last_error")).isIn("TOKEN_UNUSABLE", "STALE_OR_INELIGIBLE");
        assertThat((String) jobARow.get("raw_payload")).doesNotContain("ciphertext");

        // User B's job was delivered normally
        Map<String, Object> jobBRow = jdbc.queryForMap(
                "SELECT status FROM background_jobs WHERE idempotency_key = cast(? as text)",
                challengeB.toString()
        );
        assertThat(jobBRow.get("status")).isEqualTo("SUCCESS");
        String deliveredToB = extractDeliveredOtp(userBEmail, "Password Recovery Code");
        assertThat(deliveredToB).isEqualTo(otpB);

        // Old bound OTP and prior grant cannot produce a grant or reset
        mvc.perform(post("/api/v1/auth/forgot-password/verify")
                        .with(req -> { req.setRemoteAddr(nextPeer()); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", userAEmail, "code", oldBoundOtp))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RESET_CODE_INVALID"));

        mvc.perform(post("/api/v1/auth/reset-password")
                        .with(req -> { req.setRemoteAddr(nextPeer()); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "resetGrant", priorGrant.rawGrant(),
                                "newPassword", "AttemptedPostResetPassword123"
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RESET_GRANT_INVALID"));

        // A valid request admitted AFTER reset can bind and complete recovery via real HTTP intake
        mvc.perform(post("/api/v1/auth/forgot-password")
                        .with(req -> { req.setRemoteAddr(nextPeer()); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", userAEmail))))
                .andExpect(status().isAccepted());

        worker.runJobCycle();

        String deliveredPostResetOtp = extractDeliveredOtp(userAEmail, "Password Recovery Code");
        assertThat(deliveredPostResetOtp).isNotNull();

        // Verify post-reset OTP -> obtains valid grant
        MvcResult postResetVerifyRes = mvc.perform(post("/api/v1/auth/forgot-password/verify")
                        .with(req -> { req.setRemoteAddr(nextPeer()); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", userAEmail, "code", deliveredPostResetOtp))))
                .andExpect(status().isOk())
                .andReturn();

        String postResetGrant = json.readTree(postResetVerifyRes.getResponse().getContentAsString()).get("resetGrant").asText();

        // Complete password reset with post-reset grant
        String finalPassword = "FinalPostResetPassword789";
        mvc.perform(post("/api/v1/auth/reset-password")
                        .with(req -> { req.setRemoteAddr(nextPeer()); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "resetGrant", postResetGrant,
                                "newPassword", finalPassword
                        ))))
                .andExpect(status().isOk());

        // Authenticate with final password
        BrowserCsrf csrf = getBootCsrf();
        mvc.perform(post("/api/v1/auth/login")
                        .secure(true)
                        .cookie(csrf.cookie())
                        .header(csrf.headerName(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", userAEmail, "password", finalPassword))))
                .andExpect(status().isOk());
    }

    @Test
    void transactionFailureDuringResetRollsBackPasswordHashGrantConsumptionAndSessionsTogether() throws Exception {
        String email = "rollback-concurrency-" + UUID.randomUUID() + "@example.test";
        String initialPassword = "RollbackInitPassword123";
        String failedNewPassword = "FailedNewPassword456";
        String retryNewPassword = "RetrySuccessPassword789";
        UUID userId = addActiveVerifiedUser(email, initialPassword);

        // Log in to establish an active session
        BrowserCsrf bootCsrf = getBootCsrf();
        mvc.perform(post("/api/v1/auth/login")
                        .secure(true)
                        .cookie(bootCsrf.cookie())
                        .header(bootCsrf.headerName(), bootCsrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", initialPassword))))
                .andExpect(status().isOk());

        UUID activeSessionId = jdbc.queryForObject(
                "SELECT id FROM auth_sessions WHERE user_id = ? AND revoked_at IS NULL", UUID.class, userId
        );
        assertThat(activeSessionId).isNotNull();

        // Pre-create legitimate reset grant
        PasswordResetGrantToken grant = PasswordResetGrantToken.generate();
        jdbc.update("""
                INSERT INTO auth_tokens(id, user_id, email, purpose, token_hash, created_at, expires_at, used_at, attempts)
                VALUES (?, ?, cast(? as citext), 'RESET_PASSWORD_GRANT', ?, clock_timestamp(), clock_timestamp() + INTERVAL '10 minutes', NULL, 0)
                """, UUID.randomUUID(), userId, email, grant.digest());

        Map<String, Object> userBefore = jdbc.queryForMap(
                "SELECT password_hash, version FROM users WHERE id = ?", userId
        );

        // Install temporary trigger that forces a DB exception on auth_sessions update
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION fail_session_update_concurrency() RETURNS TRIGGER AS $$
                BEGIN
                    RAISE EXCEPTION 'Simulated concurrency DB failure';
                END;
                $$ LANGUAGE plpgsql;
                """);
        jdbc.execute("""
                CREATE TRIGGER trg_fail_session_update_concurrency
                BEFORE UPDATE ON auth_sessions
                FOR EACH ROW EXECUTE FUNCTION fail_session_update_concurrency();
                """);

        try {
            // Attempt reset: must fail due to DB trigger
            assertThatThrownBy(() -> mvc.perform(post("/api/v1/auth/reset-password")
                            .with(req -> { req.setRemoteAddr(nextPeer()); return req; })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of(
                                    "resetGrant", grant.rawGrant(),
                                    "newPassword", failedNewPassword
                            )))))
                    .isNotNull();

            // Assert complete atomic rollback:
            // 1. Password hash and version rolled back
            Map<String, Object> userAfterRollback = jdbc.queryForMap(
                    "SELECT password_hash, version FROM users WHERE id = ?", userId
            );
            assertThat(userAfterRollback.get("password_hash")).isEqualTo(userBefore.get("password_hash"));
            assertThat(userAfterRollback.get("version")).isEqualTo(userBefore.get("version"));

            // 2. Grant consumption rolled back (used_at remains NULL)
            Instant grantUsedAt = jdbc.queryForObject(
                    "SELECT used_at FROM auth_tokens WHERE token_hash = ?", Instant.class, grant.digest()
            );
            assertThat(grantUsedAt).isNull();

            // 3. Sessions rolled back (active session not revoked)
            Instant sessionRevokedAt = jdbc.queryForObject(
                    "SELECT revoked_at FROM auth_sessions WHERE id = ?", Instant.class, activeSessionId
            );
            assertThat(sessionRevokedAt).isNull();

            // 4. Old password still authenticates without creating a second session
            String persistedHash = jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, userId);
            assertThat(encoder.matches(initialPassword, persistedHash)).isTrue();

        } finally {
            // Always remove failure fixture in finally block
            jdbc.execute("DROP TRIGGER IF EXISTS trg_fail_session_update_concurrency ON auth_sessions");
            jdbc.execute("DROP FUNCTION IF EXISTS fail_session_update_concurrency");
        }

        // Ordinary retry after fixture removal with the exact same grant succeeds
        mvc.perform(post("/api/v1/auth/reset-password")
                        .with(req -> { req.setRemoteAddr(nextPeer()); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "resetGrant", grant.rawGrant(),
                                "newPassword", retryNewPassword
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revokedSessions").value(1));

        // Grant is now consumed
        Instant grantUsedAtAfterRetry = jdbc.queryForObject(
                "SELECT used_at FROM auth_tokens WHERE token_hash = ?", Instant.class, grant.digest()
        );
        assertThat(grantUsedAtAfterRetry).isNotNull();

        // Active session is now revoked
        Instant sessionRevokedAtAfterRetry = jdbc.queryForObject(
                "SELECT revoked_at FROM auth_sessions WHERE id = ?", Instant.class, activeSessionId
        );
        assertThat(sessionRevokedAtAfterRetry).isNotNull();

        // Old password rejected; retry password authenticates
        BrowserCsrf retryCsrf = getBootCsrf();
        mvc.perform(post("/api/v1/auth/login")
                        .secure(true)
                        .cookie(retryCsrf.cookie())
                        .header(retryCsrf.headerName(), retryCsrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", initialPassword))))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/v1/auth/login")
                        .secure(true)
                        .cookie(retryCsrf.cookie())
                        .header(retryCsrf.headerName(), retryCsrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", retryNewPassword))))
                .andExpect(status().isOk());
    }
}

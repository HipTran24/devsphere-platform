package com.project.devsphere;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.devsphere.identity.api.PasswordRecoveryController;
import com.project.devsphere.identity.application.PasswordRecoveryService;
import com.project.devsphere.identity.domain.PasswordResetGrantToken;
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

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
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
class PasswordRecoveryGrantSafetyIntegrationTest {

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        PasswordRecoveryIntegrationTest.configure(registry);
    }

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JavaMailSender mailSender;
    @Autowired private VerificationMailWorker worker;
    @Autowired private VerificationCodeCrypto crypto;

    private final ObjectMapper json = new ObjectMapper();
    private final Argon2PasswordEncoder encoder = new Argon2PasswordEncoder(16, 32, 1, 19456, 2);
    private static final AtomicInteger peerCounter = new AtomicInteger(100);

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
        return "10.88." + (seq / 250) + "." + (seq % 250);
    }

    private UUID addActiveVerifiedUser(String email, String rawPassword) {
        UUID id = UUID.randomUUID();
        String hash = encoder.encode(rawPassword);
        jdbc.update("""
                INSERT INTO users(id, email, display_name, status, email_verified_at, password_hash)
                VALUES (?, cast(? as citext), 'Grant Safety User', 'ACTIVE', clock_timestamp() - INTERVAL '1 day', ?)
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

    private record InvalidGrantCase(String description, String grantValue) {}

    @Test
    void uniformResetGrantInvalidRejectionsAcrossInvalidGrantFormsAndPurposes() throws Exception {
        String targetEmail = "uniform-invalid-" + UUID.randomUUID() + "@example.test";
        String initialPassword = "targetUserPassword123";
        UUID targetUserId = addActiveVerifiedUser(targetEmail, initialPassword);

        Map<String, Object> userBefore = jdbc.queryForMap(
                "SELECT password_hash, updated_at, version, status FROM users WHERE id = ?", targetUserId
        );

        List<InvalidGrantCase> cases = new ArrayList<>();

        // 1. Expired-at-insertion valid grant (legal past timestamps, no mutable trigger updates)
        PasswordResetGrantToken expiredGrant = PasswordResetGrantToken.generate();
        jdbc.update("""
                INSERT INTO auth_tokens(id, user_id, email, purpose, token_hash, created_at, expires_at, used_at, attempts)
                VALUES (?, ?, cast(? as citext), 'RESET_PASSWORD_GRANT', ?, clock_timestamp() - INTERVAL '20 minutes', clock_timestamp() - INTERVAL '10 minutes', NULL, 0)
                """, UUID.randomUUID(), targetUserId, targetEmail, expiredGrant.digest());
        cases.add(new InvalidGrantCase("expired-at-insertion valid grant", expiredGrant.rawGrant()));

        // 2. Already-used grant (legal past used_at)
        PasswordResetGrantToken usedGrant = PasswordResetGrantToken.generate();
        jdbc.update("""
                INSERT INTO auth_tokens(id, user_id, email, purpose, token_hash, created_at, expires_at, used_at, attempts)
                VALUES (?, ?, cast(? as citext), 'RESET_PASSWORD_GRANT', ?, clock_timestamp() - INTERVAL '10 minutes', clock_timestamp() + INTERVAL '10 minutes', clock_timestamp() - INTERVAL '2 minutes', 0)
                """, UUID.randomUUID(), targetUserId, targetEmail, usedGrant.digest());
        cases.add(new InvalidGrantCase("already-used grant", usedGrant.rawGrant()));

        // 3. Canonical forged grant (valid 43-char Base64URL, not in DB)
        PasswordResetGrantToken forgedGrant = PasswordResetGrantToken.generate();
        cases.add(new InvalidGrantCase("canonical forged grant", forgedGrant.rawGrant()));

        // 4. Non-canonical alias: padding '='
        PasswordResetGrantToken validCanonical = PasswordResetGrantToken.generate();
        jdbc.update("""
                INSERT INTO auth_tokens(id, user_id, email, purpose, token_hash, created_at, expires_at, used_at, attempts)
                VALUES (?, ?, cast(? as citext), 'RESET_PASSWORD_GRANT', ?, clock_timestamp(), clock_timestamp() + INTERVAL '10 minutes', NULL, 0)
                """, UUID.randomUUID(), targetUserId, targetEmail, validCanonical.digest());
        cases.add(new InvalidGrantCase("noncanonical alias padding", validCanonical.rawGrant() + "="));

        // 5. Non-canonical alias: leading space
        cases.add(new InvalidGrantCase("noncanonical alias leading space", " " + validCanonical.rawGrant()));

        // 6. Non-canonical alias: trailing space
        cases.add(new InvalidGrantCase("noncanonical alias trailing space", validCanonical.rawGrant() + " "));

        // 7. Non-canonical alias: invalid trailing bits (toggling a low padding bit of the final base64url index)
        String base64UrlAlphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        char lastChar = validCanonical.rawGrant().charAt(42);
        int lastCharIndex = base64UrlAlphabet.indexOf(lastChar);
        assertThat(lastCharIndex).isGreaterThanOrEqualTo(0);
        int aliasIndex = lastCharIndex ^ 1;
        char aliasLastChar = base64UrlAlphabet.charAt(aliasIndex);
        String trailingBitsAlias = validCanonical.rawGrant().substring(0, 42) + aliasLastChar;
        byte[] aliasDecoded = java.util.Base64.getUrlDecoder().decode(trailingBitsAlias);
        byte[] canonicalDecoded = java.util.Base64.getUrlDecoder().decode(validCanonical.rawGrant());
        assertThat(aliasDecoded).isEqualTo(canonicalDecoded);
        assertThat(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(aliasDecoded)).isEqualTo(validCanonical.rawGrant());
        assertThat(trailingBitsAlias).isNotEqualTo(validCanonical.rawGrant());
        cases.add(new InvalidGrantCase("noncanonical invalid trailing bits", trailingBitsAlias));

        // 8. Unsupported string form: empty string
        cases.add(new InvalidGrantCase("empty string form", ""));

        // 9. Unsupported string form: whitespace only
        cases.add(new InvalidGrantCase("whitespace string form", "   "));

        // 10. Unsupported string form: too short
        cases.add(new InvalidGrantCase("too short string form", "tooShort"));

        // 11. Unsupported string form: too long
        cases.add(new InvalidGrantCase("too long string form", "a".repeat(44)));

        // 12. Unsupported string form: illegal characters
        cases.add(new InvalidGrantCase("illegal characters", validCanonical.rawGrant().substring(0, 42) + "!"));

        // 13. Digest recorded under wrong token purpose (VERIFY_EMAIL)
        PasswordResetGrantToken wrongPurposeGrant = PasswordResetGrantToken.generate();
        jdbc.update("""
                INSERT INTO auth_tokens(id, user_id, email, purpose, token_hash, created_at, expires_at, used_at, attempts)
                VALUES (?, ?, cast(? as citext), 'VERIFY_EMAIL', ?, clock_timestamp(), clock_timestamp() + INTERVAL '10 minutes', NULL, 0)
                """, UUID.randomUUID(), targetUserId, targetEmail, wrongPurposeGrant.digest());
        cases.add(new InvalidGrantCase("digest recorded under wrong token purpose", wrongPurposeGrant.rawGrant()));

        String requestedNewPassword = "CandidateNewPassword123";

        for (InvalidGrantCase c : cases) {
            MvcResult res = mvc.perform(post("/api/v1/auth/reset-password")
                            .with(req -> { req.setRemoteAddr(nextPeer()); return req; })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of(
                                    "resetGrant", c.grantValue(),
                                    "newPassword", requestedNewPassword
                            ))))
                    .andExpect(status().isBadRequest())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                    .andExpect(jsonPath("$.code").value("RESET_GRANT_INVALID"))
                    .andExpect(jsonPath("$.title").value("RESET_GRANT_INVALID"))
                    .andExpect(jsonPath("$.detail").value("Password reset grant is invalid or expired"))
                    .andReturn();

            assertThat(res.getResponse().getHeaderNames()).noneMatch(h -> h.equalsIgnoreCase(HttpHeaders.SET_COOKIE));

            String body = res.getResponse().getContentAsString();
            if (!c.grantValue().isBlank()) {
                assertThat(body).as("Response body must not reflect raw grant secret for " + c.description())
                        .doesNotContain(c.grantValue());
            }
            assertThat(body).as("Response body must not reflect new password secret for " + c.description())
                    .doesNotContain(requestedNewPassword);
        }

        // Assert valid canonical grant fixture was never consumed by alias attempts
        Instant validCanonicalUsedAt = jdbc.queryForObject(
                "SELECT used_at FROM auth_tokens WHERE token_hash = ?", Instant.class, validCanonical.digest()
        );
        assertThat(validCanonicalUsedAt).isNull();

        // Semantic assertion: target user row remains completely unchanged
        Map<String, Object> userAfter = jdbc.queryForMap(
                "SELECT password_hash, updated_at, version, status FROM users WHERE id = ?", targetUserId
        );
        assertThat(userAfter.get("password_hash")).isEqualTo(userBefore.get("password_hash"));
        assertThat(userAfter.get("version")).isEqualTo(userBefore.get("version"));
        assertThat(userAfter.get("status")).isEqualTo("ACTIVE");

        // Old password still authenticates
        BrowserCsrf csrf = getBootCsrf();
        mvc.perform(post("/api/v1/auth/login")
                        .secure(true)
                        .cookie(csrf.cookie())
                        .header(csrf.headerName(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", targetEmail, "password", initialPassword))))
                .andExpect(status().isOk());
    }

    @Test
    void accountIneligibilityAndBearerCredentialAuthorityAreStrictlyEnforced() throws Exception {
        // --- Scenario 2A: Locked account cannot reset ---
        String lockedEmail = "locked-ineligible-" + UUID.randomUUID() + "@example.test";
        String lockedPassword = "initialLockedPassword123";
        UUID lockedUserId = addActiveVerifiedUser(lockedEmail, lockedPassword);

        PasswordResetGrantToken lockedGrant = PasswordResetGrantToken.generate();
        jdbc.update("""
                INSERT INTO auth_tokens(id, user_id, email, purpose, token_hash, created_at, expires_at, used_at, attempts)
                VALUES (?, ?, cast(? as citext), 'RESET_PASSWORD_GRANT', ?, clock_timestamp(), clock_timestamp() + INTERVAL '10 minutes', NULL, 0)
                """, UUID.randomUUID(), lockedUserId, lockedEmail, lockedGrant.digest());

        // Lock user after grant issuance
        jdbc.update("UPDATE users SET status = 'LOCKED' WHERE id = ?", lockedUserId);

        mvc.perform(post("/api/v1/auth/reset-password")
                        .with(req -> { req.setRemoteAddr(nextPeer()); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "resetGrant", lockedGrant.rawGrant(),
                                "newPassword", "NewLockedPassword123"
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("RESET_GRANT_INVALID"));

        String lockedStatusAfter = jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, lockedUserId);
        assertThat(lockedStatusAfter).isEqualTo("LOCKED");

        Instant lockedGrantUsedAt = jdbc.queryForObject(
                "SELECT used_at FROM auth_tokens WHERE token_hash = ?", Instant.class, lockedGrant.digest()
        );
        assertThat(lockedGrantUsedAt).isNull();

        // --- Scenario 2B: Email changed after grant issuance cannot reset ---
        String originalEmail = "orig-change-" + UUID.randomUUID() + "@example.test";
        String updatedEmail = "new-change-" + UUID.randomUUID() + "@example.test";
        String origPassword = "emailOrigPassword123";
        UUID emailUserId = addActiveVerifiedUser(originalEmail, origPassword);

        PasswordResetGrantToken emailGrant = PasswordResetGrantToken.generate();
        jdbc.update("""
                INSERT INTO auth_tokens(id, user_id, email, purpose, token_hash, created_at, expires_at, used_at, attempts)
                VALUES (?, ?, cast(? as citext), 'RESET_PASSWORD_GRANT', ?, clock_timestamp(), clock_timestamp() + INTERVAL '10 minutes', NULL, 0)
                """, UUID.randomUUID(), emailUserId, originalEmail, emailGrant.digest());

        // Change user email in database
        jdbc.update("UPDATE users SET email = cast(? as citext) WHERE id = ?", updatedEmail, emailUserId);

        mvc.perform(post("/api/v1/auth/reset-password")
                        .with(req -> { req.setRemoteAddr(nextPeer()); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "resetGrant", emailGrant.rawGrant(),
                                "newPassword", "NewEmailPassword123"
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("RESET_GRANT_INVALID"));

        Instant emailGrantUsedAt = jdbc.queryForObject(
                "SELECT used_at FROM auth_tokens WHERE token_hash = ?", Instant.class, emailGrant.digest()
        );
        assertThat(emailGrantUsedAt).isNull();

        // --- Scenario 2C: Grant is a bearer credential; authenticated other account supplies no authority/restriction ---
        String userAEmail = "user-a-target-" + UUID.randomUUID() + "@example.test";
        String userAPassword = "UserAPassword123";
        String userANewPassword = "UserANewPassword123";
        UUID userAId = addActiveVerifiedUser(userAEmail, userAPassword);

        // User A logs in to create an active session
        BrowserCsrf csrfA = getBootCsrf();
        mvc.perform(post("/api/v1/auth/login")
                        .secure(true)
                        .cookie(csrfA.cookie())
                        .header(csrfA.headerName(), csrfA.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", userAEmail, "password", userAPassword))))
                .andExpect(status().isOk());

        int userASessionsBefore = jdbc.queryForObject(
                "SELECT count(*) FROM auth_sessions WHERE user_id = ? AND revoked_at IS NULL", Integer.class, userAId
        );
        assertThat(userASessionsBefore).isEqualTo(1);

        PasswordResetGrantToken userAGrant = PasswordResetGrantToken.generate();
        jdbc.update("""
                INSERT INTO auth_tokens(id, user_id, email, purpose, token_hash, created_at, expires_at, used_at, attempts)
                VALUES (?, ?, cast(? as citext), 'RESET_PASSWORD_GRANT', ?, clock_timestamp(), clock_timestamp() + INTERVAL '10 minutes', NULL, 0)
                """, UUID.randomUUID(), userAId, userAEmail, userAGrant.digest());

        // User B (caller) logs in and gets bearer access token and refresh cookie
        String userBEmail = "user-b-caller-" + UUID.randomUUID() + "@example.test";
        String userBPassword = "UserBPassword123";
        UUID userBId = addActiveVerifiedUser(userBEmail, userBPassword);

        // Also add a separate token for User B to prove tokens remain untouched
        UUID userBTokenId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO auth_tokens(id, user_id, email, purpose, token_hash, created_at, expires_at, used_at, attempts)
                VALUES (?, ?, cast(? as citext), 'VERIFY_EMAIL', 'dummyTokenHashUserB', clock_timestamp(), clock_timestamp() + INTERVAL '1 hour', NULL, 0)
                """, userBTokenId, userBId, userBEmail);

        BrowserCsrf csrfB = getBootCsrf();
        MvcResult loginBRes = mvc.perform(post("/api/v1/auth/login")
                        .secure(true)
                        .cookie(csrfB.cookie())
                        .header(csrfB.headerName(), csrfB.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", userBEmail, "password", userBPassword))))
                .andExpect(status().isOk())
                .andReturn();

        String userBAccessToken = json.readTree(loginBRes.getResponse().getContentAsString()).get("accessToken").asText();
        Cookie userBRefreshCookie = loginBRes.getResponse().getCookie("__Secure-devsphere-refresh");
        assertThat(userBRefreshCookie).isNotNull();

        Map<String, Object> userBBefore = jdbc.queryForMap(
                "SELECT password_hash, status, version FROM users WHERE id = ?", userBId
        );

        // Request reset for User A while authenticated as User B
        mvc.perform(post("/api/v1/auth/reset-password")
                        .with(req -> { req.setRemoteAddr(nextPeer()); return req; })
                        .secure(true)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userBAccessToken)
                        .cookie(userBRefreshCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "resetGrant", userAGrant.rawGrant(),
                                "newPassword", userANewPassword
                        ))))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.revokedSessions").value(1));

        // User A's grant is consumed and sessions are revoked
        Instant userAGrantUsedAt = jdbc.queryForObject(
                "SELECT used_at FROM auth_tokens WHERE token_hash = ?", Instant.class, userAGrant.digest()
        );
        assertThat(userAGrantUsedAt).isNotNull();

        int userASessionsAfter = jdbc.queryForObject(
                "SELECT count(*) FROM auth_sessions WHERE user_id = ? AND revoked_at IS NULL", Integer.class, userAId
        );
        assertThat(userASessionsAfter).isZero();

        // User A's new password logs in; old password fails
        BrowserCsrf freshCsrf = getBootCsrf();
        mvc.perform(post("/api/v1/auth/login")
                        .secure(true)
                        .cookie(freshCsrf.cookie())
                        .header(freshCsrf.headerName(), freshCsrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", userAEmail, "password", userAPassword))))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/v1/auth/login")
                        .secure(true)
                        .cookie(freshCsrf.cookie())
                        .header(freshCsrf.headerName(), freshCsrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", userAEmail, "password", userANewPassword))))
                .andExpect(status().isOk());

        // User B (caller) remains COMPLETELY unaffected:
        // 1. User B's access token is still valid
        mvc.perform(get("/api/v1/auth/me")
                        .secure(true)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userBAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userBId.toString()));

        // 2. User B's password and status remain unchanged
        Map<String, Object> userBAfter = jdbc.queryForMap(
                "SELECT password_hash, status, version FROM users WHERE id = ?", userBId
        );
        assertThat(userBAfter.get("password_hash")).isEqualTo(userBBefore.get("password_hash"));
        assertThat(userBAfter.get("status")).isEqualTo(userBBefore.get("status"));
        assertThat(userBAfter.get("version")).isEqualTo(userBBefore.get("version"));

        // 3. User B's separate tokens remain intact
        Instant userBTokenUsedAt = jdbc.queryForObject(
                "SELECT used_at FROM auth_tokens WHERE id = ?", Instant.class, userBTokenId
        );
        assertThat(userBTokenUsedAt).isNull();
    }

    @Test
    void noSessionRecoveryRevokesZeroSessionsAndPreservesExistingRevocationsAndOtherTokens() throws Exception {
        String email = "no-session-" + UUID.randomUUID() + "@example.test";
        String initialPassword = "NoSessionInitialPassword123";
        String newPassword = "NoSessionNewPassword123";
        UUID userId = addActiveVerifiedUser(email, initialPassword);

        // User has ZERO active sessions
        int activeSessionsBefore = jdbc.queryForObject(
                "SELECT count(*) FROM auth_sessions WHERE user_id = ? AND revoked_at IS NULL", Integer.class, userId
        );
        assertThat(activeSessionsBefore).isZero();

        // Insert historical already-revoked session
        UUID historicalSessionId = UUID.randomUUID();
        UUID familyId = UUID.randomUUID();
        OffsetDateTime historicalIssued = OffsetDateTime.now().minusHours(3);
        OffsetDateTime historicalRevoked = OffsetDateTime.now().minusHours(2);
        jdbc.update("""
                INSERT INTO auth_sessions(id, user_id, refresh_hash, issued_at, expires_at, revoked_at, session_family_id)
                VALUES (?, ?, 'historicalRefreshDigest123', ?, ? + INTERVAL '1 day', ?, ?)
                """, historicalSessionId, userId, historicalIssued, historicalIssued, historicalRevoked, familyId);

        Map<String, Object> historicalBefore = jdbc.queryForMap(
                "SELECT issued_at, expires_at, revoked_at FROM auth_sessions WHERE id = ?", historicalSessionId
        );

        // Insert separate purpose token (VERIFY_EMAIL)
        UUID verifyEmailTokenId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO auth_tokens(id, user_id, email, purpose, token_hash, created_at, expires_at, used_at, attempts)
                VALUES (?, ?, cast(? as citext), 'VERIFY_EMAIL', 'dummyHmacToken123', clock_timestamp(), clock_timestamp() + INTERVAL '1 hour', NULL, 0)
                """, verifyEmailTokenId, userId, email);

        // 1. Request recovery OTP
        mvc.perform(post("/api/v1/auth/forgot-password")
                        .with(req -> { req.setRemoteAddr(nextPeer()); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email))))
                .andExpect(status().isAccepted())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.status").value("ACCEPTED"));

        // 2. Deliver OTP via controlled worker
        worker.runJobCycle();
        String recoveryOtp = extractDeliveredOtp(email, "Password Recovery Code");

        // 3. Exchange OTP for reset grant
        MvcResult verifyRes = mvc.perform(post("/api/v1/auth/forgot-password/verify")
                        .with(req -> { req.setRemoteAddr(nextPeer()); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "code", recoveryOtp))))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.resetGrant").isString())
                .andExpect(jsonPath("$.expiresAt").isString())
                .andReturn();

        String grant = json.readTree(verifyRes.getResponse().getContentAsString()).get("resetGrant").asText();

        // 4. Reset password
        MvcResult resetRes = mvc.perform(post("/api/v1/auth/reset-password")
                        .with(req -> { req.setRemoteAddr(nextPeer()); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("resetGrant", grant, "newPassword", newPassword))))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.revokedSessions").value(0))
                .andReturn();

        // No auth cookies or sessions issued during reset
        assertThat(resetRes.getResponse().getHeaderNames()).noneMatch(h -> h.equalsIgnoreCase(HttpHeaders.SET_COOKIE));

        // User remains ACTIVE and email_verified_at is intact
        Map<String, Object> userRow = jdbc.queryForMap(
                "SELECT status, email_verified_at FROM users WHERE id = ?", userId
        );
        assertThat(userRow.get("status")).isEqualTo("ACTIVE");
        assertThat(userRow.get("email_verified_at")).isNotNull();

        // Historical already-revoked session timestamps remain completely unchanged
        Map<String, Object> historicalAfter = jdbc.queryForMap(
                "SELECT issued_at, expires_at, revoked_at FROM auth_sessions WHERE id = ?", historicalSessionId
        );
        assertThat(historicalAfter).isEqualTo(historicalBefore);

        // Separate purpose token remains intact
        Instant separateTokenUsedAt = jdbc.queryForObject(
                "SELECT used_at FROM auth_tokens WHERE id = ?", Instant.class, verifyEmailTokenId
        );
        assertThat(separateTokenUsedAt).isNull();

        // Authentication evidence: old password rejected, new password logs in
        BrowserCsrf csrf = getBootCsrf();
        mvc.perform(post("/api/v1/auth/login")
                        .secure(true)
                        .cookie(csrf.cookie())
                        .header(csrf.headerName(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", initialPassword))))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/v1/auth/login")
                        .secure(true)
                        .cookie(csrf.cookie())
                        .header(csrf.headerName(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", newPassword))))
                .andExpect(status().isOk());
    }

    @Test
    void resetWithZeroTotalSessionRowsReturnsZeroRevokedSessionsAndPreservesStateWithoutSessionCreation() throws Exception {
        String email = "zero-session-" + UUID.randomUUID() + "@example.test";
        String initialPassword = "ZeroSessionInitPassword123";
        String newPassword = "ZeroSessionNewPassword123";
        UUID userId = addActiveVerifiedUser(email, initialPassword);

        // Assert user has literally ZERO rows in auth_sessions before reset
        int totalSessionsBefore = jdbc.queryForObject(
                "SELECT count(*) FROM auth_sessions WHERE user_id = ?", Integer.class, userId
        );
        assertThat(totalSessionsBefore).isZero();

        // Legitimate persisted grant fixture
        PasswordResetGrantToken grant = PasswordResetGrantToken.generate();
        jdbc.update("""
                INSERT INTO auth_tokens(id, user_id, email, purpose, token_hash, created_at, expires_at, used_at, attempts)
                VALUES (?, ?, cast(? as citext), 'RESET_PASSWORD_GRANT', ?, clock_timestamp(), clock_timestamp() + INTERVAL '10 minutes', NULL, 0)
                """, UUID.randomUUID(), userId, email, grant.digest());

        // Perform HTTP reset
        MvcResult resetRes = mvc.perform(post("/api/v1/auth/reset-password")
                        .with(req -> { req.setRemoteAddr(nextPeer()); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("resetGrant", grant.rawGrant(), "newPassword", newPassword))))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.revokedSessions").value(0))
                .andReturn();

        // No auth cookies or sessions issued during reset
        assertThat(resetRes.getResponse().getHeaderNames()).noneMatch(h -> h.equalsIgnoreCase(HttpHeaders.SET_COOKIE));

        // Assert literally ZERO rows in auth_sessions after reset (no session creation / auto-login)
        int totalSessionsAfter = jdbc.queryForObject(
                "SELECT count(*) FROM auth_sessions WHERE user_id = ?", Integer.class, userId
        );
        assertThat(totalSessionsAfter).isZero();

        // Target user remains ACTIVE and verified; password hash updated
        Map<String, Object> userRow = jdbc.queryForMap(
                "SELECT status, email_verified_at, password_hash FROM users WHERE id = ?", userId
        );
        assertThat(userRow.get("status")).isEqualTo("ACTIVE");
        assertThat(userRow.get("email_verified_at")).isNotNull();
        assertThat(encoder.matches(newPassword, (String) userRow.get("password_hash"))).isTrue();
        assertThat(encoder.matches(initialPassword, (String) userRow.get("password_hash"))).isFalse();

        // Authentication evidence: old password rejected, new password logs in
        BrowserCsrf csrf = getBootCsrf();
        mvc.perform(post("/api/v1/auth/login")
                        .secure(true)
                        .cookie(csrf.cookie())
                        .header(csrf.headerName(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", initialPassword))))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/v1/auth/login")
                        .secure(true)
                        .cookie(csrf.cookie())
                        .header(csrf.headerName(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", newPassword))))
                .andExpect(status().isOk());
    }

    @Test
    void completeRealHttpFlowFromRegistrationToRecoveryResetAndReauthentication() throws Exception {
        String flowEmail = "full-http-flow-" + UUID.randomUUID() + "@example.test";
        String oldPassword = "InitialOldPassword123";
        String newPassword = "CompleteNewPassword456";

        // Step 1: Register new account via real HTTP
        MvcResult regRes = mvc.perform(post("/api/v1/auth/register")
                        .secure(true)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "email", flowEmail,
                                "password", oldPassword,
                                "displayName", "Real HTTP Flow User"
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").isString())
                .andExpect(jsonPath("$.challengeId").isString())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn();

        JsonNode regBody = json.readTree(regRes.getResponse().getContentAsString());
        UUID userId = UUID.fromString(regBody.get("userId").asText());
        UUID emailChallengeId = UUID.fromString(regBody.get("challengeId").asText());

        // Step 2: Deliver verification OTP via controlled worker and mail transport
        worker.runJobCycle();
        String verificationCode = extractDeliveredOtp(flowEmail, "Verification Code");
        assertThat(verificationCode).matches("^[0-9]{6}$");

        // Step 3: Verify email via HTTP
        mvc.perform(post("/api/v1/auth/verify-email")
                        .secure(true)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "challengeId", emailChallengeId.toString(),
                                "code", verificationCode
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // Step 4: Login with old password using real boot CSRF
        BrowserCsrf loginCsrf = getBootCsrf();
        MvcResult loginRes = mvc.perform(post("/api/v1/auth/login")
                        .secure(true)
                        .cookie(loginCsrf.cookie())
                        .header(loginCsrf.headerName(), loginCsrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", flowEmail, "password", oldPassword))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andReturn();

        String oldAccessToken = json.readTree(loginRes.getResponse().getContentAsString()).get("accessToken").asText();
        Cookie oldRefreshCookie = loginRes.getResponse().getCookie("__Secure-devsphere-refresh");
        assertThat(oldRefreshCookie).isNotNull();

        // Step 5: Verify authenticated access via /me
        mvc.perform(get("/api/v1/auth/me")
                        .secure(true)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + oldAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userId.toString()));

        // Step 6: Request password recovery via anonymous HTTP endpoint
        mvc.perform(post("/api/v1/auth/forgot-password")
                        .with(req -> { req.setRemoteAddr(nextPeer()); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", flowEmail))))
                .andExpect(status().isAccepted())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.status").value("ACCEPTED"));

        // Step 7: Worker delivers recovery OTP to controlled mail
        worker.runJobCycle();
        String recoveryOtp = extractDeliveredOtp(flowEmail, "Password Recovery Code");
        assertThat(recoveryOtp).matches("^[0-9]{6}$");

        // Step 8: Exchange recovery OTP for reset grant
        MvcResult grantRes = mvc.perform(post("/api/v1/auth/forgot-password/verify")
                        .with(req -> { req.setRemoteAddr(nextPeer()); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", flowEmail, "code", recoveryOtp))))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.resetGrant").isString())
                .andExpect(jsonPath("$.expiresAt").isString())
                .andReturn();

        String resetGrant = json.readTree(grantRes.getResponse().getContentAsString()).get("resetGrant").asText();

        // Step 9: Reset password using grant
        mvc.perform(post("/api/v1/auth/reset-password")
                        .with(req -> { req.setRemoteAddr(nextPeer()); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "resetGrant", resetGrant,
                                "newPassword", newPassword
                        ))))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.revokedSessions").isNumber());

        // Step 10: Re-authentication evidence:
        // 10a. Old password rejected
        BrowserCsrf postResetCsrf1 = getBootCsrf();
        mvc.perform(post("/api/v1/auth/login")
                        .secure(true)
                        .cookie(postResetCsrf1.cookie())
                        .header(postResetCsrf1.headerName(), postResetCsrf1.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", flowEmail, "password", oldPassword))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

        // 10b. Old access token rejected
        mvc.perform(get("/api/v1/auth/me")
                        .secure(true)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + oldAccessToken))
                .andExpect(status().isUnauthorized());

        // 10c. Old refresh token rejected
        BrowserCsrf refreshCsrf = getBootCsrf();
        mvc.perform(post("/api/v1/auth/refresh")
                        .secure(true)
                        .cookie(refreshCsrf.cookie(), oldRefreshCookie)
                        .header(refreshCsrf.headerName(), refreshCsrf.token()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_SESSION"));

        // 10d. New password logs in successfully
        BrowserCsrf postResetCsrf2 = getBootCsrf();
        MvcResult newLoginRes = mvc.perform(post("/api/v1/auth/login")
                        .secure(true)
                        .cookie(postResetCsrf2.cookie())
                        .header(postResetCsrf2.headerName(), postResetCsrf2.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", flowEmail, "password", newPassword))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andReturn();

        String newAccessToken = json.readTree(newLoginRes.getResponse().getContentAsString()).get("accessToken").asText();

        // 10e. Authenticated /me succeeds with new access token
        mvc.perform(get("/api/v1/auth/me")
                        .secure(true)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + newAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userId.toString()));
    }

    @Test
    void grantPersistenceDigestSafetyAndRedactedDiagnosticsWithAdversarialStrings() throws Exception {
        String email = "persistence-safety-" + UUID.randomUUID() + "@example.test";
        String password = "PersistenceUserPassword123";
        UUID userId = addActiveVerifiedUser(email, password);

        // Capture deterministic user fields before OTP/grant issuance and assert legitimate password verification
        Map<String, Object> userBefore = jdbc.queryForMap(
                "SELECT password_hash, email, display_name FROM users WHERE id = ?", userId
        );
        assertThat(encoder.matches(password, (String) userBefore.get("password_hash"))).isTrue();

        // Request recovery and acquire grant
        mvc.perform(post("/api/v1/auth/forgot-password")
                        .with(req -> { req.setRemoteAddr(nextPeer()); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email))))
                .andExpect(status().isAccepted());

        worker.runJobCycle();
        String otp = extractDeliveredOtp(email, "Password Recovery Code");

        MvcResult verifyRes = mvc.perform(post("/api/v1/auth/forgot-password/verify")
                        .with(req -> { req.setRemoteAddr(nextPeer()); return req; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "code", otp))))
                .andExpect(status().isOk())
                .andReturn();

        String rawGrant = json.readTree(verifyRes.getResponse().getContentAsString()).get("resetGrant").asText();
        assertThat(rawGrant).hasSize(43);

        // 1. Assert DB stores only canonical SHA-256 digest, never raw grant
        String expectedDigest = PasswordResetGrantToken.digest(rawGrant);
        String persistedDigest = jdbc.queryForObject(
                "SELECT token_hash FROM auth_tokens WHERE user_id = ? AND purpose = 'RESET_PASSWORD_GRANT'",
                String.class, userId
        );
        assertThat(persistedDigest).isEqualTo(expectedDigest);
        assertThat(persistedDigest).matches("^[0-9a-f]{64}$");
        assertThat(persistedDigest).isNotEqualTo(rawGrant);

        // Verify OTP storage by exact HMAC digest equality for its known challenge/purpose and never equality to plaintext
        Map<String, Object> otpRow = jdbc.queryForMap(
                "SELECT id, token_hash FROM auth_tokens WHERE user_id = ? AND purpose = 'RESET_PASSWORD'",
                userId
        );
        UUID otpChallengeId = (UUID) otpRow.get("id");
        String persistedOtpDigest = (String) otpRow.get("token_hash");
        String expectedOtpDigest = crypto.hash(otpChallengeId, otp, VerificationCodeCrypto.Purpose.RESET_PASSWORD);
        assertThat(persistedOtpDigest).isEqualTo(expectedOtpDigest);
        assertThat(persistedOtpDigest).isNotEqualTo(otp);
        assertThat(crypto.matches(otpChallengeId, otp, persistedOtpDigest, VerificationCodeCrypto.Purpose.RESET_PASSWORD)).isTrue();

        // Capture user fields after OTP/grant issuance and assert exact before/after equality
        Map<String, Object> userAfter = jdbc.queryForMap(
                "SELECT password_hash, email, display_name FROM users WHERE id = ?", userId
        );
        assertThat(userAfter.get("password_hash")).isEqualTo(userBefore.get("password_hash"));
        assertThat(userAfter.get("email")).isEqualTo(userBefore.get("email"));
        assertThat(userAfter.get("display_name")).isEqualTo(userBefore.get("display_name"));
        // Legitimate Argon2 password verification rather than incidental substring searches
        assertThat(encoder.matches(password, (String) userAfter.get("password_hash"))).isTrue();

        // 2. Redacted diagnostic representations
        PasswordResetGrantToken token = PasswordResetGrantToken.generate();
        assertThat(token.toString()).isEqualTo("PasswordResetGrantToken[redacted]");
        assertThat(token.toString()).doesNotContain(token.rawGrant());
        assertThat(token.toString()).doesNotContain(token.digest());

        PasswordRecoveryController.ForgotPasswordRequest forgotReq =
                new PasswordRecoveryController.ForgotPasswordRequest("victim@example.test");
        assertThat(forgotReq.toString()).isEqualTo("ForgotPasswordRequest[redacted]");
        assertThat(forgotReq.toString()).doesNotContain("victim@example.test");

        PasswordRecoveryController.VerifyRecoveryRequest verifyReq =
                new PasswordRecoveryController.VerifyRecoveryRequest("victim@example.test", "123456");
        assertThat(verifyReq.toString()).isEqualTo("VerifyRecoveryRequest[redacted]");
        assertThat(verifyReq.toString()).doesNotContain("123456");

        PasswordRecoveryController.VerifyRecoveryResponse verifyResp =
                new PasswordRecoveryController.VerifyRecoveryResponse(rawGrant, Instant.now());
        assertThat(verifyResp.toString()).isEqualTo("VerifyRecoveryResponse[redacted]");
        assertThat(verifyResp.toString()).doesNotContain(rawGrant);

        PasswordRecoveryController.ResetPasswordRequest resetReq =
                new PasswordRecoveryController.ResetPasswordRequest(rawGrant, "newSecretPassword123");
        assertThat(resetReq.toString()).isEqualTo("ResetPasswordRequest[redacted]");
        assertThat(resetReq.toString()).doesNotContain(rawGrant);
        assertThat(resetReq.toString()).doesNotContain("newSecretPassword123");

        PasswordRecoveryService.VerifyRecoveryResult recoveryResult =
                PasswordRecoveryService.VerifyRecoveryResult.accepted(rawGrant, Instant.now());
        assertThat(recoveryResult.toString()).isEqualTo("VerifyRecoveryResult[redacted]");
        assertThat(recoveryResult.toString()).doesNotContain(rawGrant);

        // 3. Adversarial strings against public error / body shape
        String[] adversarialInputs = {
                "' OR 1=1; --",
                "'; DROP TABLE auth_tokens; --",
                "<script>alert('xss')</script>",
                "<img src=x onerror=alert(1)>",
                "grant\r\nSet-Cookie: evil_cookie=injected\r\n",
                "grant\u0000\uFFFF\u0007",
                "%s%n%d%x%p%#x",
                "A".repeat(3000),
                "{\"injectedJson\": true, \"extra\": [1,2,3]}"
        };

        for (String adversarial : adversarialInputs) {
            MvcResult res = mvc.perform(post("/api/v1/auth/reset-password")
                            .with(req -> { req.setRemoteAddr(nextPeer()); return req; })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of(
                                    "resetGrant", adversarial,
                                    "newPassword", "ValidPassword123"
                            ))))
                    .andExpect(status().isBadRequest())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                    .andExpect(jsonPath("$.code").value("RESET_GRANT_INVALID"))
                    .andReturn();

            assertThat(res.getResponse().getHeaderNames()).noneMatch(h -> h.equalsIgnoreCase(HttpHeaders.SET_COOKIE));
            String body = res.getResponse().getContentAsString();
            assertThat(body).doesNotContain(adversarial);
            assertThat(body).doesNotContain("ValidPassword123");
        }

        // Assert database tables intact after adversarial attempts
        int remainingTokens = jdbc.queryForObject("SELECT count(*) FROM auth_tokens", Integer.class);
        assertThat(remainingTokens).isGreaterThanOrEqualTo(1);
    }
}

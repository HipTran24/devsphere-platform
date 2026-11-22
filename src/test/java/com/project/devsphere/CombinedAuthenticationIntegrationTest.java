package com.project.devsphere;

import com.project.devsphere.identity.domain.VerificationCodeCrypto;
import com.project.devsphere.identity.domain.SessionTokens;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.http.Cookie;
import java.util.Base64;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "devsphere.database.manage-runtime-privileges=false",
        "devsphere.auth.mail.from=devsphere@example.test",
        "spring.mail.host=mail.example.test",
        "devsphere.auth.mail.initial-delay-ms=600000"
})
@AutoConfigureMockMvc
class CombinedAuthenticationIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired VerificationCodeCrypto crypto;
    @Autowired SessionTokens sessionTokens;
    @Autowired PasswordEncoder passwordEncoder;
    private static final String PASSWORD = "a long test password";
    @MockitoBean JavaMailSender mailSender;
    private final JsonMapper json = new JsonMapper();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        DevsphereApplicationTests.database(registry);
        registry.add("devsphere.auth.session.signing-key",
                () -> Base64.getEncoder().encodeToString(signingKey()));
        byte[] hmac = new byte[32];
        byte[] encryption = new byte[32];
        hmac[0] = 1;
        encryption[0] = 2;
        registry.add("devsphere.auth.otp.hmac-key", () -> Base64.getEncoder().encodeToString(hmac));
        registry.add("devsphere.auth.otp.encryption-key", () -> Base64.getEncoder().encodeToString(encryption));
    }

    @Test
    void registeredUnicodePasswordWorksThroughVerificationLoginRotationAndLogout() throws Exception {
        String email = UUID.randomUUID() + "@example.test";
        String symbol = new String(Character.toChars(0x1F9D1));
        String password = symbol.repeat(128);
        var registration = mvc.perform(post("/api/v1/auth/register").secure(true).contentType("application/json")
                        .content(json.writeValueAsString(Map.of(
                                "email", email, "password", password, "displayName", symbol.repeat(120)))))
                .andExpect(status().isCreated()).andReturn().getResponse();
        var receipt = json.readTree(registration.getContentAsString());
        String userId = receipt.get("userId").asText();
        UUID challenge = UUID.fromString(receipt.get("challengeId").asText());
        String encryptedCode = jdbc.queryForObject("""
                select payload->>'ciphertext' from background_jobs
                where job_kind='AUTH_VERIFY_EMAIL' and idempotency_key=?
                """, String.class, challenge.toString());
        mvc.perform(post("/api/v1/auth/verify-email").secure(true).contentType("application/json")
                        .content(json.writeValueAsString(Map.of(
                                "challengeId", challenge, "code", crypto.decrypt(challenge, encryptedCode)))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));

        var csrfResponse = mvc.perform(get("/api/v1/auth/csrf").secure(true))
                .andExpect(status().isOk()).andReturn().getResponse();
        Cookie csrfCookie = csrfResponse.getCookie("__Host-devsphere-csrf");
        assertThat(csrfCookie).isNotNull();
        var csrf = json.readTree(csrfResponse.getContentAsString());
        String header = csrf.get("headerName").asText();
        String token = csrf.get("token").asText();

        var login = mvc.perform(post("/api/v1/auth/login").secure(true).cookie(csrfCookie)
                        .header(header, token).contentType("application/json")
                        .content(json.writeValueAsString(Map.of("email", email, "password", password))))
                .andExpect(status().isOk()).andReturn().getResponse();
        String firstAccess = json.readTree(login.getContentAsString()).get("accessToken").asText();
        Cookie firstRefresh = login.getCookie("__Secure-devsphere-refresh");
        assertThat(firstRefresh).isNotNull();
        assertThat(login.getHeader("Set-Cookie"))
                .contains("Path=/api/v1/auth", "HttpOnly", "Secure", "SameSite=Lax");
        assertThat(firstRefresh.getMaxAge()).isPositive().isLessThanOrEqualTo(14 * 24 * 60 * 60);
        mvc.perform(get("/api/v1/auth/me").secure(true).header("Authorization", "Bearer " + firstAccess))
                .andExpect(status().isOk()).andExpect(jsonPath("$.userId").value(userId));

        var rotated = mvc.perform(post("/api/v1/auth/refresh").secure(true).cookie(csrfCookie, firstRefresh)
                        .header(header, token))
                .andExpect(status().isOk()).andReturn().getResponse();
        String newAccess = json.readTree(rotated.getContentAsString()).get("accessToken").asText();
        Cookie newRefresh = rotated.getCookie("__Secure-devsphere-refresh");
        assertThat(newRefresh).isNotNull();
        assertThat(newRefresh.getValue()).isNotEqualTo(firstRefresh.getValue());
        mvc.perform(get("/api/v1/auth/me").secure(true).header("Authorization", "Bearer " + firstAccess))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/me").secure(true).header("Authorization", "Bearer " + newAccess))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/auth/logout").secure(true).cookie(csrfCookie, newRefresh).header(header, token))
                .andExpect(status().isNoContent())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Set-Cookie", org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.containsString("Max-Age=0"),
                                org.hamcrest.Matchers.containsString("Path=/api/v1/auth"),
                                org.hamcrest.Matchers.containsString("HttpOnly"),
                                org.hamcrest.Matchers.containsString("Secure"),
                                org.hamcrest.Matchers.containsString("SameSite=Lax"))));
        mvc.perform(get("/api/v1/auth/me").secure(true).header("Authorization", "Bearer " + newAccess))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/refresh").secure(true).cookie(csrfCookie, newRefresh).header(header, token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void negativeCredentialsAndAccountStatesUseRealHttp() throws Exception {
        BrowserCsrf c = csrf();
        Fixture active = account("ACTIVE");
        Fixture pending = account("PENDING");
        Fixture locked = account("LOCKED");

        login(active, "wrong-password", c)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

        login(pending, PASSWORD, c)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EMAIL_UNVERIFIED"));

        login(locked, PASSWORD, c)
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));

        for (Fixture f : List.of(active, pending, locked)) {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM auth_sessions WHERE user_id = ?",
                    Integer.class,
                    f.id()
            );
            assertThat(count).isEqualTo(0);
        }
        var response = login(active, PASSWORD, c).andExpect(status().isOk()).andReturn().getResponse();
        String access = json.readTree(response.getContentAsString()).get("accessToken").asText();
        Cookie refresh = response.getCookie("__Secure-devsphere-refresh");
        assertThat(refresh).isNotNull();
        jdbc.update("update users set status='LOCKED' where id=?", active.id());
        bearer(access).andExpect(status().isUnauthorized());
        refresh(refresh, c).andExpect(status().isUnauthorized());
    }

    @Test
    void invalidJwtAndExpiredDatabaseSessionsAreRejected() throws Exception {
        BrowserCsrf c = csrf();
        Session valid = loginActive(c);

        bearer(valid.access())
                .andExpect(status().isOk());

        for (String invalidToken : invalidAccessTokens(valid.access())) {
            bearer(invalidToken)
                    .andExpect(status().isUnauthorized());
        }

        Session expired = expiredDatabaseSession(valid.userId());

        bearer(expired.access())
                .andExpect(status().isUnauthorized());

        refresh(expired.refresh(), c)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_SESSION"));

        bearer(valid.access())
                .andExpect(status().isOk());
    }

    @Test
    void simultaneousHttpRefreshRevokesTheReplayedFamily() throws Exception {
        BrowserCsrf c = csrf();
        Session initial = loginActive(c);

        List<MockHttpServletResponse> responses = concurrentRefresh(initial.refresh(), c);

        List<Integer> statuses = responses.stream()
                .map(MockHttpServletResponse::getStatus)
                .sorted()
                .toList();
        assertThat(statuses).containsExactly(200, 401);

        MockHttpServletResponse okResponse = responses.stream()
                .filter(r -> r.getStatus() == 200)
                .findFirst()
                .orElseThrow();

        String newAccess = json.readTree(okResponse.getContentAsString())
                .get("accessToken")
                .asText();

        bearer(newAccess)
                .andExpect(status().isUnauthorized());

        Integer totalRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM auth_sessions WHERE user_id = ?",
                Integer.class,
                initial.userId()
        );
        assertThat(totalRows).isEqualTo(2);

        Integer activeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM auth_sessions WHERE user_id = ? AND revoked_at IS NULL AND expires_at > NOW()",
                Integer.class,
                initial.userId()
        );
        assertThat(activeCount).isEqualTo(0);
    }

    private record Fixture(UUID id, String email) {}

    private record Session(UUID userId, String access, Cookie refresh) {
        @Override
        public String toString() {
            return "Session[redacted]";
        }
    }

    private record BrowserCsrf(Cookie cookie, String header, String token) {
        @Override
        public String toString() {
            return "BrowserCsrf[redacted]";
        }
    }

    private static byte[] signingKey() {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 3);
        return key;
    }

    private BrowserCsrf csrf() throws Exception {
        var response = mvc.perform(get("/api/v1/auth/csrf").secure(true))
                .andExpect(status().isOk()).andReturn().getResponse();
        Cookie cookie = response.getCookie("__Host-devsphere-csrf");
        assertThat(cookie).isNotNull();
        var body = json.readTree(response.getContentAsString());
        return new BrowserCsrf(cookie, body.get("headerName").asText(), body.get("token").asText());
    }

    private Fixture account(String state) {
        UUID id = UUID.randomUUID();
        String email = id + "@example.test";
        jdbc.update("""
                insert into users(id,email,display_name,password_hash,status,email_verified_at)
                values (?,cast(? as citext),'Session Test User',?,?,case when ?='PENDING' then null else clock_timestamp() end)
                """, id, email, passwordEncoder.encode(PASSWORD), state, state);
        return new Fixture(id, email);
    }

    private ResultActions login(Fixture user, String password, BrowserCsrf csrf) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").secure(true)
                .cookie(csrf.cookie()).header(csrf.header(), csrf.token()).contentType("application/json")
                .content(json.writeValueAsString(Map.of("email", user.email(), "password", password))));
    }

    private Session loginActive(BrowserCsrf csrf) throws Exception {
        var user = account("ACTIVE");
        var response = login(user, PASSWORD, csrf).andExpect(status().isOk()).andReturn().getResponse();
        Cookie refresh = response.getCookie("__Secure-devsphere-refresh");
        assertThat(refresh).isNotNull();
        return new Session(user.id(), json.readTree(response.getContentAsString()).get("accessToken").asText(), refresh);
    }

    private ResultActions bearer(String access) throws Exception {
        return mvc.perform(get("/api/v1/auth/me").secure(true).header("Authorization", "Bearer " + access));
    }

    private ResultActions refresh(Cookie refresh, BrowserCsrf csrf) throws Exception {
        return mvc.perform(post("/api/v1/auth/refresh").secure(true)
                .cookie(csrf.cookie(), refresh).header(csrf.header(), csrf.token()));
    }

    private List<String> invalidAccessTokens(String valid) throws Exception {
        var parsed = SignedJWT.parse(valid);
        var claims = parsed.getJWTClaimsSet();
        Instant now = Instant.now();
        var variants = List.of(
                new JWTClaimsSet.Builder(claims).issuer("other-issuer").build(),
                new JWTClaimsSet.Builder(claims).audience("other-audience").build(),
                new JWTClaimsSet.Builder(claims).claim("token_use", "refresh").build(),
                new JWTClaimsSet.Builder(claims).subject("invalid-uuid").build(),
                new JWTClaimsSet.Builder(claims).subject(UUID.randomUUID().toString()).build(),
                new JWTClaimsSet.Builder(claims).claim("sid", null).build(),
                new JWTClaimsSet.Builder(claims).jwtID(null).build(),
                new JWTClaimsSet.Builder(claims).notBeforeTime(Date.from(now.plusSeconds(30)))
                        .issueTime(Date.from(now.plusSeconds(30)))
                        .expirationTime(Date.from(now.plusSeconds(120))).build(),
                new JWTClaimsSet.Builder(claims).expirationTime(Date.from(now.plusSeconds(1000))).build(),
                new JWTClaimsSet.Builder(claims).issueTime(Date.from(now.minusSeconds(60)))
                        .notBeforeTime(Date.from(now.minusSeconds(60)))
                        .expirationTime(Date.from(now.minusSeconds(1))).build());
        List<String> tokens = new ArrayList<>();
        for (var variant : variants) {
            var jwt = new SignedJWT(parsed.getHeader(), variant);
            jwt.sign(new MACSigner(signingKey()));
            tokens.add(jwt.serialize());
        }
        byte[] wrongKey = signingKey();
        wrongKey[0] = 4;
        var forged = new SignedJWT(parsed.getHeader(), claims);
        forged.sign(new MACSigner(wrongKey));
        tokens.add(forged.serialize());
        return tokens;
    }

    private Session expiredDatabaseSession(UUID userId) {
        UUID sessionId = UUID.randomUUID();
        String refresh = sessionTokens.generateRefresh();
        jdbc.update("""
                insert into auth_sessions(id,user_id,session_family_id,refresh_hash,issued_at,expires_at,created_at)
                values (?,?,?,?,clock_timestamp()-interval '2 hours',clock_timestamp()-interval '1 hour',
                    clock_timestamp()-interval '2 hours')
                """, sessionId, userId, UUID.randomUUID(), sessionTokens.digestRefresh(refresh));
        return new Session(userId, sessionTokens.issueAccess(userId, sessionId).value(),
                new Cookie("__Secure-devsphere-refresh", refresh));
    }

    private List<MockHttpServletResponse> concurrentRefresh(Cookie refresh, BrowserCsrf csrf) throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<MockHttpServletResponse> request = () -> {
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("Concurrent requests did not start");
                }
                return refresh(refresh, csrf).andReturn().getResponse();
            };
            var first = executor.submit(request);
            var second = executor.submit(request);
            start.countDown();
            try {
                return List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            } finally {
                first.cancel(true);
                second.cancel(true);
            }
        }
    }
}

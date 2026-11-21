package com.project.devsphere.identity.api;

import tools.jackson.databind.json.JsonMapper;
import com.project.devsphere.identity.application.LoginAttemptLimiter;
import com.project.devsphere.identity.application.SessionService;
import com.project.devsphere.identity.domain.SessionTokens;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SessionController.class)
@Import({SessionSecurityConfiguration.class, LoginAttemptLimiter.class})
class SessionSecurityTest {
    private static final String LOGIN = """
            {"email":" USER@example.test ","password":"a long test password"}
            """;
    private static final String REFRESH_COOKIE = "__Secure-devsphere-refresh";
    @Autowired MockMvc mvc;
    @MockitoBean SessionService sessionService;

    @Test
    void csrfIsRequiredBeforeLoginRefreshOrLogoutTouchesService() throws Exception {
        for (String operation : new String[]{"login", "refresh", "logout"}) {
            mvc.perform(post("/api/v1/auth/" + operation).contentType("application/json").content(LOGIN)
                            .cookie(new Cookie(REFRESH_COOKIE, "test-refresh")))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(sessionService);
    }

    @Test
    void loginSetsScopedSecureCookieAndExposesOnlyAccessToken() throws Exception {
        Csrf csrf = csrf();
        Instant expiresAt = Instant.now().plusSeconds(900);
        when(sessionService.login("user@example.test", "a long test password")).thenReturn(
                new SessionService.Attempt(SessionService.Outcome.SUCCESS,
                        new SessionTokens.IssuedAccess("test-access", expiresAt),
                        "test-refresh", Instant.now().plusSeconds(2000)));

        var result = mvc.perform(post("/api/v1/auth/login").cookie(csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).contentType("application/json").content(LOGIN))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.accessToken").value("test-access"))
                .andExpect(jsonPath("$.refresh").doesNotExist())
                .andReturn();
        assertThat(result.getResponse().getHeader("Set-Cookie"))
                .contains("__Secure-devsphere-refresh=test-refresh", "Path=/api/v1/auth", "HttpOnly", "Secure", "SameSite=Lax");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("test-refresh");
    }

    @Test
    void replayResponseClearsCookieWithMatchingAttributes() throws Exception {
        Csrf csrf = csrf();
        when(sessionService.refresh("first-refresh")).thenReturn(
                new SessionService.Attempt(SessionService.Outcome.SESSION_REPLAYED, null, null, null));
        var result = mvc.perform(post("/api/v1/auth/refresh")
                        .cookie(csrf.cookie(), new Cookie(REFRESH_COOKIE, "first-refresh"))
                        .header(csrf.headerName(), csrf.token()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_REPLAYED"))
                .andReturn();
        assertThat(result.getResponse().getHeader("Set-Cookie"))
                .contains("__Secure-devsphere-refresh=", "Path=/api/v1/auth", "Max-Age=0", "HttpOnly", "Secure");
    }

    @Test
    void meRequiresAndUsesPrincipalFromSessionService() throws Exception {
        mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"));
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        when(sessionService.authenticate("test-access")).thenReturn(Optional.of(
                new SessionService.Principal(userId, sessionId, "Example", "USER")));
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer test-access"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userId.toString()))
                .andExpect(jsonPath("$.sessionId").value(sessionId.toString()))
                .andExpect(jsonPath("$.systemRole").value("USER"));
    }

    @Test
    void sessionListAndRevokeRequireBearerEvenWithCookies() throws Exception {
        UUID family = UUID.randomUUID();
        String path = "/api/v1/auth/sessions";
        mvc.perform(get(path).cookie(new Cookie(REFRESH_COOKIE, "opaque"))).andExpect(status().isUnauthorized());
        mvc.perform(delete(path + "/" + family).cookie(new Cookie(REFRESH_COOKIE, "opaque")))
                .andExpect(status().isUnauthorized());

        var principal = new SessionService.Principal(UUID.randomUUID(), UUID.randomUUID(), "Example", "USER");
        when(sessionService.authenticate("test-access")).thenReturn(Optional.of(principal));
        Instant now = Instant.now();
        when(sessionService.list(principal)).thenReturn(List.of(
                new SessionService.SessionSummary(family, now, now.plusSeconds(3600), true)));
        when(sessionService.revoke(principal, family)).thenReturn(true);
        mvc.perform(get(path).header("Authorization", "Bearer test-access"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].familyId").value(family.toString()));
        mvc.perform(delete(path + "/" + family).header("Authorization", "Bearer test-access"))
                .andExpect(status().isNoContent());
        mvc.perform(delete(path + "/" + UUID.randomUUID()).header("Authorization", "Bearer test-access"))
                .andExpect(status().isNotFound());
    }

    private Csrf csrf() throws Exception {
        var response = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk())
                .andReturn().getResponse();
        var json = new JsonMapper().readTree(response.getContentAsString());
        Cookie cookie = response.getCookie("__Host-devsphere-csrf");
        assertThat(cookie).isNotNull();
        assertThat(cookie.isHttpOnly()).isTrue();
        return new Csrf(cookie, json.get("headerName").asText(), json.get("token").asText());
    }

    private record Csrf(Cookie cookie, String headerName, String token) {}
}

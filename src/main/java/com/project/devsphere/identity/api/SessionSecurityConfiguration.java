package com.project.devsphere.identity.api;

import com.project.devsphere.identity.application.SessionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SessionSecurityConfiguration {

    private static final PathPatternRequestMatcher.Builder ROUTES = PathPatternRequestMatcher.withDefaults();
    private static final RequestMatcher LIST_SESSIONS = ROUTES.matcher(HttpMethod.GET, "/api/v1/auth/sessions");
    private static final RequestMatcher REVOKE_SESSION = ROUTES.matcher(HttpMethod.DELETE, "/api/v1/auth/sessions/{familyId}");
    private static final RequestMatcher CURRENT_USER = ROUTES.matcher(HttpMethod.GET, "/api/v1/auth/me");
    private static final RequestMatcher BEARER_ENDPOINTS =
            new OrRequestMatcher(CURRENT_USER, LIST_SESSIONS, REVOKE_SESSION);

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            SessionService sessionService) throws Exception {

        CookieCsrfTokenRepository csrfTokenRepository = new CookieCsrfTokenRepository();
        csrfTokenRepository.setCookieName("__Host-devsphere-csrf");
        csrfTokenRepository.setCookieCustomizer(customizer -> customizer
                .path("/")
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax"));

        XorCsrfTokenRequestAttributeHandler csrfTokenRequestHandler = new XorCsrfTokenRequestAttributeHandler();

        http
            .csrf(csrf -> csrf
                .csrfTokenRepository(csrfTokenRepository)
                .csrfTokenRequestHandler(csrfTokenRequestHandler)
                .ignoringRequestMatchers(
                    REVOKE_SESSION,
                    ROUTES.matcher(HttpMethod.POST, "/api/v1/auth/register"),
                    ROUTES.matcher(HttpMethod.POST, "/api/v1/auth/verify-email"),
                    ROUTES.matcher(HttpMethod.POST, "/api/v1/auth/verification/resend"),
                    ROUTES.matcher(HttpMethod.POST, "/api/v1/auth/forgot-password"),
                    ROUTES.matcher(HttpMethod.POST, "/api/v1/auth/forgot-password/verify"),
                    ROUTES.matcher(HttpMethod.POST, "/api/v1/auth/reset-password")
                )
            )
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .requestCache(AbstractHttpConfigurer::disable)
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint((request, response, failure) ->
                        writeProblem(response, HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized"))
                .accessDeniedHandler((request, response, failure) ->
                        writeProblem(response, HttpServletResponse.SC_FORBIDDEN, "Forbidden"))
            )
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/register").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/verify-email").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/verification/resend").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/forgot-password").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/forgot-password/verify").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/reset-password").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/refresh").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/logout").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf").permitAll()
                .requestMatchers(BEARER_ENDPOINTS).authenticated()
                .requestMatchers("/api/**").denyAll()
                .anyRequest().permitAll()
            )
            .addFilterBefore(
                new SessionBearerAuthenticationFilter(sessionService),
                UsernamePasswordAuthenticationFilter.class
            );

        return http.build();
    }

    private static void writeProblem(HttpServletResponse response, int status, String title) throws IOException {
        response.setStatus(status);
        if (status == HttpServletResponse.SC_UNAUTHORIZED) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.getWriter().write("{\"title\":\"" + title + "\",\"status\":" + status + "}");
    }

    private static final class SessionBearerAuthenticationFilter extends OncePerRequestFilter {

        private static final String BEARER_PREFIX = "Bearer ";

        private final SessionService sessionService;

        SessionBearerAuthenticationFilter(SessionService sessionService) {
            this.sessionService = sessionService;
        }

        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            return !BEARER_ENDPOINTS.matches(request);
        }

        @Override
        protected void doFilterInternal(
                HttpServletRequest request,
                HttpServletResponse response,
                FilterChain filterChain) throws ServletException, IOException {

            List<String> authHeaders = Collections.list(request.getHeaders(HttpHeaders.AUTHORIZATION));
            if (authHeaders.size() == 1) {
                String header = authHeaders.get(0).trim();
                if (header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
                    String candidateToken = header.substring(BEARER_PREFIX.length()).trim();
                    if (!candidateToken.isEmpty() && !candidateToken.contains(" ") && !candidateToken.contains(",")) {
                        Optional<SessionService.Principal> principalOptional = sessionService.authenticate(candidateToken);
                        if (principalOptional.isPresent()) {
                            SessionService.Principal principal = principalOptional.get();
                            SecurityContext context = SecurityContextHolder.createEmptyContext();
                            context.setAuthentication(
                                    UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of())
                            );
                            SecurityContextHolder.setContext(context);
                        }
                    }
                }
            }

            filterChain.doFilter(request, response);
        }
    }
}

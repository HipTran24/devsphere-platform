package com.project.devsphere.identity.api;

import com.project.devsphere.identity.application.LoginAttemptLimiter;
import com.project.devsphere.identity.application.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.CodePointLength;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
public class SessionController {

    private static final String REFRESH_COOKIE_NAME = "__Secure-devsphere-refresh";
    private static final String AUTH_PATH = "/api/v1/auth";
    private static final String CACHE_CONTROL_VALUE = "no-store";

    private final SessionService sessionService;
    private final LoginAttemptLimiter loginAttemptLimiter;

    public SessionController(SessionService sessionService, LoginAttemptLimiter loginAttemptLimiter) {
        this.sessionService = sessionService;
        this.loginAttemptLimiter = loginAttemptLimiter;
    }

    public record LoginRequest(
            @NotBlank(message = "Email is required")
            @Email(message = "Email must be valid")
            @CodePointLength(max = 320)
            String email,

            @NotNull(message = "Password is required")
            @CodePointLength(min = 1, max = 128, message = "Password must be between 1 and 128 characters")
            String password
    ) {
        public LoginRequest {
            email = email == null ? null : email.strip().toLowerCase(Locale.ROOT);
        }

        @Override
        public String toString() {
            return "LoginRequest[redacted]";
        }
    }

    public record AccessResponse(String accessToken, Instant expiresAt) {
        @Override
        public String toString() {
            return "AccessResponse[expiresAt=" + expiresAt + "]";
        }
    }

    public record UserPrincipalResponse(
            UUID userId,
            UUID sessionId,
            String displayName,
            String systemRole
    ) {}

    public record CsrfResponse(
            String headerName,
            String parameterName,
            String token
    ) {
        @Override
        public String toString() {
            return "CsrfResponse[redacted]";
        }
    }

    @GetMapping("/csrf")
    public ResponseEntity<CsrfResponse> csrf(CsrfToken csrfToken) {
        CsrfResponse response = new CsrfResponse(
                csrfToken.getHeaderName(),
                csrfToken.getParameterName(),
                csrfToken.getToken()
        );
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_VALUE)
                .body(response);
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        Duration retryAfter = loginAttemptLimiter.retryAfter(request.email(), httpRequest.getRemoteAddr());
        if (!retryAfter.isZero()) {
            long seconds = retryAfter.toSeconds() + (retryAfter.getNano() == 0 ? 0 : 1);
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.TOO_MANY_REQUESTS, "Too many sign-in attempts. Try again later.");
            problem.setProperty("code", "LOGIN_RATE_LIMITED");
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, Long.toString(seconds))
                    .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_VALUE)
                    .body(problem);
        }
        SessionService.Attempt attempt = sessionService.login(request.email(), request.password());

        return switch (attempt.outcome()) {
            case SUCCESS -> {
                ResponseCookie refreshCookie = buildRefreshCookie(
                        attempt.refresh(),
                        attempt.refreshExpiresAt()
                );
                AccessResponse responseBody = new AccessResponse(
                        attempt.access().value(),
                        attempt.access().expiresAt()
                );
                yield ResponseEntity.ok()
                        .header(HttpHeaders.SET_COOKIE, refreshCookie.toString())
                        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_VALUE)
                        .body(responseBody);
            }
            case INVALID_CREDENTIALS -> errorResponse(
                    HttpStatus.UNAUTHORIZED,
                    "INVALID_CREDENTIALS",
                    "Invalid email or password."
            );
            case ACCOUNT_LOCKED -> errorResponse(
                    HttpStatus.LOCKED,
                    "ACCOUNT_LOCKED",
                    "Account is temporarily locked."
            );
            case EMAIL_UNVERIFIED -> errorResponse(
                    HttpStatus.FORBIDDEN,
                    "EMAIL_UNVERIFIED",
                    "Email address must be verified before signing in."
            );
            default -> errorResponse(
                    HttpStatus.UNAUTHORIZED,
                    "INVALID_CREDENTIALS",
                    "Authentication failed."
            );
        };
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(
            @CookieValue(value = REFRESH_COOKIE_NAME, required = false) String rawRefreshToken
    ) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return errorResponse(
                    HttpStatus.UNAUTHORIZED,
                    "INVALID_SESSION",
                    "Valid refresh session is missing."
            );
        }

        SessionService.Attempt attempt = sessionService.refresh(rawRefreshToken);

        return switch (attempt.outcome()) {
            case SUCCESS -> {
                ResponseCookie newRefreshCookie = buildRefreshCookie(
                        attempt.refresh(),
                        attempt.refreshExpiresAt()
                );
                AccessResponse responseBody = new AccessResponse(
                        attempt.access().value(),
                        attempt.access().expiresAt()
                );
                yield ResponseEntity.ok()
                        .header(HttpHeaders.SET_COOKIE, newRefreshCookie.toString())
                        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_VALUE)
                        .body(responseBody);
            }
            case INVALID_SESSION -> errorResponseWithCookie(
                    HttpStatus.UNAUTHORIZED,
                    "INVALID_SESSION",
                    "Session is invalid or has expired.",
                    buildClearRefreshCookie()
            );
            case SESSION_REPLAYED -> errorResponseWithCookie(
                    HttpStatus.UNAUTHORIZED,
                    "SESSION_REPLAYED",
                    "Session invalidated due to token reuse detection.",
                    buildClearRefreshCookie()
            );
            case ACCOUNT_LOCKED -> errorResponse(
                    HttpStatus.LOCKED,
                    "ACCOUNT_LOCKED",
                    "Account is temporarily locked."
            );
            case EMAIL_UNVERIFIED -> errorResponse(
                    HttpStatus.FORBIDDEN,
                    "EMAIL_UNVERIFIED",
                    "Email address must be verified."
            );
            default -> errorResponseWithCookie(
                    HttpStatus.UNAUTHORIZED,
                    "INVALID_SESSION",
                    "Session refresh failed.",
                    buildClearRefreshCookie()
            );
        };
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(value = REFRESH_COOKIE_NAME, required = false) String rawRefreshToken
    ) {
        if (rawRefreshToken != null && !rawRefreshToken.isBlank()) {
            sessionService.logout(rawRefreshToken);
        }

        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, buildClearRefreshCookie().toString())
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_VALUE)
                .build();
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(@AuthenticationPrincipal SessionService.Principal principal) {
        if (principal == null) {
            return errorResponse(
                    HttpStatus.UNAUTHORIZED,
                    "UNAUTHORIZED",
                    "Authentication is required to access this resource."
            );
        }

        UserPrincipalResponse response = new UserPrincipalResponse(
                principal.userId(),
                principal.sessionId(),
                principal.displayName(),
                principal.systemRole()
        );

        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_VALUE)
                .body(response);
    }

    @GetMapping("/sessions")
    public ResponseEntity<List<SessionService.SessionSummary>> sessions(
            @AuthenticationPrincipal SessionService.Principal principal) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_VALUE)
                .body(sessionService.list(principal));
    }

    @DeleteMapping("/sessions/{familyId}")
    public ResponseEntity<?> revokeSession(
            @AuthenticationPrincipal SessionService.Principal principal, @PathVariable UUID familyId) {
        if (!sessionService.revoke(principal, familyId)) {
            return errorResponse(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND", "Active session was not found.");
        }
        return ResponseEntity.noContent()
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_VALUE)
                .build();
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ProblemDetail> invalidRequest() {
        return errorResponse(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Invalid request.");
    }

    private ResponseCookie buildRefreshCookie(String tokenValue, Instant expiresAt) {
        long maxAgeSeconds = Math.max(0, Duration.between(Instant.now(), expiresAt).toSeconds());
        return ResponseCookie.from(REFRESH_COOKIE_NAME, tokenValue)
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path(AUTH_PATH)
                .maxAge(maxAgeSeconds)
                .build();
    }

    private ResponseCookie buildClearRefreshCookie() {
        return ResponseCookie.from(REFRESH_COOKIE_NAME, "")
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path(AUTH_PATH)
                .maxAge(0)
                .build();
    }

    private ResponseEntity<ProblemDetail> errorResponse(HttpStatus status, String code, String message) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, message);
        problemDetail.setTitle(status.getReasonPhrase());
        problemDetail.setProperty("code", code);

        return ResponseEntity.status(status)
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_VALUE)
                .body(problemDetail);
    }

    private ResponseEntity<ProblemDetail> errorResponseWithCookie(
            HttpStatus status,
            String code,
            String message,
            ResponseCookie cookie
    ) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, message);
        problemDetail.setTitle(status.getReasonPhrase());
        problemDetail.setProperty("code", code);

        return ResponseEntity.status(status)
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_VALUE)
                .body(problemDetail);
    }
}

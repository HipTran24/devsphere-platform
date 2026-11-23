package com.project.devsphere.identity.api;

import com.project.devsphere.identity.application.PasswordRecoveryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.hibernate.validator.constraints.CodePointLength;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Locale;

@RestController
@RequestMapping(path = "/api/v1/auth", consumes = MediaType.APPLICATION_JSON_VALUE)
public class PasswordRecoveryController {

    private final PasswordRecoveryService passwordRecoveryService;

    public PasswordRecoveryController(PasswordRecoveryService passwordRecoveryService) {
        this.passwordRecoveryService = passwordRecoveryService;
    }

    public record ForgotPasswordRequest(
            @NotBlank
            @Email
            @CodePointLength(max = 320)
            String email
    ) {
        public ForgotPasswordRequest {
            email = email == null ? null : email.strip().toLowerCase(Locale.ROOT);
        }

        @Override
        public String toString() {
            return "ForgotPasswordRequest[redacted]";
        }
    }

    public record ForgotPasswordResponse(String status) {}

    public record VerifyRecoveryRequest(
            @NotBlank
            @Email
            @CodePointLength(max = 320)
            String email,

            @NotBlank
            @Pattern(regexp = "^[0-9]{6}$")
            String code
    ) {
        public VerifyRecoveryRequest {
            email = email == null ? null : email.strip().toLowerCase(Locale.ROOT);
        }

        @Override
        public String toString() {
            return "VerifyRecoveryRequest[redacted]";
        }
    }

    public record VerifyRecoveryResponse(String resetGrant, Instant expiresAt) {
        @Override
        public String toString() {
            return "VerifyRecoveryResponse[redacted]";
        }
    }

    public record ResetPasswordRequest(
            @NotNull
            String resetGrant,

            @NotBlank
            @CodePointLength(min = 12, max = 128)
            String newPassword
    ) {
        @Override
        public String toString() {
            return "ResetPasswordRequest[redacted]";
        }
    }

    public record ResetPasswordResponse(int revokedSessions) {}

    @PostMapping("/forgot-password")
    public ResponseEntity<ForgotPasswordResponse> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest request,
            HttpServletRequest servletRequest) {

        String peer = servletRequest.getRemoteAddr();
        passwordRecoveryService.requestPasswordRecovery(request.email(), peer);

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .cacheControl(CacheControl.noStore())
                .body(new ForgotPasswordResponse("ACCEPTED"));
    }

    @PostMapping("/forgot-password/verify")
    public ResponseEntity<VerifyRecoveryResponse> verifyRecoveryCode(
            @Valid @RequestBody VerifyRecoveryRequest request,
            HttpServletRequest servletRequest) {

        String peer = servletRequest.getRemoteAddr();
        PasswordRecoveryService.VerifyRecoveryResult result = passwordRecoveryService.verifyRecoveryCode(
                request.email(),
                request.code(),
                peer
        );

        if (!result.accepted()) {
            throw new PasswordRecoveryService.ResetCodeInvalidException();
        }

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new VerifyRecoveryResponse(result.resetGrant(), result.expiresAt()));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<ResetPasswordResponse> resetPassword(
            @Valid @RequestBody ResetPasswordRequest request,
            HttpServletRequest servletRequest) {

        String peer = servletRequest.getRemoteAddr();
        int revokedCount = passwordRecoveryService.resetPassword(request.resetGrant(), request.newPassword(), peer);

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new ResetPasswordResponse(revokedCount));
    }

    @ExceptionHandler(PasswordRecoveryService.RecoveryException.class)
    public ResponseEntity<ProblemDetail> handleRecoveryException(PasswordRecoveryService.RecoveryException ex) {
        HttpStatus status = switch (ex.getCode()) {
            case "RESET_CODE_INVALID", "RESET_GRANT_INVALID" -> HttpStatus.BAD_REQUEST;
            case "RATE_LIMITED" -> HttpStatus.TOO_MANY_REQUESTS;
            case "AUTH_NOT_CONFIGURED" -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.BAD_REQUEST;
        };

        String detail = switch (ex.getCode()) {
            case "RESET_CODE_INVALID" -> "Recovery code is invalid, expired, or exhausted";
            case "RESET_GRANT_INVALID" -> "Password reset grant is invalid or expired";
            case "RATE_LIMITED" -> "Too many requests. Please try again later.";
            case "AUTH_NOT_CONFIGURED" -> "Authentication service is not configured";
            default -> status.getReasonPhrase();
        };

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(ex.getCode());
        problem.setProperty("code", ex.getCode());

        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore());

        if (status == HttpStatus.TOO_MANY_REQUESTS && ex.getRetryAfterSeconds() > 0) {
            builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()));
        }

        return builder.body(problem);
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<ProblemDetail> handleValidationExceptions(Exception ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed");
        problem.setTitle("VALIDATION_FAILED");
        problem.setProperty("code", "VALIDATION_FAILED");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .cacheControl(CacheControl.noStore())
                .body(problem);
    }
}

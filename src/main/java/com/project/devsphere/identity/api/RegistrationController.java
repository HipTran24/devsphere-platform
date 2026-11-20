package com.project.devsphere.identity.api;

import com.project.devsphere.identity.application.RegistrationService.ChallengeReceipt;
import com.project.devsphere.identity.application.RegistrationService.RegistrationReceipt;
import com.project.devsphere.identity.application.RegistrationService;
import com.project.devsphere.identity.application.RegistrationService.VerificationOutcome;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.hibernate.validator.constraints.CodePointLength;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class RegistrationController {

    private final RegistrationService registrationService;

    public RegistrationController(RegistrationService registrationService) {
        this.registrationService = registrationService;
    }

    public record RegisterRequest(
            @NotBlank
            @Email
            @CodePointLength(max = 320)
            String email,

            @NotBlank
            @CodePointLength(min = 12, max = 128)
            String password,

            @NotBlank
            @CodePointLength(max = 120)
            @Pattern(regexp = "^[^\\p{Cc}]*$")
            String displayName
    ) {
        public RegisterRequest {
            email = email == null ? null : email.strip().toLowerCase(Locale.ROOT);
            if (displayName != null && displayName.codePoints().noneMatch(Character::isISOControl)) {
                displayName = displayName.strip();
            }
        }

        @Override
        public String toString() {
            return "RegisterRequest[redacted]";
        }
    }

    public record VerifyEmailRequest(
            @NotNull
            UUID challengeId,

            @NotBlank
            @Pattern(regexp = "^[0-9]{6}$")
            String code
    ) {
        @Override
        public String toString() {
            return "VerifyEmailRequest[redacted]";
        }
    }

    public record ResendVerificationRequest(
            @NotNull
            UUID challengeId
    ) {}

    public record VerifyEmailResponse(
            UUID userId,
            String status
    ) {}

    @PostMapping("/register")
    public ResponseEntity<RegistrationReceipt> register(@Valid @RequestBody RegisterRequest request) {
        RegistrationReceipt receipt = registrationService.register(
                request.email(),
                request.password(),
                request.displayName()
        );
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(receipt);
    }

    @PostMapping("/verify-email")
    public ResponseEntity<?> verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        VerificationOutcome outcome = registrationService.verify(request.challengeId(), request.code());
        if (outcome != null && outcome.accepted()) {
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(new VerifyEmailResponse(outcome.userId(), "ACTIVE"));
        }
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "OTP invalid");
        problem.setTitle("OTP_INVALID");
        problem.setProperty("code", "OTP_INVALID");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .cacheControl(CacheControl.noStore())
                .body(problem);
    }

    @PostMapping("/verification/resend")
    public ResponseEntity<ChallengeReceipt> resend(@Valid @RequestBody ResendVerificationRequest request) {
        ChallengeReceipt receipt = registrationService.resend(request.challengeId());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .cacheControl(CacheControl.noStore())
                .body(receipt);
    }

    @ExceptionHandler(RegistrationService.Rejection.class)
    public ResponseEntity<ProblemDetail> handleRejection(RegistrationService.Rejection ex) {
        String code = ex.code != null ? ex.code : "UNKNOWN_ERROR";
        HttpStatus status = switch (code) {
            case "EMAIL_EXISTS", "EMAIL_ALREADY_EXISTS" -> HttpStatus.CONFLICT;
            case "OTP_INVALID" -> HttpStatus.BAD_REQUEST;
            case "RATE_LIMITED" -> HttpStatus.TOO_MANY_REQUESTS;
            case "AUTH_NOT_CONFIGURED" -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.BAD_REQUEST;
        };

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, status.getReasonPhrase());
        problem.setTitle(code);
        problem.setProperty("code", code);

        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore());

        if (status == HttpStatus.TOO_MANY_REQUESTS && ex.retryAfterSeconds > 0) {
            builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.retryAfterSeconds));
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

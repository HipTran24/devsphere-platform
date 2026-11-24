package com.project.devsphere.ai.api;

import com.project.devsphere.ai.api.dto.AiWorkspaceConfigResponse;
import com.project.devsphere.ai.api.dto.UpdateAiConnectionsRequest;
import com.project.devsphere.ai.api.dto.UpdateAiPrivacyRequest;
import com.project.devsphere.ai.api.dto.UpdateAiRoutesRequest;
import com.project.devsphere.ai.application.AiConnectionConfigService;
import com.project.devsphere.ai.application.AiException;
import com.project.devsphere.identity.application.SessionService;
import jakarta.validation.Valid;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Objects;
import java.util.UUID;

@RestController
@RequestMapping(path = "/api/v1/workspaces/{workspaceId}/ai/config", produces = MediaType.APPLICATION_JSON_VALUE)
public class WorkspaceAiConfigController {

    private final AiConnectionConfigService configService;

    public WorkspaceAiConfigController(AiConnectionConfigService configService) {
        this.configService = Objects.requireNonNull(configService, "configService must not be null");
    }

    @GetMapping
    public ResponseEntity<AiWorkspaceConfigResponse> getConfig(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable UUID workspaceId) {
        AiWorkspaceConfigResponse response = configService.getConfig(principal, workspaceId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    @PutMapping(path = "/connections", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AiWorkspaceConfigResponse> updateConnections(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable UUID workspaceId,
            @Valid @RequestBody UpdateAiConnectionsRequest request) {
        AiWorkspaceConfigResponse response = configService.updateConnections(principal, workspaceId, request);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    @PutMapping(path = "/routes", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AiWorkspaceConfigResponse> updateRoutes(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable UUID workspaceId,
            @Valid @RequestBody UpdateAiRoutesRequest request) {
        AiWorkspaceConfigResponse response = configService.updateRoutes(principal, workspaceId, request);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    @PutMapping(path = "/privacy", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AiWorkspaceConfigResponse> updatePrivacy(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable UUID workspaceId,
            @Valid @RequestBody UpdateAiPrivacyRequest request) {
        AiWorkspaceConfigResponse response = configService.updatePrivacyPolicy(principal, workspaceId, request);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    @ExceptionHandler(AiException.class)
    public ResponseEntity<ProblemDetail> handleAiException(AiException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
        problem.setTitle(ex.getStatus().getReasonPhrase());
        problem.setProperty("code", ex.getCode());
        return ResponseEntity.status(ex.getStatus())
                .cacheControl(CacheControl.noStore())
                .body(problem);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
        problem.setTitle("Forbidden");
        problem.setProperty("code", "ACCESS_DENIED");
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .cacheControl(CacheControl.noStore())
                .body(problem);
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ProblemDetail> handleValidation(Exception ex) {
        String message = "Validation failed";
        if (ex instanceof MethodArgumentNotValidException manv) {
            message = manv.getBindingResult().getFieldErrors().stream()
                    .findFirst()
                    .map(err -> err.getField() + ": " + err.getDefaultMessage())
                    .orElse("Validation failed");
        }
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, message);
        problem.setTitle("Bad Request");
        problem.setProperty("code", "VALIDATION_FAILED");
        return ResponseEntity.badRequest()
                .cacheControl(CacheControl.noStore())
                .body(problem);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleIntegrity(DataIntegrityViolationException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Database constraint violation");
        problem.setTitle("Conflict");
        problem.setProperty("code", "DATA_INTEGRITY_VIOLATION");
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .cacheControl(CacheControl.noStore())
                .body(problem);
    }
}

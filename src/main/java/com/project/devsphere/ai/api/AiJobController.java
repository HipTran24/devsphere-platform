package com.project.devsphere.ai.api;

import com.project.devsphere.ai.api.dto.AiJobResponse;
import com.project.devsphere.ai.api.dto.CompleteAiJobRequest;
import com.project.devsphere.ai.api.dto.CreateAiJobRequest;
import com.project.devsphere.ai.application.AiException;
import com.project.devsphere.ai.application.AiExecutionService;
import com.project.devsphere.ai.application.AiJobService;
import com.project.devsphere.identity.application.SessionService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@RestController
@RequestMapping(path = "/api/v1/workspaces/{workspaceId}/projects/{projectId}/ai/jobs", produces = MediaType.APPLICATION_JSON_VALUE)
public class AiJobController {

    private final AiJobService aiJobService;
    private final ObjectProvider<AiExecutionService> aiExecutionService;

    public AiJobController(AiJobService aiJobService) {
        this(aiJobService, null);
    }

    @Autowired
    public AiJobController(AiJobService aiJobService, ObjectProvider<AiExecutionService> aiExecutionService) {
        this.aiJobService = Objects.requireNonNull(aiJobService, "aiJobService must not be null");
        this.aiExecutionService = aiExecutionService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AiJobResponse> createJob(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @Valid @RequestBody CreateAiJobRequest request) {

        AiJobResponse response = aiJobService.createJob(principal, workspaceId, projectId, request);
        return ResponseEntity
                .created(URI.create("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/ai/jobs/" + response.id()))
                .body(response);
    }

    @GetMapping("/{jobId}")
    public ResponseEntity<AiJobResponse> getJob(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @PathVariable UUID jobId) {

        AiJobResponse response = aiJobService.getJob(principal, workspaceId, projectId, jobId);
        return ResponseEntity.ok(response);
    }

    @PostMapping(path = "/{jobId}/complete", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AiJobResponse> completeJob(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @PathVariable UUID jobId,
            @Valid @RequestBody CompleteAiJobRequest request) {

        AiJobResponse response = aiJobService.completeJob(principal, workspaceId, projectId, jobId, request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{jobId}/execute")
    public ResponseEntity<AiJobResponse> executeJob(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @PathVariable UUID jobId) {

        AiExecutionService executionService = aiExecutionService == null ? null : aiExecutionService.getIfAvailable();
        if (executionService == null) {
            throw AiException.unavailable("AI execution service is unavailable");
        }
        AiJobResponse response = executionService.executeJob(principal, workspaceId, projectId, jobId);
        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<List<AiJobResponse>> listJobs(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId) {

        List<AiJobResponse> response = aiJobService.listJobs(principal, workspaceId, projectId);
        return ResponseEntity.ok(response);
    }

    @ExceptionHandler(AiException.class)
    public ResponseEntity<ProblemDetail> handleAiException(AiException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
        problem.setTitle(ex.getStatus().getReasonPhrase());
        problem.setProperty("code", ex.getCode());
        return ResponseEntity.status(ex.getStatus()).body(problem);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
        problem.setTitle("Forbidden");
        problem.setProperty("code", "ACCESS_DENIED");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .orElse("Validation failed");
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, message);
        problem.setTitle("Bad Request");
        problem.setProperty("code", "VALIDATION_FAILED");
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleIntegrity(DataIntegrityViolationException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Database constraint violation");
        problem.setTitle("Conflict");
        problem.setProperty("code", "DATA_INTEGRITY_VIOLATION");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }
}

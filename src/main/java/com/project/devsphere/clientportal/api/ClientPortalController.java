package com.project.devsphere.clientportal.api;

import com.project.devsphere.clientportal.api.dto.*;
import com.project.devsphere.clientportal.application.ClientPortalException;
import com.project.devsphere.clientportal.application.ClientPortalService;
import com.project.devsphere.identity.application.SessionService;
import jakarta.validation.Valid;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@RestController
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
public class ClientPortalController {

    private final ClientPortalService clientPortalService;

    public ClientPortalController(ClientPortalService clientPortalService) {
        this.clientPortalService = Objects.requireNonNull(clientPortalService, "clientPortalService must not be null");
    }

    @GetMapping("/api/v1/client-portal/workspaces/{workspaceId}/projects/{projectId}/overview")
    public ResponseEntity<ClientProjectOverviewResponse> getOverview(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId) {

        ClientProjectOverviewResponse response = clientPortalService.getClientProjectOverview(principal, workspaceId, projectId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/api/v1/client-portal/workspaces/{workspaceId}/projects/{projectId}/milestones")
    public ResponseEntity<List<PublishedMilestoneResponse>> listMilestones(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId) {

        List<PublishedMilestoneResponse> response = clientPortalService.listPublishedMilestones(principal, workspaceId, projectId);
        return ResponseEntity.ok(response);
    }

    @PostMapping(path = "/api/v1/client-portal/workspaces/{workspaceId}/projects/{projectId}/milestones/{milestoneId}/review",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<MilestoneReviewReceiptResponse> reviewMilestone(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @PathVariable UUID milestoneId,
            @Valid @RequestBody ReviewMilestoneRequest request) {

        MilestoneReviewReceiptResponse response = clientPortalService.reviewMilestone(
                principal, workspaceId, projectId, milestoneId, request.decision(), request.comments()
        );
        return ResponseEntity.ok(response);
    }

    @PatchMapping(path = "/api/v1/workspaces/{workspaceId}/projects/{projectId}/milestones/{milestoneId}/publish",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PublishedMilestoneResponse> publishMilestone(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @PathVariable UUID milestoneId,
            @RequestBody PublishMilestoneRequest request) {

        PublishedMilestoneResponse response = clientPortalService.publishMilestone(
                principal, workspaceId, projectId, milestoneId, request != null && request.publishedToClient()
        );
        return ResponseEntity.ok(response);
    }

    @ExceptionHandler(ClientPortalException.class)
    public ResponseEntity<ProblemDetail> handleClientPortalException(ClientPortalException ex) {
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

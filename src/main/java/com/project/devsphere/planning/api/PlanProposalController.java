package com.project.devsphere.planning.api;

import com.project.devsphere.identity.application.SessionService;
import com.project.devsphere.planning.api.dto.CreatePlanProposalRequest;
import com.project.devsphere.planning.api.dto.PlanProposalResponse;
import com.project.devsphere.planning.api.dto.SolveProposalRequest;
import com.project.devsphere.planning.api.dto.SolverDiagnosticsResponse;
import com.project.devsphere.planning.application.PlanProposalException;
import com.project.devsphere.planning.application.PlanProposalService;
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

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@RestController
@RequestMapping(
        path = "/api/v1/workspaces/{workspaceId}/projects/{projectId}/plan-proposals",
        produces = MediaType.APPLICATION_JSON_VALUE
)
public class PlanProposalController {

    private final PlanProposalService proposalService;

    public PlanProposalController(PlanProposalService proposalService) {
        this.proposalService = Objects.requireNonNull(proposalService, "proposalService must not be null");
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PlanProposalResponse> createDraftProposal(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @Valid @RequestBody CreatePlanProposalRequest request) {

        PlanProposalResponse response = proposalService.createDraftProposal(principal, workspaceId, projectId, request);
        URI location = URI.create(String.format("/api/v1/workspaces/%s/projects/%s/plan-proposals/%s",
                workspaceId, projectId, response.id()));
        return ResponseEntity.created(location).body(response);
    }

    @PostMapping(path = "/{proposalId}/solve", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PlanProposalResponse> solveProposal(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @PathVariable UUID proposalId,
            @Valid @RequestBody SolveProposalRequest request) {

        PlanProposalResponse response = proposalService.solveProposal(principal, workspaceId, projectId, proposalId, request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{proposalId}")
    public ResponseEntity<PlanProposalResponse> getProposal(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @PathVariable UUID proposalId) {

        PlanProposalResponse response = proposalService.getProposal(principal, workspaceId, projectId, proposalId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{proposalId}/diagnostics")
    public ResponseEntity<SolverDiagnosticsResponse> getDiagnostics(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @PathVariable UUID proposalId) {

        SolverDiagnosticsResponse response = proposalService.getDiagnostics(principal, workspaceId, projectId, proposalId);
        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<List<PlanProposalResponse>> listProposals(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId) {

        List<PlanProposalResponse> response = proposalService.listProposals(principal, workspaceId, projectId);
        return ResponseEntity.ok(response);
    }

    @ExceptionHandler(PlanProposalException.class)
    public ResponseEntity<ProblemDetail> handlePlanProposalException(PlanProposalException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
        problem.setTitle(ex.getCode());
        problem.setProperty("code", ex.getCode());
        return ResponseEntity.status(ex.getStatus()).body(problem);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
        problem.setTitle("ACCESS_DENIED");
        problem.setProperty("code", "ACCESS_DENIED");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidationException(MethodArgumentNotValidException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed for request arguments");
        problem.setTitle("VALIDATION_FAILED");
        problem.setProperty("code", "VALIDATION_FAILED");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        String msg = ex.getMessage() != null ? ex.getMessage() : "Data integrity violation";
        HttpStatus status = HttpStatus.CONFLICT;
        String code = "DATA_INTEGRITY_VIOLATION";

        if (msg.contains("Proposal tempKey must be unique") || msg.contains("Proposal requires items array")
                || msg.contains("Dependencies must be an array") || msg.contains("Proposal dependency cycle")
                || msg.contains("Proposal estimate must be nonnegative")) {
            status = HttpStatus.UNPROCESSABLE_ENTITY;
            code = "INVALID_PROPOSAL_PAYLOAD";
        } else if (msg.contains("Proposal starts DRAFT") || msg.contains("Applied proposal is immutable")) {
            status = HttpStatus.CONFLICT;
            code = "PROPOSAL_STATE_INVALID";
        }

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, msg);
        problem.setTitle(code);
        problem.setProperty("code", code);
        return ResponseEntity.status(status).body(problem);
    }
}

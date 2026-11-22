package com.project.devsphere.workspace.api;

import com.project.devsphere.identity.application.SessionService;
import com.project.devsphere.workspace.api.dto.AcceptInvitationRequest;
import com.project.devsphere.workspace.api.dto.CreateInvitationRequest;
import com.project.devsphere.workspace.api.dto.DeclineInvitationRequest;
import com.project.devsphere.workspace.api.dto.InviteMemberRequest;
import com.project.devsphere.workspace.api.dto.TransferOwnershipRequest;
import com.project.devsphere.workspace.api.dto.WorkspaceInvitationResponse;
import com.project.devsphere.workspace.api.dto.WorkspaceMemberDetailsResponse;
import com.project.devsphere.workspace.application.AcceptInvitationCommand;
import com.project.devsphere.workspace.application.CreateInvitationCommand;
import com.project.devsphere.workspace.application.DeclineInvitationCommand;
import com.project.devsphere.workspace.application.InvitationCreationResult;
import com.project.devsphere.workspace.application.TransferOwnershipCommand;
import com.project.devsphere.workspace.application.WorkspaceInvitationSummary;
import com.project.devsphere.workspace.application.WorkspaceMemberDetails;
import com.project.devsphere.workspace.application.WorkspaceMembershipService;
import com.project.devsphere.workspace.application.WorkspaceProjectException;
import jakarta.validation.Valid;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/workspaces")
public class WorkspaceMembershipController {

    private final WorkspaceMembershipService membershipService;

    public WorkspaceMembershipController(WorkspaceMembershipService membershipService) {
        this.membershipService = membershipService;
    }

    @PostMapping("/{workspaceId}/invitations")
    public ResponseEntity<WorkspaceInvitationResponse> createInvitation(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable UUID workspaceId,
            @Valid @RequestBody InviteMemberRequest request) {

        if (principal == null) {
            throw WorkspaceProjectException.accessDenied("Authentication required");
        }

        InvitationCreationResult result = membershipService.createInvitation(
                principal,
                workspaceId,
                new CreateInvitationCommand(request.email())
        );

        WorkspaceInvitationResponse response = new WorkspaceInvitationResponse(
                result.id(),
                result.workspaceId(),
                result.email(),
                principal.userId(),
                result.role(),
                result.status(),
                result.expiresAt(),
                null,
                result.token()
        );

        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    @GetMapping("/{workspaceId}/invitations")
    public ResponseEntity<List<WorkspaceInvitationResponse>> listInvitations(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable UUID workspaceId) {

        if (principal == null) {
            throw WorkspaceProjectException.accessDenied("Authentication required");
        }

        List<WorkspaceInvitationSummary> summaries = membershipService.listInvitations(principal, workspaceId);
        List<WorkspaceInvitationResponse> responses = summaries.stream()
                .map(s -> new WorkspaceInvitationResponse(
                        s.id(),
                        s.workspaceId(),
                        s.email(),
                        s.invitedBy(),
                        s.role(),
                        s.status(),
                        s.expiresAt(),
                        s.acceptedAt(),
                        null
                ))
                .toList();

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(responses);
    }

    @DeleteMapping("/{workspaceId}/invitations/{invitationId}")
    public ResponseEntity<Void> revokeInvitation(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable UUID workspaceId,
            @PathVariable UUID invitationId) {

        if (principal == null) {
            throw WorkspaceProjectException.accessDenied("Authentication required");
        }

        membershipService.revokeInvitation(principal, workspaceId, invitationId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/invitations/accept")
    public ResponseEntity<Map<String, String>> acceptInvitation(
            @AuthenticationPrincipal SessionService.Principal principal,
            @Valid @RequestBody AcceptInvitationRequest request) {

        if (principal == null) {
            throw WorkspaceProjectException.accessDenied("Authentication required");
        }

        membershipService.acceptInvitation(principal, new AcceptInvitationCommand(request.token()));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(Map.of("status", "ACCEPTED"));
    }

    @PostMapping("/invitations/decline")
    public ResponseEntity<Map<String, String>> declineInvitation(
            @AuthenticationPrincipal SessionService.Principal principal,
            @Valid @RequestBody DeclineInvitationRequest request) {

        if (principal == null) {
            throw WorkspaceProjectException.accessDenied("Authentication required");
        }

        membershipService.declineInvitation(principal, new DeclineInvitationCommand(request.token()));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(Map.of("status", "DECLINED"));
    }

    @GetMapping("/{workspaceId}/members")
    public ResponseEntity<List<WorkspaceMemberDetailsResponse>> listMembers(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable UUID workspaceId) {

        if (principal == null) {
            throw WorkspaceProjectException.accessDenied("Authentication required");
        }

        List<WorkspaceMemberDetails> members = membershipService.listMembers(principal, workspaceId);
        List<WorkspaceMemberDetailsResponse> responses = members.stream()
                .map(m -> new WorkspaceMemberDetailsResponse(
                        m.userId(),
                        workspaceId,
                        m.userId(),
                        m.email(),
                        m.displayName(),
                        m.role(),
                        m.status(),
                        m.joinedAt()
                ))
                .toList();

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(responses);
    }

    @DeleteMapping("/{workspaceId}/members/{userId}")
    public ResponseEntity<Void> removeMember(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable UUID workspaceId,
            @PathVariable UUID userId) {

        if (principal == null) {
            throw WorkspaceProjectException.accessDenied("Authentication required");
        }

        membershipService.removeMember(principal, workspaceId, userId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{workspaceId}/transfer-ownership")
    public ResponseEntity<Map<String, String>> transferOwnership(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable UUID workspaceId,
            @Valid @RequestBody TransferOwnershipRequest request) {

        if (principal == null) {
            throw WorkspaceProjectException.accessDenied("Authentication required");
        }

        membershipService.transferOwnership(
                principal,
                workspaceId,
                new TransferOwnershipCommand(request.targetUserId(), request.expectedVersion(), request.reason())
        );

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(Map.of("status", "TRANSFERRED"));
    }

    @ExceptionHandler(WorkspaceProjectException.class)
    public ResponseEntity<ProblemDetail> handleWorkspaceProjectException(WorkspaceProjectException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
        problem.setTitle(ex.getCode());
        problem.setProperty("code", ex.getCode());
        return ResponseEntity.status(ex.getStatus())
                .cacheControl(CacheControl.noStore())
                .body(problem);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Duplicate resource or unique constraint violated");
        problem.setTitle("DUPLICATE_RESOURCE");
        problem.setProperty("code", "DUPLICATE_RESOURCE");
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .cacheControl(CacheControl.noStore())
                .body(problem);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
        problem.setTitle("ACCESS_DENIED");
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
    public ResponseEntity<ProblemDetail> handleValidationExceptions(Exception ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed");
        problem.setTitle("VALIDATION_FAILED");
        problem.setProperty("code", "VALIDATION_FAILED");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .cacheControl(CacheControl.noStore())
                .body(problem);
    }
}

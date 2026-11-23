package com.project.devsphere.project.api;

import com.project.devsphere.identity.application.SessionService;
import com.project.devsphere.identity.domain.ProjectRole;
import com.project.devsphere.project.api.dto.AcceptPmRequest;
import com.project.devsphere.project.api.dto.GrantRoleRequest;
import com.project.devsphere.project.api.dto.PmAssignmentResponse;
import com.project.devsphere.project.api.dto.ProjectMemberDetailsResponse;
import com.project.devsphere.project.api.dto.ProjectMemberRoleResponse;
import com.project.devsphere.project.api.dto.ProposePmRequest;
import com.project.devsphere.project.application.AcceptPmCommand;
import com.project.devsphere.project.application.GrantRoleCommand;
import com.project.devsphere.project.application.PmAssignmentDetails;
import com.project.devsphere.project.application.PmTransferService;
import com.project.devsphere.project.application.ProjectMemberDetails;
import com.project.devsphere.project.application.ProjectRoleService;
import com.project.devsphere.project.application.ProposePmCommand;
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
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/projects/{projectId}")
public class ProjectRoleController {

    private final ProjectRoleService projectRoleService;
    private final PmTransferService pmTransferService;

    public ProjectRoleController(ProjectRoleService projectRoleService, PmTransferService pmTransferService) {
        this.projectRoleService = projectRoleService;
        this.pmTransferService = pmTransferService;
    }

    @GetMapping("/members")
    public ResponseEntity<List<ProjectMemberDetailsResponse>> listMembers(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId) {

        if (principal == null) {
            throw WorkspaceProjectException.accessDenied("Authentication required");
        }

        List<ProjectMemberDetails> members = projectRoleService.listProjectMembers(principal, workspaceId, projectId);
        List<ProjectMemberDetailsResponse> responses = members.stream()
                .map(m -> new ProjectMemberDetailsResponse(
                        m.userId(),
                        projectId,
                        workspaceId,
                        m.userId(),
                        m.status(),
                        m.joinedAt(),
                        m.roles().stream().map(ProjectRole::name).toList()
                ))
                .toList();

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(responses);
    }

    @PostMapping("/members")
    public ResponseEntity<Map<String, Object>> grantRole(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @Valid @RequestBody GrantRoleRequest request) {

        if (principal == null) {
            throw WorkspaceProjectException.accessDenied("Authentication required");
        }

        ProjectRole role;
        try {
            role = ProjectRole.valueOf(request.role().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw WorkspaceProjectException.validationFailed("Invalid role: " + request.role());
        }

        if (role == ProjectRole.PM) {
            throw WorkspaceProjectException.pmRoleRestricted("PM role cannot be granted directly via member role API");
        }

        projectRoleService.grantProjectRole(
                principal,
                workspaceId,
                projectId,
                new GrantRoleCommand(request.userId(), role)
        );

        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(Map.of(
                        "status", "GRANTED",
                        "userId", request.userId(),
                        "role", role.name()
                ));
    }

    @DeleteMapping("/members/{userId}/roles/{role}")
    public ResponseEntity<Void> revokeRole(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @PathVariable UUID userId,
            @PathVariable String role) {

        if (principal == null) {
            throw WorkspaceProjectException.accessDenied("Authentication required");
        }

        ProjectRole projectRole;
        try {
            projectRole = ProjectRole.valueOf(role.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw WorkspaceProjectException.validationFailed("Invalid role: " + role);
        }

        if (projectRole == ProjectRole.PM) {
            throw WorkspaceProjectException.pmRoleRestricted("Cannot revoke PM role via member role API");
        }

        projectRoleService.revokeProjectRole(principal, workspaceId, projectId, userId, projectRole);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/members/{userId}")
    public ResponseEntity<Void> removeMember(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @PathVariable UUID userId) {

        if (principal == null) {
            throw WorkspaceProjectException.accessDenied("Authentication required");
        }

        projectRoleService.removeProjectMember(principal, workspaceId, projectId, userId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/pm-assignments")
    public ResponseEntity<PmAssignmentResponse> proposePmAssignment(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @Valid @RequestBody ProposePmRequest request) {

        if (principal == null) {
            throw WorkspaceProjectException.accessDenied("Authentication required");
        }

        PmAssignmentDetails details = pmTransferService.proposePmAssignment(
                principal,
                workspaceId,
                projectId,
                new ProposePmCommand(request.candidateUserId(), request.expectedVersion())
        );

        PmAssignmentResponse response = new PmAssignmentResponse(
                details.id(),
                projectId,
                details.candidateUserId(),
                details.proposedBy(),
                details.proposedAt(),
                details.expiresAt(),
                details.status()
        );

        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    @GetMapping("/pm-assignments/current")
    public ResponseEntity<PmAssignmentResponse> getCurrentPmAssignment(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId) {

        if (principal == null) {
            throw WorkspaceProjectException.accessDenied("Authentication required");
        }

        Optional<PmAssignmentDetails> detailsOpt = pmTransferService.getCurrentPmAssignment(principal, workspaceId, projectId);
        if (detailsOpt.isEmpty()) {
            return ResponseEntity.noContent().build();
        }

        PmAssignmentDetails details = detailsOpt.get();
        PmAssignmentResponse response = new PmAssignmentResponse(
                details.id(),
                projectId,
                details.candidateUserId(),
                details.proposedBy(),
                details.proposedAt(),
                details.expiresAt(),
                details.status()
        );

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    @PostMapping("/pm-assignments/{assignmentId}/accept")
    public ResponseEntity<Map<String, String>> acceptPmAssignment(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @PathVariable UUID assignmentId,
            @Valid @RequestBody AcceptPmRequest request) {

        if (principal == null) {
            throw WorkspaceProjectException.accessDenied("Authentication required");
        }

        pmTransferService.acceptPmAssignment(
                principal,
                workspaceId,
                projectId,
                assignmentId,
                new AcceptPmCommand(request.expectedVersion())
        );

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(Map.of("status", "ACCEPTED"));
    }

    @PostMapping("/pm-assignments/{assignmentId}/decline")
    public ResponseEntity<Map<String, String>> declinePmAssignment(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @PathVariable UUID assignmentId) {

        if (principal == null) {
            throw WorkspaceProjectException.accessDenied("Authentication required");
        }

        pmTransferService.declinePmAssignment(principal, workspaceId, projectId, assignmentId);

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(Map.of("status", "DECLINED"));
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

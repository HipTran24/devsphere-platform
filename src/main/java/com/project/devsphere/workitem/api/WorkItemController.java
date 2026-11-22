package com.project.devsphere.workitem.api;

import com.project.devsphere.identity.application.SessionService;
import com.project.devsphere.workitem.api.dto.AssignWorkItemRequest;
import com.project.devsphere.workitem.api.dto.BlockWorkItemRequest;
import com.project.devsphere.workitem.api.dto.CreateWorkItemRequest;
import com.project.devsphere.workitem.api.dto.RejectWorkItemRequest;
import com.project.devsphere.workitem.api.dto.TransitionStatusRequest;
import com.project.devsphere.workitem.api.dto.UpdateWorkItemRequest;
import com.project.devsphere.workitem.api.dto.WorkItemResponse;
import com.project.devsphere.workitem.application.WorkItemService;
import com.project.devsphere.workitem.domain.WorkItem;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/projects/{projectId}")
public class WorkItemController {

    private final WorkItemService workItemService;

    public WorkItemController(WorkItemService workItemService) {
        this.workItemService = workItemService;
    }

    @PostMapping("/work-items")
    public ResponseEntity<WorkItemResponse> createWorkItem(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @Valid @RequestBody CreateWorkItemRequest request) {

        if (principal == null) {
            throw WorkspaceProjectException.accessDenied("Authentication required");
        }

        WorkItemResponse response = workItemService.createWorkItem(principal, workspaceId, projectId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    @GetMapping("/work-items")
    public ResponseEntity<List<WorkItemResponse>> listWorkItems(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @RequestParam(required = false) WorkItem.Type type,
            @RequestParam(required = false) WorkItem.Status status,
            @RequestParam(required = false) UUID assigneeId,
            @RequestParam(required = false) UUID parentId,
            @RequestParam(required = false) UUID requirementId) {

        if (principal == null) {
            throw WorkspaceProjectException.accessDenied("Authentication required");
        }

        List<WorkItemResponse> response = workItemService.listWorkItems(
                principal, workspaceId, projectId, type, status, assigneeId, parentId, requirementId
        );
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    @GetMapping("/work-items/{workItemId}")
    public ResponseEntity<WorkItemResponse> getWorkItem(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @PathVariable UUID workItemId) {

        if (principal == null) {
            throw WorkspaceProjectException.accessDenied("Authentication required");
        }

        WorkItemResponse response = workItemService.getWorkItem(principal, workspaceId, projectId, workItemId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    @PatchMapping("/work-items/{workItemId}")
    public ResponseEntity<WorkItemResponse> updateWorkItem(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @PathVariable UUID workItemId,
            @Valid @RequestBody UpdateWorkItemRequest request) {

        if (principal == null) {
            throw WorkspaceProjectException.accessDenied("Authentication required");
        }

        WorkItemResponse response = workItemService.updateWorkItem(principal, workspaceId, projectId, workItemId, request);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    @DeleteMapping("/work-items/{workItemId}")
    public ResponseEntity<Void> deleteWorkItem(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @PathVariable UUID workItemId) {

        if (principal == null) {
            throw WorkspaceProjectException.accessDenied("Authentication required");
        }

        workItemService.deleteWorkItem(principal, workspaceId, projectId, workItemId);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/work-items/{workItemId}/assign")
    public ResponseEntity<WorkItemResponse> assignWorkItem(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @PathVariable UUID workItemId,
            @Valid @RequestBody AssignWorkItemRequest request) {

        if (principal == null) {
            throw WorkspaceProjectException.accessDenied("Authentication required");
        }

        WorkItemResponse response = workItemService.assignWorkItem(principal, workspaceId, projectId, workItemId, request);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    @PatchMapping("/work-items/{workItemId}/status")
    public ResponseEntity<WorkItemResponse> transitionStatus(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @PathVariable UUID workItemId,
            @Valid @RequestBody TransitionStatusRequest request) {

        if (principal == null) {
            throw WorkspaceProjectException.accessDenied("Authentication required");
        }

        WorkItemResponse response = workItemService.transitionStatus(principal, workspaceId, projectId, workItemId, request);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    @PostMapping("/work-items/{workItemId}/accept")
    public ResponseEntity<WorkItemResponse> acceptWorkItem(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @PathVariable UUID workItemId) {

        if (principal == null) {
            throw WorkspaceProjectException.accessDenied("Authentication required");
        }

        WorkItemResponse response = workItemService.acceptWorkItem(principal, workspaceId, projectId, workItemId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    @PostMapping("/work-items/{workItemId}/reject")
    public ResponseEntity<WorkItemResponse> rejectWorkItem(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @PathVariable UUID workItemId,
            @Valid @RequestBody(required = false) RejectWorkItemRequest request) {

        if (principal == null) {
            throw WorkspaceProjectException.accessDenied("Authentication required");
        }

        WorkItemResponse response = workItemService.rejectWorkItem(
                principal,
                workspaceId,
                projectId,
                workItemId,
                request != null ? request : new RejectWorkItemRequest(null, null)
        );
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    @PatchMapping("/work-items/{workItemId}/blocked")
    public ResponseEntity<WorkItemResponse> blockWorkItem(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @PathVariable UUID workItemId,
            @Valid @RequestBody BlockWorkItemRequest request) {

        if (principal == null) {
            throw WorkspaceProjectException.accessDenied("Authentication required");
        }

        WorkItemResponse response = workItemService.blockWorkItem(principal, workspaceId, projectId, workItemId, request);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    @GetMapping("/backlog")
    public ResponseEntity<List<WorkItemResponse>> getBacklog(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId) {

        if (principal == null) {
            throw WorkspaceProjectException.accessDenied("Authentication required");
        }

        List<WorkItemResponse> response = workItemService.getBacklog(principal, workspaceId, projectId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
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

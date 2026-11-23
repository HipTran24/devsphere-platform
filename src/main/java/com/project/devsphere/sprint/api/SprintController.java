package com.project.devsphere.sprint.api;

import com.project.devsphere.identity.application.SessionService;
import com.project.devsphere.sprint.api.dto.CloseSprintRequest;
import com.project.devsphere.sprint.api.dto.CreateSprintRequest;
import com.project.devsphere.sprint.api.dto.SprintItemsRequest;
import com.project.devsphere.sprint.api.dto.SprintProgressResponse;
import com.project.devsphere.sprint.api.dto.SprintResponse;
import com.project.devsphere.sprint.api.dto.StartSprintRequest;
import com.project.devsphere.sprint.api.dto.UpdateSprintRequest;
import com.project.devsphere.sprint.application.SprintProgressService;
import com.project.devsphere.sprint.application.SprintService;
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

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/projects/{projectId}/sprints")
public class SprintController {

    private final SprintService sprintService;
    private final SprintProgressService sprintProgressService;

    public SprintController(SprintService sprintService, SprintProgressService sprintProgressService) {
        this.sprintService = Objects.requireNonNull(sprintService, "sprintService must not be null");
        this.sprintProgressService = Objects.requireNonNull(sprintProgressService, "sprintProgressService must not be null");
    }

    @PostMapping
    public ResponseEntity<SprintResponse> createSprint(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @Valid @RequestBody CreateSprintRequest request) {

        if (principal == null) {
            throw WorkspaceProjectException.accessDenied("Authentication required");
        }

        SprintResponse response = sprintService.createSprint(principal, workspaceId, projectId, request);
        URI location = URI.create("/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/sprints/" + response.id());
        return ResponseEntity.created(location)
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    @GetMapping
    public ResponseEntity<List<SprintResponse>> listSprints(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @RequestParam(required = false) String status) {

        if (principal == null) {
            throw WorkspaceProjectException.accessDenied("Authentication required");
        }

        List<SprintResponse> responses = sprintService.listSprints(principal, workspaceId, projectId, status);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(responses);
    }

    @GetMapping("/{sprintId}")
    public ResponseEntity<SprintResponse> getSprint(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @PathVariable UUID sprintId) {

        if (principal == null) {
            throw WorkspaceProjectException.accessDenied("Authentication required");
        }

        SprintResponse response = sprintService.getSprint(principal, workspaceId, projectId, sprintId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    @PatchMapping("/{sprintId}")
    public ResponseEntity<SprintResponse> updateSprint(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @PathVariable UUID sprintId,
            @Valid @RequestBody UpdateSprintRequest request) {

        if (principal == null) {
            throw WorkspaceProjectException.accessDenied("Authentication required");
        }

        SprintResponse response = sprintService.updateSprint(principal, workspaceId, projectId, sprintId, request);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    @PostMapping("/{sprintId}/start")
    public ResponseEntity<SprintResponse> startSprint(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @PathVariable UUID sprintId,
            @Valid @RequestBody(required = false) StartSprintRequest request) {

        if (principal == null) {
            throw WorkspaceProjectException.accessDenied("Authentication required");
        }

        SprintResponse response = sprintService.startSprint(principal, workspaceId, projectId, sprintId, request);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    @PostMapping("/{sprintId}/close")
    public ResponseEntity<SprintResponse> closeSprint(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @PathVariable UUID sprintId,
            @Valid @RequestBody(required = false) CloseSprintRequest request) {

        if (principal == null) {
            throw WorkspaceProjectException.accessDenied("Authentication required");
        }

        SprintResponse response = sprintService.closeSprint(principal, workspaceId, projectId, sprintId, request);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    @PostMapping("/{sprintId}/items")
    public ResponseEntity<Map<String, Object>> addSprintItems(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @PathVariable UUID sprintId,
            @Valid @RequestBody SprintItemsRequest request) {

        if (principal == null) {
            throw WorkspaceProjectException.accessDenied("Authentication required");
        }

        List<UUID> itemIds = request != null ? request.resolvedItemIds() : List.of();
        String action = request != null && request.action() != null ? request.action() : "ADD";
        sprintService.modifySprintItems(principal, workspaceId, projectId, sprintId, action, itemIds);

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(Map.of("sprintId", sprintId, "itemCount", itemIds.size(), "action", action));
    }

    @DeleteMapping("/{sprintId}/items")
    public ResponseEntity<Map<String, Object>> removeSprintItems(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @PathVariable UUID sprintId,
            @Valid @RequestBody SprintItemsRequest request) {

        if (principal == null) {
            throw WorkspaceProjectException.accessDenied("Authentication required");
        }

        List<UUID> itemIds = request != null ? request.resolvedItemIds() : List.of();
        sprintService.modifySprintItems(principal, workspaceId, projectId, sprintId, "REMOVE", itemIds);

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(Map.of("sprintId", sprintId, "itemCount", itemIds.size(), "action", "REMOVE"));
    }

    @GetMapping("/{sprintId}/progress")
    public ResponseEntity<SprintProgressResponse> getSprintProgress(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @PathVariable UUID sprintId) {

        if (principal == null) {
            throw WorkspaceProjectException.accessDenied("Authentication required");
        }

        SprintProgressResponse response = sprintProgressService.getSprintProgress(principal, workspaceId, projectId, sprintId);
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

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidationException(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Validation failed");

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setTitle("VALIDATION_FAILED");
        problem.setProperty("code", "VALIDATION_FAILED");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .cacheControl(CacheControl.noStore())
                .body(problem);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleNotReadableException(HttpMessageNotReadableException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Malformed JSON request body");
        problem.setTitle("MALFORMED_JSON");
        problem.setProperty("code", "MALFORMED_JSON");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .cacheControl(CacheControl.noStore())
                .body(problem);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> handleTypeMismatchException(MethodArgumentTypeMismatchException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Parameter '" + ex.getName() + "' is invalid");
        problem.setTitle("INVALID_TYPE");
        problem.setProperty("code", "INVALID_TYPE");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .cacheControl(CacheControl.noStore())
                .body(problem);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDeniedException(AccessDeniedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
        problem.setTitle("ACCESS_DENIED");
        problem.setProperty("code", "ACCESS_DENIED");
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .cacheControl(CacheControl.noStore())
                .body(problem);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        String msg = ex.getMessage() != null ? ex.getMessage() : "";
        HttpStatus status = HttpStatus.CONFLICT;
        String code = "DATA_INTEGRITY_VIOLATION";

        if (msg.contains("uq_project_active_sprint") || msg.contains("ACTIVE")) {
            code = "ACTIVE_SPRINT_EXISTS";
            msg = "Active sprint already exists in project";
        } else if (msg.contains("Closed Sprint metrics and dates are immutable")) {
            code = "SPRINT_CLOSED";
            msg = "Closed Sprint metrics and dates are immutable";
        } else if (msg.contains("DONE Work Item scope and schedule are frozen") || msg.contains("DONE scope is immutable")) {
            code = "SCOPE_LOCKED";
            msg = "DONE Work Item scope and schedule are frozen";
        }

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, msg);
        problem.setTitle(code);
        problem.setProperty("code", code);
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .body(problem);
    }
}

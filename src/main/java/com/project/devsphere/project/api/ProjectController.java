package com.project.devsphere.project.api;

import com.project.devsphere.identity.application.SessionService;
import com.project.devsphere.project.api.dto.ActivateProjectRequest;
import com.project.devsphere.project.api.dto.CreateProjectRequest;
import com.project.devsphere.project.api.dto.ProjectResponse;
import com.project.devsphere.project.api.dto.UpdateProjectRequest;
import com.project.devsphere.project.application.ActivateProjectCommand;
import com.project.devsphere.project.application.CreateProjectCommand;
import com.project.devsphere.project.application.ProjectService;
import com.project.devsphere.project.application.UpdateProjectCommand;
import com.project.devsphere.project.domain.Project;
import com.project.devsphere.workspace.application.WorkspaceProjectException;
import jakarta.validation.Valid;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/projects")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @PostMapping
    public ResponseEntity<ProjectResponse> createProject(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable UUID workspaceId,
            @Valid @RequestBody CreateProjectRequest request) {

        if (principal == null) {
            throw WorkspaceProjectException.accessDenied("Authentication required");
        }

        CreateProjectCommand command = new CreateProjectCommand(
                request.projectKey(),
                request.name(),
                request.description(),
                request.objective(),
                request.timezone(),
                request.plannedStart(),
                request.committedDeadline(),
                request.sprintLengthDays(),
                request.selfAssignPm()
        );

        Project project = projectService.createProject(principal, workspaceId, command);
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(toResponse(project));
    }

    @GetMapping
    public ResponseEntity<List<ProjectResponse>> listProjects(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable UUID workspaceId) {

        if (principal == null) {
            throw WorkspaceProjectException.accessDenied("Authentication required");
        }

        List<Project> projects = projectService.listProjects(principal, workspaceId);
        List<ProjectResponse> responses = projects.stream()
                .map(this::toResponse)
                .toList();

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(responses);
    }

    @GetMapping("/{projectId}")
    public ResponseEntity<ProjectResponse> getProject(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId) {

        if (principal == null) {
            throw WorkspaceProjectException.accessDenied("Authentication required");
        }

        Project project = projectService.getProject(principal, workspaceId, projectId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(toResponse(project));
    }

    @PatchMapping("/{projectId}")
    public ResponseEntity<ProjectResponse> updateProject(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @Valid @RequestBody UpdateProjectRequest request) {

        if (principal == null) {
            throw WorkspaceProjectException.accessDenied("Authentication required");
        }

        UpdateProjectCommand command = new UpdateProjectCommand(
                request.name(),
                request.description(),
                request.objective(),
                request.timezone(),
                request.plannedStart(),
                request.committedDeadline(),
                request.sprintLengthDays(),
                request.deadlineApproverId(),
                request.expectedVersion()
        );

        Project project = projectService.updateProject(principal, workspaceId, projectId, command);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(toResponse(project));
    }

    @PostMapping("/{projectId}/activate")
    public ResponseEntity<ProjectResponse> activateProject(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @Valid @RequestBody(required = false) ActivateProjectRequest request) {

        if (principal == null) {
            throw WorkspaceProjectException.accessDenied("Authentication required");
        }

        Long expectedVersion = request != null ? request.expectedVersion() : null;
        ActivateProjectCommand command = new ActivateProjectCommand(expectedVersion);

        Project project = projectService.activateProject(principal, workspaceId, projectId, command);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(toResponse(project));
    }

    private ProjectResponse toResponse(Project p) {
        return projectService.toResponse(p);
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
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "A project already exists in this workspace or constraint violation");
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

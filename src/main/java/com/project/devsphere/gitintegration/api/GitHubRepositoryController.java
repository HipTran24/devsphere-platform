package com.project.devsphere.gitintegration.api;

import com.project.devsphere.gitintegration.api.dto.ConnectRepositoryRequest;
import com.project.devsphere.gitintegration.api.dto.GitHubActivityResponse;
import com.project.devsphere.gitintegration.api.dto.GitHubRepositoryResponse;
import com.project.devsphere.gitintegration.api.dto.GitHubSyncSummaryResponse;
import com.project.devsphere.gitintegration.application.GitHubActivitySyncService;
import com.project.devsphere.gitintegration.application.GitHubException;
import com.project.devsphere.gitintegration.application.GitHubRemoteSyncService;
import com.project.devsphere.gitintegration.application.GitHubRepositoryService;
import com.project.devsphere.gitintegration.domain.GitHubRepository;
import com.project.devsphere.gitintegration.infrastructure.GitHubApiUnavailableException;
import com.project.devsphere.identity.application.SessionService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/projects/{projectId}/github")
public class GitHubRepositoryController {

    private final GitHubRepositoryService repositoryService;
    private final GitHubActivitySyncService activitySyncService;
    private final ObjectProvider<GitHubRemoteSyncService> remoteSyncServiceProvider;

    @Autowired
    public GitHubRepositoryController(
            GitHubRepositoryService repositoryService,
            GitHubActivitySyncService activitySyncService,
            ObjectProvider<GitHubRemoteSyncService> remoteSyncServiceProvider) {
        this.repositoryService = Objects.requireNonNull(repositoryService, "repositoryService must not be null");
        this.activitySyncService = Objects.requireNonNull(activitySyncService, "activitySyncService must not be null");
        this.remoteSyncServiceProvider = remoteSyncServiceProvider;
    }

    public GitHubRepositoryController(
            GitHubRepositoryService repositoryService,
            GitHubActivitySyncService activitySyncService) {
        this(repositoryService, activitySyncService, null);
    }

    /**
     * Connects a GitHub repository to a project. Guarded by PM role.
     */
    @PostMapping("/repositories")
    public ResponseEntity<GitHubRepositoryResponse> connectRepository(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable("workspaceId") UUID workspaceId,
            @PathVariable("projectId") UUID projectId,
            @Valid @RequestBody ConnectRepositoryRequest request) {

        GitHubRepository repository = repositoryService.connectRepository(
                principal,
                workspaceId,
                projectId,
                request.toCommand()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(GitHubRepositoryResponse.from(repository));
    }

    /**
     * Lists repositories connected to a project. Guarded by project membership.
     */
    @GetMapping("/repositories")
    public ResponseEntity<List<GitHubRepositoryResponse>> listRepositories(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable("workspaceId") UUID workspaceId,
            @PathVariable("projectId") UUID projectId) {

        List<GitHubRepository> repositories = repositoryService.listRepositories(principal, workspaceId, projectId);
        List<GitHubRepositoryResponse> response = repositories.stream()
                .map(GitHubRepositoryResponse::from)
                .toList();
        return ResponseEntity.ok(response);
    }

    /**
     * Disconnects a GitHub repository (marks active = false). Guarded by PM role.
     */
    @PostMapping("/repositories/{repositoryId}/disconnect")
    public ResponseEntity<GitHubRepositoryResponse> disconnectRepository(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable("workspaceId") UUID workspaceId,
            @PathVariable("projectId") UUID projectId,
            @PathVariable("repositoryId") UUID repositoryId) {

        GitHubRepository repository = repositoryService.disconnectRepository(
                principal,
                workspaceId,
                projectId,
                repositoryId
        );
        return ResponseEntity.ok(GitHubRepositoryResponse.from(repository));
    }

    /**
     * Delete alias to disconnect repository. Guarded by PM role.
     */
    @DeleteMapping("/repositories/{repositoryId}")
    public ResponseEntity<Void> deleteRepository(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable("workspaceId") UUID workspaceId,
            @PathVariable("projectId") UUID projectId,
            @PathVariable("repositoryId") UUID repositoryId) {

        repositoryService.disconnectRepository(principal, workspaceId, projectId, repositoryId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Triggers a live GitHub App REST API sync for a connected repository. Guarded by PM role.
     */
    @PostMapping("/repositories/{repositoryId}/sync")
    public ResponseEntity<GitHubSyncSummaryResponse> syncRepository(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable("workspaceId") UUID workspaceId,
            @PathVariable("projectId") UUID projectId,
            @PathVariable("repositoryId") UUID repositoryId) {

        GitHubRemoteSyncService remoteSyncService = remoteSyncServiceProvider != null
                ? remoteSyncServiceProvider.getIfAvailable()
                : null;
        if (remoteSyncService == null) {
            throw new GitHubApiUnavailableException("GitHub remote sync service is unavailable");
        }
        GitHubSyncSummaryResponse summary = remoteSyncService.syncRepository(
                principal, workspaceId, projectId, repositoryId);
        return ResponseEntity.ok(summary);
    }

    /**
     * Lists git activities for a project ordered by happened_at DESC.
     * Guarded by project membership.
     */
    @GetMapping("/activities")
    public ResponseEntity<List<GitHubActivityResponse>> listActivities(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable("workspaceId") UUID workspaceId,
            @PathVariable("projectId") UUID projectId,
            @RequestParam(value = "workItemId", required = false) UUID workItemId,
            @RequestParam(value = "kind", required = false) String kind) {

        List<GitHubActivityResponse> activities = activitySyncService.listActivities(
                principal,
                workspaceId,
                projectId,
                workItemId,
                kind
        );
        return ResponseEntity.ok(activities);
    }

    @ExceptionHandler(GitHubApiUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleGitHubApiUnavailable(GitHubApiUnavailableException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
        problem.setTitle(GitHubApiUnavailableException.ERROR_CODE);
        problem.setProperty("code", GitHubApiUnavailableException.ERROR_CODE);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .cacheControl(CacheControl.noStore());
        if (ex.getRetryAfterSeconds() != null) {
            builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()));
        }
        return builder.body(problem);
    }

    @ExceptionHandler(GitHubException.class)
    public ResponseEntity<ProblemDetail> handleGitHubException(GitHubException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
        problem.setTitle(ex.getCode());
        problem.setProperty("code", ex.getCode());
        return ResponseEntity.status(ex.getStatus())
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
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed: " + ex.getMessage());
        problem.setTitle("VALIDATION_FAILED");
        problem.setProperty("code", "VALIDATION_FAILED");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .cacheControl(CacheControl.noStore())
                .body(problem);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Data integrity conflict: " + ex.getMessage());
        problem.setTitle("DUPLICATE_RESOURCE");
        problem.setProperty("code", "DUPLICATE_RESOURCE");
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .cacheControl(CacheControl.noStore())
                .body(problem);
    }
}

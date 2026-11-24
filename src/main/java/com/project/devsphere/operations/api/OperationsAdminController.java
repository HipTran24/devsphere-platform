package com.project.devsphere.operations.api;

import com.project.devsphere.administration.api.dto.AdminPageResponse;
import com.project.devsphere.identity.application.AccessPolicy;
import com.project.devsphere.identity.application.SessionService;
import com.project.devsphere.operations.api.dto.BackgroundJobResponse;
import com.project.devsphere.operations.api.dto.DispatchResultResponse;
import com.project.devsphere.operations.api.dto.JobQueueStatsResponse;
import com.project.devsphere.operations.api.dto.OperationsHealthResponse;
import com.project.devsphere.operations.api.dto.QuartzTriggerResponse;
import com.project.devsphere.operations.application.DemoSeedDataService;
import com.project.devsphere.operations.application.DemoSeedResultResponse;
import com.project.devsphere.operations.application.OperationsAdminService;
import com.project.devsphere.operations.application.OperationsException;
import com.project.devsphere.operations.domain.BackgroundJob;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * System-administrator operations console: queue health, job inspection/retry, manual dispatch and
 * Quartz trigger management. Guarded by {@code ROLE_ADMIN} in the security chain and re-checked
 * against the live account through {@link AccessPolicy#requireSystemAdmin}. Every response, including
 * RFC 7807 problems, is {@code Cache-Control: no-store}.
 */
@RestController
@RequestMapping("/api/v1/admin/operations")
public class OperationsAdminController {

    private final OperationsAdminService operationsAdminService;
    private final AccessPolicy accessPolicy;
    private final DemoSeedDataService demoSeedDataService;
    private final boolean demoSeedEnabled;
    private final Environment environment;

    public OperationsAdminController(OperationsAdminService operationsAdminService, AccessPolicy accessPolicy) {
        this(operationsAdminService, accessPolicy, null, false, null);
    }

    @Autowired
    public OperationsAdminController(
            OperationsAdminService operationsAdminService,
            AccessPolicy accessPolicy,
            ObjectProvider<DemoSeedDataService> demoSeedDataServiceProvider,
            @Value("${devsphere.seed.demo.enabled:false}") boolean demoSeedEnabled,
            Environment environment) {
        this.operationsAdminService = operationsAdminService;
        this.accessPolicy = accessPolicy;
        this.demoSeedDataService = demoSeedDataServiceProvider != null ? demoSeedDataServiceProvider.getIfAvailable() : null;
        this.demoSeedEnabled = demoSeedEnabled;
        this.environment = environment;
    }

    @GetMapping("/health")
    public ResponseEntity<OperationsHealthResponse> health(@AuthenticationPrincipal SessionService.Principal principal) {
        accessPolicy.requireSystemAdmin(principal);
        return noStore(HttpStatus.OK, operationsAdminService.health());
    }

    @GetMapping("/jobs")
    public ResponseEntity<AdminPageResponse<BackgroundJobResponse>> jobs(
            @AuthenticationPrincipal SessionService.Principal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) BackgroundJob.JobKind jobKind,
            @RequestParam(required = false) BackgroundJob.Status status,
            @RequestParam(required = false) UUID workspaceId,
            @RequestParam(required = false) UUID projectId) {
        accessPolicy.requireSystemAdmin(principal);
        return noStore(HttpStatus.OK, operationsAdminService.listJobs(page, size, jobKind, status, workspaceId, projectId));
    }

    @PostMapping("/jobs/{jobId}/retry")
    public ResponseEntity<BackgroundJobResponse> retry(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable UUID jobId) {
        accessPolicy.requireSystemAdmin(principal);
        return noStore(HttpStatus.OK, operationsAdminService.retryJob(jobId, principal));
    }

    @PostMapping("/jobs/dispatch")
    public ResponseEntity<DispatchResultResponse> dispatch(
            @AuthenticationPrincipal SessionService.Principal principal,
            @RequestParam(defaultValue = "50") int maxJobs,
            @RequestParam(required = false) BackgroundJob.JobKind jobKind) {
        accessPolicy.requireSystemAdmin(principal);
        return noStore(HttpStatus.OK, operationsAdminService.dispatch(maxJobs, jobKind, principal));
    }

    @GetMapping("/outbox/stats")
    public ResponseEntity<JobQueueStatsResponse> outboxStats(@AuthenticationPrincipal SessionService.Principal principal) {
        accessPolicy.requireSystemAdmin(principal);
        return noStore(HttpStatus.OK, operationsAdminService.stats());
    }

    @GetMapping("/quartz/triggers")
    public ResponseEntity<List<QuartzTriggerResponse>> triggers(@AuthenticationPrincipal SessionService.Principal principal) {
        accessPolicy.requireSystemAdmin(principal);
        return noStore(HttpStatus.OK, operationsAdminService.triggers());
    }

    @PostMapping("/quartz/triggers/{jobName}/trigger")
    public ResponseEntity<QuartzTriggerResponse> trigger(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable String jobName) {
        accessPolicy.requireSystemAdmin(principal);
        return noStore(HttpStatus.ACCEPTED, operationsAdminService.triggerJob(jobName, principal));
    }

    @PostMapping("/seed-demo")
    public ResponseEntity<DemoSeedResultResponse> seedDemo(@AuthenticationPrincipal SessionService.Principal principal) {
        accessPolicy.requireSystemAdmin(principal);
        if (!demoSeedEnabled || isProdProfileActive() || demoSeedDataService == null) {
            throw new OperationsException(
                    HttpStatus.FORBIDDEN,
                    "DEMO_SEED_DISABLED",
                    "Demo data seeding is disabled in this environment"
            );
        }
        return noStore(HttpStatus.OK, demoSeedDataService.seedDemoData());
    }

    private boolean isProdProfileActive() {
        return environment != null
                && Arrays.stream(environment.getActiveProfiles()).anyMatch(profile -> "prod".equalsIgnoreCase(profile));
    }

    @ExceptionHandler(OperationsException.class)
    public ResponseEntity<ProblemDetail> handleOperationsException(OperationsException ex) {
        return problem(ex.getStatus(), ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex) {
        return problem(HttpStatus.FORBIDDEN, "FORBIDDEN", "Access denied");
    }

    @ExceptionHandler({
            MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class,
            HttpMessageNotReadableException.class
    })
    public ResponseEntity<ProblemDetail> handleValidation(Exception ex) {
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Validation failed");
    }

    private static <T> ResponseEntity<T> noStore(HttpStatus status, T body) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(code);
        problem.setProperty("code", code);
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(problem);
    }
}

package com.project.devsphere.resource.api;

import com.project.devsphere.identity.application.SessionService;
import com.project.devsphere.resource.application.ResourceCapacityException;
import com.project.devsphere.resource.application.ResourceCapacityService;
import jakarta.validation.Valid;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/projects/{projectId}")
public class ResourceCapacityController {

    private final ResourceCapacityService resourceCapacityService;

    public ResourceCapacityController(ResourceCapacityService resourceCapacityService) {
        this.resourceCapacityService = Objects.requireNonNull(resourceCapacityService, "resourceCapacityService must not be null");
    }

    @PutMapping("/resources")
    public ResponseEntity<ResourceCapacityResponse> putCapacity(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable("workspaceId") UUID workspaceId,
            @PathVariable("projectId") UUID projectId,
            @Valid @RequestBody PutResourceCapacityRequest request) {

        ResourceCapacityService.PutCapacityResult result = resourceCapacityService.putCapacity(principal, workspaceId, projectId, request);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .body(result.response());
    }

    @PostMapping("/resources/{developerId}/effective-changes")
    public ResponseEntity<ResourceCapacityResponse> effectiveChange(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable("workspaceId") UUID workspaceId,
            @PathVariable("projectId") UUID projectId,
            @PathVariable("developerId") UUID developerId,
            @Valid @RequestBody EffectiveCapacityChangeRequest request) {

        ResourceCapacityResponse response = resourceCapacityService.effectiveChange(principal, workspaceId, projectId, developerId, request);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    @PostMapping("/time-off")
    public ResponseEntity<Void> addTimeOff(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable("workspaceId") UUID workspaceId,
            @PathVariable("projectId") UUID projectId,
            @Valid @RequestBody AddTimeOffRequest request) {

        resourceCapacityService.addTimeOff(principal, workspaceId, projectId, request);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .build();
    }

    @GetMapping("/resources")
    public ResponseEntity<List<ResourceCapacityResponse>> listCapacities(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable("workspaceId") UUID workspaceId,
            @PathVariable("projectId") UUID projectId,
            @RequestParam(value = "developerId", required = false) UUID developerId,
            @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        List<ResourceCapacityResponse> responses = resourceCapacityService.listCapacities(principal, workspaceId, projectId, developerId, from, to);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(responses);
    }

    @GetMapping("/resources/{developerId}/calendar")
    public ResponseEntity<ResourceCalendarResponse> getCalendar(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable("workspaceId") UUID workspaceId,
            @PathVariable("projectId") UUID projectId,
            @PathVariable("developerId") UUID developerId,
            @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        ResourceCalendarResponse response = resourceCapacityService.getCalendar(principal, workspaceId, projectId, developerId, from, to);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    @ExceptionHandler(ResourceCapacityException.class)
    public ResponseEntity<ProblemDetail> handleResourceCapacityException(ResourceCapacityException ex) {
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
        String msg = ex.getMessage();
        if (msg != null && msg.contains("ex_capacity_effective_overlap")) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Effective period overlaps with an existing capacity interval");
            problem.setTitle("RESOURCE_PERIOD_OVERLAP");
            problem.setProperty("code", "RESOURCE_PERIOD_OVERLAP");
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .cacheControl(CacheControl.noStore())
                    .body(problem);
        }
        if (msg != null && msg.contains("Capacity requires Developer role")) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Target user is not an active developer on the project");
            problem.setTitle("ASSIGNEE_NOT_DEVELOPER");
            problem.setProperty("code", "ASSIGNEE_NOT_DEVELOPER");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .cacheControl(CacheControl.noStore())
                    .body(problem);
        }
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Data integrity conflict: " + msg);
        problem.setTitle("RESOURCE_PERIOD_OVERLAP");
        problem.setProperty("code", "RESOURCE_PERIOD_OVERLAP");
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .cacheControl(CacheControl.noStore())
                .body(problem);
    }
}

package com.project.devsphere.codequality.api;

import com.project.devsphere.codequality.api.dto.CiRunResponse;
import com.project.devsphere.codequality.api.dto.RecordCiRunRequest;
import com.project.devsphere.codequality.application.CiRunService;
import com.project.devsphere.codequality.application.CodeQualityException;
import com.project.devsphere.codequality.domain.CiRun;
import com.project.devsphere.identity.application.SessionService;
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
@RequestMapping("/api/v1/workspaces/{workspaceId}/projects/{projectId}/ci-runs")
public class CiRunController {

    private final CiRunService ciRunService;

    public CiRunController(CiRunService ciRunService) {
        this.ciRunService = Objects.requireNonNull(ciRunService, "ciRunService must not be null");
    }

    @PostMapping
    public ResponseEntity<CiRunResponse> recordCiRun(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable("workspaceId") UUID workspaceId,
            @PathVariable("projectId") UUID projectId,
            @Valid @RequestBody RecordCiRunRequest request) {

        CiRun ciRun = ciRunService.recordCiRun(principal, workspaceId, projectId, request.toCommand());
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(CiRunResponse.from(ciRun));
    }

    @GetMapping
    public ResponseEntity<List<CiRunResponse>> listCiRuns(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable("workspaceId") UUID workspaceId,
            @PathVariable("projectId") UUID projectId,
            @RequestParam(value = "workItemId", required = false) UUID workItemId,
            @RequestParam(value = "prNumber", required = false) Integer prNumber,
            @RequestParam(value = "headSha", required = false) String headSha) {

        List<CiRun> runs = ciRunService.listCiRuns(principal, workspaceId, projectId, workItemId, prNumber, headSha);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(runs.stream().map(CiRunResponse::from).toList());
    }

    @GetMapping("/{ciRunId}")
    public ResponseEntity<CiRunResponse> getCiRun(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable("workspaceId") UUID workspaceId,
            @PathVariable("projectId") UUID projectId,
            @PathVariable("ciRunId") UUID ciRunId) {

        CiRun ciRun = ciRunService.getCiRun(principal, workspaceId, projectId, ciRunId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(CiRunResponse.from(ciRun));
    }

    @ExceptionHandler(CodeQualityException.class)
    public ResponseEntity<ProblemDetail> handleCodeQualityException(CodeQualityException ex) {
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

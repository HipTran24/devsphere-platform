package com.project.devsphere.monitoring.api;

import com.project.devsphere.identity.application.SessionService;
import com.project.devsphere.monitoring.api.dto.AlertResponse;
import com.project.devsphere.monitoring.api.dto.UpdateAlertStatusRequest;
import com.project.devsphere.monitoring.application.AlertException;
import com.project.devsphere.monitoring.application.AlertService;
import com.project.devsphere.monitoring.application.MonitoringException;
import com.project.devsphere.monitoring.domain.Alert;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@RestController
@RequestMapping(
        path = "/api/v1/workspaces/{workspaceId}/projects/{projectId}/alerts",
        produces = MediaType.APPLICATION_JSON_VALUE
)
public class AlertController {

    private final AlertService alertService;

    public AlertController(AlertService alertService) {
        this.alertService = Objects.requireNonNull(alertService, "alertService must not be null");
    }

    @GetMapping
    public ResponseEntity<List<AlertResponse>> listAlerts(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @RequestParam(required = false) Alert.Status status,
            @RequestParam(required = false) Alert.Severity severity,
            @RequestParam(required = false) UUID workItemId) {

        List<AlertResponse> alerts = alertService.listAlerts(principal, workspaceId, projectId, status, severity, workItemId);
        return ResponseEntity.ok(alerts);
    }

    @GetMapping("/{alertId}")
    public ResponseEntity<AlertResponse> getAlert(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @PathVariable UUID alertId) {

        AlertResponse response = alertService.getAlert(principal, workspaceId, projectId, alertId);
        return ResponseEntity.ok(response);
    }

    @PatchMapping(path = "/{alertId}/status", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AlertResponse> updateAlertStatus(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @PathVariable UUID alertId,
            @Valid @RequestBody UpdateAlertStatusRequest request) {

        AlertResponse response = alertService.updateAlertStatus(principal, workspaceId, projectId, alertId, request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{alertId}/history")
    public ResponseEntity<List<AlertResponse>> getAlertHistory(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @PathVariable UUID alertId) {

        List<AlertResponse> history = alertService.getAlertHistory(principal, workspaceId, projectId, alertId);
        return ResponseEntity.ok(history);
    }

    @ExceptionHandler(AlertException.class)
    public ResponseEntity<ProblemDetail> handleAlertException(AlertException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
        problem.setTitle(ex.getCode());
        problem.setProperty("code", ex.getCode());
        return ResponseEntity.status(ex.getStatus()).body(problem);
    }

    @ExceptionHandler(MonitoringException.class)
    public ResponseEntity<ProblemDetail> handleMonitoringException(MonitoringException ex) {
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
}

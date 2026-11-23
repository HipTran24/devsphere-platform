package com.project.devsphere.monitoring.api;

import com.project.devsphere.identity.application.AccessPolicy;
import com.project.devsphere.identity.application.SessionService;
import com.project.devsphere.identity.domain.ProjectRole;
import com.project.devsphere.monitoring.api.dto.*;
import com.project.devsphere.monitoring.application.*;
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
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping(
        path = "/api/v1/workspaces/{workspaceId}/projects/{projectId}",
        produces = MediaType.APPLICATION_JSON_VALUE
)
public class ControlTowerController {

    private final CpmAnalyzer cpmAnalyzer;
    private final AlertRuleEngine alertRuleEngine;
    private final RescheduleTriggerService rescheduleTriggerService;
    private final ProgressEvaluationService progressEvaluationService;
    private final AccessPolicy accessPolicy;

    public ControlTowerController(
            CpmAnalyzer cpmAnalyzer,
            AlertRuleEngine alertRuleEngine,
            RescheduleTriggerService rescheduleTriggerService,
            ProgressEvaluationService progressEvaluationService,
            AccessPolicy accessPolicy) {
        this.cpmAnalyzer = Objects.requireNonNull(cpmAnalyzer, "cpmAnalyzer must not be null");
        this.alertRuleEngine = Objects.requireNonNull(alertRuleEngine, "alertRuleEngine must not be null");
        this.rescheduleTriggerService = Objects.requireNonNull(rescheduleTriggerService, "rescheduleTriggerService must not be null");
        this.progressEvaluationService = Objects.requireNonNull(progressEvaluationService, "progressEvaluationService must not be null");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy must not be null");
    }

    @GetMapping("/control-tower/graph")
    public ResponseEntity<ControlTowerGraphResponse> getControlTowerGraph(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId) {

        accessPolicy.requireAnyProjectRole(principal, workspaceId, projectId, Set.of(ProjectRole.PM, ProjectRole.DEVELOPER, ProjectRole.VIEWER));

        CpmAnalyzer.CpmAnalysisResult result = cpmAnalyzer.analyzeProject(projectId);
        return ResponseEntity.ok(result.toGraphResponse());
    }

    @PostMapping(path = "/impact-analyses", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ImpactAnalysisResponse> runImpactAnalysis(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @Valid @RequestBody ImpactAnalysisRequest request) {

        accessPolicy.requireProjectRole(principal, workspaceId, projectId, ProjectRole.PM);

        CpmAnalyzer.CpmAnalysisResult result = cpmAnalyzer.analyzeProject(projectId);
        ImpactAnalysisResponse response = result.calculateImpact(request.workItemId(), request.delayMinutes());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/reschedule")
    public ResponseEntity<RescheduleResponse> triggerReschedule(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId) {

        RescheduleResponse response = rescheduleTriggerService.triggerReschedule(principal, workspaceId, projectId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/alert-rules")
    public ResponseEntity<AlertRuleSettingsDTO> getAlertRules(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId) {

        AlertRuleSettingsDTO settings = alertRuleEngine.getRuleSettings(principal, workspaceId, projectId);
        return ResponseEntity.ok(settings);
    }

    @PatchMapping(path = "/alert-rules", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AlertRuleSettingsDTO> updateAlertRules(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @Valid @RequestBody UpdateAlertRulesRequest request) {

        AlertRuleSettingsDTO settings = alertRuleEngine.updateRuleSettings(principal, workspaceId, projectId, request);
        return ResponseEntity.ok(settings);
    }

    @PostMapping({"/alerts/evaluate", "/alerts/detect"})
    public ResponseEntity<List<AlertResponse>> evaluateAlertRules(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId) {

        List<AlertResponse> triggered = alertRuleEngine.evaluateProjectRules(principal, workspaceId, projectId);
        return ResponseEntity.ok(triggered);
    }

    @PostMapping("/work-items/{workItemId}/evaluate-progress")
    public ResponseEntity<ProgressEvaluationResponse> evaluateWorkItemProgress(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @PathVariable UUID workItemId) {

        ProgressEvaluationResponse response = progressEvaluationService.evaluateProgress(principal, workspaceId, projectId, workItemId);
        return ResponseEntity.ok(response);
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

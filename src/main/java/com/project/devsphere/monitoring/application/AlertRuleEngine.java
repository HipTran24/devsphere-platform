package com.project.devsphere.monitoring.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.project.devsphere.identity.application.AccessPolicy;
import com.project.devsphere.identity.application.SessionService;
import com.project.devsphere.identity.domain.ProjectRole;
import com.project.devsphere.monitoring.api.dto.AlertResponse;
import com.project.devsphere.monitoring.api.dto.AlertRuleSettingsDTO;
import com.project.devsphere.monitoring.api.dto.UpdateAlertRulesRequest;
import com.project.devsphere.monitoring.domain.Alert;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

@Service
public class AlertRuleEngine {

    private final JdbcTemplate jdbcTemplate;
    private final AlertService alertService;
    private final AccessPolicy accessPolicy;
    private final ObjectMapper objectMapper;

    @org.springframework.beans.factory.annotation.Autowired
    public AlertRuleEngine(
            JdbcTemplate jdbcTemplate,
            AlertService alertService,
            AccessPolicy accessPolicy,
            @org.springframework.beans.factory.annotation.Autowired(required = false) ObjectMapper objectMapper) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.alertService = Objects.requireNonNull(alertService, "alertService must not be null");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy must not be null");
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
    }

    /**
     * Retrieves the alert rule configuration settings from project.settings->'alertRules'.
     */
    @Transactional(readOnly = true)
    public AlertRuleSettingsDTO getRuleSettings(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId) {

        accessPolicy.requireAnyProjectRole(principal, workspaceId, projectId, Set.of(ProjectRole.PM, ProjectRole.DEVELOPER, ProjectRole.VIEWER));

        return loadRuleSettings(projectId);
    }

    /**
     * Updates alert rule settings stored in project.settings.
     * Guarded by PM role.
     */
    @Transactional
    public AlertRuleSettingsDTO updateRuleSettings(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UpdateAlertRulesRequest request) {

        accessPolicy.requireProjectRole(principal, workspaceId, projectId, ProjectRole.PM);

        if (request == null) {
            throw AlertException.validationFailed("Request must not be null");
        }

        AlertRuleSettingsDTO current = loadRuleSettings(projectId);

        AlertRuleSettingsDTO updated = new AlertRuleSettingsDTO(
                request.taskBlockedRuleEnabled() != null ? request.taskBlockedRuleEnabled() : current.taskBlockedRuleEnabled(),
                request.scheduleOverdueRuleEnabled() != null ? request.scheduleOverdueRuleEnabled() : current.scheduleOverdueRuleEnabled(),
                request.deadlineBreachRiskRuleEnabled() != null ? request.deadlineBreachRiskRuleEnabled() : current.deadlineBreachRiskRuleEnabled(),
                request.progressEvidenceMissingRuleEnabled() != null ? request.progressEvidenceMissingRuleEnabled() : current.progressEvidenceMissingRuleEnabled(),
                request.deadlineWarningDays() != null ? request.deadlineWarningDays() : current.deadlineWarningDays(),
                request.overdueGraceMinutes() != null ? request.overdueGraceMinutes() : current.overdueGraceMinutes(),
                request.discrepancyThresholdPercent() != null ? request.discrepancyThresholdPercent() : current.discrepancyThresholdPercent()
        );

        saveRuleSettings(projectId, updated);
        return updated;
    }

    /**
     * Evaluates all active monitoring rules for the project and generates/deduplicates alerts.
     */
    @Transactional
    public List<AlertResponse> evaluateProjectRules(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId) {

        accessPolicy.requireAnyProjectRole(principal, workspaceId, projectId, Set.of(ProjectRole.PM, ProjectRole.DEVELOPER));

        AlertRuleSettingsDTO settings = loadRuleSettings(projectId);
        List<AlertResponse> triggeredAlerts = new ArrayList<>();

        // 1. Rule: TASK_BLOCKED
        if (settings.taskBlockedRuleEnabled()) {
            triggeredAlerts.addAll(evaluateBlockedTasks(projectId));
        }

        // 2. Rule: SCHEDULE_OVERDUE
        if (settings.scheduleOverdueRuleEnabled()) {
            triggeredAlerts.addAll(evaluateOverdueTasks(projectId, settings.overdueGraceMinutes()));
        }

        // 3. Rule: DEADLINE_BREACH_RISK
        if (settings.deadlineBreachRiskRuleEnabled()) {
            evaluateDeadlineBreachRisk(projectId, settings.deadlineWarningDays()).ifPresent(triggeredAlerts::add);
        }

        // 4. Rule: PROGRESS_EVIDENCE_MISSING
        if (settings.progressEvidenceMissingRuleEnabled()) {
            triggeredAlerts.addAll(evaluateMissingProgressEvidence(projectId));
        }

        return triggeredAlerts;
    }

    private List<AlertResponse> evaluateBlockedTasks(UUID projectId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, item_key, title, blocked_reason, blocked_at, status
                FROM work_items
                WHERE project_id = ? AND is_blocked = true AND status <> 'DONE' AND deleted_at IS NULL
                """, projectId);

        List<AlertResponse> results = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            UUID workItemId = (UUID) row.get("id");
            String itemKey = (String) row.get("item_key");
            String title = (String) row.get("title");
            String reason = (String) row.get("blocked_reason");
            Timestamp blockedAt = (Timestamp) row.get("blocked_at");

            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("schemaVersion", 1);
            evidence.put("workItemId", workItemId.toString());
            evidence.put("itemKey", itemKey);
            evidence.put("blockedReason", reason != null ? reason : "");
            evidence.put("blockedAt", blockedAt != null ? blockedAt.toInstant().toString() : null);

            Map<String, Object> impact = new LinkedHashMap<>();
            impact.put("schemaVersion", 1);
            impact.put("rule", "TASK_BLOCKED");

            Alert alert = alertService.createOrUpdateAlert(
                    projectId,
                    workItemId,
                    "TASK_BLOCKED",
                    Alert.Severity.HIGH,
                    "Task " + itemKey + " is blocked: " + (reason != null && !reason.isBlank() ? reason : title),
                    evidence,
                    impact
            );
            results.add(AlertResponse.from(alert));
        }
        return results;
    }

    private List<AlertResponse> evaluateOverdueTasks(UUID projectId, int graceMinutes) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT w.id, w.item_key, w.title, w.status, e.finish_at
                FROM work_items w
                JOIN schedule_entries e ON e.work_item_id = w.id
                JOIN projects p ON p.active_schedule_version_id = e.schedule_version_id
                WHERE p.id = ? AND w.status <> 'DONE' AND w.deleted_at IS NULL
                  AND e.finish_at + (? * interval '1 minute') < now()
                """, projectId, graceMinutes);

        List<AlertResponse> results = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            UUID workItemId = (UUID) row.get("id");
            String itemKey = (String) row.get("item_key");
            String title = (String) row.get("title");
            String status = (String) row.get("status");
            Timestamp finishAt = (Timestamp) row.get("finish_at");

            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("schemaVersion", 1);
            evidence.put("workItemId", workItemId.toString());
            evidence.put("itemKey", itemKey);
            evidence.put("scheduledFinishAt", finishAt != null ? finishAt.toInstant().toString() : null);
            evidence.put("taskStatus", status);

            Map<String, Object> impact = new LinkedHashMap<>();
            impact.put("schemaVersion", 1);
            impact.put("rule", "SCHEDULE_OVERDUE");

            Alert alert = alertService.createOrUpdateAlert(
                    projectId,
                    workItemId,
                    "SCHEDULE_OVERDUE",
                    Alert.Severity.HIGH,
                    "Task " + itemKey + " (" + title + ") is overdue",
                    evidence,
                    impact
            );
            results.add(AlertResponse.from(alert));
        }
        return results;
    }

    private Optional<AlertResponse> evaluateDeadlineBreachRisk(UUID projectId, int warningDays) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT p.committed_deadline, s.forecast_finish, s.planned_finish
                FROM projects p
                JOIN schedule_versions s ON s.id = p.active_schedule_version_id
                WHERE p.id = ?
                """, projectId);

        if (rows.isEmpty()) {
            return Optional.empty();
        }

        Map<String, Object> row = rows.get(0);
        Date committedDate = (Date) row.get("committed_deadline");
        Date forecastDate = (Date) row.get("forecast_finish");
        Date plannedDate = (Date) row.get("planned_finish");

        LocalDate committedDeadline = committedDate != null ? committedDate.toLocalDate() : null;
        LocalDate forecastFinish = forecastDate != null ? forecastDate.toLocalDate()
                : (plannedDate != null ? plannedDate.toLocalDate() : null);

        if (committedDeadline != null && forecastFinish != null) {
            boolean isBreached = forecastFinish.isAfter(committedDeadline);
            boolean isAtRisk = !isBreached && forecastFinish.plusDays(warningDays).isAfter(committedDeadline);

            if (isBreached || isAtRisk) {
                Alert.Severity severity = isBreached ? Alert.Severity.CRITICAL : Alert.Severity.HIGH;
                String alertCode = isBreached ? "DEADLINE_BREACH_RISK" : "DEADLINE_APPROACHING";

                Map<String, Object> evidence = new LinkedHashMap<>();
                evidence.put("schemaVersion", 1);
                evidence.put("committedDeadline", committedDeadline.toString());
                evidence.put("forecastFinish", forecastFinish.toString());
                evidence.put("breached", isBreached);

                Map<String, Object> impact = new LinkedHashMap<>();
                impact.put("schemaVersion", 1);
                impact.put("rule", "DEADLINE_BREACH_RISK");

                Alert alert = alertService.createOrUpdateAlert(
                        projectId,
                        null,
                        alertCode,
                        severity,
                        isBreached ? "Project committed deadline breached: forecast " + forecastFinish + " is after deadline " + committedDeadline
                                : "Project deadline at risk: forecast " + forecastFinish + " approaches deadline " + committedDeadline,
                        evidence,
                        impact
                );
                return Optional.of(AlertResponse.from(alert));
            }
        }

        return Optional.empty();
    }

    private List<AlertResponse> evaluateMissingProgressEvidence(UUID projectId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT w.id, w.item_key, w.title, w.status, w.claimed_progress
                FROM work_items w
                WHERE w.project_id = ? AND w.status IN ('IN_PROGRESS', 'IN_REVIEW') AND w.deleted_at IS NULL
                  AND NOT EXISTS (
                      SELECT 1 FROM acceptance_criteria ac
                      WHERE ac.work_item_id = w.id
                  )
                """, projectId);

        List<AlertResponse> results = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            UUID workItemId = (UUID) row.get("id");
            String itemKey = (String) row.get("item_key");
            String title = (String) row.get("title");
            String status = (String) row.get("status");

            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("schemaVersion", 1);
            evidence.put("workItemId", workItemId.toString());
            evidence.put("itemKey", itemKey);
            evidence.put("taskStatus", status);
            evidence.put("reason", "No acceptance criteria defined for active work item");

            Map<String, Object> impact = new LinkedHashMap<>();
            impact.put("schemaVersion", 1);
            impact.put("rule", "PROGRESS_EVIDENCE_MISSING");

            Alert alert = alertService.createOrUpdateAlert(
                    projectId,
                    workItemId,
                    "PROGRESS_EVIDENCE_MISSING",
                    Alert.Severity.MEDIUM,
                    "Task " + itemKey + " (" + title + ") has status " + status + " but lacks acceptance criteria",
                    evidence,
                    impact
            );
            results.add(AlertResponse.from(alert));
        }
        return results;
    }

    private AlertRuleSettingsDTO loadRuleSettings(UUID projectId) {
        String settingsJson = jdbcTemplate.queryForObject(
                "SELECT settings::text FROM projects WHERE id = ?",
                String.class,
                projectId
        );

        if (settingsJson == null || settingsJson.isBlank()) {
            return AlertRuleSettingsDTO.defaults();
        }

        try {
            Map<String, Object> settings = objectMapper.readValue(settingsJson, new TypeReference<>() {});
            if (settings.get("alertRules") instanceof Map<?, ?> rulesMap) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) rulesMap;
                return new AlertRuleSettingsDTO(
                        m.get("taskBlockedRuleEnabled") instanceof Boolean b ? b : true,
                        m.get("scheduleOverdueRuleEnabled") instanceof Boolean b ? b : true,
                        m.get("deadlineBreachRiskRuleEnabled") instanceof Boolean b ? b : true,
                        m.get("progressEvidenceMissingRuleEnabled") instanceof Boolean b ? b : true,
                        m.get("deadlineWarningDays") instanceof Number n ? n.intValue() : 3,
                        m.get("overdueGraceMinutes") instanceof Number n ? n.intValue() : 60,
                        m.get("discrepancyThresholdPercent") instanceof Number n ? n.intValue() : 25
                );
            }
        } catch (Exception ignored) {
        }

        return AlertRuleSettingsDTO.defaults();
    }

    private void saveRuleSettings(UUID projectId, AlertRuleSettingsDTO ruleSettings) {
        String settingsJson = jdbcTemplate.queryForObject(
                "SELECT settings::text FROM projects WHERE id = ?",
                String.class,
                projectId
        );

        Map<String, Object> settings = new LinkedHashMap<>();
        if (settingsJson != null && !settingsJson.isBlank()) {
            try {
                settings = objectMapper.readValue(settingsJson, new TypeReference<>() {});
            } catch (Exception ignored) {
            }
        }

        Map<String, Object> rulesMap = new LinkedHashMap<>();
        rulesMap.put("taskBlockedRuleEnabled", ruleSettings.taskBlockedRuleEnabled());
        rulesMap.put("scheduleOverdueRuleEnabled", ruleSettings.scheduleOverdueRuleEnabled());
        rulesMap.put("deadlineBreachRiskRuleEnabled", ruleSettings.deadlineBreachRiskRuleEnabled());
        rulesMap.put("progressEvidenceMissingRuleEnabled", ruleSettings.progressEvidenceMissingRuleEnabled());
        rulesMap.put("deadlineWarningDays", ruleSettings.deadlineWarningDays());
        rulesMap.put("overdueGraceMinutes", ruleSettings.overdueGraceMinutes());
        rulesMap.put("discrepancyThresholdPercent", ruleSettings.discrepancyThresholdPercent());

        settings.put("alertRules", rulesMap);

        try {
            String updatedJson = objectMapper.writeValueAsString(settings);
            jdbcTemplate.update(
                    "UPDATE projects SET settings = ?::jsonb, updated_at = now() WHERE id = ?",
                    updatedJson,
                    projectId
            );
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize project settings", e);
        }
    }
}

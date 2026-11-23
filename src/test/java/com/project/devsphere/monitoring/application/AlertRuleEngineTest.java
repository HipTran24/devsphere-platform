package com.project.devsphere.monitoring.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.devsphere.identity.application.AccessPolicy;
import com.project.devsphere.identity.application.SessionService;
import com.project.devsphere.identity.domain.ProjectRole;
import com.project.devsphere.monitoring.api.dto.AlertResponse;
import com.project.devsphere.monitoring.api.dto.AlertRuleSettingsDTO;
import com.project.devsphere.monitoring.api.dto.UpdateAlertRulesRequest;
import com.project.devsphere.monitoring.domain.Alert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.mockito.quality.Strictness;
import org.mockito.junit.jupiter.MockitoSettings;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AlertRuleEngineTest {

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private AlertService alertService;
    @Mock private AccessPolicy accessPolicy;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AlertRuleEngine ruleEngine;

    private final UUID workspaceId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final SessionService.Principal pmPrincipal = new SessionService.Principal(UUID.randomUUID(), UUID.randomUUID(), "pm@example.test", "USER");

    @BeforeEach
    void setUp() {
        ruleEngine = new AlertRuleEngine(jdbcTemplate, alertService, accessPolicy, objectMapper);
    }

    @Test
    @DisplayName("getRuleSettings returns defaults when project settings are empty")
    void getRuleSettingsDefaults() {
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class), eq(projectId)))
                .thenReturn("{\"schemaVersion\":1}");

        AlertRuleSettingsDTO settings = ruleEngine.getRuleSettings(pmPrincipal, workspaceId, projectId);

        assertThat(settings).isNotNull();
        assertThat(settings.taskBlockedRuleEnabled()).isTrue();
        assertThat(settings.scheduleOverdueRuleEnabled()).isTrue();
        assertThat(settings.deadlineBreachRiskRuleEnabled()).isTrue();
        assertThat(settings.progressEvidenceMissingRuleEnabled()).isTrue();
        assertThat(settings.deadlineWarningDays()).isEqualTo(3);
        assertThat(settings.overdueGraceMinutes()).isEqualTo(60);

        verify(accessPolicy).requireAnyProjectRole(pmPrincipal, workspaceId, projectId, Set.of(ProjectRole.PM, ProjectRole.DEVELOPER, ProjectRole.VIEWER));
    }

    @Test
    @DisplayName("updateRuleSettings updates settings in project.settings and returns updated settings")
    void updateRuleSettings() {
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class), eq(projectId)))
                .thenReturn("{\"schemaVersion\":1}");

        UpdateAlertRulesRequest req = new UpdateAlertRulesRequest(
                false, // Disable blocked rule
                true,
                true,
                false,
                5,
                120,
                30
        );

        AlertRuleSettingsDTO updated = ruleEngine.updateRuleSettings(pmPrincipal, workspaceId, projectId, req);

        assertThat(updated.taskBlockedRuleEnabled()).isFalse();
        assertThat(updated.scheduleOverdueRuleEnabled()).isTrue();
        assertThat(updated.progressEvidenceMissingRuleEnabled()).isFalse();
        assertThat(updated.deadlineWarningDays()).isEqualTo(5);
        assertThat(updated.overdueGraceMinutes()).isEqualTo(120);

        verify(accessPolicy).requireProjectRole(pmPrincipal, workspaceId, projectId, ProjectRole.PM);
        verify(jdbcTemplate).update(contains("UPDATE projects SET settings = ?::jsonb"), anyString(), eq(projectId));
    }

    @Test
    @DisplayName("evaluateProjectRules triggers TASK_BLOCKED alert when work item has is_blocked = true")
    void evaluateBlockedTasks() {
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class), eq(projectId)))
                .thenReturn("{\"schemaVersion\":1}");

        UUID blockedTaskId = UUID.randomUUID();
        Map<String, Object> taskRow = new HashMap<>();
        taskRow.put("id", blockedTaskId);
        taskRow.put("item_key", "PRJ-101");
        taskRow.put("title", "Database Migration");
        taskRow.put("blocked_reason", "Awaiting schema approval");
        taskRow.put("blocked_at", Timestamp.from(Instant.now()));
        taskRow.put("status", "IN_PROGRESS");

        // First query: TASK_BLOCKED query
        when(jdbcTemplate.queryForList(contains("is_blocked = true"), eq(projectId)))
                .thenReturn(List.of(taskRow));

        // Other queries return empty lists
        when(jdbcTemplate.queryForList(contains("finish_at +"), eq(projectId), anyInt()))
                .thenReturn(List.of());
        when(jdbcTemplate.queryForList(contains("schedule_versions s"), eq(projectId)))
                .thenReturn(List.of());
        when(jdbcTemplate.queryForList(contains("NOT EXISTS ("), eq(projectId)))
                .thenReturn(List.of());

        Alert mockAlert = new Alert();
        mockAlert.setId(UUID.randomUUID());
        mockAlert.setProjectId(projectId);
        mockAlert.setWorkItemId(blockedTaskId);
        mockAlert.setAlertCode("TASK_BLOCKED");
        mockAlert.setSeverity(Alert.Severity.HIGH);
        mockAlert.setStatus(Alert.Status.NEW);
        mockAlert.setTitle("Task PRJ-101 is blocked");
        mockAlert.setFingerprint("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");

        when(alertService.createOrUpdateAlert(eq(projectId), eq(blockedTaskId), eq("TASK_BLOCKED"), eq(Alert.Severity.HIGH), anyString(), anyMap(), anyMap()))
                .thenReturn(mockAlert);

        List<AlertResponse> alerts = ruleEngine.evaluateProjectRules(pmPrincipal, workspaceId, projectId);

        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).alertCode()).isEqualTo("TASK_BLOCKED");
        assertThat(alerts.get(0).workItemId()).isEqualTo(blockedTaskId);
    }

    @Test
    @DisplayName("evaluateProjectRules triggers DEADLINE_BREACH_RISK alert when forecast_finish is after committed_deadline")
    void evaluateDeadlineBreach() {
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class), eq(projectId)))
                .thenReturn("{\"schemaVersion\":1}");

        // Empty blocked, overdue, missing evidence queries
        when(jdbcTemplate.queryForList(contains("is_blocked = true"), eq(projectId))).thenReturn(List.of());
        when(jdbcTemplate.queryForList(contains("finish_at +"), eq(projectId), anyInt())).thenReturn(List.of());
        when(jdbcTemplate.queryForList(contains("NOT EXISTS ("), eq(projectId))).thenReturn(List.of());

        // Deadline query returns breached deadline: forecast finish (2026-12-31) is after committed (2026-12-15)
        Map<String, Object> deadlineRow = new HashMap<>();
        deadlineRow.put("committed_deadline", Date.valueOf(LocalDate.of(2026, 12, 15)));
        deadlineRow.put("forecast_finish", Date.valueOf(LocalDate.of(2026, 12, 31)));
        deadlineRow.put("planned_finish", Date.valueOf(LocalDate.of(2026, 12, 31)));

        when(jdbcTemplate.queryForList(contains("schedule_versions s"), eq(projectId)))
                .thenReturn(List.of(deadlineRow));

        Alert mockAlert = new Alert();
        mockAlert.setId(UUID.randomUUID());
        mockAlert.setProjectId(projectId);
        mockAlert.setAlertCode("DEADLINE_BREACH_RISK");
        mockAlert.setSeverity(Alert.Severity.CRITICAL);
        mockAlert.setStatus(Alert.Status.NEW);
        mockAlert.setTitle("Project committed deadline breached");
        mockAlert.setFingerprint("fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210");

        when(alertService.createOrUpdateAlert(eq(projectId), isNull(), eq("DEADLINE_BREACH_RISK"), eq(Alert.Severity.CRITICAL), anyString(), anyMap(), anyMap()))
                .thenReturn(mockAlert);

        List<AlertResponse> alerts = ruleEngine.evaluateProjectRules(pmPrincipal, workspaceId, projectId);

        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).alertCode()).isEqualTo("DEADLINE_BREACH_RISK");
        assertThat(alerts.get(0).severity()).isEqualTo(Alert.Severity.CRITICAL);
    }
}

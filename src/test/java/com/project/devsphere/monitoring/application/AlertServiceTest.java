package com.project.devsphere.monitoring.application;

import com.project.devsphere.identity.application.AccessPolicy;
import com.project.devsphere.identity.application.SessionService;
import com.project.devsphere.identity.domain.ProjectRole;
import com.project.devsphere.monitoring.api.dto.AlertResponse;
import com.project.devsphere.monitoring.api.dto.UpdateAlertStatusRequest;
import com.project.devsphere.monitoring.domain.Alert;
import com.project.devsphere.monitoring.infrastructure.persistence.AlertRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AlertServiceTest {

    @Mock private AlertRepository alertRepository;
    @Mock private AccessPolicy accessPolicy;

    private AlertService alertService;

    private final UUID workspaceId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID workItemId = UUID.randomUUID();
    private final SessionService.Principal pmPrincipal = new SessionService.Principal(UUID.randomUUID(), UUID.randomUUID(), "pm@example.test", "USER");

    @BeforeEach
    void setUp() {
        alertService = new AlertService(alertRepository, accessPolicy);
    }

    @Test
    @DisplayName("computeFingerprint produces 64-character lowercase hex string matching ^[0-9a-f]{64}$")
    void computeFingerprintFormat() {
        String fp = alertService.computeFingerprint(projectId, "TASK_BLOCKED", workItemId);
        assertThat(fp).matches("^[0-9a-f]{64}$");

        // Deterministic: same inputs produce identical fingerprint
        String fp2 = alertService.computeFingerprint(projectId, "TASK_BLOCKED", workItemId);
        assertThat(fp).isEqualTo(fp2);

        // Different code produces different fingerprint
        String fp3 = alertService.computeFingerprint(projectId, "SCHEDULE_OVERDUE", workItemId);
        assertThat(fp).isNotEqualTo(fp3);
    }

    @Test
    @DisplayName("createOrUpdateAlert creates new alert with status NEW when no open alert exists")
    void createAlertNew() {
        when(alertRepository.findFirstByProjectIdAndFingerprintAndStatusNot(eq(projectId), anyString(), eq(Alert.Status.RESOLVED)))
                .thenReturn(Optional.empty());
        when(alertRepository.findFirstByProjectIdAndFingerprintAndStatusOrderByResolvedAtDesc(eq(projectId), anyString(), eq(Alert.Status.RESOLVED)))
                .thenReturn(Optional.empty());

        when(alertRepository.save(any(Alert.class))).thenAnswer(i -> i.getArgument(0));

        Alert created = alertService.createOrUpdateAlert(
                projectId,
                workItemId,
                "TASK_BLOCKED",
                Alert.Severity.HIGH,
                "Task is blocked",
                Map.of("reason", "Waiting on review"),
                Map.of("impact", "High")
        );

        assertThat(created).isNotNull();
        assertThat(created.getStatus()).isEqualTo(Alert.Status.NEW);
        assertThat(created.getSeverity()).isEqualTo(Alert.Severity.HIGH);
        assertThat(created.getPreviousAlertId()).isNull();
        assertThat(created.getFingerprint()).matches("^[0-9a-f]{64}$");
        verify(alertRepository).save(any(Alert.class));
    }

    @Test
    @DisplayName("createOrUpdateAlert deduplicates: updates evidence and monotonically escalates severity on existing open alert")
    void deduplicateAndEscalateSeverity() {
        String fingerprint = alertService.computeFingerprint(projectId, "TASK_BLOCKED", workItemId);

        Alert existing = new Alert();
        existing.setId(UUID.randomUUID());
        existing.setProjectId(projectId);
        existing.setWorkItemId(workItemId);
        existing.setFingerprint(fingerprint);
        existing.setStatus(Alert.Status.NEW);
        existing.setSeverity(Alert.Severity.LOW); // Low severity initially

        when(alertRepository.findFirstByProjectIdAndFingerprintAndStatusNot(eq(projectId), eq(fingerprint), eq(Alert.Status.RESOLVED)))
                .thenReturn(Optional.of(existing));

        when(alertRepository.save(any(Alert.class))).thenAnswer(i -> i.getArgument(0));

        Alert updated = alertService.createOrUpdateAlert(
                projectId,
                workItemId,
                "TASK_BLOCKED",
                Alert.Severity.CRITICAL, // Escalating to CRITICAL
                "Updated title",
                Map.of("newEvidence", "true"),
                Map.of()
        );

        assertThat(updated).isSameAs(existing);
        assertThat(updated.getSeverity()).isEqualTo(Alert.Severity.CRITICAL);
        assertThat(updated.getEvidence()).containsEntry("newEvidence", "true");
        // Status remains NEW
        assertThat(updated.getStatus()).isEqualTo(Alert.Status.NEW);
    }

    @Test
    @DisplayName("createOrUpdateAlert links previousAlertId when a resolved alert exists with the same fingerprint")
    void linkPreviousResolvedAlert() {
        String fingerprint = alertService.computeFingerprint(projectId, "TASK_BLOCKED", workItemId);
        UUID resolvedAlertId = UUID.randomUUID();

        Alert resolvedAlert = new Alert();
        resolvedAlert.setId(resolvedAlertId);
        resolvedAlert.setProjectId(projectId);
        resolvedAlert.setStatus(Alert.Status.RESOLVED);

        when(alertRepository.findFirstByProjectIdAndFingerprintAndStatusNot(eq(projectId), eq(fingerprint), eq(Alert.Status.RESOLVED)))
                .thenReturn(Optional.empty());
        when(alertRepository.findFirstByProjectIdAndFingerprintAndStatusOrderByResolvedAtDesc(eq(projectId), eq(fingerprint), eq(Alert.Status.RESOLVED)))
                .thenReturn(Optional.of(resolvedAlert));

        when(alertRepository.save(any(Alert.class))).thenAnswer(i -> i.getArgument(0));

        Alert newAlert = alertService.createOrUpdateAlert(
                projectId,
                workItemId,
                "TASK_BLOCKED",
                Alert.Severity.HIGH,
                "Recurring blocked task",
                Map.of(),
                Map.of()
        );

        assertThat(newAlert.getPreviousAlertId()).isEqualTo(resolvedAlertId);
        assertThat(newAlert.getId()).isNotEqualTo(resolvedAlertId);
    }

    @Test
    @DisplayName("updateAlertStatus transitions NEW -> ACKNOWLEDGED and sets acknowledgedBy")
    void transitionToAcknowledged() {
        UUID alertId = UUID.randomUUID();
        Alert alert = new Alert();
        alert.setId(alertId);
        alert.setProjectId(projectId);
        alert.setStatus(Alert.Status.NEW);
        alert.setVersion(0L);

        when(alertRepository.findByIdAndProjectId(alertId, projectId)).thenReturn(Optional.of(alert));
        when(alertRepository.save(any(Alert.class))).thenAnswer(i -> i.getArgument(0));

        UpdateAlertStatusRequest req = new UpdateAlertStatusRequest(Alert.Status.ACKNOWLEDGED, null, 0L);
        AlertResponse res = alertService.updateAlertStatus(pmPrincipal, workspaceId, projectId, alertId, req);

        assertThat(res.status()).isEqualTo(Alert.Status.ACKNOWLEDGED);
        assertThat(res.acknowledgedBy()).isEqualTo(pmPrincipal.userId());
        verify(accessPolicy).requireProjectRole(pmPrincipal, workspaceId, projectId, ProjectRole.PM);
    }

    @Test
    @DisplayName("updateAlertStatus transitions ACKNOWLEDGED -> IN_PROGRESS and assigns owner")
    void transitionToInProgress() {
        UUID alertId = UUID.randomUUID();
        UUID devUserId = UUID.randomUUID();

        Alert alert = new Alert();
        alert.setId(alertId);
        alert.setProjectId(projectId);
        alert.setStatus(Alert.Status.ACKNOWLEDGED);
        alert.setAcknowledgedBy(pmPrincipal.userId());
        alert.setVersion(1L);

        when(alertRepository.findByIdAndProjectId(alertId, projectId)).thenReturn(Optional.of(alert));
        when(alertRepository.save(any(Alert.class))).thenAnswer(i -> i.getArgument(0));

        UpdateAlertStatusRequest req = new UpdateAlertStatusRequest(Alert.Status.IN_PROGRESS, devUserId, 1L);
        AlertResponse res = alertService.updateAlertStatus(pmPrincipal, workspaceId, projectId, alertId, req);

        assertThat(res.status()).isEqualTo(Alert.Status.IN_PROGRESS);
        assertThat(res.assignedTo()).isEqualTo(devUserId);
    }

    @Test
    @DisplayName("updateAlertStatus transitions to RESOLVED and sets resolvedAt")
    void transitionToResolved() {
        UUID alertId = UUID.randomUUID();
        Alert alert = new Alert();
        alert.setId(alertId);
        alert.setProjectId(projectId);
        alert.setStatus(Alert.Status.IN_PROGRESS);
        alert.setVersion(2L);

        when(alertRepository.findByIdAndProjectId(alertId, projectId)).thenReturn(Optional.of(alert));
        when(alertRepository.save(any(Alert.class))).thenAnswer(i -> i.getArgument(0));

        UpdateAlertStatusRequest req = new UpdateAlertStatusRequest(Alert.Status.RESOLVED, null, 2L);
        AlertResponse res = alertService.updateAlertStatus(pmPrincipal, workspaceId, projectId, alertId, req);

        assertThat(res.status()).isEqualTo(Alert.Status.RESOLVED);
        assertThat(res.resolvedAt()).isNotNull();
    }

    @Test
    @DisplayName("updateAlertStatus rejects backward transition to NEW")
    void rejectTransitionBackToNew() {
        UUID alertId = UUID.randomUUID();
        Alert alert = new Alert();
        alert.setId(alertId);
        alert.setProjectId(projectId);
        alert.setStatus(Alert.Status.ACKNOWLEDGED);
        alert.setVersion(1L);

        when(alertRepository.findByIdAndProjectId(alertId, projectId)).thenReturn(Optional.of(alert));

        UpdateAlertStatusRequest req = new UpdateAlertStatusRequest(Alert.Status.NEW, null, 1L);
        assertThatThrownBy(() -> alertService.updateAlertStatus(pmPrincipal, workspaceId, projectId, alertId, req))
                .isInstanceOf(AlertException.class)
                .hasMessageContaining("Cannot transition back to NEW");
    }

    @Test
    @DisplayName("updateAlertStatus rejects reopening a RESOLVED alert")
    void rejectReopeningResolvedAlert() {
        UUID alertId = UUID.randomUUID();
        Alert alert = new Alert();
        alert.setId(alertId);
        alert.setProjectId(projectId);
        alert.setStatus(Alert.Status.RESOLVED);
        alert.setVersion(3L);

        when(alertRepository.findByIdAndProjectId(alertId, projectId)).thenReturn(Optional.of(alert));

        UpdateAlertStatusRequest req = new UpdateAlertStatusRequest(Alert.Status.ACKNOWLEDGED, null, 3L);
        assertThatThrownBy(() -> alertService.updateAlertStatus(pmPrincipal, workspaceId, projectId, alertId, req))
                .isInstanceOf(AlertException.class)
                .hasMessageContaining("Resolved alert cannot be reopened");
    }

    @Test
    @DisplayName("updateAlertStatus detects version conflict when expectedVersion mismatches")
    void versionConflict() {
        UUID alertId = UUID.randomUUID();
        Alert alert = new Alert();
        alert.setId(alertId);
        alert.setProjectId(projectId);
        alert.setStatus(Alert.Status.NEW);
        alert.setVersion(5L);

        when(alertRepository.findByIdAndProjectId(alertId, projectId)).thenReturn(Optional.of(alert));

        UpdateAlertStatusRequest req = new UpdateAlertStatusRequest(Alert.Status.ACKNOWLEDGED, null, 4L); // Expected 4, actual 5!
        assertThatThrownBy(() -> alertService.updateAlertStatus(pmPrincipal, workspaceId, projectId, alertId, req))
                .isInstanceOf(AlertException.class)
                .hasMessageContaining("Alert version conflict");
    }
}

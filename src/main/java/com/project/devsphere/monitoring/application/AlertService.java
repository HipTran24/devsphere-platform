package com.project.devsphere.monitoring.application;

import com.project.devsphere.identity.application.AccessPolicy;
import com.project.devsphere.identity.application.SessionService;
import com.project.devsphere.identity.domain.ProjectRole;
import com.project.devsphere.monitoring.api.dto.AlertResponse;
import com.project.devsphere.monitoring.api.dto.UpdateAlertStatusRequest;
import com.project.devsphere.monitoring.domain.Alert;
import com.project.devsphere.monitoring.infrastructure.persistence.AlertRepository;
import com.project.devsphere.realtime.application.RealtimeEventPublisher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;

@Service
public class AlertService {

    private final AlertRepository alertRepository;
    private final AccessPolicy accessPolicy;
    private final ObjectProvider<RealtimeEventPublisher> realtimeEventPublisher;
    private final ObjectProvider<JdbcTemplate> jdbcTemplate;

    public AlertService(AlertRepository alertRepository, AccessPolicy accessPolicy) {
        this(alertRepository, accessPolicy, null, null);
    }

    @Autowired
    public AlertService(
            AlertRepository alertRepository,
            AccessPolicy accessPolicy,
            ObjectProvider<RealtimeEventPublisher> realtimeEventPublisher,
            ObjectProvider<JdbcTemplate> jdbcTemplate) {
        this.alertRepository = Objects.requireNonNull(alertRepository, "alertRepository must not be null");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy must not be null");
        this.realtimeEventPublisher = realtimeEventPublisher;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Computes the deterministic SHA-256 fingerprint for alert deduplication.
     * Pattern: SHA-256(projectId:alertCode:(workItemId != null ? workItemId : "project"))
     * Returns 64 lowercase hex characters matching ^[0-9a-f]{64}$.
     */
    public String computeFingerprint(UUID projectId, String alertCode, UUID workItemId) {
        String subject = workItemId != null ? workItemId.toString() : "project";
        String raw = projectId.toString() + ":" + alertCode + ":" + subject;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Deduplicates or creates an alert.
     * If an open alert exists (status <> 'RESOLVED') for the (projectId, fingerprint),
     * updates evidence, impact snapshot, and escalates severity monotonically if needed.
     * If no open alert exists, checks for the last resolved alert to chain recurrence,
     * then inserts a new alert with status NEW.
     */
    @Transactional
    public Alert createOrUpdateAlert(
            UUID projectId,
            UUID workItemId,
            String alertCode,
            Alert.Severity severity,
            String title,
            Map<String, Object> evidence,
            Map<String, Object> impactSnapshot) {

        String fingerprint = computeFingerprint(projectId, alertCode, workItemId);

        // Check for existing open alert matching uq_open_alert_fingerprint
        Optional<Alert> openOpt = alertRepository.findFirstByProjectIdAndFingerprintAndStatusNot(
                projectId, fingerprint, Alert.Status.RESOLVED);

        if (openOpt.isPresent()) {
            Alert existing = openOpt.get();
            if (evidence != null && !evidence.isEmpty()) {
                existing.setEvidence(new LinkedHashMap<>(evidence));
            }
            if (impactSnapshot != null && !impactSnapshot.isEmpty()) {
                existing.setImpactSnapshot(new LinkedHashMap<>(impactSnapshot));
            }
            // Monotonic severity escalation: LOW < MEDIUM < HIGH < CRITICAL
            if (severity != null && severity.compareTo(existing.getSeverity()) > 0) {
                existing.setSeverity(severity);
            }
            existing.setUpdatedAt(Instant.now());
            Alert saved = alertRepository.save(existing);
            publishAlertEvent(resolveWorkspaceId(projectId), projectId, RealtimeEventPublisher.EVENT_ALERT_UPSERTED, saved);
            return saved;
        }

        // Recurrence chaining: lookup last resolved alert with the same fingerprint
        Optional<Alert> lastResolved = alertRepository.findFirstByProjectIdAndFingerprintAndStatusOrderByResolvedAtDesc(
                projectId, fingerprint, Alert.Status.RESOLVED);

        UUID previousAlertId = lastResolved.map(Alert::getId).orElse(null);

        Alert newAlert = new Alert();
        newAlert.setProjectId(projectId);
        newAlert.setWorkItemId(workItemId);
        newAlert.setPreviousAlertId(previousAlertId);
        newAlert.setFingerprint(fingerprint);
        newAlert.setAlertCode(alertCode);
        newAlert.setSeverity(severity != null ? severity : Alert.Severity.MEDIUM);
        newAlert.setStatus(Alert.Status.NEW);
        newAlert.setTitle(title != null ? title : ("Alert: " + alertCode));
        newAlert.setEvidence(evidence != null ? new LinkedHashMap<>(evidence) : new LinkedHashMap<>(Map.of("schemaVersion", 1)));
        newAlert.setImpactSnapshot(impactSnapshot != null ? new LinkedHashMap<>(impactSnapshot) : new LinkedHashMap<>(Map.of("schemaVersion", 1)));
        newAlert.setDetectedAt(Instant.now());
        newAlert.setCreatedAt(Instant.now());
        newAlert.setUpdatedAt(Instant.now());

        Alert saved = alertRepository.save(newAlert);
        publishAlertEvent(resolveWorkspaceId(projectId), projectId, RealtimeEventPublisher.EVENT_ALERT_UPSERTED, saved);
        return saved;
    }

    /**
     * Updates alert lifecycle status and assignee.
     * Guarded by PM role. Enforces optimistic concurrency and valid transitions.
     */
    @Transactional
    public AlertResponse updateAlertStatus(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UUID alertId,
            UpdateAlertStatusRequest request) {

        accessPolicy.requireProjectRole(principal, workspaceId, projectId, ProjectRole.PM);

        if (request == null || request.status() == null) {
            throw AlertException.validationFailed("Request and status must not be null");
        }

        Alert alert = alertRepository.findByIdAndProjectId(alertId, projectId)
                .orElseThrow(() -> AlertException.notFound("Alert not found: " + alertId));

        if (request.expectedVersion() != null && !Objects.equals(alert.getVersion(), request.expectedVersion())) {
            throw AlertException.versionConflict("Alert version conflict. Expected: " + request.expectedVersion() + ", actual: " + alert.getVersion());
        }

        Alert.Status current = alert.getStatus();
        Alert.Status target = request.status();

        if (current == Alert.Status.RESOLVED && target != Alert.Status.RESOLVED) {
            throw AlertException.invalidTransition("Resolved alert cannot be reopened; new condition will create a new alert");
        }

        if (current != target) {
            switch (target) {
                case ACKNOWLEDGED -> {
                    if (current != Alert.Status.NEW) {
                        throw AlertException.invalidTransition("Cannot transition from " + current + " to ACKNOWLEDGED");
                    }
                    alert.setStatus(Alert.Status.ACKNOWLEDGED);
                    alert.setAcknowledgedBy(principal.userId());
                }
                case IN_PROGRESS -> {
                    if (current != Alert.Status.ACKNOWLEDGED && current != Alert.Status.NEW) {
                        throw AlertException.invalidTransition("Cannot transition from " + current + " to IN_PROGRESS");
                    }
                    alert.setStatus(Alert.Status.IN_PROGRESS);
                    if (alert.getAcknowledgedBy() == null) {
                        alert.setAcknowledgedBy(principal.userId());
                    }
                    if (request.assignedTo() != null) {
                        alert.setAssignedTo(request.assignedTo());
                    }
                }
                case RESOLVED -> {
                    alert.setStatus(Alert.Status.RESOLVED);
                    alert.setResolvedAt(Instant.now());
                }
                case NEW -> throw AlertException.invalidTransition("Cannot transition back to NEW");
            }
        } else {
            if (request.assignedTo() != null) {
                alert.setAssignedTo(request.assignedTo());
            }
        }

        alert.setUpdatedAt(Instant.now());
        Alert saved = alertRepository.save(alert);
        AlertResponse response = AlertResponse.from(saved);
        publishAlertResponse(workspaceId, projectId, RealtimeEventPublisher.EVENT_ALERT_UPDATED, response);
        return response;
    }

    /**
     * Lists alerts for a project with optional filters.
     * Accessible by internal project members (PM, DEVELOPER, VIEWER). CLIENT-only is denied.
     */
    @Transactional(readOnly = true)
    public List<AlertResponse> listAlerts(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            Alert.Status status,
            Alert.Severity severity,
            UUID workItemId) {

        accessPolicy.requireAnyProjectRole(principal, workspaceId, projectId, Set.of(ProjectRole.PM, ProjectRole.DEVELOPER, ProjectRole.VIEWER));

        List<Alert> alerts = alertRepository.findByProjectIdOrderByDetectedAtDesc(projectId);
        return alerts.stream()
                .filter(a -> status == null || a.getStatus() == status)
                .filter(a -> severity == null || a.getSeverity() == severity)
                .filter(a -> workItemId == null || Objects.equals(a.getWorkItemId(), workItemId))
                .map(AlertResponse::from)
                .toList();
    }

    /**
     * Retrieves a single alert by ID.
     * Guarded by internal project role. CLIENT-only is denied.
     */
    @Transactional(readOnly = true)
    public AlertResponse getAlert(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UUID alertId) {

        accessPolicy.requireAnyProjectRole(principal, workspaceId, projectId, Set.of(ProjectRole.PM, ProjectRole.DEVELOPER, ProjectRole.VIEWER));

        Alert alert = alertRepository.findByIdAndProjectId(alertId, projectId)
                .orElseThrow(() -> AlertException.notFound("Alert not found: " + alertId));

        return AlertResponse.from(alert);
    }

    /**
     * Retrieves the chained recurrence history of an alert via previous_alert_id.
     * Guarded by internal project role. CLIENT-only is denied.
     */
    @Transactional(readOnly = true)
    public List<AlertResponse> getAlertHistory(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UUID alertId) {

        accessPolicy.requireAnyProjectRole(principal, workspaceId, projectId, Set.of(ProjectRole.PM, ProjectRole.DEVELOPER, ProjectRole.VIEWER));

        Alert current = alertRepository.findByIdAndProjectId(alertId, projectId)
                .orElseThrow(() -> AlertException.notFound("Alert not found: " + alertId));

        List<AlertResponse> history = new ArrayList<>();
        history.add(AlertResponse.from(current));

        Set<UUID> visited = new HashSet<>();
        visited.add(current.getId());

        UUID nextId = current.getPreviousAlertId();
        while (nextId != null && !visited.contains(nextId)) {
            visited.add(nextId);
            Optional<Alert> prevOpt = alertRepository.findByIdAndProjectId(nextId, projectId);
            if (prevOpt.isEmpty()) {
                break;
            }
            Alert prev = prevOpt.get();
            history.add(AlertResponse.from(prev));
            nextId = prev.getPreviousAlertId();
        }

        return history;
    }

    private UUID resolveWorkspaceId(UUID projectId) {
        JdbcTemplate jdbc = jdbcTemplate == null ? null : jdbcTemplate.getIfAvailable();
        if (jdbc == null || projectId == null) {
            return null;
        }
        List<UUID> workspaceIds = jdbc.query(
                "SELECT workspace_id FROM projects WHERE id = ?",
                (rs, rowNum) -> rs.getObject("workspace_id", UUID.class),
                projectId
        );
        return workspaceIds.isEmpty() ? null : workspaceIds.get(0);
    }

    private void publishAlertEvent(UUID workspaceId, UUID projectId, String eventType, Alert saved) {
        if (saved == null) {
            return;
        }
        publishAlertResponse(workspaceId, projectId, eventType, AlertResponse.from(saved));
    }

    private void publishAlertResponse(UUID workspaceId, UUID projectId, String eventType, AlertResponse response) {
        RealtimeEventPublisher publisher = realtimeEventPublisher == null ? null : realtimeEventPublisher.getIfAvailable();
        if (publisher == null || workspaceId == null || projectId == null || response == null) {
            return;
        }
        publisher.publishAlert(workspaceId, projectId, eventType, response);
    }
}

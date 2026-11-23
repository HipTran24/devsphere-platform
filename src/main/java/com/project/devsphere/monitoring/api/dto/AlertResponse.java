package com.project.devsphere.monitoring.api.dto;

import com.project.devsphere.monitoring.domain.Alert;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AlertResponse(
        UUID id,
        UUID projectId,
        UUID workItemId,
        UUID previousAlertId,
        String fingerprint,
        String alertCode,
        Alert.Severity severity,
        Alert.Status status,
        String title,
        Map<String, Object> evidence,
        Map<String, Object> impactSnapshot,
        UUID assignedTo,
        UUID acknowledgedBy,
        Instant detectedAt,
        Instant resolvedAt,
        Instant createdAt,
        Instant updatedAt,
        Long version
) {
    public static AlertResponse from(Alert alert) {
        return new AlertResponse(
                alert.getId(),
                alert.getProjectId(),
                alert.getWorkItemId(),
                alert.getPreviousAlertId(),
                alert.getFingerprint(),
                alert.getAlertCode(),
                alert.getSeverity(),
                alert.getStatus(),
                alert.getTitle(),
                alert.getEvidence(),
                alert.getImpactSnapshot(),
                alert.getAssignedTo(),
                alert.getAcknowledgedBy(),
                alert.getDetectedAt(),
                alert.getResolvedAt(),
                alert.getCreatedAt(),
                alert.getUpdatedAt(),
                alert.getVersion()
        );
    }
}

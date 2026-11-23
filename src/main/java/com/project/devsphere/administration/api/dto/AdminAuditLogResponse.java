package com.project.devsphere.administration.api.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AdminAuditLogResponse(
        UUID id,
        UUID workspaceId,
        UUID projectId,
        UUID actorId,
        String action,
        String entityType,
        UUID entityId,
        Map<String, Object> safeDetails,
        Instant occurredAt,
        String traceId
) {
}

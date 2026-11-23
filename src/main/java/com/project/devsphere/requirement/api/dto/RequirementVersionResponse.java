package com.project.devsphere.requirement.api.dto;

import java.time.Instant;
import java.util.UUID;

public record RequirementVersionResponse(
        UUID id,
        UUID requirementId,
        Integer versionNo,
        String description,
        String acceptanceSummary,
        String changeReason,
        Instant approvedAt,
        Instant createdAt
) {
}

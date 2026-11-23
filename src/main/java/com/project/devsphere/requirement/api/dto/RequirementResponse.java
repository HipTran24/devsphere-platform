package com.project.devsphere.requirement.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RequirementResponse(
        UUID id,
        UUID projectId,
        String code,
        String kind,
        UUID parentRequirementId,
        String title,
        String description,
        String status,
        Integer currentVersion,
        UUID requestedBy,
        UUID approvedBy,
        String acceptanceSummary,
        Instant approvedAt,
        List<RequirementVersionResponse> versions,
        Instant createdAt,
        Instant updatedAt,
        Long version
) {
}

package com.project.devsphere.requirement.api.dto;

import java.time.Instant;
import java.util.UUID;

public record ChangeRequestResponse(
        UUID id,
        UUID projectId,
        String code,
        String title,
        String description,
        String status,
        String changeRequestStatus,
        UUID targetRequirementId,
        UUID requestedBy,
        UUID approvedBy,
        String decisionReason,
        UUID proposalId,
        Instant createdAt,
        Instant updatedAt,
        Long version
) {
}

package com.project.devsphere.planning.api.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ApplyPlanResponse(
        UUID proposalId,
        UUID scheduleVersionId,
        Integer versionNo,
        int createdWorkItems,
        int updatedWorkItems,
        LocalDate committedDeadline,
        Instant appliedAt
) {
}

package com.project.devsphere.workitem.api.dto;

import com.project.devsphere.workitem.domain.WorkItem;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record UpdateWorkItemRequest(
        String title,
        String description,
        WorkItem.Priority priority,
        BigDecimal storyPoints,
        Integer estimateMinutes,
        Integer remainingMinutes,
        String[] labels,
        UUID parentId,
        UUID sprintId,
        UUID milestoneId,
        UUID requirementId,
        Instant dueAt,

        @NotNull(message = "expectedVersion is required")
        @Min(value = 0, message = "expectedVersion must be non-negative")
        Long expectedVersion
) {
}

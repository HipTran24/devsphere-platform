package com.project.devsphere.workitem.api.dto;

import com.project.devsphere.workitem.domain.WorkItem;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CreateWorkItemRequest(
        @NotNull(message = "Type is required")
        WorkItem.Type type,

        @NotBlank(message = "Title must not be blank")
        @Size(max = 255, message = "Title must not exceed 255 characters")
        String title,

        String description,
        UUID parentId,
        UUID requirementId,
        UUID sprintId,
        UUID milestoneId,
        WorkItem.Priority priority,
        BigDecimal storyPoints,
        Integer estimateMinutes,
        String[] labels,
        UUID assigneeId,
        Instant dueAt
) {
    public CreateWorkItemRequest {
        if (description == null) {
            description = "";
        }
        if (priority == null) {
            priority = WorkItem.Priority.MEDIUM;
        }
        if (labels == null) {
            labels = new String[0];
        }
    }
}

package com.project.devsphere.workitem.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.project.devsphere.workitem.domain.WorkItem;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkItemResponse(
        UUID id,
        UUID projectId,
        String itemKey,
        UUID parentId,
        UUID requirementId,
        UUID sprintId,
        UUID milestoneId,
        WorkItem.Type type,
        WorkItem.WorkKind workKind,
        String title,
        String description,
        WorkItem.Status status,
        Boolean isBlocked,
        String blockedReason,
        WorkItem.Priority priority,
        UUID assigneeId,
        String[] labels,
        BigDecimal backlogRank,
        BigDecimal storyPoints,
        Integer estimateMinutes,
        Integer remainingMinutes,
        Integer timeSpentMinutes,
        BigDecimal claimedProgress,
        BigDecimal verifiedProgress,
        Instant startedAt,
        Instant completedAt,
        Instant dueAt,
        UUID acceptedBy,
        Instant createdAt,
        Instant updatedAt,
        Long version
) {
}

package com.project.devsphere.sprint.api.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record SprintProgressResponse(
        UUID sprintId,
        String sprintName,
        String status,
        BigDecimal committedPoints,
        BigDecimal completedPoints,
        BigDecimal remainingPoints,
        int totalItems,
        int doneItems,
        int inProgressItems,
        int inReviewItems,
        int toDoItems,
        int totalEstimateMinutes,
        int totalRemainingMinutes,
        int totalSpentMinutes,
        int blockedCount
) {
}

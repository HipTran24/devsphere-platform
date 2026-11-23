package com.project.devsphere.clientportal.api.dto;

import java.time.Instant;
import java.util.UUID;

public record MilestoneReviewReceiptResponse(
        UUID milestoneId,
        String decision,
        String status,
        String acceptanceStatus,
        UUID reviewedBy,
        Instant reviewedAt,
        String comments
) {
}

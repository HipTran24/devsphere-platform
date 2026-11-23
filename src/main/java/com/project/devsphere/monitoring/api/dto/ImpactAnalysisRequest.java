package com.project.devsphere.monitoring.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record ImpactAnalysisRequest(
        @NotNull(message = "workItemId is required")
        UUID workItemId,

        @NotNull(message = "delayMinutes is required")
        @Min(value = 1, message = "delayMinutes must be positive")
        Integer delayMinutes,

        String reason
) {
    public ImpactAnalysisRequest(UUID workItemId, Integer delayMinutes) {
        this(workItemId, delayMinutes, null);
    }
}

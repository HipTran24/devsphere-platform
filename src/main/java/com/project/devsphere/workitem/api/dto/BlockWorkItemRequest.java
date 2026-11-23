package com.project.devsphere.workitem.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record BlockWorkItemRequest(
        @NotNull(message = "isBlocked is required")
        Boolean isBlocked,

        String blockedReason,

        @Min(value = 0, message = "expectedVersion must be non-negative")
        Long expectedVersion
) {
}

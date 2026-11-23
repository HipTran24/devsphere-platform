package com.project.devsphere.workitem.api.dto;

import com.project.devsphere.workitem.domain.WorkItem;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record TransitionStatusRequest(
        @NotNull(message = "status is required")
        WorkItem.Status status,

        @Min(value = 0, message = "expectedVersion must be non-negative")
        Long expectedVersion
) {
}

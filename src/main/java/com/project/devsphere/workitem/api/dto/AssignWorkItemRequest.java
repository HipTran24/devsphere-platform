package com.project.devsphere.workitem.api.dto;

import jakarta.validation.constraints.Min;
import java.util.UUID;

public record AssignWorkItemRequest(
        UUID assigneeId,
        @Min(value = 0, message = "expectedVersion must be non-negative")
        Long expectedVersion
) {
}

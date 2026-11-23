package com.project.devsphere.workitem.api.dto;

import jakarta.validation.constraints.Min;

public record RejectWorkItemRequest(
        String rejectionReason,
        @Min(value = 0, message = "expectedVersion must be non-negative")
        Long expectedVersion
) {
}

package com.project.devsphere.project.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record AcceptPmRequest(
        @NotNull(message = "expectedVersion is required")
        @Min(value = 0, message = "expectedVersion must be non-negative")
        Long expectedVersion
) {
}

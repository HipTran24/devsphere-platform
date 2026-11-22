package com.project.devsphere.administration.api.dto;

import com.project.devsphere.identity.domain.User;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record UserStatusUpdateRequest(
        @NotNull(message = "Status is required")
        User.Status status,

        @NotNull(message = "Expected version is required")
        @Min(value = 0, message = "Expected version must be non-negative")
        Long expectedVersion
) {
}

package com.project.devsphere.project.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record GrantRoleRequest(
        @NotNull(message = "userId is required")
        UUID userId,

        @NotBlank(message = "role is required")
        String role
) {
}

package com.project.devsphere.workspace.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record TransferOwnershipRequest(
        @NotNull(message = "targetUserId is required")
        UUID targetUserId,

        @NotNull(message = "expectedVersion is required")
        @Min(value = 0, message = "expectedVersion must be non-negative")
        Long expectedVersion,

        String reason
) {
}

package com.project.devsphere.workitem.api.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CreateDependencyRequest(
        @NotNull(message = "predecessorId is required")
        UUID predecessorId,

        @NotNull(message = "successorId is required")
        UUID successorId,

        String linkType,
        Integer lagMinutes
) {
    public CreateDependencyRequest {
        if (linkType == null || linkType.isBlank()) {
            linkType = "FINISH_TO_START";
        }
        if (lagMinutes == null) {
            lagMinutes = 0;
        }
    }
}

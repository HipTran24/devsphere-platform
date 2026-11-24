package com.project.devsphere.ai.api.dto;

import jakarta.validation.constraints.NotNull;
import java.util.Map;

public record UpdateAiRoutesRequest(
        @NotNull(message = "routes must not be null")
        Map<String, String> routes
) {
}

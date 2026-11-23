package com.project.devsphere.ai.api.dto;

import jakarta.validation.constraints.NotNull;
import java.util.Map;

public record CompleteAiJobRequest(
        @NotNull(message = "outputJson must not be null")
        Map<String, Object> outputJson,

        Integer tokensIn,
        Integer tokensOut,
        String validatedByModel
) {
}

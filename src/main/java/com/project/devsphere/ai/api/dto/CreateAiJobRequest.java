package com.project.devsphere.ai.api.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;

public record CreateAiJobRequest(
        @NotBlank(message = "jobType must not be blank")
        String jobType,

        String modelName,
        String connectionId,
        String promptVersion,
        String outputSchemaVersion,
        Map<String, Object> inputRefs,
        Map<String, Object> outputJson,
        Integer tokensIn,
        Integer tokensOut,
        Boolean autoValidate
) {
}

package com.project.devsphere.ai.api.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateAiPrivacyRequest(
        @NotBlank(message = "privacyPolicy must not be blank")
        String privacyPolicy
) {
}

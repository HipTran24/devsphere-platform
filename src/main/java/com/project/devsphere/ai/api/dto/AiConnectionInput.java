package com.project.devsphere.ai.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AiConnectionInput(
        @NotBlank(message = "connection id must not be blank")
        @Size(max = 80, message = "connection id must be at most 80 characters")
        String id,

        @NotBlank(message = "provider must not be blank")
        String provider,

        @NotBlank(message = "baseUrl must not be blank")
        String baseUrl,

        @NotBlank(message = "model must not be blank")
        @Size(max = 140, message = "model must be at most 140 characters")
        String model,

        Boolean enabled,
        String apiKey
) {
    @Override
    public String toString() {
        return "AiConnectionInput[id=" + id
                + ", provider=" + provider
                + ", baseUrl=" + baseUrl
                + ", model=" + model
                + ", enabled=" + enabled
                + ", apiKeyConfigured=" + (apiKey != null && !apiKey.isBlank()) + "]";
    }
}

package com.project.devsphere.ai.api.dto;

public record AiConnectionSummaryResponse(
        String id,
        String provider,
        String baseUrl,
        String model,
        boolean enabled,
        boolean apiKeyConfigured
) {
}

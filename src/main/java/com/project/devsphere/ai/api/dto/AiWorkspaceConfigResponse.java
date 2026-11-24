package com.project.devsphere.ai.api.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record AiWorkspaceConfigResponse(
        UUID workspaceId,
        String privacyPolicy,
        List<AiConnectionSummaryResponse> connections,
        Map<String, String> routes
) {
}

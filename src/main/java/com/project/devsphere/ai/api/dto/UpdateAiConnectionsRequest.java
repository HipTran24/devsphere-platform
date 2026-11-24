package com.project.devsphere.ai.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record UpdateAiConnectionsRequest(
        @NotNull(message = "connections must not be null")
        @Valid
        List<AiConnectionInput> connections
) {
}

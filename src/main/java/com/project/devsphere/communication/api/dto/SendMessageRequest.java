package com.project.devsphere.communication.api.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.UUID;

public record SendMessageRequest(
        @NotBlank String body,
        List<UUID> mentions,
        UUID replyToId
) {}

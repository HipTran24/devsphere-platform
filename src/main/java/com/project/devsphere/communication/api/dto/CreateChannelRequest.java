package com.project.devsphere.communication.api.dto;

import com.project.devsphere.communication.domain.ChatChannel;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreateChannelRequest(
        @NotNull ChatChannel.Type type,
        String name,
        UUID workItemId
) {}

package com.project.devsphere.communication.api.dto;

import com.project.devsphere.communication.domain.ChatChannel;
import java.time.Instant;
import java.util.UUID;

public record ChannelResponse(
        UUID id,
        UUID workspaceId,
        UUID projectId,
        UUID workItemId,
        ChatChannel.Type type,
        String name,
        UUID createdBy,
        Instant createdAt
) {}

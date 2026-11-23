package com.project.devsphere.communication.api.dto;

import com.project.devsphere.communication.domain.ChatChannel;

import java.time.Instant;
import java.util.UUID;

public record ChatChannelResponse(
        UUID id,
        UUID workspaceId,
        UUID projectId,
        UUID workItemId,
        ChatChannel.Type type,
        String name,
        UUID createdBy,
        Instant archivedAt,
        Instant createdAt,
        Instant updatedAt,
        Long version
) {
    public static ChatChannelResponse from(ChatChannel c) {
        return new ChatChannelResponse(
                c.getId(),
                c.getWorkspaceId(),
                c.getProjectId(),
                c.getWorkItemId(),
                c.getType(),
                c.getName(),
                c.getCreatedBy(),
                c.getArchivedAt(),
                c.getCreatedAt(),
                c.getUpdatedAt(),
                c.getVersion()
        );
    }
}

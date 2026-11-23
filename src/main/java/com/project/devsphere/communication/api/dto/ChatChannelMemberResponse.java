package com.project.devsphere.communication.api.dto;

import com.project.devsphere.communication.domain.ChatChannelMember;

import java.time.Instant;
import java.util.UUID;

public record ChatChannelMemberResponse(
        UUID id,
        UUID workspaceId,
        UUID projectId,
        UUID channelId,
        UUID userId,
        Instant joinedAt,
        Boolean muted,
        Instant createdAt,
        Instant updatedAt,
        Long version
) {
    public static ChatChannelMemberResponse from(ChatChannelMember m) {
        return new ChatChannelMemberResponse(
                m.getId(),
                m.getWorkspaceId(),
                m.getProjectId(),
                m.getChannelId(),
                m.getUserId(),
                m.getJoinedAt(),
                m.getMuted(),
                m.getCreatedAt(),
                m.getUpdatedAt(),
                m.getVersion()
        );
    }
}

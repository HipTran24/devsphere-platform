package com.project.devsphere.communication.api.dto;

import com.project.devsphere.communication.domain.ChatMessage;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public record ChatMessageResponse(
        UUID id,
        UUID workspaceId,
        UUID projectId,
        UUID channelId,
        UUID senderId,
        ChatMessage.SenderKind senderKind,
        UUID replyToId,
        String body,
        List<UUID> mentions,
        Instant sentAt,
        Instant editedAt,
        Instant deletedAt,
        Instant createdAt,
        Long version
) {
    public static ChatMessageResponse from(ChatMessage m) {
        return new ChatMessageResponse(
                m.getId(),
                m.getWorkspaceId(),
                m.getProjectId(),
                m.getChannelId(),
                m.getSenderId(),
                m.getSenderKind(),
                m.getReplyToId(),
                m.getBody(),
                m.getMentions() != null ? Arrays.asList(m.getMentions()) : List.of(),
                m.getSentAt(),
                m.getEditedAt(),
                m.getDeletedAt(),
                m.getCreatedAt(),
                m.getVersion()
        );
    }
}

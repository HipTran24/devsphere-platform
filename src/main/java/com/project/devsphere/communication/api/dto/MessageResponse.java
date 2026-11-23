package com.project.devsphere.communication.api.dto;

import com.project.devsphere.communication.domain.ChatMessage;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record MessageResponse(
        UUID id,
        UUID workspaceId,
        UUID projectId,
        UUID channelId,
        UUID senderId,
        ChatMessage.SenderKind senderKind,
        String body,
        List<UUID> mentions,
        UUID replyToId,
        Instant sentAt
) {}

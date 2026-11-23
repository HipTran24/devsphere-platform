package com.project.devsphere.notification.api.dto;

import com.project.devsphere.notification.domain.Notification;
import java.time.Instant;
import java.util.UUID;

public record NotificationResponse(
        UUID id,
        UUID workspaceId,
        UUID projectId,
        String eventType,
        String eventKey,
        String title,
        String body,
        String deeplink,
        Instant readAt,
        Instant createdAt
) {
    public static NotificationResponse from(Notification n) {
        return new NotificationResponse(
                n.getId(),
                n.getWorkspaceId(),
                n.getProjectId(),
                n.getEventType(),
                n.getEventKey(),
                n.getTitle(),
                n.getBody(),
                n.getDeeplink(),
                n.getReadAt(),
                n.getCreatedAt()
        );
    }
}

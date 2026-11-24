package com.project.devsphere.realtime.application;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Publishes structured JSON event envelopes over STOMP destinations.
 * When an active Spring transaction synchronization exists, dispatch is deferred
 * to {@link TransactionSynchronization#afterCommit()} so rolled-back transactions
 * never leak uncommitted events.
 */
@Component
public class RealtimeEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(RealtimeEventPublisher.class);

    public static final String EVENT_CHAT_MESSAGE_CREATED = "CHAT_MESSAGE_CREATED";
    public static final String EVENT_NOTIFICATION_CREATED = "NOTIFICATION_CREATED";
    public static final String EVENT_ALERT_UPSERTED = "ALERT_UPSERTED";
    public static final String EVENT_ALERT_UPDATED = "ALERT_UPDATED";

    private final SimpMessagingTemplate messagingTemplate;

    public RealtimeEventPublisher(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = Objects.requireNonNull(messagingTemplate, "messagingTemplate must not be null");
    }

    public void publishChatMessage(UUID workspaceId, UUID projectId, UUID channelId, Object payload) {
        if (workspaceId == null || projectId == null || channelId == null) {
            return;
        }
        String destination = "/topic/workspaces/" + workspaceId + "/projects/" + projectId + "/chat/" + channelId;
        publishToTopic(destination, EVENT_CHAT_MESSAGE_CREATED, workspaceId, projectId, payload);
    }

    public void publishNotification(UUID userId, UUID workspaceId, UUID projectId, Object payload) {
        if (userId == null) {
            return;
        }
        publishToUser(userId, "/queue/notifications", EVENT_NOTIFICATION_CREATED, workspaceId, projectId, payload);
    }

    public void publishAlert(UUID workspaceId, UUID projectId, String eventType, Object payload) {
        if (workspaceId == null || projectId == null || eventType == null || eventType.isBlank()) {
            return;
        }
        publishControlTowerUpdate(workspaceId, projectId, eventType, payload);
    }

    public void publishControlTowerUpdate(UUID workspaceId, UUID projectId, String eventType, Object payload) {
        if (workspaceId == null || projectId == null || eventType == null || eventType.isBlank()) {
            return;
        }
        String destination = "/topic/workspaces/" + workspaceId + "/projects/" + projectId + "/control-tower";
        publishToTopic(destination, eventType, workspaceId, projectId, payload);
    }

    public void publishToTopic(
            String destination,
            String eventType,
            UUID workspaceId,
            UUID projectId,
            Object payload) {
        if (destination == null || destination.isBlank() || eventType == null || eventType.isBlank()) {
            return;
        }
        RealtimeEventEnvelope envelope = new RealtimeEventEnvelope(
                eventType,
                workspaceId,
                projectId,
                Instant.now(),
                payload
        );
        dispatchAfterCommit(() -> messagingTemplate.convertAndSend(destination, envelope));
    }

    public void publishToUser(
            UUID userId,
            String destination,
            String eventType,
            UUID workspaceId,
            UUID projectId,
            Object payload) {
        if (userId == null || destination == null || destination.isBlank() || eventType == null || eventType.isBlank()) {
            return;
        }
        RealtimeEventEnvelope envelope = new RealtimeEventEnvelope(
                eventType,
                workspaceId,
                projectId,
                Instant.now(),
                payload
        );
        String userKey = userId.toString();
        dispatchAfterCommit(() -> messagingTemplate.convertAndSendToUser(userKey, destination, envelope));
    }

    private void dispatchAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    safeRun(action);
                }
            });
        } else {
            safeRun(action);
        }
    }

    private void safeRun(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ex) {
            log.warn("Failed to dispatch real-time STOMP event: {}", ex.getMessage());
        }
    }
}

package com.project.devsphere.realtime.application;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Structured JSON envelope pushed over STOMP WebSocket destinations.
 */
public record RealtimeEventEnvelope(
        String eventType,
        UUID workspaceId,
        UUID projectId,
        Instant timestamp,
        Object payload
) {
    public RealtimeEventEnvelope {
        Objects.requireNonNull(eventType, "eventType must not be null");
        timestamp = timestamp != null ? timestamp : Instant.now();
    }
}

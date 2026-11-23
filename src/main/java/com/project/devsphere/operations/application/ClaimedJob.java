package com.project.devsphere.operations.application;

import com.project.devsphere.operations.domain.BackgroundJob;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Immutable view of a leased background job handed to a {@link JobHandler}. */
public record ClaimedJob(
        UUID id,
        BackgroundJob.JobKind kind,
        UUID workspaceId,
        UUID projectId,
        UUID requestedBy,
        String idempotencyKey,
        Map<String, Object> payload,
        int attempts) {

    public ClaimedJob {
        payload = payload == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }

    /** Required string attribute of the payload. */
    public String requireString(String name) {
        Object value = payload.get(name);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new NonRetryableJobException("Payload attribute '" + name + "' is required");
        }
        return text;
    }

    /** Required UUID attribute of the payload. */
    public UUID requireUuid(String name) {
        String text = requireString(name);
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException ex) {
            throw new NonRetryableJobException("Payload attribute '" + name + "' must be a UUID");
        }
    }

    public String optionalString(String name) {
        Object value = payload.get(name);
        return value instanceof String text && !text.isBlank() ? text : null;
    }
}

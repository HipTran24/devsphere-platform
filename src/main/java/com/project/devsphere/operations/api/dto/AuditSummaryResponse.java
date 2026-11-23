package com.project.devsphere.operations.api.dto;

import java.time.Instant;
import java.util.List;

/** Audit event counts grouped by action over a bounded time window. */
public record AuditSummaryResponse(
        Instant from,
        Instant to,
        long totalEvents,
        List<ActionCount> actions
) {
    public record ActionCount(String action, long count) {
    }
}

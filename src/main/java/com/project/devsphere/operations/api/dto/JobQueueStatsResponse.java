package com.project.devsphere.operations.api.dto;

import java.time.Instant;
import java.util.Map;

/** Background job counters grouped by kind and status, plus lease health. */
public record JobQueueStatsResponse(
        Map<String, Map<String, Long>> countsByKindAndStatus,
        Map<String, Long> totalsByStatus,
        long expiredLeases,
        long deadLettered,
        Instant oldestQueuedAt,
        Instant generatedAt
) {
}

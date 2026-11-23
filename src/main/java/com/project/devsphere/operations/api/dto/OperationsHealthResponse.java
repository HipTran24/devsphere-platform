package com.project.devsphere.operations.api.dto;

import java.time.Instant;

/**
 * Aggregated operations health. {@code status} is UP, DEGRADED (expired leases, dead letters or a
 * stopped scheduler) or DOWN (database unreachable).
 */
public record OperationsHealthResponse(
        String status,
        boolean databaseUp,
        boolean schedulerStarted,
        boolean schedulerInStandby,
        int scheduledJobs,
        long queuedJobs,
        long runningJobs,
        long failedJobs,
        long deadLetteredJobs,
        long expiredLeases,
        Instant checkedAt
) {
}

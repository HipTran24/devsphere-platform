package com.project.devsphere.operations.api.dto;

import com.project.devsphere.operations.domain.BackgroundJob;

import java.time.Instant;
import java.util.UUID;

/**
 * Operator view of a background job. Payload and result documents are deliberately omitted so the
 * admin API never echoes business data or encrypted material held in the queue.
 */
public record BackgroundJobResponse(
        UUID id,
        String jobKind,
        String status,
        UUID workspaceId,
        UUID projectId,
        UUID requestedBy,
        String idempotencyKey,
        int attempts,
        int maxAttempts,
        Instant scheduledAt,
        Instant startedAt,
        Instant finishedAt,
        String lockedBy,
        Instant lockedUntil,
        String lastError,
        Instant createdAt,
        Instant updatedAt
) {
    public static BackgroundJobResponse from(BackgroundJob job, int maxAttempts) {
        return new BackgroundJobResponse(
                job.getId(),
                job.getJobKind() == null ? null : job.getJobKind().name(),
                job.getStatus() == null ? null : job.getStatus().name(),
                job.getWorkspaceId(),
                job.getProjectId(),
                job.getRequestedBy(),
                job.getIdempotencyKey(),
                job.getAttempts() == null ? 0 : job.getAttempts(),
                maxAttempts,
                job.getScheduledAt(),
                job.getStartedAt(),
                job.getFinishedAt(),
                job.getLockedBy(),
                job.getLockedUntil(),
                job.getLastError(),
                job.getCreatedAt(),
                job.getUpdatedAt());
    }
}

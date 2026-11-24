package com.project.devsphere.gitintegration.api.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Summary returned after executing a live GitHub App REST sync for a connected repository.
 */
public record GitHubSyncSummaryResponse(
        UUID repositoryId,
        int prsSynced,
        int commitsSynced,
        int ciRunsSynced,
        int reviewsSynced,
        Instant syncedAt
) {
}

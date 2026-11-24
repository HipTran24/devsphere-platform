package com.project.devsphere.operations.application;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Deterministic summary returned after idempotently seeding demo data across the DevSphere schema.
 */
public record DemoSeedResultResponse(
        boolean seeded,
        String workspaceSlug,
        UUID workspaceId,
        String projectKey,
        UUID projectId,
        Map<String, UUID> users,
        Map<String, UUID> channels,
        UUID githubRepositoryId,
        UUID sprintId,
        UUID milestoneId,
        UUID planProposalId,
        Map<String, Integer> counts,
        Instant seededAt
) {
}

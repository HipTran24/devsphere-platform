package com.project.devsphere.gitintegration.api.dto;

import com.project.devsphere.gitintegration.domain.GitHubActivity;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record GitHubActivityResponse(
        UUID id,
        UUID projectId,
        UUID repositoryId,
        UUID workItemId,
        String kind,
        String githubObjectId,
        String commitSha,
        Integer prNumber,
        String prHeadSha,
        String actorLogin,
        String state,
        String title,
        Map<String, Object> payload,
        Instant happenedAt,
        Instant syncedAt,
        Instant createdAt,
        Instant updatedAt,
        Long version
) {
    public static GitHubActivityResponse from(GitHubActivity activity) {
        if (activity == null) {
            return null;
        }
        return new GitHubActivityResponse(
                activity.getId(),
                activity.getProjectId(),
                activity.getRepositoryId(),
                activity.getWorkItemId(),
                activity.getKind() != null ? activity.getKind().name() : null,
                activity.getGithubObjectId(),
                activity.getCommitSha(),
                activity.getPrNumber(),
                activity.getPrHeadSha(),
                activity.getActorLogin(),
                activity.getState(),
                activity.getTitle(),
                activity.getPayload(),
                activity.getHappenedAt(),
                activity.getSyncedAt(),
                activity.getCreatedAt(),
                activity.getUpdatedAt(),
                activity.getVersion()
        );
    }
}

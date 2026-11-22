package com.project.devsphere.gitintegration.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.project.devsphere.gitintegration.domain.GitHubRepository;

import java.time.Instant;
import java.util.UUID;

public record GitHubRepositoryResponse(
        UUID id,
        UUID workspaceId,
        UUID projectId,
        Long githubInstallationId,
        Long githubRepoId,
        String fullName,
        String defaultBranch,
        @JsonProperty("isPrivate")
        Boolean isPrivate,
        Boolean active,
        String syncCursor,
        Instant lastSyncedAt,
        Instant createdAt,
        Instant updatedAt,
        Long version
) {
    public static GitHubRepositoryResponse from(GitHubRepository repo) {
        if (repo == null) {
            return null;
        }
        return new GitHubRepositoryResponse(
                repo.getId(),
                repo.getWorkspaceId(),
                repo.getProjectId(),
                repo.getGithubInstallationId(),
                repo.getGithubRepoId(),
                repo.getFullName(),
                repo.getDefaultBranch(),
                repo.getPrivateRepository(),
                repo.getActive(),
                repo.getSyncCursor(),
                repo.getLastSyncedAt(),
                repo.getCreatedAt(),
                repo.getUpdatedAt(),
                repo.getVersion()
        );
    }
}

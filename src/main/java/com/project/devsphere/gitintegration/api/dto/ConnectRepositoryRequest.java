package com.project.devsphere.gitintegration.api.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.project.devsphere.gitintegration.application.ConnectRepositoryCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record ConnectRepositoryRequest(
        @NotNull(message = "githubInstallationId is required")
        @Positive(message = "githubInstallationId must be positive")
        Long githubInstallationId,

        @NotNull(message = "githubRepoId is required")
        @Positive(message = "githubRepoId must be positive")
        Long githubRepoId,

        @NotBlank(message = "fullName is required")
        String fullName,

        String defaultBranch,

        @JsonAlias({"private", "privateRepository"})
        @JsonProperty("isPrivate")
        Boolean isPrivate
) {
    public ConnectRepositoryCommand toCommand() {
        return new ConnectRepositoryCommand(
                githubInstallationId,
                githubRepoId,
                fullName,
                defaultBranch != null && !defaultBranch.isBlank() ? defaultBranch.trim() : "main",
                isPrivate != null ? isPrivate : true
        );
    }
}

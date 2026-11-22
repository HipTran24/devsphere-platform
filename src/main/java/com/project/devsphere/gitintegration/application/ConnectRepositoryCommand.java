package com.project.devsphere.gitintegration.application;

public record ConnectRepositoryCommand(
        Long githubInstallationId,
        Long githubRepoId,
        String fullName,
        String defaultBranch,
        Boolean isPrivate
) {
    public ConnectRepositoryCommand {
        if (defaultBranch == null || defaultBranch.isBlank()) {
            defaultBranch = "main";
        }
        if (isPrivate == null) {
            isPrivate = true;
        }
    }

    public Boolean privateRepository() {
        return isPrivate();
    }
}

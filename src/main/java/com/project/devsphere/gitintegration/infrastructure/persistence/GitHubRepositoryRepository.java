package com.project.devsphere.gitintegration.infrastructure.persistence;

import com.project.devsphere.gitintegration.domain.GitHubRepository;
import com.project.devsphere.sharedkernel.persistence.ProjectScopedRepository;

public interface GitHubRepositoryRepository extends ProjectScopedRepository<GitHubRepository> {
}

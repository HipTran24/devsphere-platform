package com.project.devsphere.gitintegration.infrastructure.persistence;

import com.project.devsphere.gitintegration.domain.GitHubEvent;
import com.project.devsphere.sharedkernel.persistence.ProjectScopedRepository;

public interface GitHubEventRepository extends ProjectScopedRepository<GitHubEvent> {
}

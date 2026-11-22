package com.project.devsphere.gitintegration.infrastructure.persistence;

import com.project.devsphere.gitintegration.domain.GitHubRepository;
import com.project.devsphere.sharedkernel.persistence.ProjectScopedRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GitHubRepositoryRepository extends ProjectScopedRepository<GitHubRepository> {

    Optional<GitHubRepository> findByGithubRepoId(Long githubRepoId);

    Optional<GitHubRepository> findByGithubRepoIdAndActiveTrue(Long githubRepoId);

    Optional<GitHubRepository> findByProjectIdAndActiveTrue(UUID projectId);

    List<GitHubRepository> findAllByProjectId(UUID projectId);

    List<GitHubRepository> findByProjectIdOrderByCreatedAtDesc(UUID projectId);

    boolean existsByProjectIdAndActiveTrue(UUID projectId);

    boolean existsByGithubRepoId(Long githubRepoId);
}

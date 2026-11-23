package com.project.devsphere.gitintegration.infrastructure.persistence;

import com.project.devsphere.gitintegration.domain.GitHubEvent;
import com.project.devsphere.sharedkernel.persistence.ProjectScopedRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GitHubEventRepository extends ProjectScopedRepository<GitHubEvent> {

    Optional<GitHubEvent> findByDeliveryId(String deliveryId);

    boolean existsByDeliveryId(String deliveryId);

    List<GitHubEvent> findByProjectIdOrderByReceivedAtDesc(UUID projectId);

    List<GitHubEvent> findByRepositoryIdOrderByReceivedAtDesc(UUID repositoryId);
}

package com.project.devsphere.codequality.infrastructure.persistence;

import com.project.devsphere.codequality.domain.CiRun;
import com.project.devsphere.sharedkernel.persistence.ProjectScopedRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CiRunRepository extends ProjectScopedRepository<CiRun> {

    Optional<CiRun> findByRepositoryIdAndGithubRunIdAndRunAttempt(UUID repositoryId, Long githubRunId, Integer runAttempt);

    List<CiRun> findByProjectIdOrderByCreatedAtDesc(UUID projectId);

    List<CiRun> findByProjectIdAndHeadShaOrderByCreatedAtDesc(UUID projectId, String headSha);

    List<CiRun> findByProjectIdAndPrNumberOrderByCreatedAtDesc(UUID projectId, Integer prNumber);

    List<CiRun> findByProjectIdAndWorkItemIdOrderByCreatedAtDesc(UUID projectId, UUID workItemId);

    Optional<CiRun> findFirstByProjectIdAndHeadShaAndStatusOrderByCompletedAtDesc(UUID projectId, String headSha, CiRun.Status status);

    Optional<CiRun> findFirstByProjectIdAndHeadShaAndStatusAndConclusionOrderByCompletedAtDesc(
            UUID projectId, String headSha, CiRun.Status status, CiRun.Conclusion conclusion);
}

package com.project.devsphere.codequality.infrastructure.persistence;

import com.project.devsphere.codequality.domain.CodeReview;
import com.project.devsphere.sharedkernel.persistence.ProjectScopedRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CodeReviewRepository extends ProjectScopedRepository<CodeReview> {

    List<CodeReview> findByProjectIdOrderByCreatedAtDesc(UUID projectId);

    List<CodeReview> findByProjectIdAndHeadShaOrderByCreatedAtDesc(UUID projectId, String headSha);

    List<CodeReview> findByProjectIdAndPrNumberOrderByCreatedAtDesc(UUID projectId, Integer prNumber);

    List<CodeReview> findByProjectIdAndWorkItemIdOrderByCreatedAtDesc(UUID projectId, UUID workItemId);

    Optional<CodeReview> findFirstByProjectIdAndPrNumberAndHeadShaAndReviewerKindOrderByCreatedAtDesc(
            UUID projectId, Integer prNumber, String headSha, CodeReview.ReviewerKind reviewerKind);

    Optional<CodeReview> findFirstByProjectIdAndHeadShaAndStatusOrderByCreatedAtDesc(
            UUID projectId, String headSha, CodeReview.Status status);
}

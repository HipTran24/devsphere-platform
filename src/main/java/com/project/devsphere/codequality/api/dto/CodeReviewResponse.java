package com.project.devsphere.codequality.api.dto;

import com.project.devsphere.codequality.domain.CodeReview;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record CodeReviewResponse(
        UUID id,
        UUID projectId,
        UUID repositoryId,
        UUID workItemId,
        UUID aiJobId,
        Integer prNumber,
        String headSha,
        Long contextVersion,
        CodeReview.Scope scope,
        CodeReview.ReviewerKind reviewerKind,
        List<Object> findings,
        String summary,
        CodeReview.Status status,
        Map<String, Object> gateSnapshot,
        UUID reviewerId,
        String modelName,
        Instant completedAt,
        Instant createdAt,
        Instant updatedAt,
        Long version
) {
    public static CodeReviewResponse from(CodeReview review) {
        return new CodeReviewResponse(
                review.getId(),
                review.getProjectId(),
                review.getRepositoryId(),
                review.getWorkItemId(),
                review.getAiJobId(),
                review.getPrNumber(),
                review.getHeadSha(),
                review.getContextVersion(),
                review.getScope(),
                review.getReviewerKind(),
                review.getFindings(),
                review.getSummary(),
                review.getStatus(),
                review.getGateSnapshot(),
                review.getReviewerId(),
                review.getModelName(),
                review.getCompletedAt(),
                review.getCreatedAt(),
                review.getUpdatedAt(),
                review.getVersion()
        );
    }
}

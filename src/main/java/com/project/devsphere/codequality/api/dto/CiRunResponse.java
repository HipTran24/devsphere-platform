package com.project.devsphere.codequality.api.dto;

import com.project.devsphere.codequality.domain.CiRun;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record CiRunResponse(
        UUID id,
        UUID projectId,
        UUID repositoryId,
        UUID workItemId,
        Long githubRunId,
        Integer runAttempt,
        String headSha,
        Integer prNumber,
        CiRun.Status status,
        CiRun.Conclusion conclusion,
        List<Object> checks,
        Map<String, Object> metrics,
        Instant startedAt,
        Instant completedAt,
        Instant createdAt,
        Instant updatedAt,
        Long version
) {
    public static CiRunResponse from(CiRun run) {
        return new CiRunResponse(
                run.getId(),
                run.getProjectId(),
                run.getRepositoryId(),
                run.getWorkItemId(),
                run.getGithubRunId(),
                run.getRunAttempt(),
                run.getHeadSha(),
                run.getPrNumber(),
                run.getStatus(),
                run.getConclusion(),
                run.getChecks(),
                run.getMetrics(),
                run.getStartedAt(),
                run.getCompletedAt(),
                run.getCreatedAt(),
                run.getUpdatedAt(),
                run.getVersion()
        );
    }
}

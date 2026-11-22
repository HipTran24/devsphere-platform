package com.project.devsphere.codequality.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record RecordCiRunCommand(
        UUID repositoryId,
        Long githubRunId,
        Integer runAttempt,
        String headSha,
        Integer prNumber,
        UUID workItemId,
        String status,
        String conclusion,
        List<Object> checks,
        Map<String, Object> metrics,
        Instant startedAt,
        Instant completedAt
) {
    public RecordCiRunCommand(
            Long githubRunId,
            Integer runAttempt,
            String headSha,
            Integer prNumber,
            String status,
            String conclusion,
            List<Object> checks,
            Map<String, Object> metrics,
            Instant startedAt,
            Instant completedAt) {
        this(null, githubRunId, runAttempt, headSha, prNumber, null, status, conclusion, checks, metrics, startedAt, completedAt);
    }
}

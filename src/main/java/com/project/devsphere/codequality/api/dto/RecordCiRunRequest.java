package com.project.devsphere.codequality.api.dto;

import com.project.devsphere.codequality.application.RecordCiRunCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record RecordCiRunRequest(
        UUID repositoryId,
        @NotNull Long githubRunId,
        Integer runAttempt,
        @NotBlank String headSha,
        Integer prNumber,
        UUID workItemId,
        String status,
        String conclusion,
        List<Object> checks,
        Map<String, Object> metrics,
        Instant startedAt,
        Instant completedAt
) {
    public RecordCiRunCommand toCommand() {
        return new RecordCiRunCommand(
                repositoryId,
                githubRunId,
                runAttempt,
                headSha,
                prNumber,
                workItemId,
                status,
                conclusion,
                checks,
                metrics,
                startedAt,
                completedAt
        );
    }
}

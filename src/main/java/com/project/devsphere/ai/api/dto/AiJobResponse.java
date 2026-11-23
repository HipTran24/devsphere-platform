package com.project.devsphere.ai.api.dto;

import com.project.devsphere.ai.domain.AiJob;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AiJobResponse(
        UUID id,
        UUID workspaceId,
        UUID projectId,
        UUID requestedBy,
        String jobType,
        String status,
        String modelName,
        String connectionId,
        Map<String, Object> routeSnapshot,
        String promptVersion,
        String outputSchemaVersion,
        Map<String, Object> inputRefs,
        Map<String, Object> outputJson,
        String validationStatus,
        Map<String, Object> validationReport,
        int validationAttempts,
        String validatedByModel,
        Integer tokensIn,
        Integer tokensOut,
        String errorCode,
        Instant startedAt,
        Instant finishedAt,
        Instant createdAt,
        Instant updatedAt,
        Long version
) {
    public static AiJobResponse from(AiJob job) {
        return new AiJobResponse(
                job.getId(),
                job.getWorkspaceId(),
                job.getProjectId(),
                job.getRequestedBy(),
                job.getJobType() != null ? job.getJobType().name() : null,
                job.getStatus() != null ? job.getStatus().name() : null,
                job.getModelName(),
                job.getConnectionId(),
                job.getRouteSnapshot(),
                job.getPromptVersion(),
                job.getOutputSchemaVersion(),
                job.getInputRefs(),
                job.getOutputJson(),
                job.getValidationStatus() != null ? job.getValidationStatus().name() : null,
                job.getValidationReport(),
                job.getValidationAttempts() != null ? job.getValidationAttempts().intValue() : 0,
                job.getValidatedByModel(),
                job.getTokensIn(),
                job.getTokensOut(),
                job.getErrorCode(),
                job.getStartedAt(),
                job.getFinishedAt(),
                job.getCreatedAt(),
                job.getUpdatedAt(),
                job.getVersion()
        );
    }
}

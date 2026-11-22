package com.project.devsphere.codequality.api.dto;

import com.project.devsphere.codequality.application.SubmitReviewCommand;
import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.UUID;

public record SubmitReviewRequest(
        UUID repositoryId,
        String scope,
        Integer prNumber,
        @NotBlank String headSha,
        String status,
        String summary,
        List<Object> findings,
        UUID workItemId
) {
    public SubmitReviewCommand toCommand() {
        return new SubmitReviewCommand(
                repositoryId,
                scope,
                prNumber,
                headSha,
                status,
                summary,
                findings,
                workItemId
        );
    }
}

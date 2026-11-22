package com.project.devsphere.codequality.application;

import java.util.List;
import java.util.UUID;

public record SubmitReviewCommand(
        UUID repositoryId,
        String scope,
        Integer prNumber,
        String headSha,
        String status,
        String summary,
        List<Object> findings,
        UUID workItemId
) {
    public SubmitReviewCommand(
            String scope,
            Integer prNumber,
            String headSha,
            String status,
            String summary,
            List<Object> findings) {
        this(null, scope, prNumber, headSha, status, summary, findings, null);
    }
}

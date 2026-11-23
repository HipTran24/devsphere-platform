package com.project.devsphere.monitoring.api.dto;

public record CodeEvidenceDto(
        boolean prLinked,
        Integer prNumber,
        String headSha,
        boolean ciSuccess,
        String ciStatus,
        boolean codeReviewPass,
        String reviewStatus,
        boolean independentReviewer
) {}

package com.project.devsphere.codequality.api.dto;

import java.util.List;
import java.util.UUID;

public record QualityGateResponse(
        QualityGateStatus status,
        boolean passed,
        UUID workItemId,
        Integer prNumber,
        String headSha,
        boolean ciPassed,
        boolean reviewPassed,
        boolean criteriaPassed,
        String details,
        List<String> blockers
) {}

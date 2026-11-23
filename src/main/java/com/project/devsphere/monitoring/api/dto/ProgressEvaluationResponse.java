package com.project.devsphere.monitoring.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ProgressEvaluationResponse(
        UUID workItemId,
        String itemKey,
        String workKind,
        String currentStatus,
        BigDecimal claimedProgress,
        BigDecimal verifiedProgress,
        String evaluationStatus,
        BigDecimal totalCriteriaWeight,
        BigDecimal verifiedCriteriaWeight,
        int totalCriteriaCount,
        int verifiedCriteriaCount,
        boolean discrepancyDetected,
        CodeEvidenceDto codeEvidence,
        List<CriterionEvaluationDto> criteriaEvaluations,
        Instant evaluatedAt
) {}

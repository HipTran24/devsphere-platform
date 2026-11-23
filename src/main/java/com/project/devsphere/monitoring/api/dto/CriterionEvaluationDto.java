package com.project.devsphere.monitoring.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CriterionEvaluationDto(
        UUID criterionId,
        Integer position,
        String description,
        String evidenceKind,
        BigDecimal weight,
        String status,
        List<Object> evidence,
        UUID verifiedBy,
        Instant verifiedAt
) {}

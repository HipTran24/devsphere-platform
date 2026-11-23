package com.project.devsphere.workitem.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.project.devsphere.workitem.domain.AcceptanceCriterion;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AcceptanceCriterionResponse(
        UUID id,
        UUID projectId,
        UUID workItemId,
        Integer position,
        String description,
        AcceptanceCriterion.EvidenceKind evidenceKind,
        BigDecimal weight,
        AcceptanceCriterion.Status status,
        UUID verifiedBy,
        Instant verifiedAt,
        Instant createdAt,
        Instant updatedAt,
        Long version
) {
}

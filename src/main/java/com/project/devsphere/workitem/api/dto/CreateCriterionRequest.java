package com.project.devsphere.workitem.api.dto;

import com.project.devsphere.workitem.domain.AcceptanceCriterion;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;

public record CreateCriterionRequest(
        @NotBlank(message = "Description must not be blank")
        String description,

        AcceptanceCriterion.EvidenceKind evidenceKind,
        BigDecimal weight,
        Integer position
) {
    public CreateCriterionRequest {
        if (evidenceKind == null) {
            evidenceKind = AcceptanceCriterion.EvidenceKind.MANUAL;
        }
        if (weight == null) {
            weight = new BigDecimal("1");
        }
    }
}

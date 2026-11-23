package com.project.devsphere.workitem.api.dto;

import com.project.devsphere.workitem.domain.AcceptanceCriterion;
import jakarta.validation.constraints.NotNull;

public record VerifyCriterionRequest(
        @NotNull(message = "Status is required")
        AcceptanceCriterion.Status status
) {
}

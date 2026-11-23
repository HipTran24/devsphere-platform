package com.project.devsphere.planning.api.dto;

import com.project.devsphere.planning.domain.PlanProposal;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record CreatePlanProposalRequest(
        PlanProposal.Kind kind,
        @NotNull(message = "expectedProjectVersion is required")
        @Min(value = 0, message = "expectedProjectVersion must be non-negative")
        Long expectedProjectVersion,
        UUID baseScheduleVersionId,
        UUID requirementVersionId,
        UUID changeRequestId,
        Map<String, Object> proposalPayload,
        List<Object> assumptions,
        Boolean changesCommittedDeadline,
        LocalDate proposedCommittedDeadline
) {}

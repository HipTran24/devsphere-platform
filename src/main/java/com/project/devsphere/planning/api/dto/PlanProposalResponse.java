package com.project.devsphere.planning.api.dto;

import com.project.devsphere.planning.domain.PlanProposal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record PlanProposalResponse(
        UUID id,
        UUID projectId,
        PlanProposal.Kind kind,
        Long baseProjectVersion,
        UUID baseScheduleVersionId,
        UUID requirementVersionId,
        UUID changeRequestId,
        UUID aiJobId,
        PlanProposal.Status status,
        Map<String, Object> proposalPayload,
        PlanProposal.SolverStatus solverStatus,
        Map<String, Object> solverDetails,
        List<Object> assumptions,
        UUID createdBy,
        UUID approvedBy,
        Instant approvedAt,
        Boolean changesCommittedDeadline,
        LocalDate proposedCommittedDeadline,
        UUID deadlineConfirmedBy,
        Instant deadlineConfirmedAt,
        Long deadlineConfirmationVersion,
        Long deadlineConfirmedProjectVersion,
        Instant createdAt,
        Instant updatedAt,
        Long version
) {
    public static PlanProposalResponse from(PlanProposal p) {
        return new PlanProposalResponse(
                p.getId(),
                p.getProjectId(),
                p.getKind(),
                p.getBaseProjectVersion(),
                p.getBaseScheduleVersionId(),
                p.getRequirementVersionId(),
                p.getChangeRequestId(),
                p.getAiJobId(),
                p.getStatus(),
                p.getProposalPayload(),
                p.getSolverStatus(),
                p.getSolverDetails(),
                p.getAssumptions(),
                p.getCreatedBy(),
                p.getApprovedBy(),
                p.getApprovedAt(),
                p.getChangesCommittedDeadline(),
                p.getProposedCommittedDeadline(),
                p.getDeadlineConfirmedBy(),
                p.getDeadlineConfirmedAt(),
                p.getDeadlineConfirmationVersion(),
                p.getDeadlineConfirmedProjectVersion(),
                p.getCreatedAt(),
                p.getUpdatedAt(),
                p.getVersion()
        );
    }
}

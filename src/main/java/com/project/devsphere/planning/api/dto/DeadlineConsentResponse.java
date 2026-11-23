package com.project.devsphere.planning.api.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record DeadlineConsentResponse(
        UUID proposalId,
        UUID projectId,
        boolean changesCommittedDeadline,
        LocalDate currentCommittedDeadline,
        LocalDate proposedCommittedDeadline,
        UUID deadlineApproverUserId,
        boolean isDesignatedApprover,
        Long proposalVersion,
        Long baseProjectVersion,
        boolean confirmed,
        UUID confirmedBy,
        Instant confirmedAt,
        List<MilestoneImpactDTO> milestoneImpacts
) {
}

package com.project.devsphere.planning.api.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record PlanProposalPreviewResponse(
        UUID proposalId,
        UUID projectId,
        Long proposalVersion,
        Long baseProjectVersion,
        String solverStatus,
        int proposedTaskCount,
        long totalMinutes,
        Instant forecastFinish,
        boolean changesCommittedDeadline,
        LocalDate currentCommittedDeadline,
        LocalDate proposedCommittedDeadline,
        List<TaskPreviewItem> tasks,
        List<MilestoneImpactDTO> milestoneImpacts
) {
    public record TaskPreviewItem(
            String tempKey,
            UUID workItemId,
            String title,
            int estimateMinutes,
            UUID developerId,
            Instant startAt,
            Instant finishAt
    ) {
    }
}

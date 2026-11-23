package com.project.devsphere.clientportal.api.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ClientProjectOverviewResponse(
        UUID projectId,
        String projectName,
        String projectKey,
        String description,
        String objective,
        String status,
        LocalDate plannedStart,
        LocalDate committedDeadline,
        int publishedMilestonesCount,
        int acceptedMilestonesCount,
        int publishedReportsCount,
        List<PublishedMilestoneResponse> milestones,
        List<PublishedReportResponse> reports
) {
}

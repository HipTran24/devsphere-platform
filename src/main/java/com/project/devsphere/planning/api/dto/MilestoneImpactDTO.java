package com.project.devsphere.planning.api.dto;

import java.time.LocalDate;
import java.util.UUID;

public record MilestoneImpactDTO(
        UUID milestoneId,
        String name,
        LocalDate targetDate,
        Integer delayDays
) {
}

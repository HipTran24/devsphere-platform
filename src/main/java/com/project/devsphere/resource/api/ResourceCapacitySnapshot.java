package com.project.devsphere.resource.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ResourceCapacitySnapshot(
        UUID projectId,
        UUID workspaceId,
        Long projectVersion,
        String timezone,
        Instant capturedAt,
        List<DeveloperCapacityRecord> developers
) {
    public record DeveloperCapacityRecord(
            UUID developerId,
            boolean eligible,
            List<CapacityInterval> intervals
    ) {}

    public record CapacityInterval(
            UUID capacityId,
            Long capacityVersion,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            int minutesPerDay,
            BigDecimal allocationPct,
            int dailyBudgetMinutes,
            List<SkillLevel> skills,
            List<WeeklySlot> weeklySlots,
            List<TimeOff> timeOff
    ) {}
}

package com.project.devsphere.resource.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class ResourceCapacityResponses {

    private ResourceCapacityResponses() {}

    public record ResourceCapacityResponse(
            UUID id,
            UUID projectId,
            UUID developerId,
            List<SkillLevel> skillLevels,
            List<WeeklySlot> weeklySlots,
            List<TimeOff> timeOff,
            Integer minutesPerDay,
            BigDecimal allocationPct,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            Long version,
            Long projectVersion
    ) {}

    public record ResourceCalendarResponse(
            UUID projectId,
            UUID developerId,
            Long projectVersion,
            String timezone,
            LocalDate from,
            LocalDate to,
            List<DailySchedule> days
    ) {}

    public record DailySchedule(
            LocalDate date,
            int availableMinutes,
            int dailyBudgetMinutes,
            List<TimeSlot> slots,
            List<TimeOff> timeOffs
    ) {}

    public record TimeSlot(
            int startMinute,
            int endMinute
    ) {}
}

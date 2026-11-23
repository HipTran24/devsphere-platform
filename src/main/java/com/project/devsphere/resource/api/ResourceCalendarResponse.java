package com.project.devsphere.resource.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ResourceCalendarResponse(
        UUID projectId,
        UUID developerId,
        Long projectVersion,
        String timezone,
        String calendarPolicyVersion,
        CalendarRange range,
        String eligibility,
        List<UUID> capacityRefs,
        List<CalendarDay> days,
        List<String> limitations
) {
    public record CalendarRange(LocalDate from, LocalDate to) {}

    public record CalendarDay(
            LocalDate date,
            UUID capacityId,
            Long capacityVersion,
            List<TimeSlotSegment> availableSegments,
            int rawAvailableMinutes,
            int dailyBudgetMinutes,
            boolean missingCapacity,
            List<String> exclusions
    ) {}

    public record TimeSlotSegment(
            Instant startAt,
            Instant endAt,
            int minutes
    ) {}
}

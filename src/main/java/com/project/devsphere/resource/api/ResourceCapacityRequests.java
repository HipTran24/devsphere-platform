package com.project.devsphere.resource.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class ResourceCapacityRequests {

    private ResourceCapacityRequests() {}

    public record PutResourceCapacityRequest(
            UUID developerId,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            List<SkillLevel> skillLevels,
            List<WeeklySlot> weeklySlots,
            List<TimeOff> timeOff,
            Integer minutesPerDay,
            BigDecimal allocationPct,
            Long expectedProjectVersion,
            Long expectedCapacityVersion
    ) {}

    public record EffectiveCapacityChangeRequest(
            UUID supersedesCapacityId,
            Long expectedCapacityVersion,
            LocalDate effectiveFrom,
            List<SkillLevel> skillLevels,
            List<WeeklySlot> weeklySlots,
            List<TimeOff> timeOff,
            Integer minutesPerDay,
            BigDecimal allocationPct,
            String reason,
            Long expectedProjectVersion
    ) {}

    public record AddTimeOffRequest(
            UUID developerId,
            Instant startAt,
            Instant endAt,
            String reason,
            Long expectedProjectVersion
    ) {}
}

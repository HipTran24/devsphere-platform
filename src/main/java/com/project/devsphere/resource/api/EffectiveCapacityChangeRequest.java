package com.project.devsphere.resource.api;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record EffectiveCapacityChangeRequest(
        @NotNull UUID supersedesCapacityId,
        @NotNull Long expectedCapacityVersion,
        @NotNull LocalDate effectiveFrom,
        List<SkillLevel> skillLevels,
        List<WeeklySlot> weeklySlots,
        List<TimeOff> timeOff,
        @NotNull @Min(1) @Max(1440) Integer minutesPerDay,
        @NotNull @DecimalMin("0.01") @DecimalMax("100.00") BigDecimal allocationPct,
        String reason,
        @NotNull Long expectedProjectVersion
) {
    public EffectiveCapacityChangeRequest {
        if (skillLevels == null) {
            skillLevels = List.of();
        }
        if (weeklySlots == null) {
            weeklySlots = List.of();
        }
        if (timeOff == null) {
            timeOff = List.of();
        }
    }
}

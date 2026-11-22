package com.project.devsphere.resource.api;

import com.project.devsphere.resource.domain.DeveloperCapacity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ResourceCapacityResponse(
        UUID id,
        UUID projectId,
        UUID developerId,
        String eligibility,
        List<SkillLevel> skillLevels,
        List<WeeklySlot> weeklySlots,
        List<TimeOff> timeOff,
        Integer minutesPerDay,
        BigDecimal allocationPct,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        Long version,
        Long projectVersion
) {
    public static ResourceCapacityResponse from(DeveloperCapacity entity, String eligibility, Long projectVersion) {
        return new ResourceCapacityResponse(
                entity.getId(),
                entity.getProjectId(),
                entity.getUserId(),
                eligibility != null ? eligibility : "ELIGIBLE",
                entity.getSkills() != null ? entity.getSkills() : List.of(),
                entity.getWeeklySlots() != null ? entity.getWeeklySlots() : List.of(),
                entity.getTimeOff() != null ? entity.getTimeOff() : List.of(),
                entity.getMinutesPerWorkday(),
                entity.getAllocationPct(),
                entity.getEffectiveFrom(),
                entity.getEffectiveTo(),
                entity.getVersion(),
                projectVersion
        );
    }
}

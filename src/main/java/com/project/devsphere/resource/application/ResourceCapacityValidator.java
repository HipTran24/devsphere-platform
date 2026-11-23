package com.project.devsphere.resource.application;

import com.project.devsphere.resource.api.SkillLevel;
import com.project.devsphere.resource.api.TimeOff;
import com.project.devsphere.resource.api.WeeklySlot;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class ResourceCapacityValidator {

    private static final int MAX_SKILLS = 128;
    private static final int MAX_WEEKLY_SLOTS = 16;
    private static final int MAX_TIME_OFF = 500;
    private static final int MAX_SKILL_NAME_LENGTH = 80;

    private ResourceCapacityValidator() {}

    public static void validateCapacityInput(
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            Integer minutesPerDay,
            BigDecimal allocationPct,
            List<SkillLevel> skillLevels,
            List<WeeklySlot> weeklySlots,
            List<TimeOff> timeOff) {

        if (effectiveFrom == null) {
            throw ResourceCapacityException.validationFailed("effectiveFrom must not be null");
        }
        if (effectiveTo != null && !effectiveTo.isAfter(effectiveFrom)) {
            throw ResourceCapacityException.calendarInvalid("effectiveTo must be strictly after effectiveFrom");
        }

        if (minutesPerDay == null || minutesPerDay < 1 || minutesPerDay > 1440) {
            throw ResourceCapacityException.validationFailed("minutesPerDay must be between 1 and 1440");
        }

        if (allocationPct == null) {
            throw ResourceCapacityException.validationFailed("allocationPct must not be null");
        }
        if (allocationPct.compareTo(BigDecimal.ZERO) <= 0 || allocationPct.compareTo(new BigDecimal("100")) > 0) {
            throw ResourceCapacityException.validationFailed("allocationPct must be greater than 0 and at most 100");
        }
        if (allocationPct.scale() > 2) {
            throw ResourceCapacityException.validationFailed("allocationPct must have at most 2 decimal places");
        }

        validateSkills(skillLevels);
        validateWeeklySlots(weeklySlots);
        validateTimeOff(timeOff);
    }

    public static List<SkillLevel> validateSkills(List<SkillLevel> skills) {
        if (skills == null) {
            return List.of();
        }
        if (skills.size() > MAX_SKILLS) {
            throw ResourceCapacityException.validationFailed("Cannot exceed " + MAX_SKILLS + " skills");
        }
        Set<String> seen = new HashSet<>();
        List<SkillLevel> normalized = new ArrayList<>(skills.size());
        for (SkillLevel sl : skills) {
            if (sl == null || sl.skill() == null || sl.skill().isBlank()) {
                throw ResourceCapacityException.validationFailed("Skill name must not be blank");
            }
            String norm = sl.skill().strip().toLowerCase(Locale.ROOT);
            if (norm.length() > MAX_SKILL_NAME_LENGTH) {
                throw ResourceCapacityException.validationFailed("Skill name must not exceed " + MAX_SKILL_NAME_LENGTH + " characters");
            }
            if (sl.level() < 1 || sl.level() > 5) {
                throw ResourceCapacityException.validationFailed("Skill level must be between 1 and 5");
            }
            if (!seen.add(norm)) {
                throw ResourceCapacityException.validationFailed("Duplicate skill name: " + norm);
            }
            normalized.add(new SkillLevel(norm, sl.level()));
        }
        return normalized;
    }

    public static void validateWeeklySlots(List<WeeklySlot> weeklySlots) {
        if (weeklySlots == null || weeklySlots.isEmpty()) {
            throw ResourceCapacityException.calendarInvalid("Weekly working slots must not be empty (at least one slot is required)");
        }
        if (weeklySlots.size() > MAX_WEEKLY_SLOTS) {
            throw ResourceCapacityException.calendarInvalid("Cannot exceed " + MAX_WEEKLY_SLOTS + " weekly slots");
        }

        List<WeeklySlot> sorted = new ArrayList<>(weeklySlots);
        sorted.sort(Comparator.comparingInt(WeeklySlot::dayOfWeek).thenComparingInt(WeeklySlot::startMinute));

        for (int i = 0; i < sorted.size(); i++) {
            WeeklySlot current = sorted.get(i);
            if (current.dayOfWeek() < 1 || current.dayOfWeek() > 7) {
                throw ResourceCapacityException.calendarInvalid("dayOfWeek must be between 1 and 7");
            }
            if (current.startMinute() < 0 || current.startMinute() > 1439) {
                throw ResourceCapacityException.calendarInvalid("startMinute must be between 0 and 1439");
            }
            if (current.endMinute() < 1 || current.endMinute() > 1440) {
                throw ResourceCapacityException.calendarInvalid("endMinute must be between 1 and 1440");
            }
            if (current.endMinute() <= current.startMinute()) {
                throw ResourceCapacityException.calendarInvalid("endMinute must be strictly greater than startMinute");
            }

            if (i > 0) {
                WeeklySlot prev = sorted.get(i - 1);
                if (prev.dayOfWeek() == current.dayOfWeek()) {
                    if (prev.endMinute() > current.startMinute()) {
                        throw ResourceCapacityException.calendarInvalid("Weekly slots overlap on day " + current.dayOfWeek());
                    }
                }
            }
        }
    }

    public static void validateTimeOff(List<TimeOff> timeOff) {
        if (timeOff == null || timeOff.isEmpty()) {
            return;
        }
        if (timeOff.size() > MAX_TIME_OFF) {
            throw ResourceCapacityException.timeOffOverlap("Cannot exceed " + MAX_TIME_OFF + " time off intervals");
        }

        List<TimeOff> sorted = new ArrayList<>(timeOff);
        sorted.sort(Comparator.comparing(TimeOff::startAt));

        for (int i = 0; i < sorted.size(); i++) {
            TimeOff current = sorted.get(i);
            if (current.startAt() == null || current.endAt() == null) {
                throw ResourceCapacityException.calendarInvalid("Time off interval startAt and endAt must not be null");
            }
            if (!current.endAt().isAfter(current.startAt())) {
                throw ResourceCapacityException.calendarInvalid("Time off endAt must be strictly after startAt");
            }

            if (i > 0) {
                TimeOff prev = sorted.get(i - 1);
                if (prev.endAt().isAfter(current.startAt())) {
                    throw ResourceCapacityException.timeOffOverlap("Time off intervals overlap: " + prev + " and " + current);
                }
            }
        }
    }
}

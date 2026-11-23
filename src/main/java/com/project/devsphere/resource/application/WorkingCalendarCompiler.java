package com.project.devsphere.resource.application;

import com.project.devsphere.resource.api.ResourceCalendarResponse;
import com.project.devsphere.resource.api.ResourceCapacityResponses;
import com.project.devsphere.resource.api.TimeOff;
import com.project.devsphere.resource.api.WeeklySlot;
import com.project.devsphere.resource.domain.DeveloperCapacity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public class WorkingCalendarCompiler {

    public static List<ResourceCapacityResponses.DailySchedule> compileDailySchedules(
            ZoneId zoneId,
            LocalDate from,
            LocalDate to,
            List<DeveloperCapacity> capacities) {

        if (from == null || to == null) {
            throw ResourceCapacityException.validationFailed("from and to dates must not be null");
        }
        if (to.isBefore(from)) {
            throw ResourceCapacityException.calendarInvalid("to date must not be before from date");
        }

        List<ResourceCapacityResponses.DailySchedule> schedules = new ArrayList<>();

        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            final LocalDate currentDate = date;
            DeveloperCapacity matchingCapacity = capacities != null ? capacities.stream()
                    .filter(c -> !currentDate.isBefore(c.getEffectiveFrom()) &&
                            (c.getEffectiveTo() == null || currentDate.isBefore(c.getEffectiveTo())))
                    .findFirst()
                    .orElse(null) : null;

            if (matchingCapacity == null) {
                schedules.add(new ResourceCapacityResponses.DailySchedule(currentDate, 0, 0, List.of(), List.of()));
                continue;
            }

            int dayOfWeek = currentDate.getDayOfWeek().getValue();
            int minutesPerWorkday = matchingCapacity.getMinutesPerWorkday() != null ? matchingCapacity.getMinutesPerWorkday() : 480;
            BigDecimal allocPct = matchingCapacity.getAllocationPct() != null ? matchingCapacity.getAllocationPct() : new BigDecimal("100");

            int dailyBudgetMinutes = BigDecimal.valueOf(minutesPerWorkday)
                    .multiply(allocPct)
                    .divide(BigDecimal.valueOf(100), 0, RoundingMode.FLOOR)
                    .intValue();

            List<WeeklySlot> slotsForDay = matchingCapacity.getWeeklySlots() != null
                    ? matchingCapacity.getWeeklySlots().stream()
                        .filter(s -> s.dayOfWeek() == dayOfWeek)
                        .sorted(Comparator.comparingInt(WeeklySlot::startMinute))
                        .toList()
                    : List.of();

            List<ResourceCapacityResponses.TimeSlot> baseSlots = new ArrayList<>();
            for (WeeklySlot ws : slotsForDay) {
                baseSlots.add(new ResourceCapacityResponses.TimeSlot(ws.startMinute(), ws.endMinute()));
            }

            Instant dayStart = currentDate.atStartOfDay(zoneId).toInstant();
            Instant dayEnd = currentDate.plusDays(1).atStartOfDay(zoneId).toInstant();

            List<TimeOff> intersectingTimeOffs = new ArrayList<>();
            List<ResourceCapacityResponses.TimeSlot> busySlots = new ArrayList<>();

            if (matchingCapacity.getTimeOff() != null) {
                for (TimeOff toff : matchingCapacity.getTimeOff()) {
                    if (toff.startAt().isBefore(dayEnd) && toff.endAt().isAfter(dayStart)) {
                        intersectingTimeOffs.add(toff);

                        ZonedDateTime offStart = toff.startAt().atZone(zoneId);
                        ZonedDateTime offEnd = toff.endAt().atZone(zoneId);

                        int busyStart = 0;
                        if (!offStart.toLocalDate().isBefore(currentDate)) {
                            busyStart = offStart.getHour() * 60 + offStart.getMinute();
                        }
                        int busyEnd = 1440;
                        if (!offEnd.toLocalDate().isAfter(currentDate)) {
                            busyEnd = offEnd.getHour() * 60 + offEnd.getMinute();
                            if (offEnd.getSecond() > 0 || offEnd.getNano() > 0) {
                                busyEnd = Math.min(1440, busyEnd + 1);
                            }
                        }

                        if (busyEnd > busyStart) {
                            busySlots.add(new ResourceCapacityResponses.TimeSlot(busyStart, busyEnd));
                        }
                    }
                }
            }

            List<ResourceCapacityResponses.TimeSlot> availableSlots = subtractBusy(baseSlots, busySlots);
            int totalAvailableMinutes = 0;
            for (ResourceCapacityResponses.TimeSlot slot : availableSlots) {
                totalAvailableMinutes += (slot.endMinute() - slot.startMinute());
            }

            schedules.add(new ResourceCapacityResponses.DailySchedule(
                    currentDate,
                    totalAvailableMinutes,
                    dailyBudgetMinutes,
                    availableSlots,
                    intersectingTimeOffs
            ));
        }

        return schedules;
    }

    private static List<ResourceCapacityResponses.TimeSlot> subtractBusy(
            List<ResourceCapacityResponses.TimeSlot> baseSlots,
            List<ResourceCapacityResponses.TimeSlot> busySlots) {
        if (baseSlots.isEmpty()) return List.of();
        if (busySlots.isEmpty()) return List.copyOf(baseSlots);

        List<ResourceCapacityResponses.TimeSlot> current = new ArrayList<>(baseSlots);
        for (ResourceCapacityResponses.TimeSlot busy : busySlots) {
            List<ResourceCapacityResponses.TimeSlot> next = new ArrayList<>();
            for (ResourceCapacityResponses.TimeSlot avail : current) {
                int aStart = avail.startMinute();
                int aEnd = avail.endMinute();
                int bStart = busy.startMinute();
                int bEnd = busy.endMinute();

                if (bEnd <= aStart || bStart >= aEnd) {
                    next.add(avail);
                } else {
                    if (bStart > aStart) {
                        next.add(new ResourceCapacityResponses.TimeSlot(aStart, bStart));
                    }
                    if (bEnd < aEnd) {
                        next.add(new ResourceCapacityResponses.TimeSlot(bEnd, aEnd));
                    }
                }
            }
            current = next;
        }
        current.sort(Comparator.comparingInt(ResourceCapacityResponses.TimeSlot::startMinute));
        return current;
    }

    public static ResourceCalendarResponse compile(
            UUID projectId,
            UUID developerId,
            Long projectVersion,
            String timezoneStr,
            String eligibility,
            LocalDate from,
            LocalDate to,
            List<DeveloperCapacity> capacities) {

        ZoneId zone = ZoneId.of(timezoneStr != null ? timezoneStr : "UTC");
        List<UUID> capacityRefs = capacities.stream().map(DeveloperCapacity::getId).distinct().toList();
        List<ResourceCalendarResponse.CalendarDay> days = new ArrayList<>();

        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            final LocalDate currentDate = date;
            DeveloperCapacity matchingCapacity = capacities.stream()
                    .filter(c -> !currentDate.isBefore(c.getEffectiveFrom()) &&
                            (c.getEffectiveTo() == null || currentDate.isBefore(c.getEffectiveTo())))
                    .findFirst()
                    .orElse(null);

            if (matchingCapacity == null) {
                days.add(new ResourceCalendarResponse.CalendarDay(
                        currentDate,
                        null,
                        null,
                        List.of(),
                        0,
                        0,
                        true,
                        List.of("No capacity configuration for this date")
                ));
                continue;
            }

            int dayOfWeek = currentDate.getDayOfWeek().getValue(); // 1=Monday, 7=Sunday
            List<WeeklySlot> slotsForDay = matchingCapacity.getWeeklySlots() != null
                    ? matchingCapacity.getWeeklySlots().stream()
                        .filter(s -> s.dayOfWeek() == dayOfWeek)
                        .sorted(Comparator.comparingInt(WeeklySlot::startMinute))
                        .toList()
                    : List.of();

            List<TimeOff> timeOffs = matchingCapacity.getTimeOff() != null
                    ? matchingCapacity.getTimeOff()
                    : List.of();

            List<ResourceCalendarResponse.TimeSlotSegment> availableSegments = new ArrayList<>();
            int totalAvailableMinutes = 0;

            for (WeeklySlot slot : slotsForDay) {
                ZonedDateTime slotStart = currentDate.atStartOfDay(zone).plusMinutes(slot.startMinute());
                ZonedDateTime slotEnd = currentDate.atStartOfDay(zone).plusMinutes(slot.endMinute());

                Instant segStart = slotStart.toInstant();
                Instant segEnd = slotEnd.toInstant();

                // Subtract timeOff intervals
                List<InstantRange> workIntervals = subtractTimeOff(new InstantRange(segStart, segEnd), timeOffs);

                for (InstantRange interval : workIntervals) {
                    int durationMinutes = (int) Duration.between(interval.start, interval.end).toMinutes();
                    if (durationMinutes > 0) {
                        availableSegments.add(new ResourceCalendarResponse.TimeSlotSegment(interval.start, interval.end, durationMinutes));
                        totalAvailableMinutes += durationMinutes;
                    }
                }
            }

            // Daily budget = floor(minutes_per_workday * allocation_pct / 100)
            BigDecimal minutesPerDay = BigDecimal.valueOf(matchingCapacity.getMinutesPerWorkday());
            BigDecimal allocPct = matchingCapacity.getAllocationPct() != null ? matchingCapacity.getAllocationPct() : new BigDecimal("100");
            int dailyBudgetMinutes = minutesPerDay.multiply(allocPct)
                    .divide(BigDecimal.valueOf(100), 0, RoundingMode.FLOOR)
                    .intValue();

            List<String> exclusions = new ArrayList<>();
            if (slotsForDay.isEmpty()) {
                exclusions.add("Non-working day");
            }
            if (totalAvailableMinutes < dailyBudgetMinutes && !slotsForDay.isEmpty()) {
                exclusions.add("Time off reduced available minutes below daily budget");
            }

            days.add(new ResourceCalendarResponse.CalendarDay(
                    currentDate,
                    matchingCapacity.getId(),
                    matchingCapacity.getVersion(),
                    availableSegments,
                    totalAvailableMinutes,
                    dailyBudgetMinutes,
                    false,
                    exclusions
            ));
        }

        return new ResourceCalendarResponse(
                projectId,
                developerId,
                projectVersion,
                zone.getId(),
                "1.0",
                new ResourceCalendarResponse.CalendarRange(from, to),
                eligibility,
                capacityRefs,
                days,
                List.of()
        );
    }

    private static List<InstantRange> subtractTimeOff(InstantRange base, List<TimeOff> timeOffs) {
        List<InstantRange> currentRanges = new ArrayList<>(List.of(base));

        for (TimeOff to : timeOffs) {
            List<InstantRange> nextRanges = new ArrayList<>();
            for (InstantRange range : currentRanges) {
                // If no overlap
                if (to.endAt().isBefore(range.start) || !to.endAt().isAfter(range.start) ||
                    to.startAt().isAfter(range.end) || !to.startAt().isBefore(range.end)) {
                    nextRanges.add(range);
                } else {
                    // Overlap exists: split range
                    if (to.startAt().isAfter(range.start)) {
                        nextRanges.add(new InstantRange(range.start, to.startAt()));
                    }
                    if (to.endAt().isBefore(range.end)) {
                        nextRanges.add(new InstantRange(to.endAt(), range.end));
                    }
                }
            }
            currentRanges = nextRanges;
        }

        return currentRanges;
    }

    private record InstantRange(Instant start, Instant end) {}
}

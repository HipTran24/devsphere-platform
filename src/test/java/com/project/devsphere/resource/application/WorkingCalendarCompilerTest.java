package com.project.devsphere.resource.application;

import com.project.devsphere.resource.api.ResourceCalendarResponse;
import com.project.devsphere.resource.api.TimeOff;
import com.project.devsphere.resource.api.WeeklySlot;
import com.project.devsphere.resource.domain.DeveloperCapacity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WorkingCalendarCompilerTest {

    @Test
    @DisplayName("Compiles daily working slots, subtracts time off, and computes daily budget")
    void compilesDailyWorkingSlotsAndTimeOff() {
        UUID projectId = UUID.randomUUID();
        UUID developerId = UUID.randomUUID();
        UUID capacityId = UUID.randomUUID();

        DeveloperCapacity capacity = new DeveloperCapacity();
        capacity.setId(capacityId);
        capacity.setProjectId(projectId);
        capacity.setUserId(developerId);
        capacity.setMinutesPerWorkday(480);
        capacity.setAllocationPct(new BigDecimal("80.00")); // budget = 480 * 80 / 100 = 384 min
        capacity.setEffectiveFrom(LocalDate.of(2026, 12, 1));
        capacity.setEffectiveTo(LocalDate.of(2026, 12, 10));

        // Monday to Friday: 09:00 - 17:00 (540 to 1020 = 480 minutes)
        List<WeeklySlot> slots = List.of(
                new WeeklySlot(1, 540, 1020),
                new WeeklySlot(2, 540, 1020),
                new WeeklySlot(3, 540, 1020),
                new WeeklySlot(4, 540, 1020),
                new WeeklySlot(5, 540, 1020)
        );
        capacity.setWeeklySlots(slots);

        // Time off on Tuesday Dec 2, 2026 from 10:00 to 12:00 (600 to 720 UTC, 120 minutes)
        Instant timeOffStart = Instant.parse("2026-12-02T10:00:00Z");
        Instant timeOffEnd = Instant.parse("2026-12-02T12:00:00Z");
        capacity.setTimeOff(List.of(new TimeOff(timeOffStart, timeOffEnd)));

        LocalDate from = LocalDate.of(2026, 12, 1); // Monday
        LocalDate to = LocalDate.of(2026, 12, 3);   // Wednesday

        ResourceCalendarResponse calendar = WorkingCalendarCompiler.compile(
                projectId,
                developerId,
                1L,
                "UTC",
                "ELIGIBLE",
                from,
                to,
                List.of(capacity)
        );

        assertThat(calendar).isNotNull();
        assertThat(calendar.days()).hasSize(3);

        // Day 1: Monday (Dec 1) - Full 480 minutes, daily budget 384
        ResourceCalendarResponse.CalendarDay day1 = calendar.days().get(0);
        assertThat(day1.date()).isEqualTo(LocalDate.of(2026, 12, 1));
        assertThat(day1.rawAvailableMinutes()).isEqualTo(480);
        assertThat(day1.dailyBudgetMinutes()).isEqualTo(384);
        assertThat(day1.missingCapacity()).isFalse();

        // Day 2: Tuesday (Dec 2) - 480 - 120 = 360 available minutes (2 segments: 09:00-10:00 and 12:00-17:00)
        ResourceCalendarResponse.CalendarDay day2 = calendar.days().get(1);
        assertThat(day2.date()).isEqualTo(LocalDate.of(2026, 12, 2));
        assertThat(day2.rawAvailableMinutes()).isEqualTo(360);
        assertThat(day2.dailyBudgetMinutes()).isEqualTo(384);
        assertThat(day2.availableSegments()).hasSize(2);
        assertThat(day2.availableSegments().get(0).minutes()).isEqualTo(60);
        assertThat(day2.availableSegments().get(1).minutes()).isEqualTo(300);

        // Day 3: Wednesday (Dec 3) - Full 480 minutes
        ResourceCalendarResponse.CalendarDay day3 = calendar.days().get(2);
        assertThat(day3.date()).isEqualTo(LocalDate.of(2026, 12, 3));
        assertThat(day3.rawAvailableMinutes()).isEqualTo(480);
        assertThat(day3.dailyBudgetMinutes()).isEqualTo(384);
    }

    @Test
    @DisplayName("Reports missing capacity when date falls outside effective intervals")
    void reportsMissingCapacityWhenDateFallsOutsideRange() {
        UUID projectId = UUID.randomUUID();
        UUID developerId = UUID.randomUUID();

        LocalDate from = LocalDate.of(2026, 12, 1);
        LocalDate to = LocalDate.of(2026, 12, 2);

        ResourceCalendarResponse calendar = WorkingCalendarCompiler.compile(
                projectId,
                developerId,
                1L,
                "UTC",
                "ELIGIBLE",
                from,
                to,
                List.of() // No capacities
        );

        assertThat(calendar.days()).hasSize(2);
        assertThat(calendar.days().get(0).missingCapacity()).isTrue();
        assertThat(calendar.days().get(0).rawAvailableMinutes()).isEqualTo(0);
        assertThat(calendar.days().get(1).missingCapacity()).isTrue();
    }
}

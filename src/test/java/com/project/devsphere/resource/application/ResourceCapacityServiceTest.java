package com.project.devsphere.resource.application;

import com.project.devsphere.identity.application.AccessPolicy;
import com.project.devsphere.identity.application.SessionService;
import com.project.devsphere.identity.domain.ProjectRole;
import com.project.devsphere.resource.api.AddTimeOffRequest;
import com.project.devsphere.resource.api.EffectiveCapacityChangeRequest;
import com.project.devsphere.resource.api.PutResourceCapacityRequest;
import com.project.devsphere.resource.api.ResourceCalendarResponse;
import com.project.devsphere.resource.api.SkillLevel;
import com.project.devsphere.resource.api.TimeOff;
import com.project.devsphere.resource.api.WeeklySlot;
import com.project.devsphere.resource.domain.DeveloperCapacity;
import com.project.devsphere.resource.infrastructure.persistence.DeveloperCapacityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ResourceCapacityServiceTest {

    @Mock private DeveloperCapacityRepository capacityRepository;
    @Mock private AccessPolicy accessPolicy;
    @Mock private JdbcTemplate jdbcTemplate;

    private ResourceCapacityService service;

    private final UUID pmUserId = UUID.randomUUID();
    private final UUID developerId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private SessionService.Principal pmPrincipal;

    @BeforeEach
    void setUp() {
        service = new ResourceCapacityService(capacityRepository, accessPolicy, jdbcTemplate);
        pmPrincipal = new SessionService.Principal(pmUserId, sessionId, "Project Manager", "USER");

        when(jdbcTemplate.queryForObject(contains("SELECT version FROM projects"), eq(Long.class), eq(projectId)))
                .thenReturn(10L);
        when(jdbcTemplate.queryForObject(contains("SELECT ds_has_project_role"), eq(Boolean.class), eq(projectId), eq(developerId)))
                .thenReturn(true);
        when(jdbcTemplate.query(contains("SELECT timezone FROM projects"), any(RowMapper.class), eq(projectId)))
                .thenReturn(List.of("UTC"));
    }

    // =========================================================================
    // 1. Daily Budget Computation Tests
    // =========================================================================

    @Nested
    @DisplayName("1. Daily Budget Computation: floor(minutesPerWorkday * allocationPct / 100)")
    class DailyBudgetComputationTests {

        private int computeBudget(int minutesPerWorkday, BigDecimal allocationPct) {
            return BigDecimal.valueOf(minutesPerWorkday)
                    .multiply(allocationPct)
                    .divide(BigDecimal.valueOf(100), 0, RoundingMode.FLOOR)
                    .intValue();
        }

        @Test
        @DisplayName("Exact budget: 480 minutes @ 100% allocation = 480")
        void standardFullTime() {
            assertThat(computeBudget(480, new BigDecimal("100"))).isEqualTo(480);
        }

        @Test
        @DisplayName("Half-time budget: 480 minutes @ 50% allocation = 240")
        void halfTime() {
            assertThat(computeBudget(480, new BigDecimal("50"))).isEqualTo(240);
        }

        @Test
        @DisplayName("Floor rounding: 450 minutes @ 33.33% = floor(149.985) = 149")
        void partialFractionFloorRounding() {
            assertThat(computeBudget(450, new BigDecimal("33.33"))).isEqualTo(149);
        }

        @Test
        @DisplayName("Floor rounding: 480 minutes @ 80.50% = floor(386.4) = 386")
        void fractionalAllocationFloorRounding() {
            assertThat(computeBudget(480, new BigDecimal("80.50"))).isEqualTo(386);
        }

        @Test
        @DisplayName("Low allocation floor rounding: 500 minutes @ 12.25% = floor(61.25) = 61")
        void lowFractionalAllocation() {
            assertThat(computeBudget(500, new BigDecimal("12.25"))).isEqualTo(61);
        }

        @Test
        @DisplayName("WorkingCalendarCompiler correctly compiles dailyBudgetMinutes using floor formula")
        void workingCalendarCompilerCalculatesDailyBudgetCorrectly() {
            DeveloperCapacity capacity = new DeveloperCapacity();
            capacity.setId(UUID.randomUUID());
            capacity.setProjectId(projectId);
            capacity.setUserId(developerId);
            capacity.setMinutesPerWorkday(450);
            capacity.setAllocationPct(new BigDecimal("33.33"));
            capacity.setEffectiveFrom(LocalDate.of(2026, 12, 1));
            capacity.setEffectiveTo(null);
            capacity.setWeeklySlots(List.of(new WeeklySlot(2, 540, 1020))); // Tuesday 9:00 - 17:00 (480 mins)
            capacity.setTimeOff(List.of());

            LocalDate targetDate = LocalDate.of(2026, 12, 1); // Tuesday (dayOfWeek = 2)

            ResourceCalendarResponse calendar = WorkingCalendarCompiler.compile(
                    projectId,
                    developerId,
                    10L,
                    "UTC",
                    "ELIGIBLE",
                    targetDate,
                    targetDate,
                    List.of(capacity)
            );

            assertThat(calendar.days()).hasSize(1);
            ResourceCalendarResponse.CalendarDay day = calendar.days().get(0);
            assertThat(day.date()).isEqualTo(targetDate);
            // floor(450 * 33.33 / 100) = 149
            assertThat(day.dailyBudgetMinutes()).isEqualTo(149);
            assertThat(day.rawAvailableMinutes()).isEqualTo(480);
            assertThat(day.missingCapacity()).isFalse();
        }
    }

    // =========================================================================
    // 2. Skill Level Boundary and Normalization Tests
    // =========================================================================

    @Nested
    @DisplayName("2. Skill Level Boundary & Normalization (1..5, lowercase strip)")
    class SkillLevelBoundaryAndNormalizationTests {

        @Test
        @DisplayName("SkillLevel record trims and lowercases skill name")
        void skillLevelNormalizesName() {
            SkillLevel sl1 = new SkillLevel("  Java  ", 3);
            assertThat(sl1.skill()).isEqualTo("java");
            assertThat(sl1.level()).isEqualTo(3);

            SkillLevel sl2 = new SkillLevel("POSTGRESQL", 5);
            assertThat(sl2.skill()).isEqualTo("postgresql");
            assertThat(sl2.level()).isEqualTo(5);

            SkillLevel sl3 = new SkillLevel("\tTypeScript \n", 1);
            assertThat(sl3.skill()).isEqualTo("typescript");
            assertThat(sl3.level()).isEqualTo(1);
        }

        @Test
        @DisplayName("SkillLevel rejects levels outside 1..5 boundary")
        void skillLevelRejectsInvalidLevels() {
            assertThatThrownBy(() -> new SkillLevel("java", 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Skill and level 1..5 are required");

            assertThatThrownBy(() -> new SkillLevel("java", 6))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Skill and level 1..5 are required");

            assertThatThrownBy(() -> new SkillLevel("java", -1))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("SkillLevel rejects null or blank skill names")
        void skillLevelRejectsBlankName() {
            assertThatThrownBy(() -> new SkillLevel(null, 3))
                    .isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> new SkillLevel("", 3))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> new SkillLevel("   ", 3))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("ResourceCapacityValidator detects duplicate skill names case-insensitively")
        void validatorRejectsDuplicateSkills() {
            List<SkillLevel> skills = List.of(
                    new SkillLevel("java", 3),
                    new SkillLevel("JAVA", 4)
            );

            assertThatThrownBy(() -> ResourceCapacityValidator.validateSkills(skills))
                    .isInstanceOf(ResourceCapacityException.class)
                    .satisfies(ex -> {
                        ResourceCapacityException rce = (ResourceCapacityException) ex;
                        assertThat(rce.getCode()).isEqualTo("VALIDATION_FAILED");
                        assertThat(rce.getMessage()).contains("Duplicate skill name: java");
                    });
        }

        @Test
        @DisplayName("ResourceCapacityValidator rejects skill name exceeding 80 characters")
        void validatorRejectsExcessiveSkillNameLength() {
            String longSkillName = "a".repeat(81);
            SkillLevel skillLevel = new SkillLevel(longSkillName, 3);

            assertThatThrownBy(() -> ResourceCapacityValidator.validateSkills(List.of(skillLevel)))
                    .isInstanceOf(ResourceCapacityException.class)
                    .satisfies(ex -> {
                        ResourceCapacityException rce = (ResourceCapacityException) ex;
                        assertThat(rce.getCode()).isEqualTo("VALIDATION_FAILED");
                        assertThat(rce.getMessage()).contains("Skill name must not exceed 80 characters");
                    });
        }
    }

    // =========================================================================
    // 3. Weekly Working Slot Bounds Tests
    // =========================================================================

    @Nested
    @DisplayName("3. Weekly Working Slot Bounds (day 1..7, start >= 0, end <= 1440, end > start)")
    class WeeklyWorkingSlotBoundsTests {

        @Test
        @DisplayName("Valid weekly working slot boundaries succeed")
        void validWeeklySlots() {
            WeeklySlot slot1 = new WeeklySlot(1, 0, 1440); // Full Monday
            assertThat(slot1.dayOfWeek()).isEqualTo(1);
            assertThat(slot1.startMinute()).isEqualTo(0);
            assertThat(slot1.endMinute()).isEqualTo(1440);

            WeeklySlot slot2 = new WeeklySlot(7, 540, 1020); // Sunday 9:00 - 17:00
            assertThat(slot2.dayOfWeek()).isEqualTo(7);
            assertThat(slot2.startMinute()).isEqualTo(540);
            assertThat(slot2.endMinute()).isEqualTo(1020);
        }

        @Test
        @DisplayName("WeeklySlot rejects invalid dayOfWeek outside 1..7")
        void weeklySlotRejectsInvalidDayOfWeek() {
            assertThatThrownBy(() -> new WeeklySlot(0, 540, 1020))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid weekly working slot");

            assertThatThrownBy(() -> new WeeklySlot(8, 540, 1020))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("WeeklySlot rejects negative startMinute or endMinute > 1440")
        void weeklySlotRejectsOutOfRangeMinutes() {
            assertThatThrownBy(() -> new WeeklySlot(1, -1, 1020))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> new WeeklySlot(1, 540, 1441))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("WeeklySlot rejects endMinute <= startMinute")
        void weeklySlotRejectsNonPositiveDuration() {
            // Equal start and end
            assertThatThrownBy(() -> new WeeklySlot(1, 540, 540))
                    .isInstanceOf(IllegalArgumentException.class);

            // Inverted start and end
            assertThatThrownBy(() -> new WeeklySlot(1, 600, 540))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Validator rejects empty weekly working slots")
        void validatorRejectsEmptyWeeklySlots() {
            assertThatThrownBy(() -> ResourceCapacityValidator.validateWeeklySlots(List.of()))
                    .isInstanceOf(ResourceCapacityException.class)
                    .satisfies(ex -> {
                        ResourceCapacityException rce = (ResourceCapacityException) ex;
                        assertThat(rce.getCode()).isEqualTo("RESOURCE_CALENDAR_INVALID");
                        assertThat(rce.getMessage()).contains("Weekly working slots must not be empty");
                    });
        }
    }

    // =========================================================================
    // 4. Time Off Interval Validation Tests
    // =========================================================================

    @Nested
    @DisplayName("4. Time Off Interval Validation (positive duration, end > start)")
    class TimeOffIntervalValidationTests {

        @Test
        @DisplayName("Valid time off interval with positive duration succeeds")
        void validTimeOffInterval() {
            Instant start = Instant.parse("2026-12-10T09:00:00Z");
            Instant end = Instant.parse("2026-12-10T17:00:00Z");

            TimeOff timeOff = new TimeOff(start, end);
            assertThat(timeOff.startAt()).isEqualTo(start);
            assertThat(timeOff.endAt()).isEqualTo(end);
        }

        @Test
        @DisplayName("TimeOff rejects endAt <= startAt")
        void timeOffRejectsNonPositiveDuration() {
            Instant t1 = Instant.parse("2026-12-10T09:00:00Z");

            // Equal timestamps
            assertThatThrownBy(() -> new TimeOff(t1, t1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Time off must have positive duration");

            // Inverted timestamps
            Instant earlier = Instant.parse("2026-12-10T08:00:00Z");
            assertThatThrownBy(() -> new TimeOff(t1, earlier))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Time off must have positive duration");
        }

        @Test
        @DisplayName("TimeOff rejects null startAt or endAt")
        void timeOffRejectsNulls() {
            Instant now = Instant.now();
            assertThatThrownBy(() -> new TimeOff(null, now))
                    .isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> new TimeOff(now, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    // =========================================================================
    // 5. Overlap Detection Tests (Weekly Slots and Time-Off)
    // =========================================================================

    @Nested
    @DisplayName("5. Overlap Detection for Weekly Slots and Time Off")
    class OverlapDetectionTests {

        @Test
        @DisplayName("Validator detects overlapping weekly slots on the same day")
        void weeklySlotsOverlapDetectedOnSameDay() {
            List<WeeklySlot> overlappingSlots = List.of(
                    new WeeklySlot(1, 540, 720), // Mon 9:00 - 12:00
                    new WeeklySlot(1, 660, 900)  // Mon 11:00 - 15:00 (overlaps from 11:00 to 12:00)
            );

            assertThatThrownBy(() -> ResourceCapacityValidator.validateWeeklySlots(overlappingSlots))
                    .isInstanceOf(ResourceCapacityException.class)
                    .satisfies(ex -> {
                        ResourceCapacityException rce = (ResourceCapacityException) ex;
                        assertThat(rce.getCode()).isEqualTo("RESOURCE_CALENDAR_INVALID");
                        assertThat(rce.getMessage()).contains("Weekly slots overlap on day 1");
                    });
        }

        @Test
        @DisplayName("Adjacent weekly slots on the same day do not overlap")
        void adjacentWeeklySlotsDoNotOverlap() {
            List<WeeklySlot> adjacentSlots = List.of(
                    new WeeklySlot(1, 540, 720), // Mon 9:00 - 12:00
                    new WeeklySlot(1, 720, 960)  // Mon 12:00 - 16:00 (adjacent at 720)
            );

            // Must not throw
            ResourceCapacityValidator.validateWeeklySlots(adjacentSlots);
        }

        @Test
        @DisplayName("Same hours on different days do not overlap")
        void sameHoursDifferentDaysDoNotOverlap() {
            List<WeeklySlot> differentDays = List.of(
                    new WeeklySlot(1, 540, 1020), // Monday 9:00 - 17:00
                    new WeeklySlot(2, 540, 1020)  // Tuesday 9:00 - 17:00
            );

            // Must not throw
            ResourceCapacityValidator.validateWeeklySlots(differentDays);
        }

        @Test
        @DisplayName("Validator detects overlapping time off intervals")
        void timeOffOverlapDetected() {
            List<TimeOff> overlappingTimeOff = List.of(
                    new TimeOff(Instant.parse("2026-12-01T09:00:00Z"), Instant.parse("2026-12-01T15:00:00Z")),
                    new TimeOff(Instant.parse("2026-12-01T14:00:00Z"), Instant.parse("2026-12-01T18:00:00Z")) // Overlaps 14:00 - 15:00
            );

            assertThatThrownBy(() -> ResourceCapacityValidator.validateTimeOff(overlappingTimeOff))
                    .isInstanceOf(ResourceCapacityException.class)
                    .satisfies(ex -> {
                        ResourceCapacityException rce = (ResourceCapacityException) ex;
                        assertThat(rce.getCode()).isEqualTo("TIME_OFF_OVERLAP");
                        assertThat(rce.getMessage()).contains("Time off intervals overlap");
                    });
        }

        @Test
        @DisplayName("Adjacent time off intervals do not overlap")
        void adjacentTimeOffIntervalsDoNotOverlap() {
            List<TimeOff> adjacentTimeOff = List.of(
                    new TimeOff(Instant.parse("2026-12-01T09:00:00Z"), Instant.parse("2026-12-01T13:00:00Z")),
                    new TimeOff(Instant.parse("2026-12-01T13:00:00Z"), Instant.parse("2026-12-01T18:00:00Z")) // Adjacent at 13:00
            );

            // Must not throw
            ResourceCapacityValidator.validateTimeOff(adjacentTimeOff);
        }

        @Test
        @DisplayName("putCapacity rejects overlapping weekly slots through service validator")
        void putCapacityRejectsOverlappingWeeklySlots() {
            PutResourceCapacityRequest request = new PutResourceCapacityRequest(
                    developerId,
                    LocalDate.of(2026, 12, 1),
                    null,
                    List.of(new SkillLevel("java", 3)),
                    List.of(
                            new WeeklySlot(1, 540, 720),
                            new WeeklySlot(1, 660, 900) // Overlaps on Monday
                    ),
                    List.of(),
                    480,
                    new BigDecimal("100"),
                    10L,
                    null
            );

            assertThatThrownBy(() -> service.putCapacity(pmPrincipal, workspaceId, projectId, request))
                    .isInstanceOf(ResourceCapacityException.class)
                    .satisfies(ex -> {
                        ResourceCapacityException rce = (ResourceCapacityException) ex;
                        assertThat(rce.getCode()).isEqualTo("RESOURCE_CALENDAR_INVALID");
                        assertThat(rce.getMessage()).contains("Weekly slots overlap on day 1");
                    });

            verify(capacityRepository, never()).save(any());
        }

        @Test
        @DisplayName("putCapacity rejects overlapping time off intervals through service validator")
        void putCapacityRejectsOverlappingTimeOff() {
            PutResourceCapacityRequest request = new PutResourceCapacityRequest(
                    developerId,
                    LocalDate.of(2026, 12, 1),
                    null,
                    List.of(new SkillLevel("java", 3)),
                    List.of(new WeeklySlot(1, 540, 1020)),
                    List.of(
                            new TimeOff(Instant.parse("2026-12-05T09:00:00Z"), Instant.parse("2026-12-05T13:00:00Z")),
                            new TimeOff(Instant.parse("2026-12-05T12:00:00Z"), Instant.parse("2026-12-05T17:00:00Z")) // Overlaps
                    ),
                    480,
                    new BigDecimal("100"),
                    10L,
                    null
            );

            assertThatThrownBy(() -> service.putCapacity(pmPrincipal, workspaceId, projectId, request))
                    .isInstanceOf(ResourceCapacityException.class)
                    .satisfies(ex -> {
                        ResourceCapacityException rce = (ResourceCapacityException) ex;
                        assertThat(rce.getCode()).isEqualTo("TIME_OFF_OVERLAP");
                    });

            verify(capacityRepository, never()).save(any());
        }
    }

    // =========================================================================
    // 6. Non-Developer Role Rejection Tests
    // =========================================================================

    @Nested
    @DisplayName("6. Non-Developer Role Rejection for Target Capacity User")
    class NonDeveloperRoleRejectionTests {

        @Test
        @DisplayName("putCapacity throws ASSIGNEE_NOT_DEVELOPER when target user lacks DEVELOPER role")
        void putCapacityRejectsNonDeveloperTargetUser() {
            UUID nonDevUserId = UUID.randomUUID();

            when(jdbcTemplate.queryForObject(contains("SELECT ds_has_project_role"), eq(Boolean.class), eq(projectId), eq(nonDevUserId)))
                    .thenReturn(false);

            PutResourceCapacityRequest request = new PutResourceCapacityRequest(
                    nonDevUserId,
                    LocalDate.of(2026, 12, 1),
                    null,
                    List.of(new SkillLevel("java", 3)),
                    List.of(new WeeklySlot(1, 540, 1020)),
                    List.of(),
                    480,
                    new BigDecimal("100"),
                    10L,
                    null
            );

            assertThatThrownBy(() -> service.putCapacity(pmPrincipal, workspaceId, projectId, request))
                    .isInstanceOf(ResourceCapacityException.class)
                    .satisfies(ex -> {
                        ResourceCapacityException rce = (ResourceCapacityException) ex;
                        assertThat(rce.getStatus().value()).isEqualTo(400);
                        assertThat(rce.getCode()).isEqualTo("ASSIGNEE_NOT_DEVELOPER");
                        assertThat(rce.getMessage()).contains("Target user is not an active developer");
                    });

            verify(capacityRepository, never()).save(any());
        }

        @Test
        @DisplayName("putCapacity succeeds when target user holds active DEVELOPER role")
        void putCapacitySucceedsForActiveDeveloper() {
            when(jdbcTemplate.queryForObject(contains("SELECT ds_has_project_role"), eq(Boolean.class), eq(projectId), eq(developerId)))
                    .thenReturn(true);

            when(capacityRepository.findByProjectIdAndUserIdAndEffectiveFrom(projectId, developerId, LocalDate.of(2026, 12, 1)))
                    .thenReturn(Optional.empty());

            when(capacityRepository.save(any(DeveloperCapacity.class)))
                    .thenAnswer(inv -> {
                        DeveloperCapacity cap = inv.getArgument(0);
                        cap.setId(UUID.randomUUID());
                        cap.setVersion(0L);
                        return cap;
                    });

            PutResourceCapacityRequest request = new PutResourceCapacityRequest(
                    developerId,
                    LocalDate.of(2026, 12, 1),
                    null,
                    List.of(new SkillLevel("java", 3)),
                    List.of(new WeeklySlot(1, 540, 1020)),
                    List.of(),
                    480,
                    new BigDecimal("100"),
                    10L,
                    null
            );

            ResourceCapacityService.PutCapacityResult result = service.putCapacity(pmPrincipal, workspaceId, projectId, request);

            assertThat(result).isNotNull();
            assertThat(result.created()).isTrue();
            assertThat(result.response().developerId()).isEqualTo(developerId);
            assertThat(result.response().eligibility()).isEqualTo("ELIGIBLE");
            assertThat(result.response().minutesPerDay()).isEqualTo(480);
        }
    }

    // =========================================================================
    // 7. Version & Lifecycle Tests (Stale Versions, Effective Change, Add Time Off)
    // =========================================================================

    @Nested
    @DisplayName("7. Optimistic Locking & Effective Change Lifecycle")
    class VersionAndLifecycleTests {

        @Test
        @DisplayName("putCapacity rejects mismatched expectedProjectVersion with PLAN_STALE")
        void putCapacityRejectsStaleProjectVersion() {
            PutResourceCapacityRequest request = new PutResourceCapacityRequest(
                    developerId,
                    LocalDate.of(2026, 12, 1),
                    null,
                    List.of(new SkillLevel("java", 3)),
                    List.of(new WeeklySlot(1, 540, 1020)),
                    List.of(),
                    480,
                    new BigDecimal("100"),
                    9L, // Stale project version (actual is 10L)
                    null
            );

            assertThatThrownBy(() -> service.putCapacity(pmPrincipal, workspaceId, projectId, request))
                    .isInstanceOf(ResourceCapacityException.class)
                    .satisfies(ex -> {
                        ResourceCapacityException rce = (ResourceCapacityException) ex;
                        assertThat(rce.getStatus().value()).isEqualTo(409);
                        assertThat(rce.getCode()).isEqualTo("PLAN_STALE");
                    });
        }

        @Test
        @DisplayName("effectiveChange closes superseded capacity and creates new effective record")
        void effectiveChangeLifecycle() {
            UUID capacityId = UUID.randomUUID();
            DeveloperCapacity oldCapacity = new DeveloperCapacity();
            oldCapacity.setId(capacityId);
            oldCapacity.setProjectId(projectId);
            oldCapacity.setUserId(developerId);
            oldCapacity.setEffectiveFrom(LocalDate.of(2026, 11, 1));
            oldCapacity.setEffectiveTo(null); // Open-ended
            oldCapacity.setMinutesPerWorkday(480);
            oldCapacity.setAllocationPct(new BigDecimal("100"));
            oldCapacity.setVersion(0L);

            when(capacityRepository.findByIdAndProjectId(capacityId, projectId))
                    .thenReturn(Optional.of(oldCapacity));

            when(capacityRepository.save(any(DeveloperCapacity.class)))
                    .thenAnswer(inv -> {
                        DeveloperCapacity cap = inv.getArgument(0);
                        if (cap.getId() == null) {
                            cap.setId(UUID.randomUUID());
                            cap.setVersion(0L);
                        }
                        return cap;
                    });

            EffectiveCapacityChangeRequest request = new EffectiveCapacityChangeRequest(
                    capacityId,
                    0L,
                    LocalDate.of(2026, 12, 1),
                    List.of(new SkillLevel("java", 4)),
                    List.of(new WeeklySlot(1, 540, 1020)),
                    List.of(),
                    480,
                    new BigDecimal("80.00"),
                    "Promoted and allocation changed",
                    10L
            );

            var response = service.effectiveChange(pmPrincipal, workspaceId, projectId, developerId, request);

            assertThat(response).isNotNull();
            assertThat(response.developerId()).isEqualTo(developerId);
            assertThat(response.effectiveFrom()).isEqualTo(LocalDate.of(2026, 12, 1));
            assertThat(response.allocationPct()).isEqualTo(new BigDecimal("80.00"));

            // Verify old capacity is now closed at effectiveFrom
            assertThat(oldCapacity.getEffectiveTo()).isEqualTo(LocalDate.of(2026, 12, 1));
            verify(capacityRepository).save(oldCapacity);
        }

        @Test
        @DisplayName("addTimeOff appends time off to intersecting capacity records")
        void addTimeOffAppendsToIntersectingCapacities() {
            UUID capacityId = UUID.randomUUID();
            DeveloperCapacity capacity = new DeveloperCapacity();
            capacity.setId(capacityId);
            capacity.setProjectId(projectId);
            capacity.setUserId(developerId);
            capacity.setEffectiveFrom(LocalDate.of(2026, 12, 1));
            capacity.setEffectiveTo(null);
            capacity.setTimeOff(new java.util.ArrayList<>(List.of(
                    new TimeOff(Instant.parse("2026-12-05T09:00:00Z"), Instant.parse("2026-12-05T17:00:00Z"))
            )));
            capacity.setVersion(0L);

            Instant newStart = Instant.parse("2026-12-15T09:00:00Z");
            Instant newEnd = Instant.parse("2026-12-15T17:00:00Z");

            when(capacityRepository.findIntersecting(eq(projectId), eq(developerId), any(LocalDate.class), any(LocalDate.class)))
                    .thenReturn(List.of(capacity));

            AddTimeOffRequest request = new AddTimeOffRequest(
                    developerId,
                    newStart,
                    newEnd,
                    "Doctor appointment",
                    10L
            );

            service.addTimeOff(pmPrincipal, workspaceId, projectId, request);

            ArgumentCaptor<DeveloperCapacity> captor = ArgumentCaptor.forClass(DeveloperCapacity.class);
            verify(capacityRepository).save(captor.capture());

            DeveloperCapacity saved = captor.getValue();
            assertThat(saved.getTimeOff()).hasSize(2);
            assertThat(saved.getTimeOff()).extracting(TimeOff::startAt).contains(newStart);
        }

        @Test
        @DisplayName("addTimeOff rejects overlapping new interval with TIME_OFF_OVERLAP")
        void addTimeOffRejectsOverlap() {
            UUID capacityId = UUID.randomUUID();
            DeveloperCapacity capacity = new DeveloperCapacity();
            capacity.setId(capacityId);
            capacity.setProjectId(projectId);
            capacity.setUserId(developerId);
            capacity.setEffectiveFrom(LocalDate.of(2026, 12, 1));
            capacity.setEffectiveTo(null);
            capacity.setTimeOff(List.of(
                    new TimeOff(Instant.parse("2026-12-05T09:00:00Z"), Instant.parse("2026-12-05T17:00:00Z"))
            ));
            capacity.setVersion(0L);

            // New interval overlaps with existing
            Instant newStart = Instant.parse("2026-12-05T12:00:00Z");
            Instant newEnd = Instant.parse("2026-12-05T19:00:00Z");

            when(capacityRepository.findIntersecting(eq(projectId), eq(developerId), any(LocalDate.class), any(LocalDate.class)))
                    .thenReturn(List.of(capacity));

            AddTimeOffRequest request = new AddTimeOffRequest(
                    developerId,
                    newStart,
                    newEnd,
                    "Conference",
                    10L
            );

            assertThatThrownBy(() -> service.addTimeOff(pmPrincipal, workspaceId, projectId, request))
                    .isInstanceOf(ResourceCapacityException.class)
                    .satisfies(ex -> {
                        ResourceCapacityException rce = (ResourceCapacityException) ex;
                        assertThat(rce.getCode()).isEqualTo("TIME_OFF_OVERLAP");
                    });
        }
    }
}

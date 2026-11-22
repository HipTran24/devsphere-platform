package com.project.devsphere.solver;

import com.project.devsphere.resource.api.ResourceCapacitySnapshot;
import com.project.devsphere.resource.api.SkillLevel;
import com.project.devsphere.resource.api.TimeOff;
import com.project.devsphere.resource.api.WeeklySlot;
import com.project.devsphere.solver.domain.SolverInputSnapshot;
import com.project.devsphere.solver.domain.SolverResult;
import com.project.devsphere.solver.infrastructure.SchedulingSolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SchedulingSolverEngineTest {

    private SchedulingSolver solver;

    private final UUID projectId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final LocalDate horizonStart = LocalDate.of(2026, 12, 1); // Tuesday
    private final LocalDate horizonEnd = LocalDate.of(2026, 12, 20);
    private final String timezone = "UTC";

    @BeforeEach
    void setUp() {
        solver = new SchedulingSolver();
    }

    private ResourceCapacitySnapshot.DeveloperCapacityRecord createDeveloper(
            UUID devId,
            String skill,
            int skillLevel,
            int minutesPerDay,
            BigDecimal allocationPct,
            List<WeeklySlot> customSlots,
            List<TimeOff> timeOffs) {

        List<WeeklySlot> slots = customSlots != null ? customSlots : List.of(
                new WeeklySlot(1, 540, 1020), // Mon 09:00 - 17:00 (480m)
                new WeeklySlot(2, 540, 1020), // Tue 09:00 - 17:00 (480m)
                new WeeklySlot(3, 540, 1020), // Wed 09:00 - 17:00 (480m)
                new WeeklySlot(4, 540, 1020), // Thu 09:00 - 17:00 (480m)
                new WeeklySlot(5, 540, 1020)  // Fri 09:00 - 17:00 (480m)
        );

        int budget = BigDecimal.valueOf(minutesPerDay)
                .multiply(allocationPct != null ? allocationPct : new BigDecimal("100"))
                .divide(BigDecimal.valueOf(100), java.math.RoundingMode.FLOOR)
                .intValue();

        List<SkillLevel> skills = skill != null
                ? List.of(new SkillLevel(skill, skillLevel))
                : List.of();

        ResourceCapacitySnapshot.CapacityInterval interval = new ResourceCapacitySnapshot.CapacityInterval(
                UUID.randomUUID(),
                1L,
                LocalDate.of(2026, 12, 1),
                null,
                minutesPerDay,
                allocationPct != null ? allocationPct : new BigDecimal("100.00"),
                budget,
                skills,
                slots,
                timeOffs != null ? timeOffs : List.of()
        );

        return new ResourceCapacitySnapshot.DeveloperCapacityRecord(devId, true, List.of(interval));
    }

    private ResourceCapacitySnapshot.DeveloperCapacityRecord createMultiSkillDeveloper(
            UUID devId, List<SkillLevel> skills, List<TimeOff> timeOffs) {

        List<WeeklySlot> slots = List.of(
                new WeeklySlot(1, 540, 1020),
                new WeeklySlot(2, 540, 1020),
                new WeeklySlot(3, 540, 1020),
                new WeeklySlot(4, 540, 1020),
                new WeeklySlot(5, 540, 1020)
        );

        ResourceCapacitySnapshot.CapacityInterval interval = new ResourceCapacitySnapshot.CapacityInterval(
                UUID.randomUUID(),
                1L,
                LocalDate.of(2026, 12, 1),
                null,
                480,
                new BigDecimal("100.00"),
                480,
                skills,
                slots,
                timeOffs != null ? timeOffs : List.of()
        );

        return new ResourceCapacitySnapshot.DeveloperCapacityRecord(devId, true, List.of(interval));
    }

    // =========================================================================
    // 1. Genuine Status Mapping & Distinction Tests
    // =========================================================================
    @Nested
    @DisplayName("1. Genuine Status Mapping & Discretion")
    class StatusMappingTests {

        @Test
        @DisplayName("FEASIBLE is strictly distinct from OPTIMAL and preserves its invariant name")
        void feasibleIsStrictlyDistinctFromOptimal() {
            assertThat(SolverResult.Status.FEASIBLE).isNotEqualTo(SolverResult.Status.OPTIMAL);
            assertThat(SolverResult.Status.FEASIBLE.name()).isEqualTo("FEASIBLE");
            assertThat(SolverResult.Status.OPTIMAL.name()).isEqualTo("OPTIMAL");
            assertThat(SolverResult.Status.INFEASIBLE.name()).isEqualTo("INFEASIBLE");
            assertThat(SolverResult.Status.UNKNOWN.name()).isEqualTo("UNKNOWN");
            assertThat(SolverResult.Status.ERROR.name()).isEqualTo("ERROR");
        }

        @Test
        @DisplayName("Empty task set returns OPTIMAL with zero assignments and zero makespan")
        void emptyTaskSetReturnsOptimal() {
            SolverInputSnapshot snapshot = new SolverInputSnapshot(
                    projectId, workspaceId, 1L, timezone, horizonStart, horizonEnd,
                    10, 2, 42,
                    List.of(), List.of(), List.of(), List.of()
            );

            SolverResult result = solver.solve(snapshot);

            assertThat(result.status()).isEqualTo(SolverResult.Status.OPTIMAL);
            assertThat(result.assignments()).isEmpty();
            assertThat(result.makespanMinutes()).isEqualTo(0L);
            assertThat(result.totalLatenessMinutes()).isEqualTo(0L);
        }

        @Test
        @DisplayName("Null snapshot returns ERROR")
        void nullSnapshotReturnsError() {
            SolverResult result = solver.solve(null);

            assertThat(result.status()).isEqualTo(SolverResult.Status.ERROR);
            assertThat(result.diagnostics().message()).contains("Snapshot must not be null");
        }

        @Test
        @DisplayName("Horizon end before start returns ERROR")
        void horizonEndBeforeStartReturnsError() {
            SolverInputSnapshot snapshot = new SolverInputSnapshot(
                    projectId, workspaceId, 1L, timezone,
                    LocalDate.of(2026, 12, 15),
                    LocalDate.of(2026, 12, 1), // inverted
                    10, 2, 42,
                    List.of(new SolverInputSnapshot.TaskItem(UUID.randomUUID(), "T1", "Task 1", 60, List.of(), null, null, false)),
                    List.of(), List.of(), List.of()
            );

            SolverResult result = solver.solve(snapshot);

            assertThat(result.status()).isEqualTo(SolverResult.Status.ERROR);
            assertThat(result.diagnostics().message()).contains("horizonEnd cannot be before horizonStart");
        }

        @Test
        @DisplayName("No eligible developer available returns INFEASIBLE")
        void noEligibleDeveloperReturnsInfeasible() {
            UUID taskId = UUID.randomUUID();
            var task = new SolverInputSnapshot.TaskItem(
                    taskId, "T-NO-DEV", "No Dev", 60,
                    List.of(new SolverInputSnapshot.SkillRequirement("golang", 3)),
                    null, null, false
            );

            SolverInputSnapshot snapshot = new SolverInputSnapshot(
                    projectId, workspaceId, 1L, timezone, horizonStart, horizonEnd,
                    10, 2, 42,
                    List.of(task), List.of(), List.of(), List.of() // developers list empty
            );

            SolverResult result = solver.solve(snapshot);

            assertThat(result.status()).isEqualTo(SolverResult.Status.INFEASIBLE);
            assertThat(result.assignments()).isEmpty();
        }
    }

    // =========================================================================
    // 2. Task Precedence & Positive Lag Tests
    // =========================================================================
    @Nested
    @DisplayName("2. Task Dependencies & Positive Lag")
    class PrecedenceAndLagTests {

        @Test
        @DisplayName("Successor cannot start before predecessor finish + lag minutes")
        void successorStartsAfterPredecessorFinishPlusLag() {
            UUID devId = UUID.randomUUID();
            var dev = createDeveloper(devId, "java", 3, 480, new BigDecimal("100"), null, null);

            UUID taskA = UUID.randomUUID();
            UUID taskB = UUID.randomUUID();
            var tA = new SolverInputSnapshot.TaskItem(taskA, "T-PRED", "Pred Task", 60, List.of(), null, null, false);
            var tB = new SolverInputSnapshot.TaskItem(taskB, "T-SUCC", "Succ Task", 60, List.of(), null, null, false);

            int lagMinutes = 60;
            var dep = new SolverInputSnapshot.TaskDependency(taskA, taskB, lagMinutes);

            SolverInputSnapshot snapshot = new SolverInputSnapshot(
                    projectId, workspaceId, 1L, timezone, horizonStart, horizonEnd,
                    15, 2, 42,
                    List.of(tA, tB), List.of(dep), List.of(dev), List.of()
            );

            SolverResult result = solver.solve(snapshot);

            assertThat(result.status()).isEqualTo(SolverResult.Status.OPTIMAL);
            assertThat(result.assignments()).hasSize(2);

            SolverResult.TaskAssignment assignA = result.assignments().stream().filter(a -> a.taskId().equals(taskA)).findFirst().orElseThrow();
            SolverResult.TaskAssignment assignB = result.assignments().stream().filter(a -> a.taskId().equals(taskB)).findFirst().orElseThrow();

            Instant minAllowedStartB = assignA.finishAt().plusSeconds(lagMinutes * 60L);
            assertThat(assignB.startAt()).isAfterOrEqualTo(minAllowedStartB);
        }

        @Test
        @DisplayName("Dependency on frozen baseline entry enforces successor start >= frozen.finishAt + lag")
        void dependencyOnFrozenBaselineEntry() {
            UUID devId = UUID.randomUUID();
            var dev = createDeveloper(devId, "java", 3, 480, new BigDecimal("100"), null, null);

            UUID frozenTaskId = UUID.randomUUID();
            Instant frozenStart = Instant.parse("2026-12-01T09:00:00Z");
            Instant frozenFinish = Instant.parse("2026-12-01T11:00:00Z");
            var frozenEntry = new SolverInputSnapshot.FrozenScheduleEntry(
                    frozenTaskId, devId, frozenStart, frozenFinish, 120
            );

            UUID successorTaskId = UUID.randomUUID();
            var succTask = new SolverInputSnapshot.TaskItem(successorTaskId, "T-SUCC-FROZEN", "Succ of Frozen", 60, List.of(), null, null, false);

            int lagMinutes = 30;
            var dep = new SolverInputSnapshot.TaskDependency(frozenTaskId, successorTaskId, lagMinutes);

            SolverInputSnapshot snapshot = new SolverInputSnapshot(
                    projectId, workspaceId, 1L, timezone, horizonStart, horizonEnd,
                    15, 2, 42,
                    List.of(succTask), List.of(dep), List.of(dev), List.of(frozenEntry)
            );

            SolverResult result = solver.solve(snapshot);

            assertThat(result.status()).isEqualTo(SolverResult.Status.OPTIMAL);
            SolverResult.TaskAssignment succAssign = result.assignments().get(0);

            Instant expectedMinStart = frozenFinish.plusSeconds(lagMinutes * 60L); // 11:30 UTC
            assertThat(succAssign.startAt()).isAfterOrEqualTo(expectedMinStart);
        }

        @Test
        @DisplayName("Predecessor missing in both tasks and frozen baseline entries returns INFEASIBLE")
        void missingPredecessorReturnsInfeasible() {
            UUID devId = UUID.randomUUID();
            var dev = createDeveloper(devId, "java", 3, 480, new BigDecimal("100"), null, null);

            UUID missingPredId = UUID.randomUUID();
            UUID succTaskId = UUID.randomUUID();
            var succTask = new SolverInputSnapshot.TaskItem(succTaskId, "T-ORPHAN", "Orphan Succ", 60, List.of(), null, null, false);

            var dep = new SolverInputSnapshot.TaskDependency(missingPredId, succTaskId, 0);

            SolverInputSnapshot snapshot = new SolverInputSnapshot(
                    projectId, workspaceId, 1L, timezone, horizonStart, horizonEnd,
                    10, 2, 42,
                    List.of(succTask), List.of(dep), List.of(dev), List.of()
            );

            SolverResult result = solver.solve(snapshot);

            assertThat(result.status()).isEqualTo(SolverResult.Status.INFEASIBLE);
            assertThat(result.diagnostics().message()).contains("not found in schedulable tasks or baseline anchors");
        }

        @Test
        @DisplayName("Multi-step dependency chain A -> B -> C respects sequential lags")
        void multiStepDependencyChain() {
            UUID devId = UUID.randomUUID();
            var dev = createDeveloper(devId, "java", 3, 480, new BigDecimal("100"), null, null);

            UUID taskA = UUID.randomUUID();
            UUID taskB = UUID.randomUUID();
            UUID taskC = UUID.randomUUID();

            var tA = new SolverInputSnapshot.TaskItem(taskA, "A", "Task A", 60, List.of(), null, null, false);
            var tB = new SolverInputSnapshot.TaskItem(taskB, "B", "Task B", 60, List.of(), null, null, false);
            var tC = new SolverInputSnapshot.TaskItem(taskC, "C", "Task C", 60, List.of(), null, null, false);

            var depAB = new SolverInputSnapshot.TaskDependency(taskA, taskB, 30);
            var depBC = new SolverInputSnapshot.TaskDependency(taskB, taskC, 60);

            SolverInputSnapshot snapshot = new SolverInputSnapshot(
                    projectId, workspaceId, 1L, timezone, horizonStart, horizonEnd,
                    15, 2, 42,
                    List.of(tA, tB, tC), List.of(depAB, depBC), List.of(dev), List.of()
            );

            SolverResult result = solver.solve(snapshot);

            assertThat(result.status()).isEqualTo(SolverResult.Status.OPTIMAL);

            SolverResult.TaskAssignment assignA = result.assignments().stream().filter(a -> a.taskId().equals(taskA)).findFirst().orElseThrow();
            SolverResult.TaskAssignment assignB = result.assignments().stream().filter(a -> a.taskId().equals(taskB)).findFirst().orElseThrow();
            SolverResult.TaskAssignment assignC = result.assignments().stream().filter(a -> a.taskId().equals(taskC)).findFirst().orElseThrow();

            assertThat(assignB.startAt()).isAfterOrEqualTo(assignA.finishAt().plusSeconds(30 * 60L));
            assertThat(assignC.startAt()).isAfterOrEqualTo(assignB.finishAt().plusSeconds(60 * 60L));
        }
    }

    // =========================================================================
    // 3. Skill Requirement Matching Tests
    // =========================================================================
    @Nested
    @DisplayName("3. Skill Requirement Matching & Boundaries")
    class SkillMatchingTests {

        @Test
        @DisplayName("Task assigned to developer meeting skill level >= required level")
        void assigneeMustPossessSkillAtOrAboveLevel() {
            UUID devJunior = UUID.randomUUID();
            var junior = createDeveloper(devJunior, "python", 2, 480, new BigDecimal("100"), null, null);

            UUID devSenior = UUID.randomUUID();
            var senior = createDeveloper(devSenior, "python", 4, 480, new BigDecimal("100"), null, null);

            UUID taskId = UUID.randomUUID();
            var task = new SolverInputSnapshot.TaskItem(
                    taskId, "T-PY", "Python Architecture", 60,
                    List.of(new SolverInputSnapshot.SkillRequirement("python", 3)),
                    null, null, false
            );

            SolverInputSnapshot snapshot = new SolverInputSnapshot(
                    projectId, workspaceId, 1L, timezone, horizonStart, horizonEnd,
                    10, 2, 42,
                    List.of(task), List.of(), List.of(junior, senior), List.of()
            );

            SolverResult result = solver.solve(snapshot);

            assertThat(result.status()).isEqualTo(SolverResult.Status.OPTIMAL);
            assertThat(result.assignments().get(0).developerId()).isEqualTo(devSenior);
        }

        @Test
        @DisplayName("Task requiring multiple skills assigned to developer possessing all required skills")
        void multipleSkillRequirements() {
            UUID devBackend = UUID.randomUUID();
            var dev1 = createMultiSkillDeveloper(devBackend, List.of(
                    new SkillLevel("backend", 4),
                    new SkillLevel("frontend", 1)
            ), null);

            UUID devFullstack = UUID.randomUUID();
            var dev2 = createMultiSkillDeveloper(devFullstack, List.of(
                    new SkillLevel("backend", 3),
                    new SkillLevel("frontend", 3)
            ), null);

            UUID taskId = UUID.randomUUID();
            var task = new SolverInputSnapshot.TaskItem(
                    taskId, "T-FULL", "Fullstack Feature", 60,
                    List.of(
                            new SolverInputSnapshot.SkillRequirement("backend", 3),
                            new SolverInputSnapshot.SkillRequirement("frontend", 3)
                    ),
                    null, null, false
            );

            SolverInputSnapshot snapshot = new SolverInputSnapshot(
                    projectId, workspaceId, 1L, timezone, horizonStart, horizonEnd,
                    10, 2, 42,
                    List.of(task), List.of(), List.of(dev1, dev2), List.of()
            );

            SolverResult result = solver.solve(snapshot);

            assertThat(result.status()).isEqualTo(SolverResult.Status.OPTIMAL);
            assertThat(result.assignments().get(0).developerId()).isEqualTo(devFullstack);
        }

        @Test
        @DisplayName("Developer skill matching is case-insensitive and ignores surrounding whitespace")
        void skillNormalizationCaseInsensitive() {
            UUID devId = UUID.randomUUID();
            var dev = createMultiSkillDeveloper(devId, List.of(
                    new SkillLevel("  JaVa  ", 3)
            ), null);

            UUID taskId = UUID.randomUUID();
            var task = new SolverInputSnapshot.TaskItem(
                    taskId, "T-NORM", "Java Normalization", 60,
                    List.of(new SolverInputSnapshot.SkillRequirement("JAVA", 3)),
                    null, null, false
            );

            SolverInputSnapshot snapshot = new SolverInputSnapshot(
                    projectId, workspaceId, 1L, timezone, horizonStart, horizonEnd,
                    10, 2, 42,
                    List.of(task), List.of(), List.of(dev), List.of()
            );

            SolverResult result = solver.solve(snapshot);

            assertThat(result.status()).isEqualTo(SolverResult.Status.OPTIMAL);
            assertThat(result.assignments().get(0).developerId()).isEqualTo(devId);
        }
    }

    // =========================================================================
    // 4. Assignee Capacity & No-Overlap Tests
    // =========================================================================
    @Nested
    @DisplayName("4. Developer Capacity & No Overlap Constraints")
    class NoOverlapTests {

        @Test
        @DisplayName("Single developer assigned two independent tasks: tasks never overlap in real time")
        void singleDeveloperNoOverlap() {
            UUID devId = UUID.randomUUID();
            var dev = createDeveloper(devId, "java", 3, 480, new BigDecimal("100"), null, null);

            UUID task1 = UUID.randomUUID();
            UUID task2 = UUID.randomUUID();
            var t1 = new SolverInputSnapshot.TaskItem(task1, "T1", "Task 1", 120, List.of(), null, null, false);
            var t2 = new SolverInputSnapshot.TaskItem(task2, "T2", "Task 2", 90, List.of(), null, null, false);

            SolverInputSnapshot snapshot = new SolverInputSnapshot(
                    projectId, workspaceId, 1L, timezone, horizonStart, horizonEnd,
                    15, 2, 42,
                    List.of(t1, t2), List.of(), List.of(dev), List.of()
            );

            SolverResult result = solver.solve(snapshot);

            assertThat(result.status()).isEqualTo(SolverResult.Status.OPTIMAL);
            assertThat(result.assignments()).hasSize(2);

            SolverResult.TaskAssignment assign1 = result.assignments().stream().filter(a -> a.taskId().equals(task1)).findFirst().orElseThrow();
            SolverResult.TaskAssignment assign2 = result.assignments().stream().filter(a -> a.taskId().equals(task2)).findFirst().orElseThrow();

            boolean noOverlap = assign1.finishAt().isBefore(assign2.startAt()) || assign1.finishAt().equals(assign2.startAt())
                    || assign2.finishAt().isBefore(assign1.startAt()) || assign2.finishAt().equals(assign1.startAt());

            assertThat(noOverlap)
                    .as("Tasks assigned to the same developer must not overlap in time")
                    .isTrue();
        }

        @Test
        @DisplayName("Two developers execute independent tasks concurrently in parallel to minimize makespan")
        void parallelExecutionAcrossDevelopers() {
            UUID dev1 = UUID.randomUUID();
            UUID dev2 = UUID.randomUUID();
            var devRec1 = createDeveloper(dev1, "java", 3, 480, new BigDecimal("100"), null, null);
            var devRec2 = createDeveloper(dev2, "java", 3, 480, new BigDecimal("100"), null, null);

            UUID task1 = UUID.randomUUID();
            UUID task2 = UUID.randomUUID();
            var t1 = new SolverInputSnapshot.TaskItem(task1, "T1", "Task 1", 120, List.of(), null, null, false);
            var t2 = new SolverInputSnapshot.TaskItem(task2, "T2", "Task 2", 120, List.of(), null, null, false);

            SolverInputSnapshot snapshot = new SolverInputSnapshot(
                    projectId, workspaceId, 1L, timezone, horizonStart, horizonEnd,
                    15, 2, 42,
                    List.of(t1, t2), List.of(), List.of(devRec1, devRec2), List.of()
            );

            SolverResult result = solver.solve(snapshot);

            assertThat(result.status()).isEqualTo(SolverResult.Status.OPTIMAL);
            assertThat(result.assignments()).hasSize(2);

            // Both start at 09:00 and finish at 11:00 UTC
            Instant expectedStart = Instant.parse("2026-12-01T09:00:00Z");
            Instant expectedFinish = Instant.parse("2026-12-01T11:00:00Z");

            assertThat(result.assignments().get(0).startAt()).isEqualTo(expectedStart);
            assertThat(result.assignments().get(0).finishAt()).isEqualTo(expectedFinish);
            assertThat(result.assignments().get(1).startAt()).isEqualTo(expectedStart);
            assertThat(result.assignments().get(1).finishAt()).isEqualTo(expectedFinish);

            // Makespan should be 660 mins from midnight (00:00 to 11:00)
            assertThat(result.makespanMinutes()).isEqualTo(660L);
        }
    }

    // =========================================================================
    // 5. Working Calendar Integration Tests
    // =========================================================================
    @Nested
    @DisplayName("5. Working Calendar Integration (Weekly Slots, Time Off & Capacity)")
    class WorkingCalendarTests {

        @Test
        @DisplayName("Task splits across non-working hours and nights, scheduling work only inside active weekly slots")
        void workScheduledOnlyInsideWeeklySlots() {
            UUID devId = UUID.randomUUID();
            // Developer works only 2 hours per day: 09:00 to 11:00 (120 mins)
            List<WeeklySlot> shortSlots = List.of(
                    new WeeklySlot(1, 540, 660), // Mon 09:00 - 11:00
                    new WeeklySlot(2, 540, 660), // Tue 09:00 - 11:00
                    new WeeklySlot(3, 540, 660)  // Wed 09:00 - 11:00
            );
            var dev = createDeveloper(devId, "java", 3, 120, new BigDecimal("100"), shortSlots, null);

            UUID taskId = UUID.randomUUID();
            // Task takes 180 minutes: 120m on Tuesday Dec 1, 60m on Wednesday Dec 2
            var task = new SolverInputSnapshot.TaskItem(taskId, "T-SPAN", "Spanning Task", 180, List.of(), null, null, false);

            SolverInputSnapshot snapshot = new SolverInputSnapshot(
                    projectId, workspaceId, 1L, timezone, horizonStart, horizonEnd,
                    15, 2, 42,
                    List.of(task), List.of(), List.of(dev), List.of()
            );

            SolverResult result = solver.solve(snapshot);

            assertThat(result.status()).isEqualTo(SolverResult.Status.OPTIMAL);
            SolverResult.TaskAssignment assign = result.assignments().get(0);

            assertThat(assign.startAt()).isEqualTo(Instant.parse("2026-12-01T09:00:00Z"));
            assertThat(assign.finishAt()).isEqualTo(Instant.parse("2026-12-02T10:00:00Z"));

            // Check segments: segment 1 on Tue (120m), segment 2 on Wed (60m)
            assertThat(assign.segments()).hasSize(2);
            assertThat(assign.segments().get(0).startAt()).isEqualTo(Instant.parse("2026-12-01T09:00:00Z"));
            assertThat(assign.segments().get(0).endAt()).isEqualTo(Instant.parse("2026-12-01T11:00:00Z"));
            assertThat(assign.segments().get(0).minutes()).isEqualTo(120);

            assertThat(assign.segments().get(1).startAt()).isEqualTo(Instant.parse("2026-12-02T09:00:00Z"));
            assertThat(assign.segments().get(1).endAt()).isEqualTo(Instant.parse("2026-12-02T10:00:00Z"));
            assertThat(assign.segments().get(1).minutes()).isEqualTo(60);
        }

        @Test
        @DisplayName("Developer time-off is strictly excluded and work is scheduled around the blocked interval")
        void workExcludesTimeOffInterval() {
            UUID devId = UUID.randomUUID();
            // Developer has time-off on Dec 1 from 09:00 to 12:00 UTC
            Instant toStart = Instant.parse("2026-12-01T09:00:00Z");
            Instant toEnd = Instant.parse("2026-12-01T12:00:00Z");
            var dev = createDeveloper(devId, "java", 3, 480, new BigDecimal("100"), null, List.of(new TimeOff(toStart, toEnd)));

            UUID taskId = UUID.randomUUID();
            var task = new SolverInputSnapshot.TaskItem(taskId, "T-TO", "Time Off Test", 60, List.of(), null, null, false);

            SolverInputSnapshot snapshot = new SolverInputSnapshot(
                    projectId, workspaceId, 1L, timezone, horizonStart, horizonEnd,
                    15, 2, 42,
                    List.of(task), List.of(), List.of(dev), List.of()
            );

            SolverResult result = solver.solve(snapshot);

            assertThat(result.status()).isEqualTo(SolverResult.Status.OPTIMAL);
            SolverResult.TaskAssignment assign = result.assignments().get(0);

            // Must start at or after 12:00 UTC
            assertThat(assign.startAt()).isAfterOrEqualTo(toEnd);
            assertThat(assign.startAt()).isEqualTo(Instant.parse("2026-12-01T12:00:00Z"));
        }

        @Test
        @DisplayName("Daily budget capped by allocation percentage (e.g. 50% allocation = 240 mins/day)")
        void dailyBudgetCappedByAllocationPercentage() {
            UUID devId = UUID.randomUUID();
            // 480 minutes workday at 50% allocation -> budget = 240 minutes per day (4 hours)
            var dev = createDeveloper(devId, "java", 3, 480, new BigDecimal("50.00"), null, null);

            UUID taskId = UUID.randomUUID();
            // Task requires 300 minutes (5 hours): 240m on Day 1, remaining 60m on Day 2
            var task = new SolverInputSnapshot.TaskItem(taskId, "T-BUDGET", "Allocation Budget Test", 300, List.of(), null, null, false);

            SolverInputSnapshot snapshot = new SolverInputSnapshot(
                    projectId, workspaceId, 1L, timezone, horizonStart, horizonEnd,
                    15, 2, 42,
                    List.of(task), List.of(), List.of(dev), List.of()
            );

            SolverResult result = solver.solve(snapshot);

            assertThat(result.status()).isEqualTo(SolverResult.Status.OPTIMAL);
            SolverResult.TaskAssignment assign = result.assignments().get(0);

            // Day 1 has 240m (09:00 - 13:00), Day 2 has 60m (09:00 - 10:00)
            assertThat(assign.segments()).hasSize(2);
            assertThat(assign.segments().get(0).minutes()).isEqualTo(240);
            assertThat(assign.segments().get(1).minutes()).isEqualTo(60);
        }
    }

    // =========================================================================
    // 6. Frozen Entries Preservation Tests
    // =========================================================================
    @Nested
    @DisplayName("6. Frozen Entries Preservation & Capacity Reservation")
    class FrozenEntriesTests {

        @Test
        @DisplayName("Frozen baseline entry blocks developer capacity and forces new work after frozen finish")
        void frozenEntriesPreserveCapacity() {
            UUID devId = UUID.randomUUID();
            var dev = createDeveloper(devId, "java", 3, 480, new BigDecimal("100"), null, null);

            UUID frozenTaskId = UUID.randomUUID();
            Instant frozenStart = Instant.parse("2026-12-01T09:00:00Z");
            Instant frozenFinish = Instant.parse("2026-12-01T13:00:00Z"); // 4 hours frozen
            var frozenEntry = new SolverInputSnapshot.FrozenScheduleEntry(
                    frozenTaskId, devId, frozenStart, frozenFinish, 240
            );

            UUID newTaskId = UUID.randomUUID();
            var newTask = new SolverInputSnapshot.TaskItem(newTaskId, "T-NEW", "New Task", 60, List.of(), null, null, false);

            SolverInputSnapshot snapshot = new SolverInputSnapshot(
                    projectId, workspaceId, 1L, timezone, horizonStart, horizonEnd,
                    15, 2, 42,
                    List.of(newTask), List.of(), List.of(dev), List.of(frozenEntry)
            );

            SolverResult result = solver.solve(snapshot);

            assertThat(result.status()).isEqualTo(SolverResult.Status.OPTIMAL);
            SolverResult.TaskAssignment assign = result.assignments().get(0);

            assertThat(assign.startAt()).isAfterOrEqualTo(frozenFinish);
            assertThat(assign.startAt()).isEqualTo(Instant.parse("2026-12-01T13:00:00Z"));
        }
    }

    // =========================================================================
    // 7. Soft Deadline Lateness & Strict Milestone Tests
    // =========================================================================
    @Nested
    @DisplayName("7. Soft Deadline Lateness Penalties & Strict Milestones")
    class DeadlineTests {

        @Test
        @DisplayName("Soft deadline lateness is penalized in objective function: urgent task is prioritized")
        void softDeadlinePrioritizedToMinimizeLateness() {
            UUID devId = UUID.randomUUID();
            var dev = createDeveloper(devId, "java", 3, 480, new BigDecimal("100"), null, null);

            UUID taskUrgent = UUID.randomUUID();
            UUID taskNormal = UUID.randomUUID();

            // Task 1 (Urgent): 360 mins, soft deadline Dec 1
            // Task 2 (Normal): 240 mins, no deadline
            // Day 1 has 480 mins. If normal ran first (240m), urgent could only get 240m on Day 1,
            // spilling into Day 2 and violating its deadline!
            // Therefore, CP-SAT must schedule urgent first so it finishes on Day 1 at 15:00 (0 lateness).
            var tNormal = new SolverInputSnapshot.TaskItem(
                    taskNormal, "T-NORMAL", "Normal Task", 240, List.of(), null,
                    null, false
            );
            var tUrgent = new SolverInputSnapshot.TaskItem(
                    taskUrgent, "T-URGENT", "Urgent Task", 360, List.of(), null,
                    LocalDate.of(2026, 12, 1), false
            );

            SolverInputSnapshot snapshot = new SolverInputSnapshot(
                    projectId, workspaceId, 1L, timezone, horizonStart, horizonEnd,
                    15, 2, 42,
                    List.of(tNormal, tUrgent), // order normal first in input list
                    List.of(), List.of(dev), List.of()
            );

            SolverResult result = solver.solve(snapshot);

            assertThat(result.status()).isEqualTo(SolverResult.Status.OPTIMAL);
            SolverResult.TaskAssignment assignUrgent = result.assignments().stream().filter(a -> a.taskId().equals(taskUrgent)).findFirst().orElseThrow();
            SolverResult.TaskAssignment assignNormal = result.assignments().stream().filter(a -> a.taskId().equals(taskNormal)).findFirst().orElseThrow();

            // Urgent task must be prioritized first (09:00 to 15:00 UTC on Dec 1)
            assertThat(assignUrgent.startAt()).isEqualTo(Instant.parse("2026-12-01T09:00:00Z"));
            assertThat(assignUrgent.finishAt()).isEqualTo(Instant.parse("2026-12-01T15:00:00Z"));
            // Normal task starts after urgent at 15:00, finishes next day at 11:00 UTC
            assertThat(assignNormal.startAt()).isEqualTo(Instant.parse("2026-12-01T15:00:00Z"));
            assertThat(assignNormal.finishAt()).isEqualTo(Instant.parse("2026-12-02T11:00:00Z"));
            assertThat(result.totalLatenessMinutes()).isEqualTo(0L);
        }

        @Test
        @DisplayName("Strict milestone deadline fails as INFEASIBLE when deadline is unachievable")
        void strictMilestoneUnachievableReturnsInfeasible() {
            UUID devId = UUID.randomUUID();
            var dev = createDeveloper(devId, "java", 3, 480, new BigDecimal("100"), null, null);

            UUID taskId = UUID.randomUUID();
            // Task requires 480m (full day), but strict deadline is yesterday
            var task = new SolverInputSnapshot.TaskItem(
                    taskId, "T-STRICT", "Strict Impossible", 480, List.of(), null,
                    LocalDate.of(2026, 11, 30), true
            );

            SolverInputSnapshot snapshot = new SolverInputSnapshot(
                    projectId, workspaceId, 1L, timezone, horizonStart, horizonEnd,
                    10, 2, 42,
                    List.of(task), List.of(), List.of(dev), List.of()
            );

            SolverResult result = solver.solve(snapshot);

            assertThat(result.status()).isEqualTo(SolverResult.Status.INFEASIBLE);
        }

        @Test
        @DisplayName("Fixed assignee constraint strictly forces assignment to specified developer")
        void fixedAssigneeEnforced() {
            UUID dev1 = UUID.randomUUID();
            UUID dev2 = UUID.randomUUID();
            var devRec1 = createDeveloper(dev1, "java", 3, 480, new BigDecimal("100"), null, null);
            var devRec2 = createDeveloper(dev2, "java", 3, 480, new BigDecimal("100"), null, null);

            UUID taskId = UUID.randomUUID();
            // Fixed assignee is dev2
            var task = new SolverInputSnapshot.TaskItem(
                    taskId, "T-FIXED", "Fixed Dev", 60, List.of(), dev2, null, false
            );

            SolverInputSnapshot snapshot = new SolverInputSnapshot(
                    projectId, workspaceId, 1L, timezone, horizonStart, horizonEnd,
                    10, 2, 42,
                    List.of(task), List.of(), List.of(devRec1, devRec2), List.of()
            );

            SolverResult result = solver.solve(snapshot);

            assertThat(result.status()).isEqualTo(SolverResult.Status.OPTIMAL);
            assertThat(result.assignments().get(0).developerId()).isEqualTo(dev2);
        }
    }

    // =========================================================================
    // 8. Timeout Handling & Diagnostic Verification
    // =========================================================================
    @Nested
    @DisplayName("8. Timeout Handling & Diagnostics")
    class TimeoutAndDiagnosticsTests {

        @Test
        @DisplayName("Solver respects configured time limit and terminates gracefully")
        void solverRespectsConfiguredTimeLimit() {
            UUID devId = UUID.randomUUID();
            var dev = createDeveloper(devId, "java", 3, 480, new BigDecimal("100"), null, null);

            UUID taskId = UUID.randomUUID();
            var task = new SolverInputSnapshot.TaskItem(taskId, "T-TIMEOUT", "Timeout Test", 60, List.of(), null, null, false);

            SolverInputSnapshot snapshot = new SolverInputSnapshot(
                    projectId, workspaceId, 1L, timezone, horizonStart, horizonEnd,
                    1, // 1 second limit
                    2,
                    42,
                    List.of(task), List.of(), List.of(dev), List.of()
            );

            long start = System.currentTimeMillis();
            SolverResult result = solver.solve(snapshot);
            long elapsed = System.currentTimeMillis() - start;

            assertThat(result).isNotNull();
            assertThat(result.diagnostics()).isNotNull();
            // Solved within reasonable margin of the 1-second time limit
            assertThat(elapsed).isLessThan(10_000L);
            assertThat((int) result.diagnostics().details().get("maxTimeSeconds")).isLessThanOrEqualTo(30);
        }

        @Test
        @DisplayName("Diagnostics records worker count and execution metrics")
        void diagnosticsRecordsMetrics() {
            UUID devId = UUID.randomUUID();
            var dev = createDeveloper(devId, "java", 3, 480, new BigDecimal("100"), null, null);

            UUID taskId = UUID.randomUUID();
            var task = new SolverInputSnapshot.TaskItem(taskId, "T-DIAG", "Diag Test", 60, List.of(), null, null, false);

            SolverInputSnapshot snapshot = new SolverInputSnapshot(
                    projectId, workspaceId, 1L, timezone, horizonStart, horizonEnd,
                    10,
                    4, // 4 workers
                    42,
                    List.of(task), List.of(), List.of(dev), List.of()
            );

            SolverResult result = solver.solve(snapshot);

            assertThat(result.status()).isEqualTo(SolverResult.Status.OPTIMAL);
            SolverResult.Diagnostics diag = result.diagnostics();
            assertThat(diag).isNotNull();
            assertThat(diag.rawNativeStatus()).isEqualTo("OPTIMAL");
            assertThat(diag.wallTimeSeconds()).isGreaterThanOrEqualTo(0.0);
            assertThat(diag.details()).containsKey("numTasks");
            assertThat(diag.details()).containsKey("numWorkers");
            assertThat((int) diag.details().get("numWorkers")).isLessThanOrEqualTo(4);
        }
    }
}

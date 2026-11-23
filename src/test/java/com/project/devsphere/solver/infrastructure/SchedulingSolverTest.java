package com.project.devsphere.solver.infrastructure;

import com.project.devsphere.resource.api.ResourceCapacitySnapshot;
import com.project.devsphere.resource.api.SkillLevel;
import com.project.devsphere.resource.api.TimeOff;
import com.project.devsphere.resource.api.WeeklySlot;
import com.project.devsphere.solver.domain.SolverInputSnapshot;
import com.project.devsphere.solver.domain.SolverResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SchedulingSolverTest {

    private SchedulingSolver solver;

    private final UUID projectId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final LocalDate horizonStart = LocalDate.of(2026, 12, 1); // Tuesday
    private final LocalDate horizonEnd = LocalDate.of(2026, 12, 15);
    private final String timezone = "UTC";

    @BeforeEach
    void setUp() {
        solver = new SchedulingSolver();
    }

    private ResourceCapacitySnapshot.DeveloperCapacityRecord createDeveloper(
            UUID devId, String skill, int skillLevel, List<TimeOff> timeOffs) {
        // Monday-Friday 9:00 - 17:00 (540 to 1020 = 480 mins/day)
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
                skill != null ? List.of(new SkillLevel(skill, skillLevel)) : List.of(),
                slots,
                timeOffs != null ? timeOffs : List.of()
        );

        return new ResourceCapacitySnapshot.DeveloperCapacityRecord(devId, true, List.of(interval));
    }

    @Test
    @DisplayName("1. Single task scheduled optimally with exact duration and segments")
    void solve_singleTask_optimal() {
        UUID devId = UUID.randomUUID();
        var dev = createDeveloper(devId, "java", 3, List.of());

        UUID taskId = UUID.randomUUID();
        var task = new SolverInputSnapshot.TaskItem(
                taskId,
                "DEV-1",
                "Implement Feature",
                60, // 2 slots = 60 mins
                List.of(new SolverInputSnapshot.SkillRequirement("java", 3)),
                null,
                null,
                false
        );

        SolverInputSnapshot snapshot = new SolverInputSnapshot(
                projectId,
                workspaceId,
                1L,
                timezone,
                horizonStart,
                horizonEnd,
                10,
                2,
                42,
                List.of(task),
                List.of(),
                List.of(dev),
                List.of()
        );

        SolverResult result = solver.solve(snapshot);

        assertThat(result.status()).isEqualTo(SolverResult.Status.OPTIMAL);
        assertThat(result.assignments()).hasSize(1);

        SolverResult.TaskAssignment assignment = result.assignments().get(0);
        assertThat(assignment.taskId()).isEqualTo(taskId);
        assertThat(assignment.developerId()).isEqualTo(devId);
        assertThat(assignment.scheduledMinutes()).isEqualTo(60);
        assertThat(assignment.startAt()).isEqualTo(Instant.parse("2026-12-01T09:00:00Z"));
        assertThat(assignment.finishAt()).isEqualTo(Instant.parse("2026-12-01T10:00:00Z"));
        assertThat(assignment.segments()).hasSize(1);
        assertThat(assignment.segments().get(0).minutes()).isEqualTo(60);
    }

    @Test
    @DisplayName("2. Finish-to-Start precedence with nonnegative lag enforced")
    void solve_precedenceWithLag() {
        UUID devId = UUID.randomUUID();
        var dev = createDeveloper(devId, "java", 3, List.of());

        UUID taskAId = UUID.randomUUID();
        var taskA = new SolverInputSnapshot.TaskItem(
                taskAId, "DEV-A", "Task A", 60, List.of(), null, null, false
        );

        UUID taskBId = UUID.randomUUID();
        var taskB = new SolverInputSnapshot.TaskItem(
                taskBId, "DEV-B", "Task B", 60, List.of(), null, null, false
        );

        // Task B depends on Task A with 30 minutes lag
        var dep = new SolverInputSnapshot.TaskDependency(taskAId, taskBId, 30);

        SolverInputSnapshot snapshot = new SolverInputSnapshot(
                projectId, workspaceId, 1L, timezone, horizonStart, horizonEnd,
                10, 2, 42,
                List.of(taskA, taskB),
                List.of(dep),
                List.of(dev),
                List.of()
        );

        SolverResult result = solver.solve(snapshot);

        assertThat(result.status()).isEqualTo(SolverResult.Status.OPTIMAL);
        assertThat(result.assignments()).hasSize(2);

        SolverResult.TaskAssignment assignA = result.assignments().stream().filter(a -> a.taskId().equals(taskAId)).findFirst().orElseThrow();
        SolverResult.TaskAssignment assignB = result.assignments().stream().filter(a -> a.taskId().equals(taskBId)).findFirst().orElseThrow();

        assertThat(assignB.startAt()).isAfterOrEqualTo(assignA.finishAt().plusSeconds(30 * 60));
    }

    @Test
    @DisplayName("3. Skills matching assigns task to qualified developer only")
    void solve_skillMatching_assignsToQualifiedDeveloper() {
        UUID devJunior = UUID.randomUUID();
        var junior = createDeveloper(devJunior, "java", 2, List.of());

        UUID devSenior = UUID.randomUUID();
        var senior = createDeveloper(devSenior, "java", 5, List.of());

        UUID taskId = UUID.randomUUID();
        var task = new SolverInputSnapshot.TaskItem(
                taskId, "DEV-SKILL", "Complex Architecture", 60,
                List.of(new SolverInputSnapshot.SkillRequirement("java", 4)), // requires level 4
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
    @DisplayName("4. Missing skill proof returns INFEASIBLE")
    void solve_missingSkill_returnsInfeasible() {
        UUID devId = UUID.randomUUID();
        var dev = createDeveloper(devId, "python", 5, List.of());

        UUID taskId = UUID.randomUUID();
        var task = new SolverInputSnapshot.TaskItem(
                taskId, "DEV-RUST", "Rust Engine", 60,
                List.of(new SolverInputSnapshot.SkillRequirement("rust", 3)),
                null, null, false
        );

        SolverInputSnapshot snapshot = new SolverInputSnapshot(
                projectId, workspaceId, 1L, timezone, horizonStart, horizonEnd,
                10, 2, 42,
                List.of(task), List.of(), List.of(dev), List.of()
        );

        SolverResult result = solver.solve(snapshot);

        assertThat(result.status()).isEqualTo(SolverResult.Status.INFEASIBLE);
        assertThat(result.diagnostics().message()).contains("no eligible developer satisfies required skills");
    }

    @Test
    @DisplayName("5. Time-off window is excluded and task is scheduled around it")
    void solve_timeOffExclusion_schedulesAroundTimeOff() {
        UUID devId = UUID.randomUUID();
        // Time-off on Dec 1 from 9:00 to 11:00 UTC
        Instant toStart = Instant.parse("2026-12-01T09:00:00Z");
        Instant toEnd = Instant.parse("2026-12-01T11:00:00Z");
        var dev = createDeveloper(devId, "java", 3, List.of(new TimeOff(toStart, toEnd)));

        UUID taskId = UUID.randomUUID();
        var task = new SolverInputSnapshot.TaskItem(
                taskId, "DEV-TO", "Morning Task", 60, List.of(), null, null, false
        );

        SolverInputSnapshot snapshot = new SolverInputSnapshot(
                projectId, workspaceId, 1L, timezone, horizonStart, horizonEnd,
                10, 2, 42,
                List.of(task), List.of(), List.of(dev), List.of()
        );

        SolverResult result = solver.solve(snapshot);

        assertThat(result.status()).isEqualTo(SolverResult.Status.OPTIMAL);
        SolverResult.TaskAssignment assignment = result.assignments().get(0);
        // Task must start after time-off finishes at 11:00
        assertThat(assignment.startAt()).isAfterOrEqualTo(toEnd);
    }

    @Test
    @DisplayName("6. Frozen baseline entry blocks developer availability")
    void solve_frozenEntryPreservation() {
        UUID devId = UUID.randomUUID();
        var dev = createDeveloper(devId, "java", 3, List.of());

        UUID frozenTaskId = UUID.randomUUID();
        Instant frozenStart = Instant.parse("2026-12-01T09:00:00Z");
        Instant frozenEnd = Instant.parse("2026-12-01T12:00:00Z");
        var frozenEntry = new SolverInputSnapshot.FrozenScheduleEntry(
                frozenTaskId, devId, frozenStart, frozenEnd, 180
        );

        UUID newTaskId = UUID.randomUUID();
        var newTask = new SolverInputSnapshot.TaskItem(
                newTaskId, "DEV-NEW", "New Work", 60, List.of(), null, null, false
        );

        SolverInputSnapshot snapshot = new SolverInputSnapshot(
                projectId, workspaceId, 1L, timezone, horizonStart, horizonEnd,
                10, 2, 42,
                List.of(newTask), List.of(), List.of(dev), List.of(frozenEntry)
        );

        SolverResult result = solver.solve(snapshot);

        assertThat(result.status()).isEqualTo(SolverResult.Status.OPTIMAL);
        SolverResult.TaskAssignment assignment = result.assignments().get(0);
        // New task must not overlap frozen baseline entry (starts at or after 12:00)
        assertThat(assignment.startAt()).isAfterOrEqualTo(frozenEnd);
    }

    @Test
    @DisplayName("7. Strict milestone deadline proven infeasible when impossible to meet")
    void solve_strictMilestone_infeasibleWhenTooTight() {
        UUID devId = UUID.randomUUID();
        var dev = createDeveloper(devId, "java", 3, List.of());

        UUID taskId = UUID.randomUUID();
        // Task requires 480 minutes (8 hours, full workday Dec 1 from 9:00 to 17:00)
        // But strict deadline is set to yesterday Dec 0!
        var task = new SolverInputSnapshot.TaskItem(
                taskId, "DEV-STRICT", "Must finish early", 480, List.of(), null,
                LocalDate.of(2026, 11, 30),
                true // strict milestone
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
    @DisplayName("8. Empty task set returns OPTIMAL with zero assignments")
    void solve_emptyTasks_returnsOptimal() {
        SolverInputSnapshot snapshot = new SolverInputSnapshot(
                projectId, workspaceId, 1L, timezone, horizonStart, horizonEnd,
                10, 2, 42,
                List.of(), List.of(), List.of(), List.of()
        );

        SolverResult result = solver.solve(snapshot);

        assertThat(result.status()).isEqualTo(SolverResult.Status.OPTIMAL);
        assertThat(result.assignments()).isEmpty();
        assertThat(result.makespanMinutes()).isEqualTo(0L);
    }

    @Test
    @DisplayName("9. Multi-task parallel distribution across two developers")
    void solve_multiTask_parallelDistribution() {
        UUID dev1 = UUID.randomUUID();
        UUID dev2 = UUID.randomUUID();
        var devRec1 = createDeveloper(dev1, "java", 3, List.of());
        var devRec2 = createDeveloper(dev2, "java", 3, List.of());

        UUID task1 = UUID.randomUUID();
        UUID task2 = UUID.randomUUID();

        var t1 = new SolverInputSnapshot.TaskItem(task1, "DEV-T1", "Task 1", 120, List.of(), null, null, false);
        var t2 = new SolverInputSnapshot.TaskItem(task2, "DEV-T2", "Task 2", 120, List.of(), null, null, false);

        SolverInputSnapshot snapshot = new SolverInputSnapshot(
                projectId, workspaceId, 1L, timezone, horizonStart, horizonEnd,
                10, 2, 42,
                List.of(t1, t2), List.of(), List.of(devRec1, devRec2), List.of()
        );

        SolverResult result = solver.solve(snapshot);

        assertThat(result.status()).isEqualTo(SolverResult.Status.OPTIMAL);
        assertThat(result.assignments()).hasSize(2);
        // Both tasks execute in parallel from 09:00 to 11:00 UTC (660 mins from midnight horizon start)
        assertThat(result.makespanMinutes()).isEqualTo(660L);
        assertThat(result.assignments().get(0).finishAt()).isEqualTo(Instant.parse("2026-12-01T11:00:00Z"));
        assertThat(result.assignments().get(1).finishAt()).isEqualTo(Instant.parse("2026-12-01T11:00:00Z"));
    }
}

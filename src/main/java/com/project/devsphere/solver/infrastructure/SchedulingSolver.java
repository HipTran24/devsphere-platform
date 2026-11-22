package com.project.devsphere.solver.infrastructure;

import com.google.ortools.Loader;
import com.google.ortools.sat.BoolVar;
import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.CpSolver;
import com.google.ortools.sat.CpSolverStatus;
import com.google.ortools.sat.IntervalVar;
import com.google.ortools.sat.IntVar;
import com.google.ortools.sat.LinearArgument;
import com.google.ortools.sat.LinearExpr;
import com.google.ortools.sat.Literal;
import com.project.devsphere.resource.api.ResourceCapacitySnapshot;
import com.project.devsphere.resource.api.SkillLevel;
import com.project.devsphere.resource.api.TimeOff;
import com.project.devsphere.resource.api.WeeklySlot;
import com.project.devsphere.solver.domain.SolverInputSnapshot;
import com.project.devsphere.solver.domain.SolverResult;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service("cpSatSchedulingSolver")
public class SchedulingSolver implements com.project.devsphere.solver.application.SchedulingSolver {

    static {
        Loader.loadNativeLibraries();
    }

    public SolverResult solve(SolverInputSnapshot snapshot) {
        if (snapshot == null) {
            return SolverResult.error("SolverInputSnapshot must not be null");
        }
        if (snapshot.tasks() == null || snapshot.tasks().isEmpty()) {
            return SolverResult.emptySuccess();
        }

        ZoneId zone;
        try {
            zone = ZoneId.of(snapshot.timezone() != null && !snapshot.timezone().isBlank()
                    ? snapshot.timezone() : "Asia/Ho_Chi_Minh");
        } catch (Exception ex) {
            zone = ZoneId.of("Asia/Ho_Chi_Minh");
        }

        LocalDate horizonStart = snapshot.horizonStart() != null ? snapshot.horizonStart() : LocalDate.now(zone);
        LocalDate horizonEnd = snapshot.horizonEnd() != null ? snapshot.horizonEnd() : horizonStart.plusDays(90);

        if (horizonEnd.isBefore(horizonStart)) {
            return SolverResult.error("horizonEnd cannot be before horizonStart");
        }

        Instant horizonStartInstant = horizonStart.atStartOfDay(zone).toInstant();
        Instant horizonEndInstant = horizonEnd.atStartOfDay(zone).toInstant();
        long totalHorizonMinutes = Math.max(1L, Duration.between(horizonStartInstant, horizonEndInstant).toMinutes());

        // 1. Build developer working cells
        Map<UUID, DeveloperContext> devContextMap = new HashMap<>();
        for (ResourceCapacitySnapshot.DeveloperCapacityRecord devRec : snapshot.developers()) {
            if (!devRec.eligible()) {
                continue;
            }
            DeveloperContext ctx = buildDeveloperContext(devRec, horizonStart, horizonEnd, zone, horizonStartInstant, snapshot.frozenEntries());
            devContextMap.put(devRec.developerId(), ctx);
        }

        // 2. Validate tasks and developer skill matching
        CpModel model = new CpModel();
        Map<UUID, TaskContext> taskContextMap = new HashMap<>();

        for (SolverInputSnapshot.TaskItem task : snapshot.tasks()) {
            int slotsNeeded = Math.max(1, (task.remainingMinutes() + 29) / 30);
            List<DeveloperCandidate> candidates = new ArrayList<>();

            for (Map.Entry<UUID, DeveloperContext> entry : devContextMap.entrySet()) {
                DeveloperContext devCtx = entry.getValue();

                if (task.fixedAssigneeId() != null && !task.fixedAssigneeId().equals(devCtx.developerId)) {
                    continue;
                }

                // Check skills
                boolean matchesSkills = true;
                for (SolverInputSnapshot.SkillRequirement req : task.requiredSkills()) {
                    String normSkill = req.skill().strip().toLowerCase(Locale.ROOT);
                    int devLevel = devCtx.skills.getOrDefault(normSkill, 0);
                    if (devLevel < req.level()) {
                        matchesSkills = false;
                        break;
                    }
                }
                if (!matchesSkills) {
                    continue;
                }

                if (devCtx.cells.size() < slotsNeeded) {
                    continue;
                }

                candidates.add(new DeveloperCandidate(devCtx, slotsNeeded));
            }

            if (candidates.isEmpty()) {
                return SolverResult.infeasible("Task " + task.taskKey() + " cannot be scheduled: no eligible developer satisfies required skills and capacity");
            }

            IntVar taskStart = model.newIntVar(0, totalHorizonMinutes, "task_start_" + task.taskId());
            IntVar taskEnd = model.newIntVar(0, totalHorizonMinutes, "task_end_" + task.taskId());
            model.addGreaterOrEqual(taskEnd, taskStart);

            taskContextMap.put(task.taskId(), new TaskContext(task, slotsNeeded, taskStart, taskEnd, candidates));
        }

        // 3. Create CP-SAT decision variables for assignment and intervals
        for (TaskContext tc : taskContextMap.values()) {
            List<Literal> candidateBools = new ArrayList<>();

            for (DeveloperCandidate cand : tc.candidates) {
                DeveloperContext devCtx = cand.devCtx;
                int slotsNeeded = tc.slotsNeeded;
                int maxStartCell = devCtx.cells.size() - slotsNeeded;

                BoolVar assignVar = model.newBoolVar("assign_" + tc.task.taskId() + "_" + devCtx.developerId);
                cand.assignVar = assignVar;
                candidateBools.add(assignVar);

                IntVar startCell = model.newIntVar(0, maxStartCell, "startCell_" + tc.task.taskId() + "_" + devCtx.developerId);
                IntVar endCell = model.newIntVar(slotsNeeded, devCtx.cells.size(), "endCell_" + tc.task.taskId() + "_" + devCtx.developerId);
                model.addEquality(endCell, LinearExpr.affine(startCell, 1, slotsNeeded));

                IntervalVar interval = model.newOptionalIntervalVar(
                        startCell,
                        LinearExpr.constant(slotsNeeded),
                        endCell,
                        assignVar,
                        "interval_" + tc.task.taskId() + "_" + devCtx.developerId
                );
                cand.startCellVar = startCell;
                cand.endCellVar = endCell;
                devCtx.intervals.add(interval);

                IntVar endCellMinusOne = model.newIntVar(slotsNeeded - 1, devCtx.cells.size() - 1, "endCellMinusOne_" + tc.task.taskId() + "_" + devCtx.developerId);
                model.addEquality(endCellMinusOne, LinearExpr.affine(startCell, 1, slotsNeeded - 1));

                IntVar realStart = model.newIntVar(0, totalHorizonMinutes, "realStart_" + tc.task.taskId() + "_" + devCtx.developerId);
                IntVar realEnd = model.newIntVar(0, totalHorizonMinutes, "realEnd_" + tc.task.taskId() + "_" + devCtx.developerId);

                model.addElement(startCell, devCtx.cellStartMinutes, realStart);
                model.addElement(endCellMinusOne, devCtx.cellEndMinutes, realEnd);

                model.addEquality(tc.taskStart, realStart).onlyEnforceIf(assignVar);
                model.addEquality(tc.taskEnd, realEnd).onlyEnforceIf(assignVar);
            }

            model.addExactlyOne(candidateBools.toArray(new com.google.ortools.sat.Literal[0]));
        }

        // 4. Enforce NoOverlap per developer
        for (DeveloperContext devCtx : devContextMap.values()) {
            if (!devCtx.intervals.isEmpty()) {
                model.addNoOverlap(devCtx.intervals);
            }
        }

        // 5. Precedence constraints with lag
        Map<UUID, SolverInputSnapshot.FrozenScheduleEntry> frozenMap = snapshot.frozenEntries().stream()
                .collect(Collectors.toMap(SolverInputSnapshot.FrozenScheduleEntry::workItemId, f -> f, (a, b) -> a));

        for (SolverInputSnapshot.TaskDependency dep : snapshot.dependencies()) {
            TaskContext succTc = taskContextMap.get(dep.successorId());
            if (succTc == null) {
                continue;
            }

            int lag = Math.max(0, dep.lagMinutes());
            TaskContext predTc = taskContextMap.get(dep.predecessorId());

            if (predTc != null) {
                model.addGreaterOrEqual(succTc.taskStart, LinearExpr.affine(predTc.taskEnd, 1, lag));
            } else {
                SolverInputSnapshot.FrozenScheduleEntry frozenPred = frozenMap.get(dep.predecessorId());
                if (frozenPred != null) {
                    long frozenEndMin = Duration.between(horizonStartInstant, frozenPred.finishAt()).toMinutes();
                    model.addGreaterOrEqual(succTc.taskStart, LinearExpr.constant(frozenEndMin + lag));
                } else {
                    return SolverResult.infeasible("Predecessor " + dep.predecessorId() + " not found in schedulable tasks or baseline anchors");
                }
            }
        }

        // 6. Milestone Deadlines & Lateness
        List<IntVar> latenessVars = new ArrayList<>();
        for (TaskContext tc : taskContextMap.values()) {
            if (tc.task.milestoneDeadline() != null) {
                Instant deadlineInstant = tc.task.milestoneDeadline().plusDays(1).atStartOfDay(zone).toInstant();
                long deadlineMinute = Duration.between(horizonStartInstant, deadlineInstant).toMinutes();

                if (tc.task.strictMilestone()) {
                    model.addLessOrEqual(tc.taskEnd, deadlineMinute);
                } else {
                    IntVar lateness = model.newIntVar(0, totalHorizonMinutes, "lateness_" + tc.task.taskId());
                    model.addMaxEquality(lateness, new LinearArgument[]{
                            LinearExpr.affine(tc.taskEnd, 1, -deadlineMinute),
                            LinearExpr.constant(0)
                    });
                    latenessVars.add(lateness);
                }
            }
        }

        // 7. Multi-Objective: Minimize Makespan + Lateness Penalties
        IntVar makespan = model.newIntVar(0, totalHorizonMinutes, "makespan");
        for (TaskContext tc : taskContextMap.values()) {
            model.addGreaterOrEqual(makespan, tc.taskEnd);
        }

        LinearArgument[] objectiveTerms = new LinearArgument[1 + latenessVars.size()];
        long[] objectiveWeights = new long[1 + latenessVars.size()];
        objectiveTerms[0] = makespan;
        objectiveWeights[0] = 1;
        for (int i = 0; i < latenessVars.size(); i++) {
            objectiveTerms[i + 1] = latenessVars.get(i);
            objectiveWeights[i + 1] = 10; // lateness penalty factor
        }
        model.minimize(LinearExpr.weightedSum(objectiveTerms, objectiveWeights));

        // 8. Configure CP-SAT solver
        CpSolver solver = new CpSolver();
        int maxTime = Math.min(snapshot.maxTimeSeconds() > 0 ? snapshot.maxTimeSeconds() : 30, 30);
        solver.getParameters().setMaxTimeInSeconds(maxTime);

        int workers = Math.min(snapshot.numWorkers() > 0 ? snapshot.numWorkers() : 4, 4);
        solver.getParameters().setNumWorkers(workers);

        if (snapshot.randomSeed() > 0) {
            solver.getParameters().setRandomSeed(snapshot.randomSeed());
        }

        CpSolverStatus status = solver.solve(model);

        // 9. Process result with strict status distinction (NEVER report FEASIBLE as OPTIMAL)
        SolverResult.Status resultStatus;
        if (status == CpSolverStatus.OPTIMAL) {
            resultStatus = SolverResult.Status.OPTIMAL;
        } else if (status == CpSolverStatus.FEASIBLE) {
            resultStatus = SolverResult.Status.FEASIBLE;
        } else if (status == CpSolverStatus.INFEASIBLE) {
            return SolverResult.infeasible("Model proved infeasible by CP-SAT solver");
        } else if (status == CpSolverStatus.MODEL_INVALID) {
            return SolverResult.error("CP-SAT model invalid: " + solver.response().getUserTime());
        } else {
            return new SolverResult(
                    SolverResult.Status.UNKNOWN,
                    List.of(),
                    0L,
                    0L,
                    new SolverResult.Diagnostics(
                            status.name(),
                            solver.wallTime(),
                            solver.userTime(),
                            solver.response().getNumBooleans(),
                            solver.numBranches(),
                            solver.numConflicts(),
                            solver.bestObjectiveBound(),
                            solver.objectiveValue(),
                            "Search inconclusive within time limit (" + maxTime + "s)",
                            Map.of("workers", workers, "maxTimeSeconds", maxTime)
                    )
            );
        }

        // 10. Extract assignments
        List<SolverResult.TaskAssignment> assignments = new ArrayList<>();
        for (TaskContext tc : taskContextMap.values()) {
            DeveloperCandidate chosen = null;
            for (DeveloperCandidate cand : tc.candidates) {
                if (solver.booleanValue(cand.assignVar)) {
                    chosen = cand;
                    break;
                }
            }

            if (chosen == null) {
                continue;
            }

            int startIdx = (int) solver.value(chosen.startCellVar);
            int slots = tc.slotsNeeded;
            List<WorkingCell> taskCells = chosen.devCtx.cells.subList(startIdx, startIdx + slots);

            Instant startAt = taskCells.get(0).startInstant;
            Instant finishAt = taskCells.get(taskCells.size() - 1).endInstant;

            // Group contiguous 30-min cells into WorkSegments
            List<SolverResult.WorkSegment> segments = new ArrayList<>();
            Instant segStart = taskCells.get(0).startInstant;
            Instant segEnd = taskCells.get(0).endInstant;
            int segMins = 30;

            for (int i = 1; i < taskCells.size(); i++) {
                WorkingCell cell = taskCells.get(i);
                if (cell.startInstant.equals(segEnd)) {
                    segEnd = cell.endInstant;
                    segMins += 30;
                } else {
                    segments.add(new SolverResult.WorkSegment(segStart, segEnd, segMins));
                    segStart = cell.startInstant;
                    segEnd = cell.endInstant;
                    segMins = 30;
                }
            }
            segments.add(new SolverResult.WorkSegment(segStart, segEnd, segMins));

            assignments.add(new SolverResult.TaskAssignment(
                    tc.task.taskId(),
                    tc.task.taskKey(),
                    chosen.devCtx.developerId,
                    startAt,
                    finishAt,
                    slots * 30,
                    segments
            ));
        }

        long makespanMinutes = (long) solver.value(makespan);
        long totalLateness = 0L;
        for (IntVar lVar : latenessVars) {
            totalLateness += (long) solver.value(lVar);
        }

        SolverResult.Diagnostics diag = new SolverResult.Diagnostics(
                status.name(),
                solver.wallTime(),
                solver.userTime(),
                solver.response().getNumBooleans(),
                solver.numBranches(),
                solver.numConflicts(),
                solver.bestObjectiveBound(),
                solver.objectiveValue(),
                "Solved with status: " + status.name(),
                Map.of(
                        "numTasks", snapshot.tasks().size(),
                        "numDevelopers", snapshot.developers().size(),
                        "numWorkers", workers,
                        "maxTimeSeconds", maxTime
                )
        );

        return new SolverResult(resultStatus, assignments, makespanMinutes, totalLateness, diag);
    }

    private DeveloperContext buildDeveloperContext(
            ResourceCapacitySnapshot.DeveloperCapacityRecord devRec,
            LocalDate horizonStart,
            LocalDate horizonEnd,
            ZoneId zone,
            Instant horizonStartInstant,
            List<SolverInputSnapshot.FrozenScheduleEntry> frozenEntries) {

        UUID devId = devRec.developerId();
        Map<String, Integer> skills = new HashMap<>();
        List<WorkingCell> cells = new ArrayList<>();

        // Aggregate skills
        for (ResourceCapacitySnapshot.CapacityInterval interval : devRec.intervals()) {
            for (SkillLevel sl : interval.skills()) {
                String norm = sl.skill().strip().toLowerCase(Locale.ROOT);
                skills.merge(norm, sl.level(), Math::max);
            }
        }

        // Frozen intervals for this developer
        List<TimeOff> frozenBlocks = new ArrayList<>();
        if (frozenEntries != null) {
            for (SolverInputSnapshot.FrozenScheduleEntry frozen : frozenEntries) {
                if (devId.equals(frozen.assigneeId())) {
                    frozenBlocks.add(new TimeOff(frozen.startAt(), frozen.finishAt()));
                }
            }
        }

        // Compile cells for each date
        LocalDate curDate = horizonStart;
        while (!curDate.isAfter(horizonEnd.minusDays(1))) {
            final LocalDate date = curDate;
            ResourceCapacitySnapshot.CapacityInterval matchingInterval = devRec.intervals().stream()
                    .filter(c -> !date.isBefore(c.effectiveFrom()) &&
                            (c.effectiveTo() == null || date.isBefore(c.effectiveTo())))
                    .findFirst()
                    .orElse(null);

            if (matchingInterval != null) {
                int dayOfWeek = date.getDayOfWeek().getValue();
                int minutesPerWorkday = matchingInterval.minutesPerDay();
                BigDecimal allocPct = matchingInterval.allocationPct() != null ? matchingInterval.allocationPct() : new BigDecimal("100");
                int dailyBudget = BigDecimal.valueOf(minutesPerWorkday)
                        .multiply(allocPct)
                        .divide(BigDecimal.valueOf(100), 0, RoundingMode.FLOOR)
                        .intValue();

                int maxSlotsOnDate = dailyBudget / 30;

                if (maxSlotsOnDate > 0) {
                    List<WeeklySlot> slotsForDay = matchingInterval.weeklySlots().stream()
                            .filter(s -> s.dayOfWeek() == dayOfWeek)
                            .sorted(Comparator.comparingInt(WeeklySlot::startMinute))
                            .toList();

                    Instant dayStart = date.atStartOfDay(zone).toInstant();
                    Instant dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant();

                    // All time off + frozen blocks on this date
                    List<TimeOff> allBlocks = new ArrayList<>(matchingInterval.timeOff());
                    allBlocks.addAll(frozenBlocks);

                    List<BusyInterval> busyList = new ArrayList<>();
                    for (TimeOff block : allBlocks) {
                        if (block.startAt().isBefore(dayEnd) && block.endAt().isAfter(dayStart)) {
                            ZonedDateTime bStart = block.startAt().atZone(zone);
                            ZonedDateTime bEnd = block.endAt().atZone(zone);

                            int startMin = 0;
                            if (!bStart.toLocalDate().isBefore(date)) {
                                startMin = bStart.getHour() * 60 + bStart.getMinute();
                            }
                            int endMin = 1440;
                            if (!bEnd.toLocalDate().isAfter(date)) {
                                endMin = bEnd.getHour() * 60 + bEnd.getMinute();
                                if (bEnd.getSecond() > 0 || bEnd.getNano() > 0) {
                                    endMin = Math.min(1440, endMin + 1);
                                }
                            }
                            if (endMin > startMin) {
                                busyList.add(new BusyInterval(startMin, endMin));
                            }
                        }
                    }

                    List<AvailableInterval> available = new ArrayList<>();
                    for (WeeklySlot ws : slotsForDay) {
                        available.addAll(subtractBusy(new AvailableInterval(ws.startMinute(), ws.endMinute()), busyList));
                    }

                    int slotsAddedForDate = 0;
                    for (AvailableInterval ai : available) {
                        for (int m = ai.start; m + 30 <= ai.end; m += 30) {
                            if (slotsAddedForDate >= maxSlotsOnDate) {
                                break;
                            }
                            Instant cStart = date.atStartOfDay(zone).plusMinutes(m).toInstant();
                            Instant cEnd = date.atStartOfDay(zone).plusMinutes(m + 30).toInstant();
                            long startMinH = Duration.between(horizonStartInstant, cStart).toMinutes();
                            long endMinH = Duration.between(horizonStartInstant, cEnd).toMinutes();

                            cells.add(new WorkingCell(cells.size(), date, m, m + 30, cStart, cEnd, startMinH, endMinH));
                            slotsAddedForDate++;
                        }
                        if (slotsAddedForDate >= maxSlotsOnDate) {
                            break;
                        }
                    }
                }
            }
            curDate = curDate.plusDays(1);
        }

        long[] startMinutes = new long[cells.size()];
        long[] endMinutes = new long[cells.size()];
        for (int i = 0; i < cells.size(); i++) {
            startMinutes[i] = cells.get(i).startMinuteFromHorizon;
            endMinutes[i] = cells.get(i).endMinuteFromHorizon;
        }

        return new DeveloperContext(devId, skills, cells, startMinutes, endMinutes);
    }

    private List<AvailableInterval> subtractBusy(AvailableInterval base, List<BusyInterval> busyList) {
        List<AvailableInterval> current = new ArrayList<>(List.of(base));
        for (BusyInterval busy : busyList) {
            List<AvailableInterval> next = new ArrayList<>();
            for (AvailableInterval avail : current) {
                if (busy.end <= avail.start || busy.start >= avail.end) {
                    next.add(avail);
                } else {
                    if (busy.start > avail.start) {
                        next.add(new AvailableInterval(avail.start, busy.start));
                    }
                    if (busy.end < avail.end) {
                        next.add(new AvailableInterval(busy.end, avail.end));
                    }
                }
            }
            current = next;
        }
        return current;
    }

    // Context helper classes
    private static class DeveloperContext {
        final UUID developerId;
        final Map<String, Integer> skills;
        final List<WorkingCell> cells;
        final long[] cellStartMinutes;
        final long[] cellEndMinutes;
        final List<IntervalVar> intervals = new ArrayList<>();

        DeveloperContext(UUID developerId, Map<String, Integer> skills, List<WorkingCell> cells, long[] cellStartMinutes, long[] cellEndMinutes) {
            this.developerId = developerId;
            this.skills = skills;
            this.cells = cells;
            this.cellStartMinutes = cellStartMinutes;
            this.cellEndMinutes = cellEndMinutes;
        }
    }

    private static class DeveloperCandidate {
        final DeveloperContext devCtx;
        final int slotsNeeded;
        BoolVar assignVar;
        IntVar startCellVar;
        IntVar endCellVar;

        DeveloperCandidate(DeveloperContext devCtx, int slotsNeeded) {
            this.devCtx = devCtx;
            this.slotsNeeded = slotsNeeded;
        }
    }

    private static class TaskContext {
        final SolverInputSnapshot.TaskItem task;
        final int slotsNeeded;
        final IntVar taskStart;
        final IntVar taskEnd;
        final List<DeveloperCandidate> candidates;

        TaskContext(SolverInputSnapshot.TaskItem task, int slotsNeeded, IntVar taskStart, IntVar taskEnd, List<DeveloperCandidate> candidates) {
            this.task = task;
            this.slotsNeeded = slotsNeeded;
            this.taskStart = taskStart;
            this.taskEnd = taskEnd;
            this.candidates = candidates;
        }
    }

    private record WorkingCell(
            int index,
            LocalDate date,
            int startMinuteOfDay,
            int endMinuteOfDay,
            Instant startInstant,
            Instant endInstant,
            long startMinuteFromHorizon,
            long endMinuteFromHorizon
    ) {}

    private record BusyInterval(int start, int end) {}
    private record AvailableInterval(int start, int end) {}
}

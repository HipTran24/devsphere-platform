package com.project.devsphere.solver.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record SolverResult(
        Status status,
        List<TaskAssignment> assignments,
        long makespanMinutes,
        long totalLatenessMinutes,
        Diagnostics diagnostics
) {
    public enum Status {
        OPTIMAL,
        FEASIBLE,
        INFEASIBLE,
        UNKNOWN,
        ERROR
    }

    public record TaskAssignment(
            UUID taskId,
            String taskKey,
            UUID developerId,
            Instant startAt,
            Instant finishAt,
            int scheduledMinutes,
            List<WorkSegment> segments
    ) {}

    public record WorkSegment(
            Instant startAt,
            Instant endAt,
            int minutes
    ) {}

    public record Diagnostics(
            String rawNativeStatus,
            double wallTimeSeconds,
            double userTimeSeconds,
            long numBooleans,
            long numBranches,
            long numConflicts,
            double bestObjectiveBound,
            double objectiveValue,
            String message,
            Map<String, Object> details
    ) {}

    public static SolverResult infeasible(String message) {
        return new SolverResult(
                Status.INFEASIBLE,
                List.of(),
                0L,
                0L,
                new Diagnostics("INFEASIBLE", 0.0, 0.0, 0L, 0L, 0L, 0.0, 0.0, message, Map.of())
        );
    }

    public static SolverResult error(String message) {
        return new SolverResult(
                Status.ERROR,
                List.of(),
                0L,
                0L,
                new Diagnostics("ERROR", 0.0, 0.0, 0L, 0L, 0L, 0.0, 0.0, message, Map.of())
        );
    }

    public static SolverResult emptySuccess() {
        return new SolverResult(
                Status.OPTIMAL,
                List.of(),
                0L,
                0L,
                new Diagnostics("OPTIMAL", 0.0, 0.0, 0L, 0L, 0L, 0.0, 0.0, "Empty task set", Map.of())
        );
    }
}

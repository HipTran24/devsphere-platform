package com.project.devsphere.planning.api.dto;

import com.project.devsphere.planning.domain.PlanProposal;

import java.util.Map;
import java.util.UUID;

public record SolverDiagnosticsResponse(
        UUID proposalId,
        PlanProposal.SolverStatus solverStatus,
        String rawNativeStatus,
        double wallTimeSeconds,
        double userTimeSeconds,
        long numBooleans,
        long numBranches,
        long numConflicts,
        double bestObjectiveBound,
        double objectiveValue,
        String message,
        long makespanMinutes,
        long totalLatenessMinutes,
        Map<String, Object> details,
        Long proposalVersion
) {
    public static SolverDiagnosticsResponse from(PlanProposal p) {
        Map<String, Object> sd = p.getSolverDetails() != null ? p.getSolverDetails() : Map.of();
        String rawStatus = sd.get("rawNativeStatus") != null ? sd.get("rawNativeStatus").toString() : p.getSolverStatus().name();
        double wall = sd.get("wallTimeSeconds") instanceof Number n ? n.doubleValue() : 0.0;
        double user = sd.get("userTimeSeconds") instanceof Number n ? n.doubleValue() : 0.0;
        long booleans = sd.get("numBooleans") instanceof Number n ? n.longValue() : 0L;
        long branches = sd.get("numBranches") instanceof Number n ? n.longValue() : 0L;
        long conflicts = sd.get("numConflicts") instanceof Number n ? n.longValue() : 0L;
        double bound = sd.get("bestObjectiveBound") instanceof Number n ? n.doubleValue() : 0.0;
        double obj = sd.get("objectiveValue") instanceof Number n ? n.doubleValue() : 0.0;
        String msg = sd.get("message") != null ? sd.get("message").toString() : "";
        long makespan = sd.get("makespanMinutes") instanceof Number n ? n.longValue() : 0L;
        long lateness = sd.get("totalLatenessMinutes") instanceof Number n ? n.longValue() : 0L;
        @SuppressWarnings("unchecked")
        Map<String, Object> details = sd.get("details") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();

        return new SolverDiagnosticsResponse(
                p.getId(),
                p.getSolverStatus(),
                rawStatus,
                wall,
                user,
                booleans,
                branches,
                conflicts,
                bound,
                obj,
                msg,
                makespan,
                lateness,
                details,
                p.getVersion()
        );
    }
}

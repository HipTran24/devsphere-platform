package com.project.devsphere.solver.domain;

import com.project.devsphere.resource.api.ResourceCapacitySnapshot;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record SolverInputSnapshot(
        UUID projectId,
        UUID workspaceId,
        Long projectVersion,
        String timezone,
        LocalDate horizonStart,
        LocalDate horizonEnd,
        int maxTimeSeconds,
        int numWorkers,
        int randomSeed,
        List<TaskItem> tasks,
        List<TaskDependency> dependencies,
        List<ResourceCapacitySnapshot.DeveloperCapacityRecord> developers,
        List<FrozenScheduleEntry> frozenEntries
) {
    public SolverInputSnapshot {
        if (maxTimeSeconds <= 0) {
            maxTimeSeconds = 30;
        }
        if (numWorkers <= 0) {
            numWorkers = 4;
        }
        if (tasks == null) {
            tasks = List.of();
        }
        if (dependencies == null) {
            dependencies = List.of();
        }
        if (developers == null) {
            developers = List.of();
        }
        if (frozenEntries == null) {
            frozenEntries = List.of();
        }
    }

    public record TaskItem(
            UUID taskId,
            String taskKey,
            String title,
            int remainingMinutes,
            List<SkillRequirement> requiredSkills,
            UUID fixedAssigneeId,
            LocalDate milestoneDeadline,
            boolean strictMilestone
    ) {
        public TaskItem {
            if (requiredSkills == null) {
                requiredSkills = List.of();
            }
        }
    }

    public record SkillRequirement(
            String skill,
            int level
    ) {}

    public record TaskDependency(
            UUID predecessorId,
            UUID successorId,
            int lagMinutes
    ) {}

    public record FrozenScheduleEntry(
            UUID workItemId,
            UUID assigneeId,
            Instant startAt,
            Instant finishAt,
            int remainingMinutes
    ) {}
}

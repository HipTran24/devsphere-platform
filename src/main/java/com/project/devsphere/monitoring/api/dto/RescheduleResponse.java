package com.project.devsphere.monitoring.api.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record RescheduleResponse(
        UUID proposalId,
        UUID projectId,
        String proposalStatus,
        String solverStatus,
        int totalTasks,
        int frozenTasks,
        LocalDate forecastFinish,
        Instant createdAt
) {
}

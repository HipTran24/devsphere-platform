package com.project.devsphere.sprint.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record SprintResponse(
        UUID id,
        UUID projectId,
        String name,
        String goal,
        LocalDate startDate,
        LocalDate endDate,
        String status,
        String reviewNotes,
        String retrospectiveNotes,
        BigDecimal committedPoints,
        BigDecimal completedPoints,
        Instant closedAt,
        Instant createdAt,
        Instant updatedAt,
        Long version
) {
}

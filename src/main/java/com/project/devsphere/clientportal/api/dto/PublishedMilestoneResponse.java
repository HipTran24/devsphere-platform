package com.project.devsphere.clientportal.api.dto;

import com.project.devsphere.project.domain.Milestone;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record PublishedMilestoneResponse(
        UUID id,
        UUID projectId,
        String code,
        String name,
        String description,
        LocalDate dueDate,
        LocalDate forecastDate,
        String status,
        String acceptanceStatus,
        boolean publishedToClient,
        UUID acceptedBy,
        Instant acceptedAt,
        Long version
) {
    public static PublishedMilestoneResponse from(Milestone m) {
        return new PublishedMilestoneResponse(
                m.getId(),
                m.getProjectId(),
                m.getCode(),
                m.getName(),
                m.getDescription(),
                m.getDueDate(),
                m.getForecastDate(),
                m.getStatus() != null ? m.getStatus().name() : null,
                m.getAcceptanceStatus() != null ? m.getAcceptanceStatus().name() : null,
                Boolean.TRUE.equals(m.getPublishedToClient()),
                m.getAcceptedBy(),
                m.getAcceptedAt(),
                m.getVersion()
        );
    }
}

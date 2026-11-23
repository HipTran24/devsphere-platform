package com.project.devsphere.clientportal.api.dto;

import com.project.devsphere.reporting.domain.Report;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

public record PublishedReportResponse(
        UUID id,
        UUID projectId,
        String type,
        LocalDate periodStart,
        LocalDate periodEnd,
        String status,
        Instant publishedAt,
        Map<String, Object> content
) {
    public static PublishedReportResponse from(Report r) {
        return new PublishedReportResponse(
                r.getId(),
                r.getProjectId(),
                r.getType() != null ? r.getType().name() : null,
                r.getPeriodStart(),
                r.getPeriodEnd(),
                r.getStatus() != null ? r.getStatus().name() : null,
                r.getPublishedAt(),
                r.getContent()
        );
    }
}

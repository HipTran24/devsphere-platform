package com.project.devsphere.reporting.api.dto;

import com.project.devsphere.reporting.domain.Report;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

public record ReportResponse(
        UUID id,
        UUID projectId,
        String type,
        LocalDate periodStart,
        LocalDate periodEnd,
        String status,
        Map<String, Object> content,
        boolean visibleToClient,
        UUID milestoneId,
        UUID generatedBy,
        UUID aiJobId,
        UUID approvedBy,
        Instant publishedAt,
        Instant createdAt,
        Instant updatedAt,
        Long version
) {
    public static ReportResponse from(Report r) {
        return new ReportResponse(
                r.getId(),
                r.getProjectId(),
                r.getType() != null ? r.getType().name() : null,
                r.getPeriodStart(),
                r.getPeriodEnd(),
                r.getStatus() != null ? r.getStatus().name() : null,
                r.getContent(),
                Boolean.TRUE.equals(r.getVisibleToClient()),
                r.getMilestoneId(),
                r.getGeneratedBy(),
                r.getAiJobId(),
                r.getApprovedBy(),
                r.getPublishedAt(),
                r.getCreatedAt(),
                r.getUpdatedAt(),
                r.getVersion()
        );
    }
}

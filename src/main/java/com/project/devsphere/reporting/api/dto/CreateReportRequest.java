package com.project.devsphere.reporting.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

public record CreateReportRequest(
        @NotBlank(message = "type must not be blank; expected DAILY, WEEKLY, SPRINT, RISK, CODE_QUALITY, CLIENT_ROADMAP")
        String type,

        @NotNull(message = "periodStart must not be null")
        LocalDate periodStart,

        @NotNull(message = "periodEnd must not be null")
        LocalDate periodEnd,

        Map<String, Object> content,
        UUID milestoneId,
        UUID aiJobId
) {
}

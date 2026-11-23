package com.project.devsphere.monitoring.api.dto;

import java.util.UUID;

public record CpmNodeDto(
        UUID workItemId,
        String itemKey,
        String title,
        String status,
        boolean isBlocked,
        int durationMinutes,
        long earlyStartMinutes,
        long earlyFinishMinutes,
        long lateStartMinutes,
        long lateFinishMinutes,
        long totalSlackMinutes,
        long freeSlackMinutes,
        boolean critical
) {}

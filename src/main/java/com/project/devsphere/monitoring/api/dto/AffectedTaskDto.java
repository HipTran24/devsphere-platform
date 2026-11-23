package com.project.devsphere.monitoring.api.dto;

import java.util.UUID;

public record AffectedTaskDto(
        UUID workItemId,
        String itemKey,
        String title,
        long originalEarlyStart,
        long delayedEarlyStart,
        long delayMinutes,
        boolean onCriticalPath
) {}

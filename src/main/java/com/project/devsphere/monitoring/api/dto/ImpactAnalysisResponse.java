package com.project.devsphere.monitoring.api.dto;

import java.util.List;
import java.util.UUID;

public record ImpactAnalysisResponse(
        UUID projectId,
        UUID targetWorkItemId,
        String targetItemKey,
        int delayMinutes,
        long targetTotalSlackMinutes,
        long projectEtaDelayMinutes,
        boolean shiftsProjectDeadline,
        List<AffectedTaskDto> affectedTasks
) {}

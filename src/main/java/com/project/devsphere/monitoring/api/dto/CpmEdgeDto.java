package com.project.devsphere.monitoring.api.dto;

import java.util.UUID;

public record CpmEdgeDto(
        UUID predecessorId,
        UUID successorId,
        String linkType,
        int lagMinutes,
        boolean critical
) {}

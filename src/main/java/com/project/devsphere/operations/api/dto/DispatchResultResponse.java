package com.project.devsphere.operations.api.dto;

import java.util.Map;

/** Result of a manual dispatch run; {@code failed} includes dead-lettered jobs. */
public record DispatchResultResponse(
        int claimed,
        int succeeded,
        int failed,
        int deadLettered,
        Map<String, Integer> processedByKind,
        long durationMillis
) {
}

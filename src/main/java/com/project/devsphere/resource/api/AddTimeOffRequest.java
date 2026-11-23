package com.project.devsphere.resource.api;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AddTimeOffRequest(
        @NotNull UUID developerId,
        @NotNull Instant startAt,
        @NotNull Instant endAt,
        List<CapacityVersionRef> capacityVersions,
        String reason,
        @NotNull Long expectedProjectVersion
) {
    public AddTimeOffRequest(UUID developerId, Instant startAt, Instant endAt, String reason, Long expectedProjectVersion) {
        this(developerId, startAt, endAt, List.of(), reason, expectedProjectVersion);
    }

    public AddTimeOffRequest {
        if (capacityVersions == null) {
            capacityVersions = List.of();
        }
    }
}

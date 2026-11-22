package com.project.devsphere.resource.api;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CapacityVersionRef(
        @NotNull UUID id,
        @NotNull Long expectedVersion
) {}

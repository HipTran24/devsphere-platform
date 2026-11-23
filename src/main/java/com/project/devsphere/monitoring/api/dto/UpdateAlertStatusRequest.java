package com.project.devsphere.monitoring.api.dto;

import com.project.devsphere.monitoring.domain.Alert;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record UpdateAlertStatusRequest(
        @NotNull Alert.Status status,
        UUID assignedTo,
        @NotNull @Min(0) Long expectedVersion
) {
}

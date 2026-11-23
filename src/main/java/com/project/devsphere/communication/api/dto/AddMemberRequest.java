package com.project.devsphere.communication.api.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record AddMemberRequest(
        @NotNull UUID userId
) {}

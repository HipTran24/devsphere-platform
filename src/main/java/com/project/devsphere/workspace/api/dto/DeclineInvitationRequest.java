package com.project.devsphere.workspace.api.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;

public record DeclineInvitationRequest(
        @JsonAlias("rawToken")
        @NotBlank(message = "Token must not be blank")
        String token
) {
    public String rawToken() {
        return token;
    }
}

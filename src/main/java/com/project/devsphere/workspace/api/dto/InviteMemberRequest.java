package com.project.devsphere.workspace.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record InviteMemberRequest(
        @NotBlank(message = "Email must not be blank")
        @Email(message = "Email must be valid")
        String email,
        String role
) {
    public InviteMemberRequest {
        if (role == null || role.isBlank()) {
            role = "MEMBER";
        }
    }
}

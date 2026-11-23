package com.project.devsphere.workspace.application;

public record CreateInvitationCommand(String email, String role) {

    public CreateInvitationCommand(String email) {
        this(email, "MEMBER");
    }

    public CreateInvitationCommand {
        if (role == null || role.isBlank()) {
            role = "MEMBER";
        }
    }
}

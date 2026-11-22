package com.project.devsphere.workspace.application;

public record CreateWorkspaceCommand(
        String name,
        String slug,
        String description
) {}

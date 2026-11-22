package com.project.devsphere.workspace.application;

public record UpdateWorkspaceCommand(
        String name,
        String description,
        Long expectedVersion
) {}

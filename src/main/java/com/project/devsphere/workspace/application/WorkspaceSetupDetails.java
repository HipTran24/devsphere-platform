package com.project.devsphere.workspace.application;

import com.project.devsphere.project.domain.Project;
import com.project.devsphere.workspace.domain.Workspace;
import java.util.UUID;

public record WorkspaceSetupDetails(
        UUID workspaceId,
        String workspaceName,
        String workspaceSlug,
        Workspace.Status workspaceStatus,
        boolean hasProject,
        UUID projectId,
        String projectKey,
        String projectName,
        Project.Status projectStatus,
        boolean hasPm,
        UUID pmUserId
) {}

package com.project.devsphere.project.application;

import com.project.devsphere.identity.application.AccessPolicy;
import com.project.devsphere.identity.application.SessionService;
import com.project.devsphere.identity.domain.ProjectRole;
import com.project.devsphere.workspace.application.WorkspaceProjectException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectRoleServiceTest {

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private AccessPolicy accessPolicy;

    private ProjectRoleService projectRoleService;

    private final UUID userId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private SessionService.Principal principal;

    @BeforeEach
    void setUp() {
        projectRoleService = new ProjectRoleService(jdbcTemplate, accessPolicy);
        principal = new SessionService.Principal(userId, sessionId, "PM User", "USER");
    }

    @Test
    @DisplayName("grantProjectRole rejects PM role with 400 PM_ROLE_RESTRICTED")
    void grantProjectRoleRejectsPm() {
        UUID targetUser = UUID.randomUUID();

        assertThatThrownBy(() -> projectRoleService.grantProjectRole(
                principal,
                workspaceId,
                projectId,
                new GrantRoleCommand(targetUser, ProjectRole.PM)
        )).isInstanceOf(WorkspaceProjectException.class)
                .satisfies(ex -> {
                    WorkspaceProjectException wpe = (WorkspaceProjectException) ex;
                    assertThat(wpe.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(wpe.getCode()).isEqualTo("PM_ROLE_RESTRICTED");
                });

        verify(accessPolicy).requireProjectRole(principal, workspaceId, projectId, ProjectRole.PM);
    }

    @Test
    @DisplayName("grantProjectRole rejects target user who is not active workspace member")
    void grantProjectRoleTargetNotWorkspaceMember() {
        UUID targetUser = UUID.randomUUID();
        when(jdbcTemplate.queryForObject(contains("workspace_members"), eq(Integer.class), eq(workspaceId), eq(targetUser)))
                .thenReturn(0);

        assertThatThrownBy(() -> projectRoleService.grantProjectRole(
                principal,
                workspaceId,
                projectId,
                new GrantRoleCommand(targetUser, ProjectRole.DEVELOPER)
        )).isInstanceOf(WorkspaceProjectException.class)
                .satisfies(ex -> assertThat(((WorkspaceProjectException) ex).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    @DisplayName("grantProjectRole succeeds for DEVELOPER and inserts role")
    void grantProjectRoleSuccess() {
        UUID targetUser = UUID.randomUUID();
        UUID projectMemberId = UUID.randomUUID();

        when(jdbcTemplate.queryForObject(contains("workspace_members"), eq(Integer.class), eq(workspaceId), eq(targetUser)))
                .thenReturn(1);
        when(jdbcTemplate.queryForObject(contains("INSERT INTO project_members"), eq(UUID.class), eq(projectId), eq(workspaceId), eq(targetUser)))
                .thenReturn(projectMemberId);
        when(jdbcTemplate.queryForObject(contains("project_member_roles"), eq(Integer.class), eq(projectId), eq(projectMemberId), eq("DEVELOPER")))
                .thenReturn(0);

        projectRoleService.grantProjectRole(
                principal,
                workspaceId,
                projectId,
                new GrantRoleCommand(targetUser, ProjectRole.DEVELOPER)
        );

        verify(jdbcTemplate).update(
                contains("INSERT INTO project_member_roles"),
                eq(projectId),
                eq(projectMemberId),
                eq("DEVELOPER"),
                eq(userId),
                eq(targetUser)
        );
    }

    @Test
    @DisplayName("revokeProjectRole clears deadline_approver_id if revoked role is user's last CLIENT role")
    void revokeProjectRoleClearsDeadlineApprover() {
        UUID targetUser = UUID.randomUUID();
        UUID projectMemberId = UUID.randomUUID();

        when(jdbcTemplate.query(contains("SELECT id FROM project_members"), any(RowMapper.class), eq(projectId), eq(targetUser)))
                .thenReturn(List.of(projectMemberId));
        when(jdbcTemplate.queryForObject(contains("SELECT count(*) FROM project_member_roles"), eq(Integer.class), eq(projectId), eq(projectMemberId)))
                .thenReturn(0);

        projectRoleService.revokeProjectRole(
                principal,
                workspaceId,
                projectId,
                targetUser,
                ProjectRole.CLIENT
        );

        verify(jdbcTemplate).update(
                contains("UPDATE project_member_roles SET revoked_at = now()"),
                eq(projectId),
                eq(projectMemberId),
                eq("CLIENT")
        );
        verify(jdbcTemplate).update(
                contains("UPDATE projects SET deadline_approver_id = NULL"),
                eq(projectId),
                eq(targetUser)
        );
    }

    @Test
    @DisplayName("revokeProjectRole does not clear deadline_approver_id if revoked role is DEVELOPER")
    void revokeProjectRoleDeveloperDoesNotClearApprover() {
        UUID targetUser = UUID.randomUUID();
        UUID projectMemberId = UUID.randomUUID();

        when(jdbcTemplate.query(contains("SELECT id FROM project_members"), any(RowMapper.class), eq(projectId), eq(targetUser)))
                .thenReturn(List.of(projectMemberId));

        projectRoleService.revokeProjectRole(
                principal,
                workspaceId,
                projectId,
                targetUser,
                ProjectRole.DEVELOPER
        );

        verify(jdbcTemplate, never()).update(
                contains("UPDATE projects SET deadline_approver_id = NULL"),
                any(), any()
        );
    }

    @Test
    @DisplayName("removeProjectMember rejects removing active PM")
    void removeProjectMemberRejectsPm() {
        UUID targetUser = UUID.randomUUID();
        UUID projectMemberId = UUID.randomUUID();

        when(jdbcTemplate.query(contains("SELECT id FROM project_members"), any(RowMapper.class), eq(projectId), eq(targetUser)))
                .thenReturn(List.of(projectMemberId));
        when(jdbcTemplate.queryForObject(contains("SELECT count(*) FROM project_member_roles"), eq(Integer.class), eq(projectId), eq(projectMemberId)))
                .thenReturn(1);

        assertThatThrownBy(() -> projectRoleService.removeProjectMember(
                principal,
                workspaceId,
                projectId,
                targetUser
        )).isInstanceOf(WorkspaceProjectException.class)
                .satisfies(ex -> assertThat(((WorkspaceProjectException) ex).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }
}

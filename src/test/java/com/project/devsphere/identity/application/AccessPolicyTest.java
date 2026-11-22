package com.project.devsphere.identity.application;

import com.project.devsphere.identity.domain.ProjectRole;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.access.AccessDeniedException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccessPolicyTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private AccessPolicy accessPolicy;

    private final UUID userId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID projectMemberId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        accessPolicy = new AccessPolicy(jdbcTemplate);
    }

    private void mockActiveUserAndSession(String systemRole) {
        // Mock user query
        when(jdbcTemplate.query(
                contains("FROM users"),
                any(RowMapper.class),
                eq(userId)
        )).thenAnswer(inv -> {
            RowMapper<?> mapper = inv.getArgument(1);
            java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
            when(rs.getString("status")).thenReturn("ACTIVE");
            when(rs.getString("system_role")).thenReturn(systemRole);
            when(rs.getTimestamp("email_verified_at")).thenReturn(Timestamp.from(Instant.now()));
            return List.of(mapper.mapRow(rs, 0));
        });

        // Mock session query
        when(jdbcTemplate.query(
                contains("FROM auth_sessions"),
                any(RowMapper.class),
                eq(sessionId),
                eq(userId)
        )).thenAnswer(inv -> {
            RowMapper<?> mapper = inv.getArgument(1);
            java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
            when(rs.getString("id")).thenReturn(sessionId.toString());
            return List.of(mapper.mapRow(rs, 0));
        });
    }

    @Test
    void requireSystemAdmin_successWhenUserIsAdmin() {
        SessionService.Principal principal = new SessionService.Principal(userId, sessionId, "Admin", "ADMIN");
        mockActiveUserAndSession("ADMIN");

        accessPolicy.requireSystemAdmin(principal);
    }

    @Test
    void requireSystemAdmin_throwsAccessDeniedWhenUserIsNotAdmin() {
        SessionService.Principal principal = new SessionService.Principal(userId, sessionId, "User", "USER");
        mockActiveUserAndSession("USER");

        assertThatThrownBy(() -> accessPolicy.requireSystemAdmin(principal))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Access denied");
    }

    @Test
    void requireSystemAdmin_throwsAccessDeniedWhenUserIsLocked() {
        SessionService.Principal principal = new SessionService.Principal(userId, sessionId, "Admin", "ADMIN");
        when(jdbcTemplate.query(
                contains("FROM users"),
                any(RowMapper.class),
                eq(userId)
        )).thenAnswer(inv -> {
            RowMapper<?> mapper = inv.getArgument(1);
            java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
            when(rs.getString("status")).thenReturn("LOCKED");
            when(rs.getString("system_role")).thenReturn("ADMIN");
            when(rs.getTimestamp("email_verified_at")).thenReturn(Timestamp.from(Instant.now()));
            return List.of(mapper.mapRow(rs, 0));
        });

        assertThatThrownBy(() -> accessPolicy.requireSystemAdmin(principal))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Access denied");
    }

    @Test
    void requireSystemAdmin_throwsAccessDeniedWhenEmailNotVerified() {
        SessionService.Principal principal = new SessionService.Principal(userId, sessionId, "Admin", "ADMIN");
        when(jdbcTemplate.query(
                contains("FROM users"),
                any(RowMapper.class),
                eq(userId)
        )).thenAnswer(inv -> {
            RowMapper<?> mapper = inv.getArgument(1);
            java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
            when(rs.getString("status")).thenReturn("ACTIVE");
            when(rs.getString("system_role")).thenReturn("ADMIN");
            when(rs.getTimestamp("email_verified_at")).thenReturn(null);
            return List.of(mapper.mapRow(rs, 0));
        });

        assertThatThrownBy(() -> accessPolicy.requireSystemAdmin(principal))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Access denied");
    }

    @Test
    void requireSystemAdmin_throwsAccessDeniedWhenSessionExpiredOrRevoked() {
        SessionService.Principal principal = new SessionService.Principal(userId, sessionId, "Admin", "ADMIN");
        when(jdbcTemplate.query(
                contains("FROM users"),
                any(RowMapper.class),
                eq(userId)
        )).thenAnswer(inv -> {
            RowMapper<?> mapper = inv.getArgument(1);
            java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
            when(rs.getString("status")).thenReturn("ACTIVE");
            when(rs.getString("system_role")).thenReturn("ADMIN");
            when(rs.getTimestamp("email_verified_at")).thenReturn(Timestamp.from(Instant.now()));
            return List.of(mapper.mapRow(rs, 0));
        });

        when(jdbcTemplate.query(
                contains("FROM auth_sessions"),
                any(RowMapper.class),
                eq(sessionId),
                eq(userId)
        )).thenReturn(List.of()); // Session not found or revoked/expired

        assertThatThrownBy(() -> accessPolicy.requireSystemAdmin(principal))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Access denied");
    }

    @Test
    void requireWorkspaceMember_returnsScopeForActiveMember() {
        SessionService.Principal principal = new SessionService.Principal(userId, sessionId, "User", "USER");
        mockActiveUserAndSession("USER");

        when(jdbcTemplate.query(
                contains("FROM workspace_members"),
                any(RowMapper.class),
                eq(workspaceId),
                eq(userId)
        )).thenAnswer(inv -> {
            RowMapper<?> mapper = inv.getArgument(1);
            java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
            when(rs.getString("role")).thenReturn("MEMBER");
            return List.of(mapper.mapRow(rs, 0));
        });

        WorkspaceScope scope = accessPolicy.requireWorkspaceMember(principal, workspaceId);

        assertThat(scope.workspaceId()).isEqualTo(workspaceId);
        assertThat(scope.userId()).isEqualTo(userId);
        assertThat(scope.owner()).isFalse();
    }

    @Test
    void requireWorkspaceMember_detectsOwner() {
        SessionService.Principal principal = new SessionService.Principal(userId, sessionId, "User", "USER");
        mockActiveUserAndSession("USER");

        when(jdbcTemplate.query(
                contains("FROM workspace_members"),
                any(RowMapper.class),
                eq(workspaceId),
                eq(userId)
        )).thenAnswer(inv -> {
            RowMapper<?> mapper = inv.getArgument(1);
            java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
            when(rs.getString("role")).thenReturn("OWNER");
            return List.of(mapper.mapRow(rs, 0));
        });

        WorkspaceScope scope = accessPolicy.requireWorkspaceMember(principal, workspaceId);

        assertThat(scope.owner()).isTrue();
    }

    @Test
    void requireWorkspaceMember_throwsAccessDeniedWhenNotActiveMember() {
        SessionService.Principal principal = new SessionService.Principal(userId, sessionId, "User", "USER");
        mockActiveUserAndSession("USER");

        when(jdbcTemplate.query(
                contains("FROM workspace_members"),
                any(RowMapper.class),
                eq(workspaceId),
                eq(userId)
        )).thenReturn(List.of());

        assertThatThrownBy(() -> accessPolicy.requireWorkspaceMember(principal, workspaceId))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Access denied");
    }

    @Test
    void requireWorkspaceOwner_returnsScopeForOwner() {
        SessionService.Principal principal = new SessionService.Principal(userId, sessionId, "User", "USER");
        mockActiveUserAndSession("USER");

        when(jdbcTemplate.query(
                contains("FROM workspace_members wm"),
                any(RowMapper.class),
                eq(workspaceId),
                eq(userId),
                eq(userId)
        )).thenAnswer(inv -> {
            RowMapper<?> mapper = inv.getArgument(1);
            java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
            when(rs.getString("role")).thenReturn("OWNER");
            return List.of(mapper.mapRow(rs, 0));
        });

        WorkspaceScope scope = accessPolicy.requireWorkspaceOwner(principal, workspaceId);

        assertThat(scope.owner()).isTrue();
    }

    @Test
    void requireWorkspaceOwner_throwsAccessDeniedWhenNotOwner() {
        SessionService.Principal principal = new SessionService.Principal(userId, sessionId, "User", "USER");
        mockActiveUserAndSession("USER");

        when(jdbcTemplate.query(
                contains("FROM workspace_members wm"),
                any(RowMapper.class),
                eq(workspaceId),
                eq(userId),
                eq(userId)
        )).thenReturn(List.of());

        assertThatThrownBy(() -> accessPolicy.requireWorkspaceOwner(principal, workspaceId))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Access denied");
    }

    @Test
    void requireProjectMember_returnsScopeWithRoles() {
        SessionService.Principal principal = new SessionService.Principal(userId, sessionId, "Dev", "USER");
        mockActiveUserAndSession("USER");

        when(jdbcTemplate.query(
                contains("FROM projects p"),
                any(RowMapper.class),
                eq(projectId),
                eq(workspaceId),
                eq(userId)
        )).thenAnswer(inv -> {
            RowMapper<?> mapper = inv.getArgument(1);
            java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
            when(rs.getString("project_member_id")).thenReturn(projectMemberId.toString());
            return List.of(mapper.mapRow(rs, 0));
        });

        when(jdbcTemplate.query(
                contains("FROM project_member_roles"),
                any(RowMapper.class),
                eq(projectId),
                eq(projectMemberId)
        )).thenAnswer(inv -> {
            RowMapper<?> mapper = inv.getArgument(1);
            java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
            when(rs.getString("role")).thenReturn("DEVELOPER");
            return List.of(mapper.mapRow(rs, 0));
        });

        ProjectScope scope = accessPolicy.requireProjectMember(principal, workspaceId, projectId);

        assertThat(scope.workspaceId()).isEqualTo(workspaceId);
        assertThat(scope.projectId()).isEqualTo(projectId);
        assertThat(scope.userId()).isEqualTo(userId);
        assertThat(scope.roles()).containsExactly(ProjectRole.DEVELOPER);
        assertThat(scope.hasRole(ProjectRole.DEVELOPER)).isTrue();
        assertThat(scope.hasRole(ProjectRole.PM)).isFalse();
    }

    @Test
    void requireProjectMember_rejectsMismatchedWorkspaceProjectCrossTenant() {
        SessionService.Principal principal = new SessionService.Principal(userId, sessionId, "Dev", "USER");
        mockActiveUserAndSession("USER");

        // When project does not belong to workspace, query returns empty
        when(jdbcTemplate.query(
                contains("FROM projects p"),
                any(RowMapper.class),
                eq(projectId),
                eq(workspaceId),
                eq(userId)
        )).thenReturn(List.of());

        assertThatThrownBy(() -> accessPolicy.requireProjectMember(principal, workspaceId, projectId))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Access denied");
    }

    @Test
    void requireProjectRole_successWhenRolePresent() {
        SessionService.Principal principal = new SessionService.Principal(userId, sessionId, "PM", "USER");
        mockActiveUserAndSession("USER");

        when(jdbcTemplate.query(
                contains("FROM projects p"),
                any(RowMapper.class),
                eq(projectId),
                eq(workspaceId),
                eq(userId)
        )).thenAnswer(inv -> {
            RowMapper<?> mapper = inv.getArgument(1);
            java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
            when(rs.getString("project_member_id")).thenReturn(projectMemberId.toString());
            return List.of(mapper.mapRow(rs, 0));
        });

        when(jdbcTemplate.query(
                contains("FROM project_member_roles"),
                any(RowMapper.class),
                eq(projectId),
                eq(projectMemberId)
        )).thenAnswer(inv -> {
            RowMapper<?> mapper = inv.getArgument(1);
            java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
            when(rs.getString("role")).thenReturn("PM");
            return List.of(mapper.mapRow(rs, 0));
        });

        ProjectScope scope = accessPolicy.requireProjectRole(principal, workspaceId, projectId, ProjectRole.PM);

        assertThat(scope.hasRole(ProjectRole.PM)).isTrue();
    }

    @Test
    void requireProjectRole_throwsAccessDeniedWhenRoleMissing() {
        SessionService.Principal principal = new SessionService.Principal(userId, sessionId, "Dev", "USER");
        mockActiveUserAndSession("USER");

        when(jdbcTemplate.query(
                contains("FROM projects p"),
                any(RowMapper.class),
                eq(projectId),
                eq(workspaceId),
                eq(userId)
        )).thenAnswer(inv -> {
            RowMapper<?> mapper = inv.getArgument(1);
            java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
            when(rs.getString("project_member_id")).thenReturn(projectMemberId.toString());
            return List.of(mapper.mapRow(rs, 0));
        });

        when(jdbcTemplate.query(
                contains("FROM project_member_roles"),
                any(RowMapper.class),
                eq(projectId),
                eq(projectMemberId)
        )).thenAnswer(inv -> {
            RowMapper<?> mapper = inv.getArgument(1);
            java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
            when(rs.getString("role")).thenReturn("DEVELOPER");
            return List.of(mapper.mapRow(rs, 0));
        });

        assertThatThrownBy(() -> accessPolicy.requireProjectRole(principal, workspaceId, projectId, ProjectRole.PM))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Access denied");
    }

    @Test
    void requireAnyProjectRole_successWhenAtLeastOneMatches() {
        SessionService.Principal principal = new SessionService.Principal(userId, sessionId, "Member", "USER");
        mockActiveUserAndSession("USER");

        when(jdbcTemplate.query(
                contains("FROM projects p"),
                any(RowMapper.class),
                eq(projectId),
                eq(workspaceId),
                eq(userId)
        )).thenAnswer(inv -> {
            RowMapper<?> mapper = inv.getArgument(1);
            java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
            when(rs.getString("project_member_id")).thenReturn(projectMemberId.toString());
            return List.of(mapper.mapRow(rs, 0));
        });

        when(jdbcTemplate.query(
                contains("FROM project_member_roles"),
                any(RowMapper.class),
                eq(projectId),
                eq(projectMemberId)
        )).thenAnswer(inv -> {
            RowMapper<?> mapper = inv.getArgument(1);
            java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
            when(rs.getString("role")).thenReturn("VIEWER");
            return List.of(mapper.mapRow(rs, 0));
        });

        ProjectScope scope = accessPolicy.requireAnyProjectRole(
                principal,
                workspaceId,
                projectId,
                Set.of(ProjectRole.DEVELOPER, ProjectRole.VIEWER)
        );

        assertThat(scope.hasRole(ProjectRole.VIEWER)).isTrue();
    }
}

package com.project.devsphere.identity.application;

import com.project.devsphere.identity.domain.ProjectRole;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Central access policy and authorization guard service for DevSphere.
 * Enforces strict default-deny, verifies caller session and identity invariants,
 * and guards system, workspace, and project scopes without positive caching.
 */
@Service
@Transactional(readOnly = true)
public class AccessPolicy {

    private static final String ACCESS_DENIED_MESSAGE = "Access denied";

    private final JdbcTemplate jdbcTemplate;

    public AccessPolicy(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
    }

    /**
     * Enforces that the caller holds the ADMIN system role, with an active account,
     * verified email, and an active, unrevoked session.
     */
    public void requireSystemAdmin(SessionService.Principal principal) {
        CallerRecord caller = validateCaller(principal);
        if (!"ADMIN".equalsIgnoreCase(caller.systemRole())) {
            throw new AccessDeniedException(ACCESS_DENIED_MESSAGE);
        }
    }

    /**
     * Enforces that the caller is an authenticated user with an active account,
     * verified email, and an active, unrevoked session.
     */
    public void requireAuthenticatedUser(SessionService.Principal principal) {
        validateCaller(principal);
    }

    /**
     * Enforces that the caller is an active member of the designated workspace.
     */
    public WorkspaceScope requireWorkspaceMember(SessionService.Principal principal, UUID workspaceId) {
        if (workspaceId == null) {
            throw new AccessDeniedException(ACCESS_DENIED_MESSAGE);
        }
        validateCaller(principal);

        String sql = """
                SELECT role
                FROM workspace_members
                WHERE workspace_id = ?
                  AND user_id = ?
                  AND status = 'ACTIVE'
                """;

        List<String> roles = jdbcTemplate.query(
                sql,
                (rs, rowNum) -> rs.getString("role"),
                workspaceId,
                principal.userId()
        );

        if (roles.isEmpty()) {
            throw new AccessDeniedException(ACCESS_DENIED_MESSAGE);
        }

        boolean isOwner = "OWNER".equalsIgnoreCase(roles.get(0));
        return new WorkspaceScope(workspaceId, principal.userId(), isOwner);
    }

    /**
     * Enforces that the caller is the registered owner of the workspace and an active member.
     * Note: OWNER does not automatically grant PM or Project membership.
     */
    public WorkspaceScope requireWorkspaceOwner(SessionService.Principal principal, UUID workspaceId) {
        if (workspaceId == null) {
            throw new AccessDeniedException(ACCESS_DENIED_MESSAGE);
        }
        validateCaller(principal);

        String sql = """
                SELECT wm.role
                FROM workspace_members wm
                JOIN workspaces w ON w.id = wm.workspace_id
                WHERE wm.workspace_id = ?
                  AND wm.user_id = ?
                  AND wm.status = 'ACTIVE'
                  AND w.owner_id = ?
                """;

        List<String> roles = jdbcTemplate.query(
                sql,
                (rs, rowNum) -> rs.getString("role"),
                workspaceId,
                principal.userId(),
                principal.userId()
        );

        if (roles.isEmpty()) {
            throw new AccessDeniedException(ACCESS_DENIED_MESSAGE);
        }

        return new WorkspaceScope(workspaceId, principal.userId(), true);
    }

    /**
     * Enforces that the caller is an active project member, verifying that the project
     * belongs strictly to the requested workspace and the caller is an active workspace member.
     * Rejects mismatched workspace/project pairs without disclosing existence.
     */
    public ProjectScope requireProjectMember(SessionService.Principal principal, UUID workspaceId, UUID projectId) {
        if (workspaceId == null || projectId == null) {
            throw new AccessDeniedException(ACCESS_DENIED_MESSAGE);
        }
        validateCaller(principal);

        String memberSql = """
                SELECT pm.id AS project_member_id
                FROM projects p
                JOIN project_members pm ON pm.project_id = p.id AND pm.workspace_id = p.workspace_id
                JOIN workspace_members wm ON wm.workspace_id = p.workspace_id AND wm.user_id = pm.user_id
                WHERE p.id = ?
                  AND p.workspace_id = ?
                  AND pm.user_id = ?
                  AND pm.status = 'ACTIVE'
                  AND wm.status = 'ACTIVE'
                """;

        List<UUID> memberIds = jdbcTemplate.query(
                memberSql,
                (rs, rowNum) -> UUID.fromString(rs.getString("project_member_id")),
                projectId,
                workspaceId,
                principal.userId()
        );

        if (memberIds.isEmpty()) {
            throw new AccessDeniedException(ACCESS_DENIED_MESSAGE);
        }

        UUID projectMemberId = memberIds.get(0);
        Set<ProjectRole> activeRoles = loadActiveProjectRoles(projectId, projectMemberId);

        return new ProjectScope(workspaceId, projectId, principal.userId(), activeRoles);
    }

    /**
     * Enforces that the caller has the specified active role in the project.
     */
    public ProjectScope requireProjectRole(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            ProjectRole requiredRole) {
        if (requiredRole == null) {
            throw new AccessDeniedException(ACCESS_DENIED_MESSAGE);
        }
        ProjectScope scope = requireProjectMember(principal, workspaceId, projectId);
        if (!scope.hasRole(requiredRole)) {
            throw new AccessDeniedException(ACCESS_DENIED_MESSAGE);
        }
        return scope;
    }

    /**
     * Enforces that the caller has at least one of the required roles in the project.
     */
    public ProjectScope requireAnyProjectRole(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            Set<ProjectRole> requiredRoles) {
        if (requiredRoles == null || requiredRoles.isEmpty()) {
            throw new AccessDeniedException(ACCESS_DENIED_MESSAGE);
        }
        ProjectScope scope = requireProjectMember(principal, workspaceId, projectId);
        if (!scope.hasAnyRole(requiredRoles)) {
            throw new AccessDeniedException(ACCESS_DENIED_MESSAGE);
        }
        return scope;
    }

    /**
     * Validates caller credentials against canonical users and auth_sessions tables:
     * - User must exist, have status 'ACTIVE', and email_verified_at != null.
     * - Session must exist, match user, have revoked_at IS NULL, and expires_at > clock_timestamp().
     */
    private CallerRecord validateCaller(SessionService.Principal principal) {
        if (principal == null || principal.userId() == null || principal.sessionId() == null) {
            throw new AccessDeniedException(ACCESS_DENIED_MESSAGE);
        }

        String userSql = """
                SELECT status, email_verified_at, system_role
                FROM users
                WHERE id = ?
                """;

        List<CallerRecord> users = jdbcTemplate.query(
                userSql,
                (rs, rowNum) -> new CallerRecord(
                        principal.userId(),
                        principal.sessionId(),
                        rs.getString("status"),
                        rs.getString("system_role"),
                        rs.getTimestamp("email_verified_at") != null
                ),
                principal.userId()
        );

        if (users.isEmpty()) {
            throw new AccessDeniedException(ACCESS_DENIED_MESSAGE);
        }

        CallerRecord caller = users.get(0);
        if (!"ACTIVE".equals(caller.status()) || !caller.emailVerified()) {
            throw new AccessDeniedException(ACCESS_DENIED_MESSAGE);
        }

        String sessionSql = """
                SELECT id
                FROM auth_sessions
                WHERE id = ?
                  AND user_id = ?
                  AND revoked_at IS NULL
                  AND expires_at > clock_timestamp()
                """;

        List<UUID> sessions = jdbcTemplate.query(
                sessionSql,
                (rs, rowNum) -> UUID.fromString(rs.getString("id")),
                principal.sessionId(),
                principal.userId()
        );

        if (sessions.isEmpty()) {
            throw new AccessDeniedException(ACCESS_DENIED_MESSAGE);
        }

        return caller;
    }

    private Set<ProjectRole> loadActiveProjectRoles(UUID projectId, UUID projectMemberId) {
        String rolesSql = """
                SELECT role
                FROM project_member_roles
                WHERE project_id = ?
                  AND project_member_id = ?
                  AND revoked_at IS NULL
                """;

        List<String> roleStrings = jdbcTemplate.query(
                rolesSql,
                (rs, rowNum) -> rs.getString("role"),
                projectId,
                projectMemberId
        );

        Set<ProjectRole> roles = new HashSet<>();
        for (String r : roleStrings) {
            if (r != null) {
                try {
                    roles.add(ProjectRole.valueOf(r.trim()));
                } catch (IllegalArgumentException ignored) {
                    // Safe ignore for any unsupported role values
                }
            }
        }
        return Collections.unmodifiableSet(roles);
    }

    private record CallerRecord(
            UUID userId,
            UUID sessionId,
            String status,
            String systemRole,
            boolean emailVerified) {}
}

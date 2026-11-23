package com.project.devsphere.project.application;

import com.project.devsphere.identity.application.AccessPolicy;
import com.project.devsphere.identity.application.SessionService;
import com.project.devsphere.identity.domain.ProjectRole;
import com.project.devsphere.workspace.application.WorkspaceProjectException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class ProjectRoleService {

    private final JdbcTemplate jdbcTemplate;
    private final AccessPolicy accessPolicy;

    public ProjectRoleService(JdbcTemplate jdbcTemplate, AccessPolicy accessPolicy) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy must not be null");
    }

    /**
     * Lists members and active roles for a project.
     * Guarded by workspace membership.
     */
    @Transactional(readOnly = true)
    public List<ProjectMemberDetails> listProjectMembers(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId) {
        accessPolicy.requireWorkspaceMember(principal, workspaceId);

        String verifyProjectSql = "SELECT count(*) FROM projects WHERE id = ? AND workspace_id = ?";
        Integer prjCount = jdbcTemplate.queryForObject(verifyProjectSql, Integer.class, projectId, workspaceId);
        if (prjCount == null || prjCount == 0) {
            throw WorkspaceProjectException.notFound("Project not found in designated workspace");
        }

        String sql = """
                SELECT pm.user_id, u.email, u.display_name, pm.status, pm.joined_at, pr.role
                FROM project_members pm
                JOIN users u ON u.id = pm.user_id
                LEFT JOIN project_member_roles pr ON pr.project_member_id = pm.id
                                                 AND pr.project_id = pm.project_id
                                                 AND pr.revoked_at IS NULL
                WHERE pm.project_id = ?
                ORDER BY pm.joined_at ASC
                """;

        Map<UUID, MemberAccumulator> membersMap = new LinkedHashMap<>();

        jdbcTemplate.query(sql, rs -> {
            UUID userId = UUID.fromString(rs.getString("user_id"));
            MemberAccumulator acc = membersMap.computeIfAbsent(userId, id -> {
                try {
                    return new MemberAccumulator(
                            id,
                            rs.getString("email"),
                            rs.getString("display_name"),
                            rs.getString("status"),
                            toInstant(rs.getTimestamp("joined_at"))
                    );
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });

            String roleStr = rs.getString("role");
            if (roleStr != null && !roleStr.isBlank()) {
                try {
                    acc.roles.add(ProjectRole.valueOf(roleStr.trim()));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }, projectId);

        List<ProjectMemberDetails> result = new ArrayList<>();
        for (MemberAccumulator acc : membersMap.values()) {
            result.add(new ProjectMemberDetails(
                    acc.userId,
                    acc.email,
                    acc.displayName,
                    acc.status,
                    acc.joinedAt,
                    Collections.unmodifiableSet(acc.roles)
            ));
        }

        return result;
    }

    /**
     * Grants a project role (DEVELOPER, CLIENT, VIEWER) to an active workspace member.
     * Guarded by requireProjectRole(PM).
     * Rejects direct PM role grants.
     */
    @Transactional
    public void grantProjectRole(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            GrantRoleCommand command) {
        accessPolicy.requireProjectRole(principal, workspaceId, projectId, ProjectRole.PM);
        setActorId(principal);

        if (command == null || command.userId() == null || command.role() == null) {
            throw WorkspaceProjectException.validationFailed("User id and role must be provided");
        }

        if (command.role() == ProjectRole.PM) {
            throw WorkspaceProjectException.pmRoleRestricted("PM role cannot be granted directly via member role API");
        }

        // Target user must be an active workspace member
        String wsMemberSql = """
                SELECT count(*)
                FROM workspace_members
                WHERE workspace_id = ?
                  AND user_id = ?
                  AND status = 'ACTIVE'
                """;
        Integer wsCount = jdbcTemplate.queryForObject(wsMemberSql, Integer.class, workspaceId, command.userId());
        if (wsCount == null || wsCount == 0) {
            throw WorkspaceProjectException.validationFailed("Target user must be an active workspace member");
        }

        // Upsert project_members record
        String upsertMemberSql = """
                INSERT INTO project_members (
                    id, project_id, workspace_id, user_id, status, joined_at, created_at, updated_at, version
                ) VALUES (
                    gen_random_uuid(), ?, ?, ?, 'ACTIVE', now(), now(), now(), 0
                )
                ON CONFLICT (project_id, user_id) DO UPDATE
                SET status = 'ACTIVE', updated_at = now()
                RETURNING id
                """;

        UUID projectMemberId = jdbcTemplate.queryForObject(
                upsertMemberSql,
                UUID.class,
                projectId,
                workspaceId,
                command.userId()
        );

        // Check if role is already actively granted
        String existingRoleSql = """
                SELECT count(*)
                FROM project_member_roles
                WHERE project_id = ?
                  AND project_member_id = ?
                  AND role = ?
                  AND revoked_at IS NULL
                """;
        Integer roleCount = jdbcTemplate.queryForObject(
                existingRoleSql,
                Integer.class,
                projectId,
                projectMemberId,
                command.role().name()
        );

        if (roleCount == null || roleCount == 0) {
            // Note: trigger ds_role_receipt enforces accepted_by == project_members.user_id
            String insertRoleSql = """
                    INSERT INTO project_member_roles (
                        id, project_id, project_member_id, role, granted_by, granted_at, accepted_by, accepted_at, created_at
                    ) VALUES (
                        gen_random_uuid(), ?, ?, ?, ?, now(), ?, now(), now()
                    )
                    """;
            jdbcTemplate.update(
                    insertRoleSql,
                    projectId,
                    projectMemberId,
                    command.role().name(),
                    principal.userId(),
                    command.userId()
            );
        }
    }

    /**
     * Revokes a specific project role.
     * Guarded by requireProjectRole(PM).
     * Clears deadline_approver_id if revoked role is the user's last CLIENT role.
     */
    @Transactional
    public void revokeProjectRole(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UUID targetUserId,
            ProjectRole role) {
        accessPolicy.requireProjectRole(principal, workspaceId, projectId, ProjectRole.PM);
        setActorId(principal);

        if (role == null) {
            throw WorkspaceProjectException.validationFailed("Role must not be null");
        }

        if (role == ProjectRole.PM) {
            throw WorkspaceProjectException.pmRoleRestricted("Cannot revoke PM role via member role API");
        }

        String memberSql = "SELECT id FROM project_members WHERE project_id = ? AND user_id = ?";
        List<UUID> memberIds = jdbcTemplate.query(memberSql, (rs, rowNum) -> UUID.fromString(rs.getString("id")), projectId, targetUserId);
        if (memberIds.isEmpty()) {
            throw WorkspaceProjectException.notFound("Project member not found");
        }

        UUID projectMemberId = memberIds.get(0);

        jdbcTemplate.update(
                "UPDATE project_member_roles SET revoked_at = now() WHERE project_id = ? AND project_member_id = ? AND role = ? AND revoked_at IS NULL",
                projectId,
                projectMemberId,
                role.name()
        );

        // If revoked role is CLIENT, check if user has any remaining active CLIENT role
        if (role == ProjectRole.CLIENT) {
            Integer clientCount = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM project_member_roles WHERE project_id = ? AND project_member_id = ? AND role = 'CLIENT' AND revoked_at IS NULL",
                    Integer.class,
                    projectId,
                    projectMemberId
            );
            if (clientCount == null || clientCount == 0) {
                jdbcTemplate.update(
                        "UPDATE projects SET deadline_approver_id = NULL WHERE id = ? AND deadline_approver_id = ?",
                        projectId,
                        targetUserId
                );
            }
        }
    }

    /**
     * Removes a member from a project, revoking all active non-PM roles.
     * Guarded by requireProjectRole(PM).
     */
    @Transactional
    public void removeProjectMember(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UUID targetUserId) {
        accessPolicy.requireProjectRole(principal, workspaceId, projectId, ProjectRole.PM);
        setActorId(principal);

        String memberSql = "SELECT id FROM project_members WHERE project_id = ? AND user_id = ?";
        List<UUID> memberIds = jdbcTemplate.query(memberSql, (rs, rowNum) -> UUID.fromString(rs.getString("id")), projectId, targetUserId);
        if (memberIds.isEmpty()) {
            throw WorkspaceProjectException.notFound("Project member not found");
        }

        UUID projectMemberId = memberIds.get(0);

        // Disallow removing active PM via this endpoint
        Integer pmCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM project_member_roles WHERE project_id = ? AND project_member_id = ? AND role = 'PM' AND revoked_at IS NULL",
                Integer.class,
                projectId,
                projectMemberId
        );
        if (pmCount != null && pmCount > 0) {
            throw WorkspaceProjectException.validationFailed("Cannot remove active PM from project");
        }

        jdbcTemplate.update(
                "UPDATE project_member_roles SET revoked_at = now() WHERE project_id = ? AND project_member_id = ? AND revoked_at IS NULL",
                projectId,
                projectMemberId
        );

        jdbcTemplate.update(
                "UPDATE project_members SET status = 'REMOVED', updated_at = now() WHERE id = ?",
                projectMemberId
        );

        jdbcTemplate.update(
                "UPDATE projects SET deadline_approver_id = NULL WHERE id = ? AND deadline_approver_id = ?",
                projectId,
                targetUserId
        );
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp != null ? timestamp.toInstant() : null;
    }

    private void setActorId(SessionService.Principal principal) {
        if (principal != null && principal.userId() != null) {
            try {
                jdbcTemplate.queryForObject("SELECT set_config('devsphere.actor_id', ?, true)", String.class, principal.userId().toString());
            } catch (Exception ignored) {
            }
        }
    }

    private static class MemberAccumulator {
        final UUID userId;
        final String email;
        final String displayName;
        final String status;
        final Instant joinedAt;
        final Set<ProjectRole> roles = new LinkedHashSet<>();

        MemberAccumulator(UUID userId, String email, String displayName, String status, Instant joinedAt) {
            this.userId = userId;
            this.email = email;
            this.displayName = displayName;
            this.status = status;
            this.joinedAt = joinedAt;
        }
    }
}

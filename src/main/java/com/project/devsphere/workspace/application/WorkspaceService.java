package com.project.devsphere.workspace.application;

import com.project.devsphere.identity.application.AccessPolicy;
import com.project.devsphere.identity.application.SessionService;
import com.project.devsphere.workspace.api.dto.*;
import com.project.devsphere.workspace.domain.Workspace;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Service
public class WorkspaceService {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final AccessPolicy accessPolicy;

    public WorkspaceService(
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate,
            AccessPolicy accessPolicy) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.accessPolicy = accessPolicy;
    }

    public WorkspaceResponse createWorkspace(SessionService.Principal principal, CreateWorkspaceCommand command) {
        if (command == null) {
            throw WorkspaceProjectException.validationFailed("Create workspace command must not be null");
        }
        return createWorkspace(principal, new CreateWorkspaceRequest(command.name(), command.slug(), command.description()));
    }

    public WorkspaceResponse createWorkspace(SessionService.Principal principal, CreateWorkspaceRequest request) {
        if (principal == null || principal.userId() == null) {
            throw WorkspaceProjectException.accessDenied("Authentication required");
        }

        // Check duplicate slug
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM workspaces WHERE slug = ?",
                Integer.class,
                request.slug()
        );
        if (count != null && count > 0) {
            throw WorkspaceProjectException.duplicate("Workspace with slug '" + request.slug() + "' already exists");
        }

        UUID workspaceId = UUID.randomUUID();
        UUID ownerId = principal.userId();

        try {
            return transactionTemplate.execute(status -> {
                jdbcTemplate.queryForObject(
                        "SELECT set_config('devsphere.actor_id', ?, true)",
                        String.class,
                        ownerId.toString()
                );

                jdbcTemplate.update(
                        "INSERT INTO workspaces (id, slug, name, owner_id, description, status, settings, created_at, updated_at, version) " +
                                "VALUES (?, ?, ?, ?, ?, 'SETUP_INCOMPLETE', '{\"schemaVersion\":1}'::jsonb, clock_timestamp(), clock_timestamp(), 0)",
                        workspaceId,
                        request.slug(),
                        request.name(),
                        ownerId,
                        request.description()
                );

                jdbcTemplate.update(
                        "INSERT INTO workspace_members (id, workspace_id, user_id, role, status, joined_at, created_at, updated_at, version) " +
                                "VALUES (gen_random_uuid(), ?, ?, 'OWNER', 'ACTIVE', clock_timestamp(), clock_timestamp(), clock_timestamp(), 0)",
                        workspaceId,
                        ownerId
                );

                return getWorkspaceById(workspaceId);
            });
        } catch (DuplicateKeyException ex) {
            throw WorkspaceProjectException.duplicate("Workspace with slug '" + request.slug() + "' already exists");
        }
    }

    public WorkspacePageResponse<WorkspaceResponse> listWorkspaces(
            SessionService.Principal principal,
            org.springframework.data.domain.Pageable pageable) {
        int page = pageable != null ? pageable.getPageNumber() : 0;
        int size = pageable != null ? pageable.getPageSize() : 20;
        return listWorkspaces(principal, page, size);
    }

    public WorkspacePageResponse<WorkspaceResponse> listWorkspaces(
            SessionService.Principal principal,
            int page,
            int size) {

        if (principal == null || principal.userId() == null) {
            throw WorkspaceProjectException.accessDenied("Authentication required");
        }

        int boundedPage = Math.max(0, Math.min(page, 10000));
        int boundedSize = Math.max(1, Math.min(size, 100));

        Long totalElements = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM workspaces w " +
                        "JOIN workspace_members wm ON wm.workspace_id = w.id " +
                        "WHERE wm.user_id = ? AND wm.status = 'ACTIVE'",
                Long.class,
                principal.userId()
        );
        long total = totalElements != null ? totalElements : 0L;
        int totalPages = total == 0 ? 0 : (int) Math.ceil((double) total / boundedSize);

        List<WorkspaceResponse> workspaces = jdbcTemplate.query(
                "SELECT w.id, w.slug, w.name, w.description, w.status, w.owner_id, w.created_at, w.updated_at, w.version " +
                        "FROM workspaces w " +
                        "JOIN workspace_members wm ON wm.workspace_id = w.id " +
                        "WHERE wm.user_id = ? AND wm.status = 'ACTIVE' " +
                        "ORDER BY w.created_at DESC " +
                        "LIMIT ? OFFSET ?",
                (rs, rowNum) -> mapWorkspaceResponse(rs),
                principal.userId(),
                boundedSize,
                boundedPage * boundedSize
        );

        return new WorkspacePageResponse<>(workspaces, boundedPage, boundedSize, total, totalPages);
    }

    public WorkspaceResponse getWorkspace(SessionService.Principal principal, UUID workspaceId) {
        accessPolicy.requireWorkspaceMember(principal, workspaceId);
        return getWorkspaceById(workspaceId);
    }

    public WorkspaceResponse updateWorkspace(
            SessionService.Principal principal,
            UUID workspaceId,
            UpdateWorkspaceCommand command) {
        if (command == null) {
            throw WorkspaceProjectException.validationFailed("Update workspace command must not be null");
        }
        return updateWorkspace(principal, workspaceId, new UpdateWorkspaceRequest(command.name(), command.description(), command.expectedVersion()));
    }

    public WorkspaceResponse updateWorkspace(
            SessionService.Principal principal,
            UUID workspaceId,
            UpdateWorkspaceRequest request) {

        accessPolicy.requireWorkspaceOwner(principal, workspaceId);

        return transactionTemplate.execute(status -> {
            jdbcTemplate.queryForObject(
                    "SELECT set_config('devsphere.actor_id', ?, true)",
                    String.class,
                    principal.userId().toString()
            );

            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT id, version FROM workspaces WHERE id = ? FOR UPDATE",
                    workspaceId
            );
            if (rows.isEmpty()) {
                throw WorkspaceProjectException.notFound("Workspace not found");
            }

            Long currentVersion = ((Number) rows.get(0).get("version")).longValue();
            if (!currentVersion.equals(request.expectedVersion())) {
                throw WorkspaceProjectException.planStale("Workspace version mismatch: expected " + request.expectedVersion() + " but was " + currentVersion);
            }

            String name = request.name();
            String description = request.description();

            int updated = jdbcTemplate.update(
                    "UPDATE workspaces SET name = COALESCE(?, name), description = ?, version = version + 1, updated_at = clock_timestamp() " +
                            "WHERE id = ? AND version = ?",
                    name,
                    description,
                    workspaceId,
                    currentVersion
            );
            if (updated == 0) {
                throw WorkspaceProjectException.planStale("Concurrent modification on workspace");
            }

            return getWorkspaceById(workspaceId);
        });
    }

    public WorkspaceSetupResponse getWorkspaceSetup(SessionService.Principal principal, UUID workspaceId) {
        accessPolicy.requireWorkspaceOwner(principal, workspaceId);

        WorkspaceResponse workspace = getWorkspaceById(workspaceId);

        Integer projectCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM projects WHERE workspace_id = ?",
                Integer.class,
                workspaceId
        );
        boolean projectConfigured = projectCount != null && projectCount > 0;

        Integer pmCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM project_members pm " +
                        "JOIN project_member_roles pmr ON pmr.project_member_id = pm.id " +
                        "WHERE pm.workspace_id = ? AND pm.status = 'ACTIVE' " +
                        "AND pmr.role = 'PM' AND pmr.revoked_at IS NULL AND pmr.accepted_at IS NOT NULL",
                Integer.class,
                workspaceId
        );
        boolean pmAssigned = pmCount != null && pmCount > 0;

        Long pendingInvites = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM workspace_invitations " +
                        "WHERE workspace_id = ? AND status = 'PENDING' AND expires_at > clock_timestamp()",
                Long.class,
                workspaceId
        );
        long pendingInvitationsCount = pendingInvites != null ? pendingInvites : 0L;

        return new WorkspaceSetupResponse(
                workspace.id(),
                workspace.slug(),
                workspace.name(),
                workspace.status(),
                projectConfigured,
                pmAssigned,
                pendingInvitationsCount,
                workspace.createdAt()
        );
    }

    private WorkspaceResponse getWorkspaceById(UUID workspaceId) {
        List<WorkspaceResponse> list = jdbcTemplate.query(
                "SELECT id, slug, name, description, status, owner_id, created_at, updated_at, version " +
                        "FROM workspaces WHERE id = ?",
                (rs, rowNum) -> mapWorkspaceResponse(rs),
                workspaceId
        );
        if (list.isEmpty()) {
            throw WorkspaceProjectException.notFound("Workspace not found");
        }
        return list.get(0);
    }

    private WorkspaceResponse mapWorkspaceResponse(ResultSet rs) throws SQLException {
        Timestamp createdTs = rs.getTimestamp("created_at");
        Timestamp updatedTs = rs.getTimestamp("updated_at");

        return new WorkspaceResponse(
                (UUID) rs.getObject("id"),
                rs.getString("slug"),
                rs.getString("name"),
                rs.getString("description"),
                Workspace.Status.valueOf(rs.getString("status")),
                (UUID) rs.getObject("owner_id"),
                createdTs != null ? createdTs.toInstant() : null,
                updatedTs != null ? updatedTs.toInstant() : null,
                rs.getLong("version")
        );
    }
}

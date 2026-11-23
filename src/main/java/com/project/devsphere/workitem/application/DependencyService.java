package com.project.devsphere.workitem.application;

import com.project.devsphere.identity.application.AccessPolicy;
import com.project.devsphere.identity.application.SessionService;
import com.project.devsphere.identity.domain.ProjectRole;
import com.project.devsphere.workitem.api.dto.CreateDependencyRequest;
import com.project.devsphere.workitem.api.dto.WorkItemDependencyResponse;
import com.project.devsphere.workspace.application.WorkspaceProjectException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class DependencyService {

    private final JdbcTemplate jdbcTemplate;
    private final AccessPolicy accessPolicy;

    public DependencyService(JdbcTemplate jdbcTemplate, AccessPolicy accessPolicy) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy must not be null");
    }

    private static final RowMapper<WorkItemDependencyResponse> DEPENDENCY_MAPPER = (rs, rowNum) -> new WorkItemDependencyResponse(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("project_id")),
            UUID.fromString(rs.getString("predecessor_id")),
            UUID.fromString(rs.getString("successor_id")),
            rs.getString("link_type"),
            rs.getInt("lag_minutes"),
            toInstant(rs.getTimestamp("created_at"))
    );

    /**
     * Adds a dependency edge between two work items in the same project.
     * Enforces active PM authority, rejects self-dependency, and detects cycles via trigger ds_dependency_dag.
     */
    @Transactional
    public WorkItemDependencyResponse addDependency(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            CreateDependencyRequest request) {

        accessPolicy.requireProjectRole(principal, workspaceId, projectId, ProjectRole.PM);

        if (request == null || request.predecessorId() == null || request.successorId() == null) {
            throw WorkspaceProjectException.validationFailed("Predecessor and successor must be provided");
        }

        if (request.predecessorId().equals(request.successorId())) {
            throw WorkspaceProjectException.dependencyCycle("Self-dependency is not allowed");
        }

        // Verify both work items exist, belong to this project, and are not deleted
        String checkSql = "SELECT count(*) FROM work_items WHERE project_id = ? AND id IN (?, ?) AND deleted_at IS NULL";
        Integer count = jdbcTemplate.queryForObject(checkSql, Integer.class, projectId, request.predecessorId(), request.successorId());
        if (count == null || count < 2) {
            throw WorkspaceProjectException.notFound("Predecessor or successor work item not found in this project");
        }

        // Check for duplicate link
        String dupSql = "SELECT count(*) FROM work_item_dependencies WHERE project_id = ? AND predecessor_id = ? AND successor_id = ?";
        Integer dupCount = jdbcTemplate.queryForObject(dupSql, Integer.class, projectId, request.predecessorId(), request.successorId());
        if (dupCount != null && dupCount > 0) {
            throw WorkspaceProjectException.duplicate("Dependency between these work items already exists");
        }

        UUID id = UUID.randomUUID();
        String insertSql = """
                INSERT INTO work_item_dependencies (id, project_id, predecessor_id, successor_id, link_type, lag_minutes, created_at)
                VALUES (?, ?, ?, ?, ?, ?, now())
                """;

        try {
            if (principal.userId() != null) {
                jdbcTemplate.queryForObject("SELECT set_config('devsphere.actor_id', ?, true)", String.class, principal.userId().toString());
            }
            jdbcTemplate.update(
                    insertSql,
                    id,
                    projectId,
                    request.predecessorId(),
                    request.successorId(),
                    request.linkType(),
                    request.lagMinutes()
            );
        } catch (DataAccessException e) {
            handleDependencyException(e);
        }

        return getDependency(projectId, id);
    }

    /**
     * Lists all dependencies for the specified project.
     */
    @Transactional(readOnly = true)
    public List<WorkItemDependencyResponse> listDependencies(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId) {

        accessPolicy.requireProjectMember(principal, workspaceId, projectId);

        String sql = """
                SELECT id, project_id, predecessor_id, successor_id, link_type, lag_minutes, created_at
                FROM work_item_dependencies
                WHERE project_id = ?
                ORDER BY created_at ASC
                """;

        return jdbcTemplate.query(sql, DEPENDENCY_MAPPER, projectId);
    }

    /**
     * Removes a dependency edge.
     */
    @Transactional
    public void removeDependency(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UUID dependencyId) {

        accessPolicy.requireProjectRole(principal, workspaceId, projectId, ProjectRole.PM);

        if (principal.userId() != null) {
            jdbcTemplate.queryForObject("SELECT set_config('devsphere.actor_id', ?, true)", String.class, principal.userId().toString());
        }

        int rows = jdbcTemplate.update(
                "DELETE FROM work_item_dependencies WHERE id = ? AND project_id = ?",
                dependencyId,
                projectId
        );

        if (rows == 0) {
            throw WorkspaceProjectException.notFound("Dependency not found");
        }
    }

    private WorkItemDependencyResponse getDependency(UUID projectId, UUID dependencyId) {
        String sql = """
                SELECT id, project_id, predecessor_id, successor_id, link_type, lag_minutes, created_at
                FROM work_item_dependencies
                WHERE id = ? AND project_id = ?
                """;

        List<WorkItemDependencyResponse> items = jdbcTemplate.query(sql, DEPENDENCY_MAPPER, dependencyId, projectId);
        if (items.isEmpty()) {
            throw WorkspaceProjectException.notFound("Dependency not found");
        }
        return items.get(0);
    }

    private void handleDependencyException(DataAccessException e) {
        String msg = getRootMessage(e);
        if (msg != null && msg.contains("Dependency cycle")) {
            throw WorkspaceProjectException.dependencyCycle("Dependency cycle detected in project work item DAG");
        }
        throw e;
    }

    private static String getRootMessage(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getMessage();
    }

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }
}

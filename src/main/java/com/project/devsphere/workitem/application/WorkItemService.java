package com.project.devsphere.workitem.application;

import com.project.devsphere.identity.application.AccessPolicy;
import com.project.devsphere.identity.application.ProjectScope;
import com.project.devsphere.identity.application.SessionService;
import com.project.devsphere.identity.domain.ProjectRole;
import com.project.devsphere.workitem.api.dto.AssignWorkItemRequest;
import com.project.devsphere.workitem.api.dto.BlockWorkItemRequest;
import com.project.devsphere.workitem.api.dto.CreateWorkItemRequest;
import com.project.devsphere.workitem.api.dto.RejectWorkItemRequest;
import com.project.devsphere.workitem.api.dto.TransitionStatusRequest;
import com.project.devsphere.workitem.api.dto.UpdateWorkItemRequest;
import com.project.devsphere.workitem.api.dto.WorkItemResponse;
import com.project.devsphere.workitem.domain.WorkItem;
import com.project.devsphere.workspace.application.WorkspaceProjectException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class WorkItemService {

    private final JdbcTemplate jdbcTemplate;
    private final AccessPolicy accessPolicy;

    public WorkItemService(JdbcTemplate jdbcTemplate, AccessPolicy accessPolicy) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy must not be null");
    }

    private static final RowMapper<WorkItemResponse> WORK_ITEM_MAPPER = (rs, rowNum) -> {
        Array labelsArray = rs.getArray("labels");
        String[] labels = labelsArray != null ? (String[]) labelsArray.getArray() : new String[0];

        return new WorkItemResponse(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("project_id")),
                rs.getString("item_key"),
                toUuid(rs.getString("parent_id")),
                toUuid(rs.getString("requirement_id")),
                toUuid(rs.getString("sprint_id")),
                toUuid(rs.getString("milestone_id")),
                WorkItem.Type.valueOf(rs.getString("type")),
                WorkItem.WorkKind.valueOf(rs.getString("work_kind")),
                rs.getString("title"),
                rs.getString("description"),
                WorkItem.Status.valueOf(rs.getString("status")),
                rs.getBoolean("is_blocked"),
                rs.getString("blocked_reason"),
                WorkItem.Priority.valueOf(rs.getString("priority")),
                toUuid(rs.getString("assignee_id")),
                labels,
                rs.getBigDecimal("backlog_rank"),
                rs.getBigDecimal("story_points"),
                rs.getObject("estimate_minutes", Integer.class),
                rs.getObject("remaining_minutes", Integer.class),
                rs.getObject("time_spent_minutes", Integer.class),
                rs.getBigDecimal("claimed_progress"),
                rs.getBigDecimal("verified_progress"),
                toInstant(rs.getTimestamp("started_at")),
                toInstant(rs.getTimestamp("completed_at")),
                toInstant(rs.getTimestamp("due_at")),
                toUuid(rs.getString("accepted_by")),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")),
                rs.getLong("version")
        );
    };

    /**
     * Creates a new work item.
     * Enforces active PM or DEVELOPER authority, assigns sequential item_key via trigger ds_work_item_key,
     * and validates parent-child hierarchy via trigger ds_work_item_guard.
     */
    @Transactional
    public WorkItemResponse createWorkItem(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            CreateWorkItemRequest request) {

        accessPolicy.requireAnyProjectRole(principal, workspaceId, projectId, Set.of(ProjectRole.PM, ProjectRole.DEVELOPER));

        if (request == null || request.type() == null || request.title() == null || request.title().isBlank()) {
            throw WorkspaceProjectException.validationFailed("Type and title must be provided");
        }

        UUID id = UUID.randomUUID();

        // Calculate next backlog rank
        BigDecimal rank = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(backlog_rank), 0) + 1 FROM work_items WHERE project_id = ? AND deleted_at IS NULL",
                BigDecimal.class,
                projectId
        );
        if (rank == null) {
            rank = BigDecimal.ONE;
        }

        String sql = """
                INSERT INTO work_items (
                    id, project_id, type, work_kind, title, description, status, priority,
                    parent_id, requirement_id, sprint_id, milestone_id, assignee_id,
                    story_points, estimate_minutes, remaining_minutes, time_spent_minutes,
                    is_blocked, blocked_reason, labels, backlog_rank, claimed_progress,
                    required_skills, progress_evidence, due_at, created_at, updated_at, version
                ) VALUES (
                    ?, ?, ?, 'CODE', ?, ?, 'TO_DO', ?,
                    ?, ?, ?, ?, ?,
                    ?, ?, ?, 0,
                    false, NULL, ?, ?, 0,
                    '[]'::jsonb, '{"schemaVersion":1}'::jsonb, ?, now(), now(), 0
                )
                """;

        try {
            setActorContext(principal.userId());
            jdbcTemplate.update(
                    sql,
                    id,
                    projectId,
                    request.type().name(),
                    request.title().trim(),
                    request.description() != null ? request.description() : "",
                    request.priority() != null ? request.priority().name() : "MEDIUM",
                    request.parentId(),
                    request.requirementId(),
                    request.sprintId(),
                    request.milestoneId(),
                    request.assigneeId(),
                    request.storyPoints(),
                    request.estimateMinutes(),
                    request.estimateMinutes(),
                    request.labels() != null ? request.labels() : new String[0],
                    rank,
                    request.dueAt() != null ? Timestamp.from(request.dueAt()) : null
            );
        } catch (DataAccessException e) {
            handleWorkItemException(e);
        }

        return getWorkItem(principal, workspaceId, projectId, id);
    }

    /**
     * Lists non-deleted work items in the project matching optional filters.
     */
    @Transactional(readOnly = true)
    public List<WorkItemResponse> listWorkItems(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            WorkItem.Type type,
            WorkItem.Status status,
            UUID assigneeId,
            UUID parentId,
            UUID requirementId) {

        accessPolicy.requireProjectMember(principal, workspaceId, projectId);

        StringBuilder sql = new StringBuilder("""
                SELECT id, project_id, item_key, parent_id, requirement_id, sprint_id, milestone_id,
                       type, work_kind, title, description, status, is_blocked, blocked_reason,
                       priority, assignee_id, labels, backlog_rank, story_points, estimate_minutes,
                       remaining_minutes, time_spent_minutes, claimed_progress, verified_progress,
                       started_at, completed_at, due_at, accepted_by, created_at, updated_at, version
                FROM work_items
                WHERE project_id = ? AND deleted_at IS NULL
                """);

        List<Object> params = new ArrayList<>();
        params.add(projectId);

        if (type != null) {
            sql.append(" AND type = ?");
            params.add(type.name());
        }
        if (status != null) {
            sql.append(" AND status = ?");
            params.add(status.name());
        }
        if (assigneeId != null) {
            sql.append(" AND assignee_id = ?");
            params.add(assigneeId);
        }
        if (parentId != null) {
            sql.append(" AND parent_id = ?");
            params.add(parentId);
        }
        if (requirementId != null) {
            sql.append(" AND requirement_id = ?");
            params.add(requirementId);
        }

        sql.append(" ORDER BY backlog_rank ASC, id ASC");

        return jdbcTemplate.query(sql.toString(), WORK_ITEM_MAPPER, params.toArray());
    }

    /**
     * Gets a single work item by ID.
     */
    @Transactional(readOnly = true)
    public WorkItemResponse getWorkItem(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UUID workItemId) {

        accessPolicy.requireProjectMember(principal, workspaceId, projectId);

        String sql = """
                SELECT id, project_id, item_key, parent_id, requirement_id, sprint_id, milestone_id,
                       type, work_kind, title, description, status, is_blocked, blocked_reason,
                       priority, assignee_id, labels, backlog_rank, story_points, estimate_minutes,
                       remaining_minutes, time_spent_minutes, claimed_progress, verified_progress,
                       started_at, completed_at, due_at, accepted_by, created_at, updated_at, version
                FROM work_items
                WHERE id = ? AND project_id = ? AND deleted_at IS NULL
                """;

        List<WorkItemResponse> items = jdbcTemplate.query(sql, WORK_ITEM_MAPPER, workItemId, projectId);
        if (items.isEmpty()) {
            throw WorkspaceProjectException.notFound("Work item not found");
        }

        return items.get(0);
    }

    /**
     * Updates non-status work item metadata.
     * Enforces optimistic locking and rejects updates to DONE items.
     */
    @Transactional
    public WorkItemResponse updateWorkItem(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UUID workItemId,
            UpdateWorkItemRequest request) {

        accessPolicy.requireAnyProjectRole(principal, workspaceId, projectId, Set.of(ProjectRole.PM, ProjectRole.DEVELOPER));

        WorkItemResponse current = getWorkItemForUpdate(workspaceId, projectId, workItemId);

        if (current.version() != null && !Objects.equals(current.version(), request.expectedVersion())) {
            throw WorkspaceProjectException.planStale("Work item version conflict");
        }

        if (current.status() == WorkItem.Status.DONE) {
            throw WorkspaceProjectException.scopeLocked("DONE Work Item scope and schedule are frozen");
        }

        StringBuilder sql = new StringBuilder("UPDATE work_items SET version = version + 1, updated_at = now()");
        List<Object> params = new ArrayList<>();

        if (request.title() != null && !request.title().isBlank()) {
            sql.append(", title = ?");
            params.add(request.title().trim());
        }
        if (request.description() != null) {
            sql.append(", description = ?");
            params.add(request.description());
        }
        if (request.priority() != null) {
            sql.append(", priority = ?");
            params.add(request.priority().name());
        }
        if (request.storyPoints() != null) {
            sql.append(", story_points = ?");
            params.add(request.storyPoints());
        }
        if (request.estimateMinutes() != null) {
            sql.append(", estimate_minutes = ?");
            params.add(request.estimateMinutes());
        }
        if (request.remainingMinutes() != null) {
            sql.append(", remaining_minutes = ?");
            params.add(request.remainingMinutes());
        }
        if (request.labels() != null) {
            sql.append(", labels = ?");
            params.add(request.labels());
        }
        if (request.parentId() != null) {
            sql.append(", parent_id = ?");
            params.add(request.parentId());
        }
        if (request.sprintId() != null) {
            sql.append(", sprint_id = ?");
            params.add(request.sprintId());
        }
        if (request.milestoneId() != null) {
            sql.append(", milestone_id = ?");
            params.add(request.milestoneId());
        }
        if (request.requirementId() != null) {
            sql.append(", requirement_id = ?");
            params.add(request.requirementId());
        }
        if (request.dueAt() != null) {
            sql.append(", due_at = ?");
            params.add(Timestamp.from(request.dueAt()));
        }

        sql.append(" WHERE id = ? AND project_id = ?");
        params.add(workItemId);
        params.add(projectId);

        try {
            setActorContext(principal.userId());
            jdbcTemplate.update(sql.toString(), params.toArray());
        } catch (DataAccessException e) {
            handleWorkItemException(e);
        }

        return getWorkItem(principal, workspaceId, projectId, workItemId);
    }

    /**
     * Soft deletes a work item.
     * Blocked if status is DONE or if item has active children.
     */
    @Transactional
    public void deleteWorkItem(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UUID workItemId) {

        accessPolicy.requireProjectRole(principal, workspaceId, projectId, ProjectRole.PM);

        WorkItemResponse current = getWorkItemForUpdate(workspaceId, projectId, workItemId);

        if (current.status() == WorkItem.Status.DONE) {
            throw WorkspaceProjectException.scopeLocked("Cannot delete a completed (DONE) work item");
        }

        Integer childCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM work_items WHERE project_id = ? AND parent_id = ? AND deleted_at IS NULL",
                Integer.class,
                projectId,
                workItemId
        );
        if (childCount != null && childCount > 0) {
            throw WorkspaceProjectException.validationFailed("Cannot delete work item with active child items");
        }

        try {
            setActorContext(principal.userId());
            jdbcTemplate.update(
                    "UPDATE work_items SET deleted_at = now(), version = version + 1, updated_at = now() WHERE id = ? AND project_id = ?",
                    workItemId,
                    projectId
            );
        } catch (DataAccessException e) {
            handleWorkItemException(e);
        }
    }

    /**
     * Assigns a work item to an active DEVELOPER.
     * Blocked if status is DONE.
     */
    @Transactional
    public WorkItemResponse assignWorkItem(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UUID workItemId,
            AssignWorkItemRequest request) {

        accessPolicy.requireProjectRole(principal, workspaceId, projectId, ProjectRole.PM);

        WorkItemResponse current = getWorkItemForUpdate(workspaceId, projectId, workItemId);

        if (current.status() == WorkItem.Status.DONE) {
            throw WorkspaceProjectException.scopeLocked("DONE Work Item scope and schedule are frozen");
        }

        if (request != null && request.expectedVersion() != null && !Objects.equals(current.version(), request.expectedVersion())) {
            throw WorkspaceProjectException.planStale("Work item version conflict");
        }

        UUID assigneeId = request != null ? request.assigneeId() : null;
        if (assigneeId != null) {
            Boolean hasRole = jdbcTemplate.queryForObject(
                    "SELECT ds_has_project_role(?, ?, 'DEVELOPER')",
                    Boolean.class,
                    projectId,
                    assigneeId
            );
            if (hasRole == null || !hasRole) {
                throw WorkspaceProjectException.assigneeNotDeveloper("Assignee requires active DEVELOPER role");
            }
        }

        try {
            setActorContext(principal.userId());
            jdbcTemplate.update(
                    "UPDATE work_items SET assignee_id = ?, version = version + 1, updated_at = now() WHERE id = ? AND project_id = ?",
                    assigneeId,
                    workItemId,
                    projectId
            );
        } catch (DataAccessException e) {
            handleWorkItemException(e);
        }

        return getWorkItem(principal, workspaceId, projectId, workItemId);
    }

    /**
     * Transitions task status according to strict state machine:
     * TO_DO -> IN_PROGRESS -> IN_REVIEW.
     * Transition to DONE must use accept endpoint.
     */
    @Transactional
    public WorkItemResponse transitionStatus(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UUID workItemId,
            TransitionStatusRequest request) {

        ProjectScope scope = accessPolicy.requireProjectMember(principal, workspaceId, projectId);

        WorkItemResponse current = getWorkItemForUpdate(workspaceId, projectId, workItemId);

        if (current.status() == WorkItem.Status.DONE) {
            throw WorkspaceProjectException.scopeLocked("DONE Work Item scope and schedule are frozen");
        }

        if (request.expectedVersion() != null && !Objects.equals(current.version(), request.expectedVersion())) {
            throw WorkspaceProjectException.planStale("Work item version conflict");
        }

        boolean isPm = scope.hasRole(ProjectRole.PM);
        boolean isAssignee = principal.userId().equals(current.assigneeId());

        if (!isPm && !isAssignee) {
            throw WorkspaceProjectException.accessDenied("Only assigned developer or PM can update work item status");
        }

        WorkItem.Status newStatus = request.status();
        WorkItem.Status oldStatus = current.status();

        if (newStatus == WorkItem.Status.DONE) {
            throw WorkspaceProjectException.invalidStateTransition("Transition to DONE requires explicit PM acceptance");
        }

        if (oldStatus == WorkItem.Status.TO_DO && newStatus == WorkItem.Status.IN_PROGRESS) {
            // Valid transition
        } else if (oldStatus == WorkItem.Status.IN_PROGRESS && newStatus == WorkItem.Status.IN_REVIEW) {
            // Valid transition
        } else {
            throw WorkspaceProjectException.invalidStateTransition("Invalid state transition from " + oldStatus + " to " + newStatus);
        }

        String sql = """
                UPDATE work_items
                SET status = ?,
                    started_at = CASE WHEN started_at IS NULL AND ? = 'IN_PROGRESS' THEN now() ELSE started_at END,
                    version = version + 1,
                    updated_at = now()
                WHERE id = ? AND project_id = ?
                """;

        try {
            setActorContext(principal.userId());
            jdbcTemplate.update(sql, newStatus.name(), newStatus.name(), workItemId, projectId);
        } catch (DataAccessException e) {
            handleWorkItemException(e);
        }

        return getWorkItem(principal, workspaceId, projectId, workItemId);
    }

    /**
     * PM accepts a task in IN_REVIEW to DONE status.
     */
    @Transactional
    public WorkItemResponse acceptWorkItem(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UUID workItemId) {

        accessPolicy.requireProjectRole(principal, workspaceId, projectId, ProjectRole.PM);

        WorkItemResponse current = getWorkItemForUpdate(workspaceId, projectId, workItemId);

        if (current.status() != WorkItem.Status.IN_REVIEW) {
            throw WorkspaceProjectException.invalidStateTransition("Only items in IN_REVIEW status can be accepted to DONE");
        }

        String sql = """
                UPDATE work_items
                SET status = 'DONE',
                    accepted_by = ?,
                    completed_at = now(),
                    version = version + 1,
                    updated_at = now()
                WHERE id = ? AND project_id = ?
                """;

        try {
            setActorContext(principal.userId());
            jdbcTemplate.update(sql, principal.userId(), workItemId, projectId);
        } catch (DataAccessException e) {
            handleWorkItemException(e);
        }

        return getWorkItem(principal, workspaceId, projectId, workItemId);
    }

    /**
     * PM rejects a task in IN_REVIEW back to IN_PROGRESS.
     */
    @Transactional
    public WorkItemResponse rejectWorkItem(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UUID workItemId,
            RejectWorkItemRequest request) {

        accessPolicy.requireProjectRole(principal, workspaceId, projectId, ProjectRole.PM);

        WorkItemResponse current = getWorkItemForUpdate(workspaceId, projectId, workItemId);

        if (current.status() != WorkItem.Status.IN_REVIEW) {
            throw WorkspaceProjectException.invalidStateTransition("Only items in IN_REVIEW status can be rejected to IN_PROGRESS");
        }

        String sql = """
                UPDATE work_items
                SET status = 'IN_PROGRESS',
                    version = version + 1,
                    updated_at = now()
                WHERE id = ? AND project_id = ?
                """;

        try {
            setActorContext(principal.userId());
            jdbcTemplate.update(sql, workItemId, projectId);
        } catch (DataAccessException e) {
            handleWorkItemException(e);
        }

        return getWorkItem(principal, workspaceId, projectId, workItemId);
    }

    /**
     * Sets or unsets the blocked flag on a work item.
     */
    @Transactional
    public WorkItemResponse blockWorkItem(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UUID workItemId,
            BlockWorkItemRequest request) {

        ProjectScope scope = accessPolicy.requireProjectMember(principal, workspaceId, projectId);

        WorkItemResponse current = getWorkItemForUpdate(workspaceId, projectId, workItemId);

        if (current.status() == WorkItem.Status.DONE) {
            throw WorkspaceProjectException.scopeLocked("DONE Work Item scope and schedule are frozen");
        }

        if (request.expectedVersion() != null && !Objects.equals(current.version(), request.expectedVersion())) {
            throw WorkspaceProjectException.planStale("Work item version conflict");
        }

        boolean isPm = scope.hasRole(ProjectRole.PM);
        boolean isAssignee = principal.userId().equals(current.assigneeId());

        if (!isPm && !isAssignee) {
            throw WorkspaceProjectException.accessDenied("Only assigned developer or PM can change blocked state");
        }

        if (request.isBlocked() && (request.blockedReason() == null || request.blockedReason().isBlank())) {
            throw WorkspaceProjectException.validationFailed("blockedReason is required when isBlocked is true");
        }

        String sql = """
                UPDATE work_items
                SET is_blocked = ?,
                    blocked_reason = ?,
                    version = version + 1,
                    updated_at = now()
                WHERE id = ? AND project_id = ?
                """;

        try {
            setActorContext(principal.userId());
            jdbcTemplate.update(
                    sql,
                    request.isBlocked(),
                    request.isBlocked() ? request.blockedReason().trim() : null,
                    workItemId,
                    projectId
            );
        } catch (DataAccessException e) {
            handleWorkItemException(e);
        }

        return getWorkItem(principal, workspaceId, projectId, workItemId);
    }

    /**
     * Lists project backlog items ordered by rank ASC, id ASC.
     */
    @Transactional(readOnly = true)
    public List<WorkItemResponse> getBacklog(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId) {

        accessPolicy.requireProjectMember(principal, workspaceId, projectId);

        String sql = """
                SELECT id, project_id, item_key, parent_id, requirement_id, sprint_id, milestone_id,
                       type, work_kind, title, description, status, is_blocked, blocked_reason,
                       priority, assignee_id, labels, backlog_rank, story_points, estimate_minutes,
                       remaining_minutes, time_spent_minutes, claimed_progress, verified_progress,
                       started_at, completed_at, due_at, accepted_by, created_at, updated_at, version
                FROM work_items
                WHERE project_id = ? AND deleted_at IS NULL
                ORDER BY backlog_rank ASC, id ASC
                """;

        return jdbcTemplate.query(sql, WORK_ITEM_MAPPER, projectId);
    }

    private WorkItemResponse getWorkItemForUpdate(UUID workspaceId, UUID projectId, UUID workItemId) {
        String sql = """
                SELECT id, project_id, item_key, parent_id, requirement_id, sprint_id, milestone_id,
                       type, work_kind, title, description, status, is_blocked, blocked_reason,
                       priority, assignee_id, labels, backlog_rank, story_points, estimate_minutes,
                       remaining_minutes, time_spent_minutes, claimed_progress, verified_progress,
                       started_at, completed_at, due_at, accepted_by, created_at, updated_at, version
                FROM work_items
                WHERE id = ? AND project_id = ? AND deleted_at IS NULL
                FOR UPDATE
                """;

        List<WorkItemResponse> items = jdbcTemplate.query(sql, WORK_ITEM_MAPPER, workItemId, projectId);
        if (items.isEmpty()) {
            throw WorkspaceProjectException.notFound("Work item not found");
        }

        return items.get(0);
    }

    private void setActorContext(UUID actorId) {
        if (actorId != null) {
            jdbcTemplate.execute("SELECT set_config('devsphere.actor_id', '" + actorId + "', true)");
        }
    }

    private void handleWorkItemException(DataAccessException e) {
        String msg = getRootMessage(e);
        if (msg != null) {
            if (msg.contains("Invalid Work Item hierarchy")) {
                throw WorkspaceProjectException.invalidHierarchy("Invalid Work Item hierarchy");
            }
            if (msg.contains("Work Item hierarchy cycle")) {
                throw WorkspaceProjectException.hierarchyCycle("Work Item hierarchy cycle");
            }
            if (msg.contains("Assignee requires active DEVELOPER role")) {
                throw WorkspaceProjectException.assigneeNotDeveloper("Assignee requires active DEVELOPER role");
            }
            if (msg.contains("DONE Work Item scope and schedule are frozen")) {
                throw WorkspaceProjectException.scopeLocked("DONE Work Item scope and schedule are frozen");
            }
            if (msg.contains("New Task starts TO_DO")) {
                throw WorkspaceProjectException.validationFailed("New Task starts TO_DO");
            }
            if (msg.contains("Invalid Task state transition")) {
                throw WorkspaceProjectException.invalidStateTransition("Invalid Task state transition");
            }
            if (msg.contains("Task acceptance requires active PM")) {
                throw WorkspaceProjectException.taskAcceptanceRequiresPm("Task acceptance requires active PM");
            }
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

    private static UUID toUuid(String val) {
        return val != null ? UUID.fromString(val) : null;
    }

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }
}

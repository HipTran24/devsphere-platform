package com.project.devsphere.sprint.application;

import com.project.devsphere.identity.application.AccessPolicy;
import com.project.devsphere.identity.application.ProjectScope;
import com.project.devsphere.identity.application.SessionService;
import com.project.devsphere.identity.domain.ProjectRole;
import com.project.devsphere.sprint.api.dto.CloseSprintRequest;
import com.project.devsphere.sprint.api.dto.CreateSprintRequest;
import com.project.devsphere.sprint.api.dto.SprintResponse;
import com.project.devsphere.sprint.api.dto.StartSprintRequest;
import com.project.devsphere.sprint.api.dto.UpdateSprintRequest;
import com.project.devsphere.sprint.domain.Sprint;
import com.project.devsphere.sprint.infrastructure.persistence.SprintRepository;
import com.project.devsphere.workspace.application.WorkspaceProjectException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
public class SprintService {

    private final SprintRepository sprintRepository;
    private final JdbcTemplate jdbcTemplate;
    private final AccessPolicy accessPolicy;

    private static final RowMapper<SprintResponse> SPRINT_ROW_MAPPER = (rs, rowNum) -> {
        java.sql.Date startD = rs.getDate("start_date");
        java.sql.Date endD = rs.getDate("end_date");
        Timestamp closedT = rs.getTimestamp("closed_at");
        Timestamp createdT = rs.getTimestamp("created_at");
        Timestamp updatedT = rs.getTimestamp("updated_at");

        return new SprintResponse(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("project_id")),
                rs.getString("name"),
                rs.getString("goal"),
                startD != null ? startD.toLocalDate() : null,
                endD != null ? endD.toLocalDate() : null,
                rs.getString("status"),
                rs.getString("review_notes"),
                rs.getString("retrospective_notes"),
                rs.getBigDecimal("committed_points"),
                rs.getBigDecimal("completed_points"),
                closedT != null ? closedT.toInstant() : null,
                createdT != null ? createdT.toInstant() : null,
                updatedT != null ? updatedT.toInstant() : null,
                rs.getLong("version")
        );
    };

    @Autowired
    public SprintService(
            @Nullable SprintRepository sprintRepository,
            JdbcTemplate jdbcTemplate,
            AccessPolicy accessPolicy) {
        this.sprintRepository = sprintRepository;
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy must not be null");
    }

    public SprintService(JdbcTemplate jdbcTemplate, AccessPolicy accessPolicy) {
        this(null, jdbcTemplate, accessPolicy);
    }

    /**
     * Creates a new sprint in PLANNED status.
     * Guarded by PM role.
     */
    public SprintResponse createSprint(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            CreateSprintCommand command) {
        setActorId(principal);
        accessPolicy.requireProjectRole(principal, workspaceId, projectId, ProjectRole.PM);

        if (command == null) {
            throw WorkspaceProjectException.validationFailed("Create sprint command must not be null");
        }
        if (command.name() == null || command.name().isBlank()) {
            throw WorkspaceProjectException.validationFailed("Sprint name must not be blank");
        }
        if (command.startDate() == null || command.endDate() == null) {
            throw WorkspaceProjectException.validationFailed("Sprint startDate and endDate must not be null");
        }
        if (!command.endDate().isAfter(command.startDate())) {
            throw WorkspaceProjectException.invalidSprintDates("Sprint endDate must be after startDate");
        }

        UUID sprintId = UUID.randomUUID();
        Instant now = Instant.now();

        String sql = """
                INSERT INTO sprints (
                    id, project_id, name, goal, start_date, end_date, status, version, created_at, updated_at
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, 'PLANNED', 0, ?, ?
                )
                """;

        jdbcTemplate.update(
                sql,
                sprintId,
                projectId,
                command.name().trim(),
                command.goal() != null ? command.goal() : "",
                java.sql.Date.valueOf(command.startDate()),
                java.sql.Date.valueOf(command.endDate()),
                Timestamp.from(now),
                Timestamp.from(now)
        );

        return getSprint(principal, workspaceId, projectId, sprintId);
    }

    public SprintResponse createSprint(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            CreateSprintRequest request) {
        if (request == null) {
            throw WorkspaceProjectException.validationFailed("Create sprint request must not be null");
        }
        return createSprint(
                principal,
                workspaceId,
                projectId,
                new CreateSprintCommand(request.name(), request.goal(), request.startDate(), request.endDate())
        );
    }

    /**
     * Gets sprint details.
     * Guarded by project membership.
     */
    @Transactional(readOnly = true)
    public SprintResponse getSprint(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UUID sprintId) {
        accessPolicy.requireProjectMember(principal, workspaceId, projectId);

        String sql = """
                SELECT id, project_id, name, goal, start_date, end_date, status, review_notes,
                       retrospective_notes, committed_points, completed_points, closed_at, created_at,
                       updated_at, version
                FROM sprints
                WHERE id = ? AND project_id = ?
                """;

        List<SprintResponse> list = jdbcTemplate.query(sql, SPRINT_ROW_MAPPER, sprintId, projectId);
        if (list.isEmpty()) {
            throw WorkspaceProjectException.notFound("Sprint not found");
        }
        return list.get(0);
    }

    /**
     * Lists sprints for a project ordered by startDate ASC, id ASC.
     * Optional filter by status. Guarded by project membership.
     */
    @Transactional(readOnly = true)
    public List<SprintResponse> listSprints(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            String statusFilter) {
        accessPolicy.requireProjectMember(principal, workspaceId, projectId);

        if (statusFilter != null && !statusFilter.isBlank()) {
            String sql = """
                    SELECT id, project_id, name, goal, start_date, end_date, status, review_notes,
                           retrospective_notes, committed_points, completed_points, closed_at, created_at,
                           updated_at, version
                    FROM sprints
                    WHERE project_id = ? AND status = ?
                    ORDER BY start_date ASC, id ASC
                    """;
            return jdbcTemplate.query(sql, SPRINT_ROW_MAPPER, projectId, statusFilter.trim().toUpperCase());
        }

        String sql = """
                SELECT id, project_id, name, goal, start_date, end_date, status, review_notes,
                       retrospective_notes, committed_points, completed_points, closed_at, created_at,
                       updated_at, version
                FROM sprints
                WHERE project_id = ?
                ORDER BY start_date ASC, id ASC
                """;
        return jdbcTemplate.query(sql, SPRINT_ROW_MAPPER, projectId);
    }

    /**
     * Updates sprint metadata.
     * Guarded by PM role.
     * Optimistic locking enforced against expectedVersion.
     * Cannot modify immutable fields of a CLOSED sprint.
     */
    public SprintResponse updateSprint(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UUID sprintId,
            UpdateSprintCommand command) {
        setActorId(principal);
        accessPolicy.requireProjectRole(principal, workspaceId, projectId, ProjectRole.PM);

        if (command == null) {
            throw WorkspaceProjectException.validationFailed("Update sprint command must not be null");
        }

        String selectSql = """
                SELECT id, project_id, name, goal, start_date, end_date, status, review_notes,
                       retrospective_notes, committed_points, completed_points, closed_at, version
                FROM sprints
                WHERE id = ? AND project_id = ?
                FOR UPDATE
                """;
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(selectSql, sprintId, projectId);
        if (rows.isEmpty()) {
            throw WorkspaceProjectException.notFound("Sprint not found");
        }
        Map<String, Object> sprintRow = rows.get(0);
        String currentStatus = (String) sprintRow.get("status");
        if ("CLOSED".equalsIgnoreCase(currentStatus)) {
            throw WorkspaceProjectException.sprintClosed("Cannot update a closed sprint");
        }

        Long currentVersion = ((Number) sprintRow.get("version")).longValue();
        if (command.expectedVersion() != null && !command.expectedVersion().equals(currentVersion)) {
            throw WorkspaceProjectException.planStale("Sprint version mismatch: expected "
                    + command.expectedVersion() + " but was " + currentVersion);
        }

        LocalDate oldStart = ((java.sql.Date) sprintRow.get("start_date")).toLocalDate();
        LocalDate oldEnd = ((java.sql.Date) sprintRow.get("end_date")).toLocalDate();
        LocalDate newStart = command.startDate() != null ? command.startDate() : oldStart;
        LocalDate newEnd = command.endDate() != null ? command.endDate() : oldEnd;
        if (!newEnd.isAfter(newStart)) {
            throw WorkspaceProjectException.invalidSprintDates("Sprint endDate must be after startDate");
        }

        String newName = command.name() != null && !command.name().isBlank() ? command.name().trim() : (String) sprintRow.get("name");
        String newGoal = command.goal() != null ? command.goal() : (String) sprintRow.get("goal");
        String newReviewNotes = command.reviewNotes() != null ? command.reviewNotes() : (String) sprintRow.get("review_notes");
        String newRetroNotes = command.retrospectiveNotes() != null ? command.retrospectiveNotes() : (String) sprintRow.get("retrospective_notes");

        String updateSql = """
                UPDATE sprints
                SET name = ?, goal = ?, start_date = ?, end_date = ?, review_notes = ?,
                    retrospective_notes = ?, version = version + 1, updated_at = now()
                WHERE id = ? AND project_id = ?
                """;
        try {
            jdbcTemplate.update(
                    updateSql,
                    newName,
                    newGoal,
                    java.sql.Date.valueOf(newStart),
                    java.sql.Date.valueOf(newEnd),
                    newReviewNotes,
                    newRetroNotes,
                    sprintId,
                    projectId
            );
        } catch (DataAccessException ex) {
            if (ex.getMessage() != null && ex.getMessage().contains("Closed Sprint metrics and dates are immutable")) {
                throw WorkspaceProjectException.sprintClosed("Cannot update a closed sprint");
            }
            throw ex;
        }

        return getSprint(principal, workspaceId, projectId, sprintId);
    }

    public SprintResponse updateSprint(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UUID sprintId,
            UpdateSprintRequest request) {
        if (request == null) {
            throw WorkspaceProjectException.validationFailed("Update sprint request must not be null");
        }
        return updateSprint(
                principal,
                workspaceId,
                projectId,
                sprintId,
                new UpdateSprintCommand(
                        request.name(),
                        request.goal(),
                        request.startDate(),
                        request.endDate(),
                        request.reviewNotes(),
                        request.retrospectiveNotes(),
                        request.expectedVersion()
                )
        );
    }

    /**
     * Starts a sprint (transitions PLANNED -> ACTIVE).
     * Guarded by PM role.
     * Enforces single active sprint invariant (uq_project_active_sprint).
     * Calculates initial committed_points from non-deleted work items in the sprint.
     */
    public SprintResponse startSprint(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UUID sprintId,
            StartSprintCommand command) {
        setActorId(principal);
        accessPolicy.requireProjectRole(principal, workspaceId, projectId, ProjectRole.PM);

        String selectSql = "SELECT id, status, version FROM sprints WHERE id = ? AND project_id = ? FOR UPDATE";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(selectSql, sprintId, projectId);
        if (rows.isEmpty()) {
            throw WorkspaceProjectException.notFound("Sprint not found");
        }
        Map<String, Object> sprintRow = rows.get(0);
        String status = (String) sprintRow.get("status");
        Long currentVersion = ((Number) sprintRow.get("version")).longValue();

        if ("CLOSED".equalsIgnoreCase(status)) {
            throw WorkspaceProjectException.sprintClosed("Sprint is already closed");
        }
        if ("ACTIVE".equalsIgnoreCase(status)) {
            throw WorkspaceProjectException.invalidStateTransition("Sprint is already active");
        }
        if (!"PLANNED".equalsIgnoreCase(status)) {
            throw WorkspaceProjectException.sprintNotPlanned("Sprint must be in PLANNED status to start");
        }

        if (command != null && command.expectedVersion() != null && !command.expectedVersion().equals(currentVersion)) {
            throw WorkspaceProjectException.planStale("Sprint version mismatch: expected "
                    + command.expectedVersion() + " but was " + currentVersion);
        }

        boolean activeExists = sprintRepository != null
                ? sprintRepository.existsByProjectIdAndStatus(projectId, Sprint.Status.ACTIVE)
                : Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                        "SELECT count(*) > 0 FROM sprints WHERE project_id = ? AND status = 'ACTIVE' AND id != ?",
                        Boolean.class,
                        projectId,
                        sprintId
                ));

        if (activeExists) {
            throw WorkspaceProjectException.activeSprintExists("Project already has an active sprint");
        }

        BigDecimal committedPoints = jdbcTemplate.queryForObject(
                "SELECT COALESCE(sum(story_points), 0) FROM work_items WHERE sprint_id = ? AND project_id = ? AND deleted_at IS NULL",
                BigDecimal.class,
                sprintId,
                projectId
        );
        if (committedPoints == null) {
            committedPoints = BigDecimal.ZERO;
        }

        String updateSql = """
                UPDATE sprints
                SET status = 'ACTIVE', committed_points = ?, version = version + 1, updated_at = now()
                WHERE id = ? AND project_id = ?
                """;

        try {
            jdbcTemplate.update(updateSql, committedPoints, sprintId, projectId);
        } catch (DataIntegrityViolationException ex) {
            if (ex.getMessage() != null && ex.getMessage().contains("uq_project_active_sprint")) {
                throw WorkspaceProjectException.activeSprintExists("Project already has an active sprint");
            }
            throw ex;
        }

        return getSprint(principal, workspaceId, projectId, sprintId);
    }

    public SprintResponse startSprint(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UUID sprintId,
            StartSprintRequest request) {
        Long expectedVersion = request != null ? request.expectedVersion() : null;
        return startSprint(principal, workspaceId, projectId, sprintId, new StartSprintCommand(expectedVersion));
    }

    /**
     * Closes an active sprint (transitions ACTIVE -> CLOSED).
     * Guarded by PM role.
     * Database trigger ds_sprint_close() automatically:
     * - stamps closed_at = now()
     * - aggregates completed_points = sum(story_points) of DONE items
     * - sets sprint_id = NULL for incomplete items (kick to backlog)
     */
    public SprintResponse closeSprint(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UUID sprintId,
            CloseSprintCommand command) {
        setActorId(principal);
        accessPolicy.requireProjectRole(principal, workspaceId, projectId, ProjectRole.PM);

        String selectSql = "SELECT id, status, version FROM sprints WHERE id = ? AND project_id = ? FOR UPDATE";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(selectSql, sprintId, projectId);
        if (rows.isEmpty()) {
            throw WorkspaceProjectException.notFound("Sprint not found");
        }
        Map<String, Object> sprintRow = rows.get(0);
        String status = (String) sprintRow.get("status");
        Long currentVersion = ((Number) sprintRow.get("version")).longValue();

        if ("CLOSED".equalsIgnoreCase(status)) {
            throw WorkspaceProjectException.sprintClosed("Sprint is already closed");
        }
        if (!"ACTIVE".equalsIgnoreCase(status)) {
            throw WorkspaceProjectException.sprintNotActive("Sprint must be in ACTIVE status to close");
        }

        if (command != null && command.expectedVersion() != null && !command.expectedVersion().equals(currentVersion)) {
            throw WorkspaceProjectException.planStale("Sprint version mismatch: expected "
                    + command.expectedVersion() + " but was " + currentVersion);
        }

        String reviewNotes = command != null ? command.reviewNotes() : null;
        String retroNotes = command != null ? command.retrospectiveNotes() : null;

        String updateSql = """
                UPDATE sprints
                SET status = 'CLOSED',
                    review_notes = COALESCE(?, review_notes),
                    retrospective_notes = COALESCE(?, retrospective_notes),
                    version = version + 1,
                    updated_at = now()
                WHERE id = ? AND project_id = ?
                """;

        try {
            jdbcTemplate.update(updateSql, reviewNotes, retroNotes, sprintId, projectId);
        } catch (DataAccessException ex) {
            if (ex.getMessage() != null && ex.getMessage().contains("Closed Sprint metrics and dates are immutable")) {
                throw WorkspaceProjectException.sprintClosed("Cannot modify closed sprint");
            }
            throw ex;
        }

        return getSprint(principal, workspaceId, projectId, sprintId);
    }

    public SprintResponse closeSprint(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UUID sprintId,
            CloseSprintRequest request) {
        if (request == null) {
            throw WorkspaceProjectException.validationFailed("Close sprint request must not be null");
        }
        return closeSprint(
                principal,
                workspaceId,
                projectId,
                sprintId,
                new CloseSprintCommand(request.reviewNotes(), request.retrospectiveNotes(), request.expectedVersion())
        );
    }

    /**
     * Assigns or removes work items from a sprint.
     * Guarded by PM or DEVELOPER roles.
     * Cannot modify items if sprint is CLOSED.
     * Cannot move or remove DONE work items (frozen by ds_work_item_guard).
     */
    public void modifySprintItems(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UUID sprintId,
            String action,
            List<UUID> itemIds) {
        setActorId(principal);
        ProjectScope scope = accessPolicy.requireProjectMember(principal, workspaceId, projectId);
        if (scope == null || (!scope.roles().contains(ProjectRole.PM) && !scope.roles().contains(ProjectRole.DEVELOPER))) {
            throw WorkspaceProjectException.accessDenied("PM or Developer role required to assign sprint items");
        }

        List<String> statuses = jdbcTemplate.query(
                "SELECT status FROM sprints WHERE id = ? AND project_id = ?",
                (rs, rowNum) -> rs.getString("status"),
                sprintId,
                projectId
        );
        if (statuses.isEmpty()) {
            throw WorkspaceProjectException.notFound("Sprint not found");
        }
        if ("CLOSED".equalsIgnoreCase(statuses.get(0))) {
            throw WorkspaceProjectException.sprintClosed("Cannot modify items in a closed sprint");
        }

        if (itemIds == null || itemIds.isEmpty()) {
            return;
        }

        boolean isRemove = "REMOVE".equalsIgnoreCase(action);

        for (UUID itemId : itemIds) {
            List<Map<String, Object>> itemRows = jdbcTemplate.queryForList(
                    "SELECT id, status, sprint_id, deleted_at FROM work_items WHERE id = ? AND project_id = ?",
                    itemId,
                    projectId
            );

            if (itemRows.isEmpty() || itemRows.get(0).get("deleted_at") != null) {
                throw WorkspaceProjectException.notFound("Work item not found: " + itemId);
            }

            String currentStatus = (String) itemRows.get(0).get("status");
            if ("DONE".equalsIgnoreCase(currentStatus)) {
                throw WorkspaceProjectException.cannotModifyDoneItemSprint("Cannot modify sprint of a DONE work item");
            }

            try {
                if (isRemove) {
                    jdbcTemplate.update(
                            "UPDATE work_items SET sprint_id = NULL, updated_at = now() WHERE id = ? AND project_id = ? AND sprint_id = ?",
                            itemId,
                            projectId,
                            sprintId
                    );
                } else {
                    jdbcTemplate.update(
                            "UPDATE work_items SET sprint_id = ?, updated_at = now() WHERE id = ? AND project_id = ?",
                            sprintId,
                            itemId,
                            projectId
                    );
                }
            } catch (DataAccessException ex) {
                if (ex.getMessage() != null && ex.getMessage().contains("DONE Work Item scope and schedule are frozen")) {
                    throw WorkspaceProjectException.cannotModifyDoneItemSprint("Cannot modify sprint of a DONE work item");
                }
                throw ex;
            }
        }
    }

    public SprintResponse modifySprintItems(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UUID sprintId,
            SprintItemAssignmentCommand command) {
        if (command == null) {
            return getSprint(principal, workspaceId, projectId, sprintId);
        }
        modifySprintItems(principal, workspaceId, projectId, sprintId, command.action(), command.itemIds());
        return getSprint(principal, workspaceId, projectId, sprintId);
    }

    public SprintResponse assignWorkItems(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UUID sprintId,
            List<UUID> itemIds) {
        modifySprintItems(principal, workspaceId, projectId, sprintId, "ADD", itemIds);
        return getSprint(principal, workspaceId, projectId, sprintId);
    }

    public SprintResponse removeWorkItems(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UUID sprintId,
            List<UUID> itemIds) {
        modifySprintItems(principal, workspaceId, projectId, sprintId, "REMOVE", itemIds);
        return getSprint(principal, workspaceId, projectId, sprintId);
    }

    private void setActorId(SessionService.Principal principal) {
        if (principal != null && principal.userId() != null) {
            jdbcTemplate.queryForObject(
                    "SELECT set_config('devsphere.actor_id', ?, true)",
                    String.class,
                    principal.userId().toString()
            );
        }
    }
}

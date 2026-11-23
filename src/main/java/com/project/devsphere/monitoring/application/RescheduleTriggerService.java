package com.project.devsphere.monitoring.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.devsphere.identity.application.AccessPolicy;
import com.project.devsphere.identity.application.SessionService;
import com.project.devsphere.identity.domain.ProjectRole;
import com.project.devsphere.monitoring.api.dto.RescheduleResponse;
import com.project.devsphere.resource.api.ResourceCapacitySnapshot;
import com.project.devsphere.resource.api.ResourceCapacitySnapshotReader;
import com.project.devsphere.solver.application.SchedulingSolver;
import com.project.devsphere.solver.domain.SolverInputSnapshot;
import com.project.devsphere.solver.domain.SolverResult;
import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

@Service
public class RescheduleTriggerService {

    private final JdbcTemplate jdbcTemplate;
    private final AccessPolicy accessPolicy;
    private final ResourceCapacitySnapshotReader capacitySnapshotReader;
    private final SchedulingSolver schedulingSolver;
    private final ObjectMapper objectMapper;
    private final EntityManager entityManager;

    @org.springframework.beans.factory.annotation.Autowired
    public RescheduleTriggerService(
            JdbcTemplate jdbcTemplate,
            AccessPolicy accessPolicy,
            ResourceCapacitySnapshotReader capacitySnapshotReader,
            SchedulingSolver schedulingSolver,
            @org.springframework.beans.factory.annotation.Autowired(required = false) ObjectMapper objectMapper,
            EntityManager entityManager) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy must not be null");
        this.capacitySnapshotReader = Objects.requireNonNull(capacitySnapshotReader, "capacitySnapshotReader must not be null");
        this.schedulingSolver = Objects.requireNonNull(schedulingSolver, "schedulingSolver must not be null");
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager must not be null");
    }

    /**
     * Triggers automated replanning candidate proposal generation.
     * Guarded by PM role.
     * Enforces:
     * - kind = 'AUTO_REPLAN', status = 'DRAFT', solver_status = 'NOT_RUN'
     * - Preserves frozen schedule entries and DONE tasks (satisfying ds_schedule_freeze)
     * - Executes CP-SAT solver and stores solution via Two-Phase Update (satisfying ds_proposal_guard)
     * - Invariant: NEVER mutates active schedule baseline or project committed deadline.
     */
    @Transactional
    public RescheduleResponse triggerReschedule(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId) {

        accessPolicy.requireProjectRole(principal, workspaceId, projectId, ProjectRole.PM);

        // Fetch project metadata
        List<Map<String, Object>> projectRows = jdbcTemplate.queryForList("""
                SELECT version, timezone, active_schedule_version_id, planned_start, committed_deadline
                FROM projects
                WHERE id = ?
                """, projectId);

        if (projectRows.isEmpty()) {
            throw AlertException.notFound("Project not found: " + projectId);
        }

        Map<String, Object> projectRow = projectRows.get(0);
        Long projectVersion = ((Number) projectRow.get("version")).longValue();
        String timezone = projectRow.get("timezone") != null ? projectRow.get("timezone").toString() : "Asia/Ho_Chi_Minh";
        UUID activeScheduleVersionId = (UUID) projectRow.get("active_schedule_version_id");
        Date plannedStartDate = (Date) projectRow.get("planned_start");
        Date committedDeadlineDate = (Date) projectRow.get("committed_deadline");

        LocalDate plannedStart = plannedStartDate != null ? plannedStartDate.toLocalDate() : LocalDate.now(ZoneId.of(timezone));
        LocalDate committedDeadline = committedDeadlineDate != null ? committedDeadlineDate.toLocalDate() : null;

        // Fetch existing schedule entries from active baseline to copy frozen / DONE items unchanged
        Map<UUID, Map<String, Object>> activeOldEntries = new HashMap<>();
        if (activeScheduleVersionId != null) {
            List<Map<String, Object>> oldEntries = jdbcTemplate.queryForList("""
                    SELECT work_item_id, sprint_id, assignee_id, start_at, finish_at,
                           remaining_minutes_snapshot, frozen
                    FROM schedule_entries
                    WHERE schedule_version_id = ?
                    """, activeScheduleVersionId);
            for (Map<String, Object> oe : oldEntries) {
                activeOldEntries.put((UUID) oe.get("work_item_id"), oe);
            }
        }

        // Fetch non-deleted backlog work items
        List<Map<String, Object>> workItems = jdbcTemplate.queryForList("""
                SELECT id, item_key, title, type, work_kind, status, is_blocked,
                       estimate_minutes, remaining_minutes, assignee_id, sprint_id, due_at
                FROM work_items
                WHERE project_id = ? AND deleted_at IS NULL
                ORDER BY created_at ASC
                """, projectId);

        Map<UUID, String> idToTempKey = new HashMap<>();
        List<Map<String, Object>> items = new ArrayList<>();
        int frozenCount = 0;

        for (Map<String, Object> wi : workItems) {
            UUID wiId = (UUID) wi.get("id");
            String itemKey = (String) wi.get("item_key");
            String tempKey = itemKey != null && !itemKey.isBlank() ? itemKey : ("TASK-" + wiId.toString().substring(0, 8));
            idToTempKey.put(wiId, tempKey);

            String title = (String) wi.get("title");
            String status = (String) wi.get("status");
            int estimateMinutes = wi.get("estimate_minutes") instanceof Number n ? n.intValue() : 0;
            int remainingMinutes = wi.get("remaining_minutes") instanceof Number n ? n.intValue() : estimateMinutes;
            UUID currentAssigneeId = (UUID) wi.get("assignee_id");
            UUID currentSprintId = (UUID) wi.get("sprint_id");

            Map<String, Object> oldEntry = activeOldEntries.get(wiId);
            boolean isDone = "DONE".equals(status);
            boolean isOldFrozen = oldEntry != null && Boolean.TRUE.equals(oldEntry.get("frozen"));

            Map<String, Object> itemMap = new LinkedHashMap<>();
            itemMap.put("tempKey", tempKey);
            itemMap.put("targetId", wiId.toString());
            itemMap.put("title", title);
            itemMap.put("type", wi.get("type") != null ? wi.get("type").toString() : "TASK");
            itemMap.put("workKind", wi.get("work_kind") != null ? wi.get("work_kind").toString() : "CODE");

            if (isDone || isOldFrozen) {
                // Must preserve exact schedule entry values unchanged to satisfy ds_schedule_freeze
                frozenCount++;
                itemMap.put("frozen", true);
                if (oldEntry != null) {
                    itemMap.put("developerId", oldEntry.get("assignee_id") != null ? oldEntry.get("assignee_id").toString() : null);
                    itemMap.put("sprintId", oldEntry.get("sprint_id") != null ? oldEntry.get("sprint_id").toString() : null);
                    if (oldEntry.get("start_at") != null) {
                        itemMap.put("startAt", ((Timestamp) oldEntry.get("start_at")).toInstant().toString());
                    }
                    if (oldEntry.get("finish_at") != null) {
                        itemMap.put("finishAt", ((Timestamp) oldEntry.get("finish_at")).toInstant().toString());
                    }
                    int remSnap = oldEntry.get("remaining_minutes_snapshot") instanceof Number n ? n.intValue() : 0;
                    itemMap.put("remainingMinutes", remSnap);
                    itemMap.put("estimateMinutes", remSnap);
                } else {
                    itemMap.put("developerId", currentAssigneeId != null ? currentAssigneeId.toString() : null);
                    itemMap.put("sprintId", currentSprintId != null ? currentSprintId.toString() : null);
                    itemMap.put("remainingMinutes", 0);
                    itemMap.put("estimateMinutes", estimateMinutes);
                }
            } else {
                itemMap.put("frozen", false);
                itemMap.put("developerId", currentAssigneeId != null ? currentAssigneeId.toString() : null);
                itemMap.put("sprintId", currentSprintId != null ? currentSprintId.toString() : null);
                itemMap.put("estimateMinutes", estimateMinutes);
                itemMap.put("remainingMinutes", remainingMinutes);
            }

            items.add(itemMap);
        }

        // Fetch dependencies
        List<Map<String, Object>> depsRows = jdbcTemplate.queryForList("""
                SELECT predecessor_id, successor_id, lag_minutes
                FROM work_item_dependencies
                WHERE project_id = ?
                """, projectId);

        List<Map<String, Object>> dependencies = new ArrayList<>();
        for (Map<String, Object> dr : depsRows) {
            UUID predId = (UUID) dr.get("predecessor_id");
            UUID succId = (UUID) dr.get("successor_id");
            int lag = dr.get("lag_minutes") instanceof Number n ? n.intValue() : 0;
            String predKey = idToTempKey.get(predId);
            String succKey = idToTempKey.get(succId);
            if (predKey != null && succKey != null) {
                dependencies.add(Map.of(
                        "predecessorKey", predKey,
                        "successorKey", succKey,
                        "lagMinutes", lag
                ));
            }
        }

        Map<String, Object> initialPayload = new LinkedHashMap<>();
        initialPayload.put("schemaVersion", 1);
        initialPayload.put("items", items);
        initialPayload.put("dependencies", dependencies);

        UUID proposalId = UUID.randomUUID();
        Instant now = Instant.now();

        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(initialPayload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize proposal payload", e);
        }

        // Insert new proposal: kind = 'AUTO_REPLAN', status = 'DRAFT', solver_status = 'NOT_RUN'
        jdbcTemplate.update("""
                INSERT INTO plan_proposals (
                    id, project_id, kind, base_project_version, base_schedule_version_id,
                    status, solver_status, proposal_payload, solver_details, assumptions,
                    changes_committed_deadline, proposed_committed_deadline,
                    created_by, created_at, updated_at, version
                ) VALUES (
                    ?, ?, 'AUTO_REPLAN', ?, ?,
                    'DRAFT', 'NOT_RUN', ?::jsonb, '{"schemaVersion":1}'::jsonb, '[]'::jsonb,
                    false, null,
                    ?, now(), now(), 0
                )
                """,
                proposalId, projectId, projectVersion, activeScheduleVersionId,
                payloadJson, principal.userId()
        );

        // Run CP-SAT solver
        LocalDate horizonStart = plannedStart;
        LocalDate horizonEnd = committedDeadline != null ? committedDeadline.plusDays(60) : plannedStart.plusDays(180);

        ResourceCapacitySnapshot capacitySnapshot = capacitySnapshotReader.read(projectId, horizonStart, horizonEnd);
        SolverInputSnapshot solverInput = buildSolverInputSnapshot(
                projectId, workspaceId, projectVersion, timezone, horizonStart, horizonEnd, 10,
                initialPayload, capacitySnapshot
        );

        SolverResult solverResult = schedulingSolver.solve(solverInput);

        // Merge solver assignments back into payload items
        Map<String, Object> solvedPayload = new LinkedHashMap<>(initialPayload);
        List<Map<String, Object>> solvedItems = new ArrayList<>();
        LocalDate maxFinishDate = null;

        Map<UUID, SolverResult.TaskAssignment> assignmentMap = new HashMap<>();
        for (SolverResult.TaskAssignment a : solverResult.assignments()) {
            assignmentMap.put(a.taskId(), a);
        }

        for (Map<String, Object> it : items) {
            Map<String, Object> updatedItem = new LinkedHashMap<>(it);
            UUID targetId = it.get("targetId") != null ? UUID.fromString(it.get("targetId").toString()) : null;
            if (targetId != null && assignmentMap.containsKey(targetId) && !Boolean.TRUE.equals(it.get("frozen"))) {
                SolverResult.TaskAssignment assign = assignmentMap.get(targetId);
                updatedItem.put("developerId", assign.developerId() != null ? assign.developerId().toString() : it.get("developerId"));
                updatedItem.put("startAt", assign.startAt().toString());
                updatedItem.put("finishAt", assign.finishAt().toString());

                LocalDate finishD = assign.finishAt().atZone(ZoneId.of(timezone)).toLocalDate();
                if (maxFinishDate == null || finishD.isAfter(maxFinishDate)) {
                    maxFinishDate = finishD;
                }
            } else if (it.get("finishAt") != null) {
                try {
                    LocalDate fd = Instant.parse(it.get("finishAt").toString()).atZone(ZoneId.of(timezone)).toLocalDate();
                    if (maxFinishDate == null || fd.isAfter(maxFinishDate)) {
                        maxFinishDate = fd;
                    }
                } catch (Exception ignored) {
                }
            }
            solvedItems.add(updatedItem);
        }
        solvedPayload.put("items", solvedItems);

        Map<String, Object> solverDetails = new LinkedHashMap<>();
        solverDetails.put("schemaVersion", 1);
        solverDetails.put("rawNativeStatus", solverResult.diagnostics().rawNativeStatus());
        solverDetails.put("wallTimeSeconds", solverResult.diagnostics().wallTimeSeconds());
        solverDetails.put("makespanMinutes", solverResult.makespanMinutes());

        String targetSolverStatus = solverResult.status().name();

        // MANDATORY TWO-PHASE UPDATE:
        // Phase 1: Update proposal_payload, solver_details, assumptions (trigger resets solver_status to NOT_RUN)
        String solvedPayloadJson;
        String solverDetailsJson;
        try {
            solvedPayloadJson = objectMapper.writeValueAsString(solvedPayload);
            solverDetailsJson = objectMapper.writeValueAsString(solverDetails);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize solved proposal payload", e);
        }

        jdbcTemplate.update("""
                UPDATE plan_proposals
                SET proposal_payload = ?::jsonb,
                    solver_details = ?::jsonb,
                    updated_at = now()
                WHERE id = ? AND project_id = ?
                """,
                solvedPayloadJson, solverDetailsJson, proposalId, projectId
        );

        // Phase 2: Update solver_status = targetSolverStatus
        jdbcTemplate.update("""
                UPDATE plan_proposals
                SET solver_status = ?,
                    updated_at = now()
                WHERE id = ? AND project_id = ?
                """,
                targetSolverStatus, proposalId, projectId
        );

        entityManager.clear();

        return new RescheduleResponse(
                proposalId,
                projectId,
                "DRAFT",
                targetSolverStatus,
                items.size(),
                frozenCount,
                maxFinishDate != null ? maxFinishDate : committedDeadline,
                now
        );
    }

    private SolverInputSnapshot buildSolverInputSnapshot(
            UUID projectId,
            UUID workspaceId,
            Long projectVersion,
            String timezone,
            LocalDate horizonStart,
            LocalDate horizonEnd,
            int maxTimeSeconds,
            Map<String, Object> payload,
            ResourceCapacitySnapshot capacitySnapshot) {

        List<SolverInputSnapshot.TaskItem> tasks = new ArrayList<>();
        Map<String, UUID> tempKeyToId = new HashMap<>();

        if (payload != null && payload.get("items") instanceof List<?> rawItems) {
            for (Object obj : rawItems) {
                if (obj instanceof Map<?, ?> itemMap) {
                    String tempKey = itemMap.get("tempKey") != null ? itemMap.get("tempKey").toString() : null;
                    UUID taskId = null;
                    if (itemMap.get("targetId") != null) {
                        try {
                            taskId = UUID.fromString(itemMap.get("targetId").toString());
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                    if (taskId == null) {
                        taskId = UUID.randomUUID();
                    }
                    if (tempKey != null) {
                        tempKeyToId.put(tempKey, taskId);
                    }

                    String title = itemMap.get("title") != null ? itemMap.get("title").toString() : (tempKey != null ? tempKey : "Task");
                    int estimateMinutes = 0;
                    if (itemMap.get("estimateMinutes") instanceof Number n) {
                        estimateMinutes = n.intValue();
                    }

                    UUID fixedAssigneeId = null;
                    if (itemMap.get("developerId") != null && Boolean.TRUE.equals(itemMap.get("frozen"))) {
                        try {
                            fixedAssigneeId = UUID.fromString(itemMap.get("developerId").toString());
                        } catch (IllegalArgumentException ignored) {
                        }
                    }

                    tasks.add(new SolverInputSnapshot.TaskItem(
                            taskId,
                            tempKey != null ? tempKey : taskId.toString(),
                            title,
                            estimateMinutes,
                            List.of(),
                            fixedAssigneeId,
                            null,
                            false
                    ));
                }
            }
        }

        List<SolverInputSnapshot.TaskDependency> dependencies = new ArrayList<>();
        if (payload != null && payload.get("dependencies") instanceof List<?> rawDeps) {
            for (Object obj : rawDeps) {
                if (obj instanceof Map<?, ?> depMap) {
                    String predKey = depMap.get("predecessorKey") != null ? depMap.get("predecessorKey").toString() : null;
                    String succKey = depMap.get("successorKey") != null ? depMap.get("successorKey").toString() : null;
                    int lag = depMap.get("lagMinutes") instanceof Number n ? n.intValue() : 0;

                    UUID predId = tempKeyToId.get(predKey);
                    UUID succId = tempKeyToId.get(succKey);
                    if (predId != null && succId != null) {
                        dependencies.add(new SolverInputSnapshot.TaskDependency(predId, succId, lag));
                    }
                }
            }
        }

        return new SolverInputSnapshot(
                projectId,
                workspaceId,
                projectVersion,
                timezone,
                horizonStart,
                horizonEnd,
                maxTimeSeconds,
                4,
                42,
                tasks,
                dependencies,
                capacitySnapshot != null ? capacitySnapshot.developers() : List.of(),
                List.of()
        );
    }
}

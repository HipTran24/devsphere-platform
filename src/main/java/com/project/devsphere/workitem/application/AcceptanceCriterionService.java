package com.project.devsphere.workitem.application;

import com.project.devsphere.identity.application.AccessPolicy;
import com.project.devsphere.identity.application.SessionService;
import com.project.devsphere.identity.domain.ProjectRole;
import com.project.devsphere.workitem.api.dto.AcceptanceCriterionResponse;
import com.project.devsphere.workitem.api.dto.CreateCriterionRequest;
import com.project.devsphere.workitem.api.dto.VerifyCriterionRequest;
import com.project.devsphere.workitem.domain.AcceptanceCriterion;
import com.project.devsphere.workspace.application.WorkspaceProjectException;
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
public class AcceptanceCriterionService {

    private final JdbcTemplate jdbcTemplate;
    private final AccessPolicy accessPolicy;

    public AcceptanceCriterionService(JdbcTemplate jdbcTemplate, AccessPolicy accessPolicy) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy must not be null");
    }

    private static final RowMapper<AcceptanceCriterionResponse> CRITERION_MAPPER = (rs, rowNum) -> new AcceptanceCriterionResponse(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("project_id")),
            UUID.fromString(rs.getString("work_item_id")),
            rs.getInt("position"),
            rs.getString("description"),
            AcceptanceCriterion.EvidenceKind.valueOf(rs.getString("evidence_kind")),
            rs.getBigDecimal("weight"),
            AcceptanceCriterion.Status.valueOf(rs.getString("status")),
            toUuid(rs.getString("verified_by")),
            toInstant(rs.getTimestamp("verified_at")),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("updated_at")),
            rs.getLong("version")
    );

    /**
     * Creates an acceptance criterion for a work item.
     * Guarded by active PM role.
     */
    @Transactional
    public AcceptanceCriterionResponse createCriterion(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UUID workItemId,
            CreateCriterionRequest request) {

        accessPolicy.requireProjectRole(principal, workspaceId, projectId, ProjectRole.PM);

        if (request == null || request.description() == null || request.description().isBlank()) {
            throw WorkspaceProjectException.validationFailed("Description must not be blank");
        }

        // Verify work item exists in project
        String checkItemSql = "SELECT count(*) FROM work_items WHERE id = ? AND project_id = ? AND deleted_at IS NULL";
        Integer itemCount = jdbcTemplate.queryForObject(checkItemSql, Integer.class, workItemId, projectId);
        if (itemCount == null || itemCount == 0) {
            throw WorkspaceProjectException.notFound("Work item not found in this project");
        }

        Integer position = request.position();
        if (position == null) {
            position = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(MAX(position), 0) + 1 FROM acceptance_criteria WHERE work_item_id = ?",
                    Integer.class,
                    workItemId
            );
            if (position == null) {
                position = 1;
            }
        }

        UUID id = UUID.randomUUID();
        String sql = """
                INSERT INTO acceptance_criteria (
                    id, project_id, work_item_id, position, description, evidence_kind, weight, status, evidence, created_at, updated_at, version
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, ?, 'NOT_EVALUATED', '[]'::jsonb, now(), now(), 0
                )
                """;

        if (principal.userId() != null) {
            jdbcTemplate.queryForObject("SELECT set_config('devsphere.actor_id', ?, true)", String.class, principal.userId().toString());
        }

        jdbcTemplate.update(
                sql,
                id,
                projectId,
                workItemId,
                position,
                request.description().trim(),
                request.evidenceKind() != null ? request.evidenceKind().name() : "MANUAL",
                request.weight()
        );

        return getCriterion(projectId, id);
    }

    /**
     * Lists all criteria for a work item.
     */
    @Transactional(readOnly = true)
    public List<AcceptanceCriterionResponse> listCriteria(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UUID workItemId) {

        accessPolicy.requireProjectMember(principal, workspaceId, projectId);

        String sql = """
                SELECT id, project_id, work_item_id, position, description, evidence_kind, weight, status, verified_by, verified_at, created_at, updated_at, version
                FROM acceptance_criteria
                WHERE work_item_id = ? AND project_id = ?
                ORDER BY position ASC, id ASC
                """;

        return jdbcTemplate.query(sql, CRITERION_MAPPER, workItemId, projectId);
    }

    /**
     * Verifies an acceptance criterion (PM only).
     */
    @Transactional
    public AcceptanceCriterionResponse verifyCriterion(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UUID criterionId,
            VerifyCriterionRequest request) {

        accessPolicy.requireProjectRole(principal, workspaceId, projectId, ProjectRole.PM);

        if (request == null || request.status() == null) {
            throw WorkspaceProjectException.validationFailed("Status must not be null");
        }

        String checkSql = "SELECT count(*) FROM acceptance_criteria WHERE id = ? AND project_id = ?";
        Integer count = jdbcTemplate.queryForObject(checkSql, Integer.class, criterionId, projectId);
        if (count == null || count == 0) {
            throw WorkspaceProjectException.notFound("Acceptance criterion not found");
        }

        if (principal.userId() != null) {
            jdbcTemplate.queryForObject("SELECT set_config('devsphere.actor_id', ?, true)", String.class, principal.userId().toString());
        }

        String sql = """
                UPDATE acceptance_criteria
                SET status = ?,
                    verified_by = ?,
                    verified_at = now(),
                    version = version + 1,
                    updated_at = now()
                WHERE id = ? AND project_id = ?
                """;

        jdbcTemplate.update(sql, request.status().name(), principal.userId(), criterionId, projectId);

        return getCriterion(projectId, criterionId);
    }

    /**
     * Deletes an acceptance criterion.
     */
    @Transactional
    public void deleteCriterion(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UUID criterionId) {

        accessPolicy.requireProjectRole(principal, workspaceId, projectId, ProjectRole.PM);

        if (principal.userId() != null) {
            jdbcTemplate.queryForObject("SELECT set_config('devsphere.actor_id', ?, true)", String.class, principal.userId().toString());
        }

        int rows = jdbcTemplate.update(
                "DELETE FROM acceptance_criteria WHERE id = ? AND project_id = ?",
                criterionId,
                projectId
        );

        if (rows == 0) {
            throw WorkspaceProjectException.notFound("Acceptance criterion not found");
        }
    }

    private AcceptanceCriterionResponse getCriterion(UUID projectId, UUID criterionId) {
        String sql = """
                SELECT id, project_id, work_item_id, position, description, evidence_kind, weight, status, verified_by, verified_at, created_at, updated_at, version
                FROM acceptance_criteria
                WHERE id = ? AND project_id = ?
                """;

        List<AcceptanceCriterionResponse> items = jdbcTemplate.query(sql, CRITERION_MAPPER, criterionId, projectId);
        if (items.isEmpty()) {
            throw WorkspaceProjectException.notFound("Acceptance criterion not found");
        }
        return items.get(0);
    }

    private static UUID toUuid(String val) {
        return val != null ? UUID.fromString(val) : null;
    }

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }
}

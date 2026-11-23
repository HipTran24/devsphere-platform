package com.project.devsphere.requirement.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.devsphere.identity.application.AccessPolicy;
import com.project.devsphere.identity.application.ProjectScope;
import com.project.devsphere.identity.application.SessionService;
import com.project.devsphere.identity.domain.ProjectRole;
import com.project.devsphere.requirement.api.dto.ChangeRequestDecisionRequest;
import com.project.devsphere.requirement.api.dto.ChangeRequestResponse;
import com.project.devsphere.requirement.api.dto.CreateChangeRequest;
import com.project.devsphere.workspace.application.WorkspaceProjectException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class ChangeRequestService {

    private final JdbcTemplate jdbcTemplate;
    private final AccessPolicy accessPolicy;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ChangeRequestService(JdbcTemplate jdbcTemplate, AccessPolicy accessPolicy) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy must not be null");
    }

    /**
     * Submits a new Change Request (CR).
     * Allowed for CLIENT or PM roles.
     * Initial status is PENDING.
     */
    @Transactional
    public ChangeRequestResponse submitChangeRequest(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            CreateChangeRequest request) {
        accessPolicy.requireAnyProjectRole(principal, workspaceId, projectId, Set.of(ProjectRole.CLIENT, ProjectRole.PM));

        if (request == null || request.title() == null || request.title().isBlank()) {
            throw WorkspaceProjectException.validationFailed("Title is required");
        }

        // Lock project for monotonic code generation
        String lockProjectSql = "SELECT 1 FROM projects WHERE id = ? AND workspace_id = ? FOR UPDATE";
        Integer prjCount;
        try {
            prjCount = jdbcTemplate.queryForObject(lockProjectSql, Integer.class, projectId, workspaceId);
        } catch (org.springframework.dao.EmptyResultDataAccessException ex) {
            prjCount = 0;
        }
        if (prjCount == null || prjCount == 0) {
            throw WorkspaceProjectException.notFound("Project not found in designated workspace");
        }

        // Validate target requirement if provided
        if (request.targetRequirementId() != null) {
            String verifyTargetSql = "SELECT count(*) FROM requirements WHERE id = ? AND project_id = ? AND kind = 'REQUIREMENT'";
            Integer targetCount = jdbcTemplate.queryForObject(verifyTargetSql, Integer.class, request.targetRequirementId(), projectId);
            if (targetCount == null || targetCount == 0) {
                throw WorkspaceProjectException.validationFailed("Target requirement not found in project");
            }
        }

        // Monotonic sequence: CR-<seq>
        String seqSql = """
                SELECT COALESCE(MAX(CAST(SUBSTRING(code FROM 4) AS integer)), 0) + 1
                FROM requirements
                WHERE project_id = ? AND code LIKE 'CR-%'
                """;
        Integer nextSeq = jdbcTemplate.queryForObject(seqSql, Integer.class, projectId);
        String code = "CR-" + (nextSeq != null ? nextSeq : 1);

        UUID crId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        String description = request.description() != null ? request.description() : "";
        Instant now = Instant.now();

        // 1. Insert into requirements with kind=CHANGE_REQUEST, status=DRAFT, change_request_status=PENDING
        String insertReqSql = """
                INSERT INTO requirements (
                    id, project_id, code, kind, parent_requirement_id, title, description,
                    status, change_request_status, current_version, requested_by, created_at, updated_at, version
                ) VALUES (
                    ?, ?, ?, 'CHANGE_REQUEST', ?, ?, ?,
                    'DRAFT', 'PENDING', 1, ?, ?, ?, 0
                )
                """;
        jdbcTemplate.update(
                insertReqSql,
                crId,
                projectId,
                code,
                request.targetRequirementId(),
                request.title().trim(),
                description,
                principal.userId(),
                Timestamp.from(now),
                Timestamp.from(now)
        );

        // 2. Insert into requirement_versions (version_no=1)
        String insertVerSql = """
                INSERT INTO requirement_versions (
                    id, project_id, requirement_id, version_no, description, acceptance_summary, created_at
                ) VALUES (
                    ?, ?, ?, 1, ?, '', ?
                )
                """;
        jdbcTemplate.update(
                insertVerSql,
                versionId,
                projectId,
                crId,
                description,
                Timestamp.from(now)
        );

        return new ChangeRequestResponse(
                crId,
                projectId,
                code,
                request.title().trim(),
                description,
                "DRAFT",
                "PENDING",
                request.targetRequirementId(),
                principal.userId(),
                null,
                null,
                null,
                now,
                now,
                0L
        );
    }

    /**
     * Lists change requests in a project.
     * PM sees all CRs; CLIENT sees only their own submitted CRs.
     */
    @Transactional(readOnly = true)
    public List<ChangeRequestResponse> listChangeRequests(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId) {
        ProjectScope scope = accessPolicy.requireProjectMember(principal, workspaceId, projectId);

        boolean isPm = scope.hasRole(ProjectRole.PM);

        StringBuilder sql = new StringBuilder("""
                SELECT r.id, r.project_id, r.code, r.title, r.description, r.status,
                       r.change_request_status, r.parent_requirement_id, r.requested_by, r.approved_by,
                       r.impact_json, r.created_at, r.updated_at, r.version,
                       p.id AS proposal_id
                FROM requirements r
                LEFT JOIN plan_proposals p ON p.change_request_id = r.id AND p.project_id = r.project_id
                WHERE r.project_id = ? AND r.kind = 'CHANGE_REQUEST'
                """);

        List<Object> params = new java.util.ArrayList<>();
        params.add(projectId);

        if (!isPm) {
            sql.append(" AND r.requested_by = ?");
            params.add(principal.userId());
        }

        sql.append(" ORDER BY r.created_at ASC");

        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> {
            String impactStr = rs.getString("impact_json");
            String decisionReason = null;
            if (impactStr != null && !impactStr.isBlank()) {
                try {
                    Map<?, ?> map = objectMapper.readValue(impactStr, Map.class);
                    decisionReason = (String) map.get("decisionReason");
                } catch (Exception ignored) {
                }
            }

            return new ChangeRequestResponse(
                    UUID.fromString(rs.getString("id")),
                    UUID.fromString(rs.getString("project_id")),
                    rs.getString("code"),
                    rs.getString("title"),
                    rs.getString("description"),
                    rs.getString("status"),
                    rs.getString("change_request_status"),
                    rs.getString("parent_requirement_id") != null ? UUID.fromString(rs.getString("parent_requirement_id")) : null,
                    UUID.fromString(rs.getString("requested_by")),
                    rs.getString("approved_by") != null ? UUID.fromString(rs.getString("approved_by")) : null,
                    decisionReason,
                    rs.getString("proposal_id") != null ? UUID.fromString(rs.getString("proposal_id")) : null,
                    toInstant(rs.getTimestamp("created_at")),
                    toInstant(rs.getTimestamp("updated_at")),
                    rs.getLong("version")
            );
        }, params.toArray());
    }

    /**
     * Decides on a Change Request (APPROVED, REJECTED, DEFERRED).
     * Guarded by requireProjectRole(PM).
     * TC-33 Invariant: When approved, generates a draft plan_proposals row without altering the baseline.
     */
    @Transactional
    public ChangeRequestResponse decideChangeRequest(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UUID crId,
            ChangeRequestDecisionRequest request) {
        accessPolicy.requireProjectRole(principal, workspaceId, projectId, ProjectRole.PM);

        if (request == null || request.decision() == null || request.decision().isBlank()) {
            throw WorkspaceProjectException.validationFailed("Decision is required");
        }

        String decision = request.decision().trim().toUpperCase();
        if (!Set.of("APPROVED", "REJECTED", "DEFERRED").contains(decision)) {
            throw WorkspaceProjectException.validationFailed("Decision must be APPROVED, REJECTED, or DEFERRED");
        }

        String lockSql = """
                SELECT id, code, title, description, parent_requirement_id, requested_by,
                       status, change_request_status, version
                FROM requirements
                WHERE id = ? AND project_id = ? AND kind = 'CHANGE_REQUEST'
                FOR UPDATE
                """;

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(lockSql, crId, projectId);
        if (rows.isEmpty()) {
            throw WorkspaceProjectException.notFound("Change request not found");
        }

        Map<String, Object> crRow = rows.get(0);
        Long currentEntityVersion = ((Number) crRow.get("version")).longValue();
        if (request.expectedVersion() != null && !request.expectedVersion().equals(currentEntityVersion)) {
            throw WorkspaceProjectException.planStale("Change request version conflict");
        }

        String existingCrStatus = (String) crRow.get("change_request_status");
        if (!"PENDING".equalsIgnoreCase(existingCrStatus)) {
            throw WorkspaceProjectException.validationFailed("Change request has already been decided");
        }

        Instant now = Instant.now();
        String newReqStatus = "APPROVED".equals(decision) ? "APPROVED" : ("REJECTED".equals(decision) ? "REJECTED" : "DRAFT");
        UUID approvedBy = "APPROVED".equals(decision) ? principal.userId() : null;

        Map<String, Object> impactMap = Map.of("decisionReason", request.decisionReason() != null ? request.decisionReason() : "");
        String impactJsonStr;
        try {
            impactJsonStr = objectMapper.writeValueAsString(impactMap);
        } catch (JsonProcessingException e) {
            impactJsonStr = "{}";
        }

        // Update requirements record
        String updateSql = """
                UPDATE requirements
                SET change_request_status = ?, status = ?, approved_by = ?,
                    impact_json = ?::jsonb, version = version + 1, updated_at = ?
                WHERE id = ?
                """;
        jdbcTemplate.update(
                updateSql,
                decision,
                newReqStatus,
                approvedBy,
                impactJsonStr,
                Timestamp.from(now),
                crId
        );

        UUID proposalId = null;

        // TC-33 Invariant: On APPROVED, create a draft plan_proposals record without modifying official baseline
        if ("APPROVED".equals(decision)) {
            proposalId = UUID.randomUUID();
            Long baseProjectVersion = jdbcTemplate.queryForObject(
                    "SELECT version FROM projects WHERE id = ?",
                    Long.class,
                    projectId
            );

            String insertProposalSql = """
                    INSERT INTO plan_proposals (
                        id, project_id, kind, base_project_version, change_request_id,
                        status, proposal_payload, solver_status, solver_details, assumptions,
                        created_by, created_at, updated_at, version
                    ) VALUES (
                        ?, ?, 'MANUAL_REPLAN', ?, ?,
                        'DRAFT', '{"schemaVersion":1,"items":[]}'::jsonb, 'NOT_RUN', '{"schemaVersion":1}'::jsonb, '[]'::jsonb,
                        ?, ?, ?, 0
                    )
                    """;
            jdbcTemplate.update(
                    insertProposalSql,
                    proposalId,
                    projectId,
                    baseProjectVersion != null ? baseProjectVersion : 0L,
                    crId,
                    principal.userId(),
                    Timestamp.from(now),
                    Timestamp.from(now)
            );
        }

        String code = (String) crRow.get("code");
        String title = (String) crRow.get("title");
        String description = (String) crRow.get("description");
        UUID parentReqId = (UUID) crRow.get("parent_requirement_id");
        UUID requestedBy = (UUID) crRow.get("requested_by");

        return new ChangeRequestResponse(
                crId,
                projectId,
                code,
                title,
                description,
                newReqStatus,
                decision,
                parentReqId,
                requestedBy,
                approvedBy,
                request.decisionReason(),
                proposalId,
                toInstant((Timestamp) crRow.get("created_at")),
                now,
                currentEntityVersion + 1
        );
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp != null ? timestamp.toInstant() : null;
    }
}

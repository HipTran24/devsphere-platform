package com.project.devsphere.requirement.application;

import com.project.devsphere.identity.application.AccessPolicy;
import com.project.devsphere.identity.application.SessionService;
import com.project.devsphere.identity.domain.ProjectRole;
import com.project.devsphere.requirement.api.dto.CreateRequirementRequest;
import com.project.devsphere.requirement.api.dto.RequirementResponse;
import com.project.devsphere.requirement.api.dto.RequirementVersionResponse;
import com.project.devsphere.requirement.api.dto.UpdateRequirementRequest;
import com.project.devsphere.requirement.domain.Requirement;
import com.project.devsphere.workspace.application.WorkspaceProjectException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class RequirementService {

    private final JdbcTemplate jdbcTemplate;
    private final AccessPolicy accessPolicy;

    public RequirementService(JdbcTemplate jdbcTemplate, AccessPolicy accessPolicy) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy must not be null");
    }

    /**
     * Creates a new requirement in DRAFT status with initial version 1.
     * Guarded by requireProjectRole(PM).
     */
    @Transactional
    public RequirementResponse createRequirement(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            CreateRequirementRequest request) {
        accessPolicy.requireProjectRole(principal, workspaceId, projectId, ProjectRole.PM);

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

        // Monotonic sequence generation: REQ-<seq>
        String seqSql = """
                SELECT COALESCE(MAX(CAST(SUBSTRING(code FROM 5) AS integer)), 0) + 1
                FROM requirements
                WHERE project_id = ? AND code LIKE 'REQ-%'
                """;
        Integer nextSeq = jdbcTemplate.queryForObject(seqSql, Integer.class, projectId);
        String code = "REQ-" + (nextSeq != null ? nextSeq : 1);

        UUID requirementId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        String description = request.description() != null ? request.description() : "";
        String acceptanceSummary = request.acceptanceSummary() != null ? request.acceptanceSummary() : "";
        Instant now = Instant.now();

        // 1. Insert requirement (status=DRAFT, current_version=1)
        String insertReqSql = """
                INSERT INTO requirements (
                    id, project_id, code, kind, parent_requirement_id, title, description,
                    status, current_version, requested_by, created_at, updated_at, version
                ) VALUES (
                    ?, ?, ?, 'REQUIREMENT', ?, ?, ?,
                    'DRAFT', 1, ?, ?, ?, 0
                )
                """;
        jdbcTemplate.update(
                insertReqSql,
                requirementId,
                projectId,
                code,
                request.parentRequirementId(),
                request.title().trim(),
                description,
                principal.userId(),
                Timestamp.from(now),
                Timestamp.from(now)
        );

        // 2. Insert initial version 1 (approved_at=null)
        String insertVerSql = """
                INSERT INTO requirement_versions (
                    id, project_id, requirement_id, version_no, description, acceptance_summary,
                    created_at
                ) VALUES (
                    ?, ?, ?, 1, ?, ?, ?
                )
                """;
        jdbcTemplate.update(
                insertVerSql,
                versionId,
                projectId,
                requirementId,
                description,
                acceptanceSummary,
                Timestamp.from(now)
        );

        RequirementVersionResponse v1 = new RequirementVersionResponse(
                versionId,
                requirementId,
                1,
                description,
                acceptanceSummary,
                null,
                null,
                now
        );

        return new RequirementResponse(
                requirementId,
                projectId,
                code,
                "REQUIREMENT",
                request.parentRequirementId(),
                request.title().trim(),
                description,
                "DRAFT",
                1,
                principal.userId(),
                null,
                acceptanceSummary,
                null,
                List.of(v1),
                now,
                now,
                0L
        );
    }

    /**
     * Lists requirements for a project with optional status filter.
     * Guarded by project membership.
     */
    @Transactional(readOnly = true)
    public List<RequirementResponse> listRequirements(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            Requirement.Status statusFilter) {
        accessPolicy.requireProjectMember(principal, workspaceId, projectId);

        StringBuilder sql = new StringBuilder("""
                SELECT r.id, r.project_id, r.code, r.kind, r.parent_requirement_id, r.title, r.description,
                       r.status, r.current_version, r.requested_by, r.approved_by, r.created_at, r.updated_at, r.version,
                       rv.acceptance_summary, rv.approved_at
                FROM requirements r
                LEFT JOIN requirement_versions rv ON rv.requirement_id = r.id AND rv.version_no = r.current_version
                WHERE r.project_id = ? AND r.kind = 'REQUIREMENT'
                """);

        List<Object> params = new ArrayList<>();
        params.add(projectId);

        if (statusFilter != null) {
            sql.append(" AND r.status = ?");
            params.add(statusFilter.name());
        }

        sql.append(" ORDER BY r.created_at ASC");

        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new RequirementResponse(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("project_id")),
                rs.getString("code"),
                rs.getString("kind"),
                rs.getString("parent_requirement_id") != null ? UUID.fromString(rs.getString("parent_requirement_id")) : null,
                rs.getString("title"),
                rs.getString("description"),
                rs.getString("status"),
                rs.getInt("current_version"),
                UUID.fromString(rs.getString("requested_by")),
                rs.getString("approved_by") != null ? UUID.fromString(rs.getString("approved_by")) : null,
                rs.getString("acceptance_summary"),
                toInstant(rs.getTimestamp("approved_at")),
                null,
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")),
                rs.getLong("version")
        ), params.toArray());
    }

    /**
     * Retrieves details of a requirement, including full version history.
     * Guarded by project membership.
     */
    @Transactional(readOnly = true)
    public RequirementResponse getRequirement(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UUID requirementId) {
        accessPolicy.requireProjectMember(principal, workspaceId, projectId);

        String sql = """
                SELECT r.id, r.project_id, r.code, r.kind, r.parent_requirement_id, r.title, r.description,
                       r.status, r.current_version, r.requested_by, r.approved_by, r.created_at, r.updated_at, r.version,
                       rv.acceptance_summary, rv.approved_at
                FROM requirements r
                LEFT JOIN requirement_versions rv ON rv.requirement_id = r.id AND rv.version_no = r.current_version
                WHERE r.id = ? AND r.project_id = ? AND r.kind = 'REQUIREMENT'
                """;

        List<RequirementResponse> list = jdbcTemplate.query(sql, (rs, rowNum) -> new RequirementResponse(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("project_id")),
                rs.getString("code"),
                rs.getString("kind"),
                rs.getString("parent_requirement_id") != null ? UUID.fromString(rs.getString("parent_requirement_id")) : null,
                rs.getString("title"),
                rs.getString("description"),
                rs.getString("status"),
                rs.getInt("current_version"),
                UUID.fromString(rs.getString("requested_by")),
                rs.getString("approved_by") != null ? UUID.fromString(rs.getString("approved_by")) : null,
                rs.getString("acceptance_summary"),
                toInstant(rs.getTimestamp("approved_at")),
                null,
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")),
                rs.getLong("version")
        ), requirementId, projectId);

        if (list.isEmpty()) {
            throw WorkspaceProjectException.notFound("Requirement not found");
        }

        RequirementResponse req = list.get(0);
        List<RequirementVersionResponse> versions = listVersions(projectId, requirementId);

        return new RequirementResponse(
                req.id(),
                req.projectId(),
                req.code(),
                req.kind(),
                req.parentRequirementId(),
                req.title(),
                req.description(),
                req.status(),
                req.currentVersion(),
                req.requestedBy(),
                req.approvedBy(),
                req.acceptanceSummary(),
                req.approvedAt(),
                versions,
                req.createdAt(),
                req.updatedAt(),
                req.version()
        );
    }

    /**
     * Updates an existing requirement.
     * Appends a new monotonically incremented version in requirement_versions,
     * leaving prior approved versions immutable.
     * Guarded by requireProjectRole(PM).
     */
    @Transactional
    public RequirementResponse updateRequirement(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UUID requirementId,
            UpdateRequirementRequest request) {
        accessPolicy.requireProjectRole(principal, workspaceId, projectId, ProjectRole.PM);

        String lockSql = """
                SELECT id, title, description, current_version, status, version
                FROM requirements
                WHERE id = ? AND project_id = ? AND kind = 'REQUIREMENT'
                FOR UPDATE
                """;
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(lockSql, requirementId, projectId);
        if (rows.isEmpty()) {
            throw WorkspaceProjectException.notFound("Requirement not found");
        }

        Map<String, Object> reqRow = rows.get(0);
        Long currentEntityVersion = ((Number) reqRow.get("version")).longValue();
        if (request != null && request.expectedVersion() != null && !request.expectedVersion().equals(currentEntityVersion)) {
            throw WorkspaceProjectException.planStale("Requirement version conflict");
        }

        int currentVersionNo = ((Number) reqRow.get("current_version")).intValue();
        int nextVersionNo = currentVersionNo + 1;

        String oldTitle = (String) reqRow.get("title");
        String oldDesc = (String) reqRow.get("description");

        String newTitle = request != null && request.title() != null && !request.title().isBlank()
                ? request.title().trim() : oldTitle;
        String newDesc = request != null && request.description() != null
                ? request.description() : oldDesc;
        String newAcceptance = request != null && request.acceptanceSummary() != null
                ? request.acceptanceSummary() : "";
        String changeReason = request != null ? request.changeReason() : null;

        UUID newVerId = UUID.randomUUID();
        Instant now = Instant.now();

        // 1. Insert new version (approved_at=null)
        String insertVerSql = """
                INSERT INTO requirement_versions (
                    id, project_id, requirement_id, version_no, description, acceptance_summary,
                    change_reason, created_at
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, ?, ?
                )
                """;
        jdbcTemplate.update(
                insertVerSql,
                newVerId,
                projectId,
                requirementId,
                nextVersionNo,
                newDesc,
                newAcceptance,
                changeReason,
                Timestamp.from(now)
        );

        // 2. Update requirement current_version and status to DRAFT
        String updateReqSql = """
                UPDATE requirements
                SET title = ?, description = ?, current_version = ?, status = 'DRAFT',
                    version = version + 1, updated_at = ?
                WHERE id = ?
                """;
        jdbcTemplate.update(
                updateReqSql,
                newTitle,
                newDesc,
                nextVersionNo,
                Timestamp.from(now),
                requirementId
        );

        return getRequirement(principal, workspaceId, projectId, requirementId);
    }

    /**
     * Approves the current version of a requirement.
     * Sets approved_at on the requirement_versions row, making it permanently immutable.
     * Guarded by requireProjectRole(PM).
     */
    @Transactional
    public RequirementResponse approveRequirement(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UUID requirementId) {
        accessPolicy.requireProjectRole(principal, workspaceId, projectId, ProjectRole.PM);

        String lockSql = "SELECT current_version FROM requirements WHERE id = ? AND project_id = ? FOR UPDATE";
        List<Integer> versions = jdbcTemplate.query(lockSql, (rs, rowNum) -> rs.getInt("current_version"), requirementId, projectId);
        if (versions.isEmpty()) {
            throw WorkspaceProjectException.notFound("Requirement not found");
        }

        int currentVer = versions.get(0);
        Instant now = Instant.now();

        jdbcTemplate.update(
                "UPDATE requirement_versions SET approved_at = ? WHERE requirement_id = ? AND version_no = ? AND approved_at IS NULL",
                Timestamp.from(now),
                requirementId,
                currentVer
        );

        jdbcTemplate.update(
                "UPDATE requirements SET status = 'APPROVED', approved_by = ?, updated_at = ? WHERE id = ?",
                principal.userId(),
                Timestamp.from(now),
                requirementId
        );

        return getRequirement(principal, workspaceId, projectId, requirementId);
    }

    private List<RequirementVersionResponse> listVersions(UUID projectId, UUID requirementId) {
        String sql = """
                SELECT id, requirement_id, version_no, description, acceptance_summary, change_reason,
                       approved_at, created_at
                FROM requirement_versions
                WHERE requirement_id = ? AND project_id = ?
                ORDER BY version_no ASC
                """;

        return jdbcTemplate.query(sql, (rs, rowNum) -> new RequirementVersionResponse(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("requirement_id")),
                rs.getInt("version_no"),
                rs.getString("description"),
                rs.getString("acceptance_summary"),
                rs.getString("change_reason"),
                toInstant(rs.getTimestamp("approved_at")),
                toInstant(rs.getTimestamp("created_at"))
        ), requirementId, projectId);
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp != null ? timestamp.toInstant() : null;
    }
}

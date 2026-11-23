package com.project.devsphere.monitoring.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.devsphere.identity.application.AccessPolicy;
import com.project.devsphere.identity.application.SessionService;
import com.project.devsphere.monitoring.api.dto.CodeEvidenceDto;
import com.project.devsphere.monitoring.api.dto.ProgressEvaluationResponse;
import com.project.devsphere.workitem.domain.AcceptanceCriterion;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@Transactional
public class ProgressEvaluationService {

    private final JdbcTemplate jdbcTemplate;
    private final AccessPolicy accessPolicy;
    private final ProgressEvaluator progressEvaluator;
    private final ObjectMapper objectMapper;

    @Autowired
    public ProgressEvaluationService(
            JdbcTemplate jdbcTemplate,
            AccessPolicy accessPolicy,
            ProgressEvaluator progressEvaluator,
            @Autowired(required = false) ObjectMapper objectMapper) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy must not be null");
        this.progressEvaluator = Objects.requireNonNull(progressEvaluator, "progressEvaluator must not be null");
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    /**
     * Evaluates progress of a work item based on its acceptance criteria and code quality evidence.
     * Purely observational: NEVER modifies work_items.status to 'DONE'.
     */
    public ProgressEvaluationResponse evaluateProgress(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UUID workItemId) {

        accessPolicy.requireProjectMember(principal, workspaceId, projectId);

        // 1. Fetch work item
        String itemSql = """
                SELECT id, project_id, item_key, title, status, work_kind, claimed_progress, assignee_id
                FROM work_items
                WHERE id = ? AND project_id = ? AND deleted_at IS NULL
                """;

        List<Map<String, Object>> itemRows = jdbcTemplate.queryForList(itemSql, workItemId, projectId);
        if (itemRows.isEmpty()) {
            throw MonitoringException.resourceNotFound("Work item not found: " + workItemId);
        }

        Map<String, Object> itemRow = itemRows.get(0);
        String itemKey = (String) itemRow.get("item_key");
        String status = (String) itemRow.get("status");
        String workKind = (String) itemRow.get("work_kind");
        BigDecimal claimedProgress = (BigDecimal) itemRow.get("claimed_progress");
        UUID assigneeId = (UUID) itemRow.get("assignee_id");

        // 2. Fetch acceptance criteria
        String critSql = """
                SELECT id, position, description, evidence_kind, weight, status, evidence, verified_by, verified_at
                FROM acceptance_criteria
                WHERE work_item_id = ? AND project_id = ?
                ORDER BY position ASC
                """;

        List<AcceptanceCriterion> criteria = jdbcTemplate.query(critSql, (rs, rowNum) -> {
            AcceptanceCriterion c = new AcceptanceCriterion();
            c.setId(rs.getObject("id", UUID.class));
            c.setProjectId(projectId);
            c.setWorkItemId(workItemId);
            c.setPosition(rs.getInt("position"));
            c.setDescription(rs.getString("description"));

            String ekStr = rs.getString("evidence_kind");
            if (ekStr != null) {
                try {
                    c.setEvidenceKind(AcceptanceCriterion.EvidenceKind.valueOf(ekStr.toUpperCase()));
                } catch (IllegalArgumentException ignored) {}
            }

            BigDecimal w = rs.getBigDecimal("weight");
            c.setWeight(w != null ? w : BigDecimal.ONE);

            String statusStr = rs.getString("status");
            if (statusStr != null) {
                try {
                    c.setStatus(AcceptanceCriterion.Status.valueOf(statusStr.toUpperCase()));
                } catch (IllegalArgumentException ignored) {
                    c.setStatus(AcceptanceCriterion.Status.NOT_EVALUATED);
                }
            } else {
                c.setStatus(AcceptanceCriterion.Status.NOT_EVALUATED);
            }

            c.setVerifiedBy(rs.getObject("verified_by", UUID.class));
            Timestamp vAt = rs.getTimestamp("verified_at");
            c.setVerifiedAt(vAt != null ? vAt.toInstant() : null);

            return c;
        }, workItemId, projectId);

        // 3. Resolve code evidence for CODE tasks
        CodeEvidenceDto codeEvidence = null;
        if ("CODE".equalsIgnoreCase(workKind)) {
            codeEvidence = resolveCodeEvidence(projectId, workItemId, assigneeId);
        }

        // 4. Compute progress
        ProgressEvaluator.EvaluationInput input = new ProgressEvaluator.EvaluationInput(
                workItemId,
                itemKey,
                workKind,
                status,
                claimedProgress,
                criteria,
                codeEvidence
        );

        ProgressEvaluationResponse response = progressEvaluator.evaluate(input);

        // 5. Observational update: persist verified_progress and progress_evidence without changing status
        updateWorkItemVerifiedProgress(principal, projectId, workItemId, response);

        return response;
    }

    private CodeEvidenceDto resolveCodeEvidence(UUID projectId, UUID workItemId, UUID assigneeId) {
        // Query latest linked PR from github_activities
        String prSql = """
                SELECT pr_number, pr_head_sha, actor_login
                FROM github_activities
                WHERE project_id = ? AND work_item_id = ? AND kind = 'PULL_REQUEST'
                ORDER BY happened_at DESC
                LIMIT 1
                """;

        List<Map<String, Object>> prRows = jdbcTemplate.queryForList(prSql, projectId, workItemId);
        if (prRows.isEmpty()) {
            return new CodeEvidenceDto(false, null, null, false, "MISSING_PR", false, "NO_REVIEW", false);
        }

        Map<String, Object> pr = prRows.get(0);
        Integer prNumber = (Integer) pr.get("pr_number");
        String headSha = (String) pr.get("pr_head_sha");
        if (headSha != null) {
            headSha = headSha.trim().toLowerCase();
        }

        // Query latest CI run on headSha
        boolean ciSuccess = false;
        String ciStatus = "NOT_FOUND";
        if (headSha != null && !headSha.isBlank()) {
            String ciSql = """
                    SELECT status, conclusion
                    FROM ci_runs
                    WHERE project_id = ? AND head_sha = ?
                    ORDER BY completed_at DESC NULLS LAST, created_at DESC
                    LIMIT 1
                    """;
            List<Map<String, Object>> ciRows = jdbcTemplate.queryForList(ciSql, projectId, headSha);
            if (!ciRows.isEmpty()) {
                String st = (String) ciRows.get(0).get("status");
                String concl = (String) ciRows.get(0).get("conclusion");
                ciStatus = concl != null ? concl : st;
                if ("SUCCESS".equalsIgnoreCase(concl)) {
                    ciSuccess = true;
                }
            }
        }

        // Query latest code review on headSha
        boolean reviewPass = false;
        String reviewStatus = "NOT_FOUND";
        boolean independentReviewer = false;

        if (headSha != null && !headSha.isBlank()) {
            String revSql = """
                    SELECT status, reviewer_id
                    FROM code_reviews
                    WHERE project_id = ? AND head_sha = ?
                    ORDER BY completed_at DESC NULLS LAST, created_at DESC
                    LIMIT 1
                    """;
            List<Map<String, Object>> revRows = jdbcTemplate.queryForList(revSql, projectId, headSha);
            if (!revRows.isEmpty()) {
                reviewStatus = (String) revRows.get(0).get("status");
                UUID reviewerId = (UUID) revRows.get(0).get("reviewer_id");

                if ("PASS".equalsIgnoreCase(reviewStatus)) {
                    reviewPass = true;
                }
                // Reviewer independence: reviewer must differ from task assignee
                if (reviewerId != null && (assigneeId == null || !reviewerId.equals(assigneeId))) {
                    independentReviewer = true;
                }
            }
        }

        return new CodeEvidenceDto(
                true,
                prNumber,
                headSha,
                ciSuccess,
                ciStatus,
                reviewPass,
                reviewStatus,
                independentReviewer
        );
    }

    private void updateWorkItemVerifiedProgress(
            SessionService.Principal principal,
            UUID projectId,
            UUID workItemId,
            ProgressEvaluationResponse response) {

        Map<String, Object> evidenceMap = new LinkedHashMap<>();
        evidenceMap.put("schemaVersion", 1);
        evidenceMap.put("evaluatedAt", response.evaluatedAt().toString());
        evidenceMap.put("evaluationStatus", response.evaluationStatus());
        evidenceMap.put("totalCriteriaWeight", response.totalCriteriaWeight());
        evidenceMap.put("verifiedCriteriaWeight", response.verifiedCriteriaWeight());
        evidenceMap.put("totalCriteriaCount", response.totalCriteriaCount());
        evidenceMap.put("verifiedCriteriaCount", response.verifiedCriteriaCount());
        evidenceMap.put("discrepancyDetected", response.discrepancyDetected());

        if (response.codeEvidence() != null) {
            evidenceMap.put("codeEvidence", response.codeEvidence());
        }

        String evidenceJson;
        try {
            evidenceJson = objectMapper.writeValueAsString(evidenceMap);
        } catch (JsonProcessingException e) {
            evidenceJson = "{\"schemaVersion\":1}";
        }

        // Observational update to verified_progress and progress_evidence
        // Note: status is untouched per FC-05.06 and Part H3
        String updateSql = """
                UPDATE work_items
                SET verified_progress = ?,
                    progress_evidence = ?::jsonb,
                    updated_at = now(),
                    version = version + 1
                WHERE id = ? AND project_id = ?
                """;

        setAuditActor(principal != null ? principal.userId() : null);
        jdbcTemplate.update(updateSql, response.verifiedProgress(), evidenceJson, workItemId, projectId);
    }

    private void setAuditActor(UUID actorId) {
        if (actorId != null) {
            try {
                jdbcTemplate.queryForObject("SELECT set_config('devsphere.actor_id', ?, true)", String.class, actorId.toString());
            } catch (Exception ignored) {}
        }
    }
}

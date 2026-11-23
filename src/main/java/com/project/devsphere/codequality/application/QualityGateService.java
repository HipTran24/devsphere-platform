package com.project.devsphere.codequality.application;

import com.project.devsphere.codequality.api.dto.QualityGateResponse;
import com.project.devsphere.codequality.api.dto.QualityGateStatus;
import com.project.devsphere.identity.application.AccessPolicy;
import com.project.devsphere.identity.application.SessionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class QualityGateService {

    private final AccessPolicy accessPolicy;
    private final JdbcTemplate jdbcTemplate;

    public QualityGateService(AccessPolicy accessPolicy, JdbcTemplate jdbcTemplate) {
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy must not be null");
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
    }

    /**
     * Evaluates the Quality Gate for a specific work item.
     * Enforces:
     * - Access: Scoped to active project members.
     * - For NON_CODE items: Passes if all acceptance criteria are verified.
     * - For CODE items:
     *   1. Linked PR activity in github_activities.
     *   2. Latest CI run on PR head SHA has status = 'COMPLETED' and conclusion = 'SUCCESS'.
     *   3. Latest human code review on PR head SHA has status = 'PASS' and context_version matching project.version.
     *   4. All acceptance criteria for the work item are status = 'VERIFIED'.
     */
    public QualityGateResponse evaluateQualityGate(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UUID workItemId) {

        accessPolicy.requireProjectMember(principal, workspaceId, projectId);

        List<Map<String, Object>> items = jdbcTemplate.queryForList(
                "SELECT id, project_id, type, status, work_kind FROM work_items WHERE id = ? AND project_id = ?",
                workItemId, projectId
        );

        if (items.isEmpty()) {
            throw CodeQualityException.validationFailed("Work item not found: " + workItemId);
        }

        Map<String, Object> item = items.get(0);
        String workKindStr = (String) item.get("work_kind");
        boolean isNonCode = "NON_CODE".equalsIgnoreCase(workKindStr);

        // Check acceptance criteria
        Map<String, Object> criteriaStats = jdbcTemplate.queryForMap(
                "SELECT COUNT(*) AS total, " +
                        "COUNT(*) FILTER (WHERE status = 'VERIFIED') AS verified " +
                        "FROM acceptance_criteria WHERE work_item_id = ? AND project_id = ?",
                workItemId, projectId
        );

        long totalCriteria = ((Number) criteriaStats.get("total")).longValue();
        long verifiedCriteria = ((Number) criteriaStats.get("verified")).longValue();
        boolean criteriaPassed = (totalCriteria == 0 || verifiedCriteria == totalCriteria);

        // NON_CODE tasks pass solely based on criteria verification
        if (isNonCode) {
            List<String> blockers = new ArrayList<>();
            if (!criteriaPassed) {
                blockers.add("Acceptance criteria not all verified (" + verifiedCriteria + "/" + totalCriteria + ")");
                return new QualityGateResponse(
                        QualityGateStatus.FAILED,
                        false,
                        workItemId,
                        null,
                        null,
                        true,
                        true,
                        false,
                        "Non-code work item has unverified acceptance criteria",
                        blockers
                );
            }
            return new QualityGateResponse(
                    QualityGateStatus.PASSED,
                    true,
                    workItemId,
                    null,
                    null,
                    true,
                    true,
                    true,
                    "Non-code work item acceptance criteria verified",
                    List.of()
            );
        }

        // CODE work items evaluation
        List<String> blockers = new ArrayList<>();

        // 1. Linked PR activity
        List<Map<String, Object>> prActivities = jdbcTemplate.queryForList(
                "SELECT pr_number, pr_head_sha, actor_login, state FROM github_activities " +
                        "WHERE work_item_id = ? AND project_id = ? AND kind = 'PULL_REQUEST' " +
                        "ORDER BY happened_at DESC LIMIT 1",
                workItemId, projectId
        );

        if (prActivities.isEmpty()) {
            blockers.add("No linked pull request activity found for work item");
            return new QualityGateResponse(
                    QualityGateStatus.PENDING_EVIDENCE,
                    false,
                    workItemId,
                    null,
                    null,
                    false,
                    false,
                    criteriaPassed,
                    "No linked pull request found",
                    blockers
            );
        }

        Map<String, Object> prActivity = prActivities.get(0);
        Integer prNumber = (Integer) prActivity.get("pr_number");
        String prHeadSha = (String) prActivity.get("pr_head_sha");
        String prState = (String) prActivity.get("state");

        if ("CLOSED".equalsIgnoreCase(prState)) {
            blockers.add("Linked pull request #" + prNumber + " is closed without being merged");
            return new QualityGateResponse(
                    QualityGateStatus.FAILED,
                    false,
                    workItemId,
                    prNumber,
                    null,
                    false,
                    false,
                    criteriaPassed,
                    "Pull request is closed without merge",
                    blockers
            );
        }

        if (prHeadSha == null || prHeadSha.isBlank()) {
            blockers.add("Linked pull request #" + prNumber + " does not have a valid head commit SHA");
            return new QualityGateResponse(
                    QualityGateStatus.PENDING_EVIDENCE,
                    false,
                    workItemId,
                    prNumber,
                    null,
                    false,
                    false,
                    criteriaPassed,
                    "Pull request has missing head SHA",
                    blockers
            );
        }

        String normalizedHeadSha = prHeadSha.trim().toLowerCase();

        // 2. Latest CI run evaluation
        List<Map<String, Object>> ciRuns = jdbcTemplate.queryForList(
                "SELECT status, conclusion FROM ci_runs " +
                        "WHERE project_id = ? AND head_sha = ? " +
                        "ORDER BY completed_at DESC NULLS LAST, created_at DESC LIMIT 1",
                projectId, normalizedHeadSha
        );

        boolean ciPassed = false;
        boolean ciFailed = false;
        boolean ciPending = false;

        if (ciRuns.isEmpty()) {
            ciPending = true;
            blockers.add("No CI run recorded for commit " + normalizedHeadSha);
        } else {
            Map<String, Object> ciRun = ciRuns.get(0);
            String status = (String) ciRun.get("status");
            String conclusion = (String) ciRun.get("conclusion");

            if ("COMPLETED".equalsIgnoreCase(status)) {
                if ("SUCCESS".equalsIgnoreCase(conclusion)) {
                    ciPassed = true;
                } else {
                    ciFailed = true;
                    blockers.add("CI run failed with conclusion: " + conclusion);
                }
            } else {
                ciPending = true;
                blockers.add("CI run is not yet completed (current status: " + status + ")");
            }
        }

        // 3. Human code review evaluation
        Long projectVersion = jdbcTemplate.queryForObject(
                "SELECT version FROM projects WHERE id = ?",
                Long.class,
                projectId
        );
        if (projectVersion == null) {
            projectVersion = 0L;
        }

        List<Map<String, Object>> reviews = jdbcTemplate.queryForList(
                "SELECT status, context_version, reviewer_kind, reviewer_id FROM code_reviews " +
                        "WHERE project_id = ? AND head_sha = ? AND pr_number = ? AND scope = 'PR' " +
                        "ORDER BY completed_at DESC NULLS LAST, created_at DESC LIMIT 1",
                projectId, normalizedHeadSha, prNumber
        );

        boolean reviewPassed = false;
        boolean reviewFailed = false;
        boolean reviewStale = false;
        boolean reviewPending = false;

        if (reviews.isEmpty()) {
            reviewPending = true;
            blockers.add("No human code review found for PR #" + prNumber + " on commit " + normalizedHeadSha);
        } else {
            Map<String, Object> review = reviews.get(0);
            String reviewStatus = (String) review.get("status");
            Long contextVersion = ((Number) review.get("context_version")).longValue();

            if ("FAIL".equalsIgnoreCase(reviewStatus) || "REQUIRES_CHANGES".equalsIgnoreCase(reviewStatus)) {
                reviewFailed = true;
                blockers.add("Latest code review has status: " + reviewStatus);
            } else if (contextVersion < projectVersion) {
                reviewStale = true;
                blockers.add("Code review is stale: project version is " + projectVersion + ", review was stamped at " + contextVersion);
            } else if ("PASS".equalsIgnoreCase(reviewStatus)) {
                reviewPassed = true;
            } else {
                reviewPending = true;
                blockers.add("Code review is not yet passed (current status: " + reviewStatus + ")");
            }
        }

        if (!criteriaPassed) {
            blockers.add("Acceptance criteria not all verified (" + verifiedCriteria + "/" + totalCriteria + ")");
        }

        // Determine overall status
        QualityGateStatus gateStatus;
        if (ciFailed || reviewFailed || (!criteriaPassed && totalCriteria > 0)) {
            gateStatus = QualityGateStatus.FAILED;
        } else if (reviewStale) {
            gateStatus = QualityGateStatus.STALE_EVIDENCE;
        } else if (ciPending || reviewPending) {
            gateStatus = QualityGateStatus.PENDING_EVIDENCE;
        } else if (ciPassed && reviewPassed && criteriaPassed) {
            gateStatus = QualityGateStatus.PASSED;
        } else {
            gateStatus = QualityGateStatus.FAILED;
        }

        boolean overallPassed = (gateStatus == QualityGateStatus.PASSED);

        return new QualityGateResponse(
                gateStatus,
                overallPassed,
                workItemId,
                prNumber,
                normalizedHeadSha,
                ciPassed,
                reviewPassed,
                criteriaPassed,
                overallPassed ? "Quality gate passed with full evidence" : String.join("; ", blockers),
                blockers
        );
    }
}

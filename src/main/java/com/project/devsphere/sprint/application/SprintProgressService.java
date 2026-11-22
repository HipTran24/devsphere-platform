package com.project.devsphere.sprint.application;

import com.project.devsphere.identity.application.AccessPolicy;
import com.project.devsphere.identity.application.SessionService;
import com.project.devsphere.sprint.api.dto.SprintProgressResponse;
import com.project.devsphere.workspace.application.WorkspaceProjectException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class SprintProgressService {

    private final JdbcTemplate jdbcTemplate;
    private final AccessPolicy accessPolicy;

    public SprintProgressService(JdbcTemplate jdbcTemplate, AccessPolicy accessPolicy) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy must not be null");
    }

    @Transactional(readOnly = true)
    public SprintProgressResponse getSprintProgress(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UUID sprintId) {
        accessPolicy.requireProjectMember(principal, workspaceId, projectId);

        String sprintSql = """
                SELECT id, name, status, committed_points, completed_points
                FROM sprints
                WHERE id = ? AND project_id = ?
                """;

        List<Map<String, Object>> sprintRows = jdbcTemplate.queryForList(sprintSql, sprintId, projectId);
        if (sprintRows.isEmpty()) {
            throw WorkspaceProjectException.notFound("Sprint not found");
        }

        Map<String, Object> sprint = sprintRows.get(0);
        String sprintName = (String) sprint.get("name");
        String status = (String) sprint.get("status");
        BigDecimal committedPoints = sprint.get("committed_points") != null
                ? (BigDecimal) sprint.get("committed_points")
                : BigDecimal.ZERO;
        BigDecimal completedPoints = sprint.get("completed_points") != null
                ? (BigDecimal) sprint.get("completed_points")
                : BigDecimal.ZERO;

        String itemsSql = """
                SELECT status, story_points, estimate_minutes, remaining_minutes, time_spent_minutes, is_blocked
                FROM work_items
                WHERE sprint_id = ? AND project_id = ? AND deleted_at IS NULL
                """;

        List<Map<String, Object>> items = jdbcTemplate.queryForList(itemsSql, sprintId, projectId);

        int totalItems = items.size();
        int doneItems = 0;
        int inProgressItems = 0;
        int inReviewItems = 0;
        int toDoItems = 0;
        int blockedCount = 0;

        int totalEstimateMinutes = 0;
        int totalRemainingMinutes = 0;
        int totalSpentMinutes = 0;

        BigDecimal activeDonePoints = BigDecimal.ZERO;

        for (Map<String, Object> item : items) {
            String itemStatus = (String) item.get("status");
            if ("DONE".equalsIgnoreCase(itemStatus)) {
                doneItems++;
                if (item.get("story_points") != null) {
                    activeDonePoints = activeDonePoints.add((BigDecimal) item.get("story_points"));
                }
            } else if ("IN_PROGRESS".equalsIgnoreCase(itemStatus)) {
                inProgressItems++;
            } else if ("IN_REVIEW".equalsIgnoreCase(itemStatus)) {
                inReviewItems++;
            } else if ("TO_DO".equalsIgnoreCase(itemStatus)) {
                toDoItems++;
            }

            Boolean isBlocked = (Boolean) item.get("is_blocked");
            if (Boolean.TRUE.equals(isBlocked)) {
                blockedCount++;
            }

            Number est = (Number) item.get("estimate_minutes");
            if (est != null) {
                totalEstimateMinutes += est.intValue();
            }

            Number rem = (Number) item.get("remaining_minutes");
            if (rem != null) {
                totalRemainingMinutes += rem.intValue();
            }

            Number spent = (Number) item.get("time_spent_minutes");
            if (spent != null) {
                totalSpentMinutes += spent.intValue();
            }
        }

        if (!"CLOSED".equalsIgnoreCase(status)) {
            completedPoints = activeDonePoints;
        }

        BigDecimal remainingPoints = committedPoints.subtract(completedPoints);
        if (remainingPoints.compareTo(BigDecimal.ZERO) < 0) {
            remainingPoints = BigDecimal.ZERO;
        }

        return new SprintProgressResponse(
                sprintId,
                sprintName,
                status,
                committedPoints,
                completedPoints,
                remainingPoints,
                totalItems,
                doneItems,
                inProgressItems,
                inReviewItems,
                toDoItems,
                totalEstimateMinutes,
                totalRemainingMinutes,
                totalSpentMinutes,
                blockedCount
        );
    }
}

package com.project.devsphere.sprint.application;

import com.project.devsphere.identity.application.AccessPolicy;
import com.project.devsphere.identity.application.SessionService;
import com.project.devsphere.sprint.api.dto.SprintProgressResponse;
import com.project.devsphere.workspace.application.WorkspaceProjectException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SprintProgressServiceTest {

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private AccessPolicy accessPolicy;

    private SprintProgressService progressService;

    private final UUID userId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID sprintId = UUID.randomUUID();
    private SessionService.Principal principal;

    @BeforeEach
    void setUp() {
        progressService = new SprintProgressService(jdbcTemplate, accessPolicy);
        principal = new SessionService.Principal(userId, sessionId, "Test User", "USER");
    }

    @Test
    @DisplayName("Calculates accurate sprint progress for active sprint with mixed status items")
    void calculatesActiveSprintProgress() {
        Map<String, Object> sprintRow = Map.of(
                "id", sprintId,
                "name", "Sprint Alpha",
                "status", "ACTIVE",
                "committed_points", new BigDecimal("20.00"),
                "completed_points", BigDecimal.ZERO
        );

        when(jdbcTemplate.queryForList(contains("SELECT id, name, status, committed_points"), eq(sprintId), eq(projectId)))
                .thenReturn(List.of(sprintRow));

        List<Map<String, Object>> items = List.of(
                Map.of("status", "DONE", "story_points", new BigDecimal("5.00"), "estimate_minutes", 120, "remaining_minutes", 0, "time_spent_minutes", 110, "is_blocked", false),
                Map.of("status", "DONE", "story_points", new BigDecimal("3.00"), "estimate_minutes", 60, "remaining_minutes", 0, "time_spent_minutes", 70, "is_blocked", false),
                Map.of("status", "IN_PROGRESS", "story_points", new BigDecimal("4.00"), "estimate_minutes", 180, "remaining_minutes", 90, "time_spent_minutes", 90, "is_blocked", true),
                Map.of("status", "IN_REVIEW", "story_points", new BigDecimal("3.00"), "estimate_minutes", 90, "remaining_minutes", 20, "time_spent_minutes", 80, "is_blocked", false),
                Map.of("status", "TO_DO", "story_points", new BigDecimal("5.00"), "estimate_minutes", 150, "remaining_minutes", 150, "time_spent_minutes", 0, "is_blocked", false)
        );

        when(jdbcTemplate.queryForList(contains("SELECT status, story_points"), eq(sprintId), eq(projectId)))
                .thenReturn(items);

        SprintProgressResponse response = progressService.getSprintProgress(principal, workspaceId, projectId, sprintId);

        verify(accessPolicy).requireProjectMember(principal, workspaceId, projectId);

        assertThat(response.sprintId()).isEqualTo(sprintId);
        assertThat(response.sprintName()).isEqualTo("Sprint Alpha");
        assertThat(response.status()).isEqualTo("ACTIVE");
        assertThat(response.committedPoints()).isEqualByComparingTo("20.00");
        assertThat(response.completedPoints()).isEqualByComparingTo("8.00");
        assertThat(response.remainingPoints()).isEqualByComparingTo("12.00");

        assertThat(response.totalItems()).isEqualTo(5);
        assertThat(response.doneItems()).isEqualTo(2);
        assertThat(response.inProgressItems()).isEqualTo(1);
        assertThat(response.inReviewItems()).isEqualTo(1);
        assertThat(response.toDoItems()).isEqualTo(1);
        assertThat(response.blockedCount()).isEqualTo(1);

        assertThat(response.totalEstimateMinutes()).isEqualTo(600);
        assertThat(response.totalRemainingMinutes()).isEqualTo(260);
        assertThat(response.totalSpentMinutes()).isEqualTo(350);
    }

    @Test
    @DisplayName("Uses snapshotted completedPoints for CLOSED sprint")
    void usesSnapshottedPointsForClosedSprint() {
        Map<String, Object> sprintRow = Map.of(
                "id", sprintId,
                "name", "Sprint Beta (Closed)",
                "status", "CLOSED",
                "committed_points", new BigDecimal("25.00"),
                "completed_points", new BigDecimal("18.00")
        );

        when(jdbcTemplate.queryForList(contains("SELECT id, name, status, committed_points"), eq(sprintId), eq(projectId)))
                .thenReturn(List.of(sprintRow));

        // For closed sprint, only DONE items remain assigned
        List<Map<String, Object>> items = List.of(
                Map.of("status", "DONE", "story_points", new BigDecimal("10.00"), "estimate_minutes", 200, "remaining_minutes", 0, "time_spent_minutes", 190, "is_blocked", false),
                Map.of("status", "DONE", "story_points", new BigDecimal("8.00"), "estimate_minutes", 150, "remaining_minutes", 0, "time_spent_minutes", 160, "is_blocked", false)
        );

        when(jdbcTemplate.queryForList(contains("SELECT status, story_points"), eq(sprintId), eq(projectId)))
                .thenReturn(items);

        SprintProgressResponse response = progressService.getSprintProgress(principal, workspaceId, projectId, sprintId);

        assertThat(response.status()).isEqualTo("CLOSED");
        assertThat(response.committedPoints()).isEqualByComparingTo("25.00");
        assertThat(response.completedPoints()).isEqualByComparingTo("18.00");
        assertThat(response.remainingPoints()).isEqualByComparingTo("7.00");
        assertThat(response.totalItems()).isEqualTo(2);
        assertThat(response.doneItems()).isEqualTo(2);
    }

    @Test
    @DisplayName("Throws 404 when sprint is not found")
    void throwsNotFoundWhenSprintMissing() {
        when(jdbcTemplate.queryForList(contains("SELECT id, name, status, committed_points"), eq(sprintId), eq(projectId)))
                .thenReturn(List.of());

        assertThatThrownBy(() -> progressService.getSprintProgress(principal, workspaceId, projectId, sprintId))
                .isInstanceOf(WorkspaceProjectException.class)
                .hasMessageContaining("Sprint not found");
    }
}

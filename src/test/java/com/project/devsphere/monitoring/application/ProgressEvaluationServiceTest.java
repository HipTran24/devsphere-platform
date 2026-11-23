package com.project.devsphere.monitoring.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.devsphere.identity.application.AccessPolicy;
import com.project.devsphere.identity.application.SessionService;
import com.project.devsphere.monitoring.api.dto.ProgressEvaluationResponse;
import com.project.devsphere.workitem.domain.AcceptanceCriterion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProgressEvaluationServiceTest {

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private AccessPolicy accessPolicy;

    private ProgressEvaluator progressEvaluator;
    private ProgressEvaluationService service;

    private final UUID workspaceId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID workItemId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final SessionService.Principal principal = new SessionService.Principal(userId, UUID.randomUUID(), "Dev", "USER");

    @BeforeEach
    void setUp() {
        progressEvaluator = new ProgressEvaluator();
        service = new ProgressEvaluationService(jdbcTemplate, accessPolicy, progressEvaluator, new ObjectMapper());
    }

    @Test
    @DisplayName("evaluateProgress throws 404 when work item not found")
    void evaluateProgress_itemNotFound_throws404() {
        when(jdbcTemplate.queryForList(anyString(), eq(workItemId), eq(projectId)))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.evaluateProgress(principal, workspaceId, projectId, workItemId))
                .isInstanceOf(MonitoringException.class)
                .satisfies(ex -> {
                    MonitoringException me = (MonitoringException) ex;
                    assertThat(me.getStatus().value()).isEqualTo(404);
                    assertThat(me.getCode()).isEqualTo("RESOURCE_NOT_FOUND");
                });
    }

    @Test
    @DisplayName("evaluateProgress computes progress and executes observational update without changing status to DONE")
    void evaluateProgress_observationalUpdate_preservesStatus() {
        Map<String, Object> itemRow = Map.of(
                "id", workItemId,
                "project_id", projectId,
                "item_key", "WI-101",
                "title", "Implement feature",
                "status", "IN_PROGRESS",
                "work_kind", "NON_CODE",
                "claimed_progress", new BigDecimal("50.00"),
                "assignee_id", userId
        );

        when(jdbcTemplate.queryForList(contains("FROM work_items"), eq(workItemId), eq(projectId)))
                .thenReturn(List.of(itemRow));

        AcceptanceCriterion c1 = new AcceptanceCriterion();
        c1.setId(UUID.randomUUID());
        c1.setPosition(1);
        c1.setDescription("Criteria 1");
        c1.setWeight(BigDecimal.ONE);
        c1.setStatus(AcceptanceCriterion.Status.VERIFIED);

        AcceptanceCriterion c2 = new AcceptanceCriterion();
        c2.setId(UUID.randomUUID());
        c2.setPosition(2);
        c2.setDescription("Criteria 2");
        c2.setWeight(BigDecimal.ONE);
        c2.setStatus(AcceptanceCriterion.Status.NOT_MET);

        when(jdbcTemplate.query(contains("FROM acceptance_criteria"), any(RowMapper.class), eq(workItemId), eq(projectId)))
                .thenReturn(List.of(c1, c2));

        ProgressEvaluationResponse response = service.evaluateProgress(principal, workspaceId, projectId, workItemId);

        assertThat(response).isNotNull();
        assertThat(response.itemKey()).isEqualTo("WI-101");
        assertThat(response.verifiedProgress()).isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(response.currentStatus()).isEqualTo("IN_PROGRESS");

        // Verify update sets verified_progress and progress_evidence ONLY (never sets status = 'DONE')
        verify(jdbcTemplate).update(
                contains("SET verified_progress = ?"),
                eq(new BigDecimal("50.00")),
                anyString(),
                eq(workItemId),
                eq(projectId)
        );
    }
}

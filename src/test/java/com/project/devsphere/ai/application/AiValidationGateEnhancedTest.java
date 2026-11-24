package com.project.devsphere.ai.application;

import com.project.devsphere.ai.domain.AiJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiValidationGateEnhancedTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private AiValidationGate validationGate;
    private UUID projectId;

    @BeforeEach
    void setUp() {
        validationGate = new AiValidationGate(jdbcTemplate);
        projectId = UUID.randomUUID();
    }

    @Test
    @DisplayName("TC-23: REQUIREMENT_PLAN rejects zero and negative estimates")
    void requirementPlanRejectsNonPositiveEstimates() {
        AiJob job = new AiJob();
        job.setJobType(AiJob.JobType.REQUIREMENT_PLAN);

        Map<String, Object> outputJson = Map.of(
                "items", List.of(
                        Map.of("tempKey", "TASK-1", "estimateMinutes", 0, "storyPoints", -2)
                )
        );

        AiValidationGate.ValidationResult result = validationGate.validate(job, outputJson, projectId);
        assertThat(result.valid()).isFalse();
        assertThat(result.status()).isEqualTo(AiJob.ValidationStatus.INVALID);
        assertThat(result.report().get("invalidEstimates")).asList().hasSize(2);
    }

    @Test
    @DisplayName("TC-23: REQUIREMENT_PLAN detects dependency cycles across dependencies and dependsOn")
    void requirementPlanDetectsDependencyCycle() {
        AiJob job = new AiJob();
        job.setJobType(AiJob.JobType.REQUIREMENT_PLAN);

        Map<String, Object> outputWithExplicitDeps = Map.of(
                "proposal_payload", Map.of(
                        "items", List.of(
                                Map.of("tempKey", "T1", "estimateMinutes", 60),
                                Map.of("tempKey", "T2", "estimateMinutes", 90),
                                Map.of("tempKey", "T3", "estimateMinutes", 30)
                        ),
                        "dependencies", List.of(
                                Map.of("predecessorKey", "T1", "successorKey", "T2"),
                                Map.of("predecessorKey", "T2", "successorKey", "T3"),
                                Map.of("predecessorKey", "T3", "successorKey", "T1")
                        )
                )
        );

        AiValidationGate.ValidationResult cycleResult = validationGate.validate(job, outputWithExplicitDeps, projectId);
        assertThat(cycleResult.valid()).isFalse();
        assertThat(cycleResult.status()).isEqualTo(AiJob.ValidationStatus.INVALID);
        assertThat(cycleResult.report().get("dependencyCycleDetected")).isEqualTo(true);

        Map<String, Object> outputWithItemDependsOnCycle = Map.of(
                "items", List.of(
                        Map.of("tempKey", "A", "storyPoints", 2, "dependsOn", List.of("B")),
                        Map.of("tempKey", "B", "storyPoints", 3, "dependsOn", List.of("A"))
                )
        );
        AiValidationGate.ValidationResult itemCycleResult = validationGate.validate(job, outputWithItemDependsOnCycle, projectId);
        assertThat(itemCycleResult.valid()).isFalse();
        assertThat(itemCycleResult.status()).isEqualTo(AiJob.ValidationStatus.INVALID);
        assertThat(itemCycleResult.report().get("dependencyCycleDetected")).isEqualTo(true);
    }

    @Test
    @DisplayName("TC-23: REQUIREMENT_PLAN accepts positive estimates with acyclic DAG")
    void requirementPlanAcceptsPositiveAcyclicPlan() {
        AiJob job = new AiJob();
        job.setJobType(AiJob.JobType.REQUIREMENT_PLAN);

        Map<String, Object> validPlan = Map.of(
                "items", List.of(
                        Map.of("tempKey", "T1", "estimateMinutes", 120, "storyPoints", 3),
                        Map.of("tempKey", "T2", "estimateMinutes", 60, "storyPoints", 2, "dependsOn", List.of("T1"))
                ),
                "dependencies", List.of(
                        Map.of("predecessorKey", "T1", "successorKey", "T2")
                )
        );

        AiValidationGate.ValidationResult result = validationGate.validate(job, validPlan, projectId);
        assertThat(result.valid()).isTrue();
        assertThat(result.status()).isEqualTo(AiJob.ValidationStatus.VALID);
    }

    @Test
    @DisplayName("TC-24: PR_REVIEW and PUSH_REVIEW reject findings targeting out-of-diff files or out-of-bounds line numbers")
    void codeReviewValidatesDiffFilesAndLineBounds() {
        AiJob job = new AiJob();
        job.setJobType(AiJob.JobType.PR_REVIEW);
        job.setInputRefs(Map.of(
                "schemaVersion", 1,
                "diffFiles", Map.of("src/main/App.java", 40)
        ));

        Map<String, Object> outOfDiffOutput = Map.of(
                "status", "REQUIRES_CHANGES",
                "findings", List.of(
                        Map.of("filePath", "src/main/Secret.java", "lineNumber", 5, "message", "Hallucinated file"),
                        Map.of("filePath", "src/main/App.java", "lineNumber", 120, "message", "Line past EOF")
                )
        );
        AiValidationGate.ValidationResult invalidResult = validationGate.validate(job, outOfDiffOutput, projectId);
        assertThat(invalidResult.valid()).isFalse();
        assertThat(invalidResult.status()).isEqualTo(AiJob.ValidationStatus.INVALID);
        assertThat(invalidResult.report().get("invalidFindings")).asList().hasSize(2);

        Map<String, Object> validReviewOutput = Map.of(
                "status", "PASS",
                "findings", List.of(
                        Map.of("filePath", "src/main/App.java", "lineNumber", 15, "message", "Looks clean")
                )
        );
        AiValidationGate.ValidationResult validResult = validationGate.validate(job, validReviewOutput, projectId);
        assertThat(validResult.valid()).isTrue();
        assertThat(validResult.status()).isEqualTo(AiJob.ValidationStatus.VALID);
    }

    @Test
    @DisplayName("TC-25: REPORT_SUMMARY marks fabricated metrics as NEEDS_REVIEW with factMismatches, and passes matching metrics")
    void reportSummaryCrossChecksMetricsAgainstDatabase() {
        AiJob job = new AiJob();
        job.setJobType(AiJob.JobType.REPORT_SUMMARY);

        when(jdbcTemplate.queryForObject(contains("FROM work_items WHERE project_id = ? AND deleted_at IS NULL"), eq(Long.class), eq(projectId)))
                .thenReturn(4L);
        when(jdbcTemplate.queryForObject(contains("status = 'DONE'"), eq(Long.class), eq(projectId)))
                .thenReturn(1L);
        when(jdbcTemplate.queryForObject(contains("FROM alerts WHERE project_id = ? AND status <> 'RESOLVED'"), eq(Long.class), eq(projectId)))
                .thenReturn(2L);
        when(jdbcTemplate.queryForObject(contains("FROM sprints WHERE project_id = ? AND status = 'ACTIVE'"), eq(Long.class), eq(projectId)))
                .thenReturn(1L);

        Map<String, Object> fabricatedOutput = Map.of(
                "summary", "All tasks done!",
                "metrics", Map.of(
                        "totalWorkItems", 10,
                        "completedWorkItems", 10,
                        "openAlerts", 0,
                        "activeSprints", 1
                )
        );

        AiValidationGate.ValidationResult mismatchResult = validationGate.validate(job, fabricatedOutput, projectId);
        assertThat(mismatchResult.valid()).isFalse();
        assertThat(mismatchResult.status()).isEqualTo(AiJob.ValidationStatus.NEEDS_REVIEW);
        assertThat(mismatchResult.report().get("factMismatches")).asList().hasSize(3);

        Map<String, Object> accurateOutput = Map.of(
                "summary", "1 of 4 work items done, 2 open alerts, 1 active sprint",
                "metrics", Map.of(
                        "totalWorkItems", 4,
                        "completedWorkItems", 1,
                        "openAlerts", 2,
                        "activeSprints", 1
                )
        );

        AiValidationGate.ValidationResult validResult = validationGate.validate(job, accurateOutput, projectId);
        assertThat(validResult.valid()).isTrue();
        assertThat(validResult.status()).isEqualTo(AiJob.ValidationStatus.VALID);
    }
}

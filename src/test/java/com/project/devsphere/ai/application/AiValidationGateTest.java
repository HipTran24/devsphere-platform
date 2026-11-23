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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiValidationGateTest {

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
    @DisplayName("Empty or null outputJson returns INVALID")
    void emptyOutputReturnsInvalid() {
        AiJob job = new AiJob();
        job.setJobType(AiJob.JobType.REQUIREMENT_PLAN);

        AiValidationGate.ValidationResult resultNull = validationGate.validate(job, null, projectId);
        assertThat(resultNull.valid()).isFalse();
        assertThat(resultNull.status()).isEqualTo(AiJob.ValidationStatus.INVALID);

        AiValidationGate.ValidationResult resultEmpty = validationGate.validate(job, Map.of(), projectId);
        assertThat(resultEmpty.valid()).isFalse();
        assertThat(resultEmpty.status()).isEqualTo(AiJob.ValidationStatus.INVALID);
    }

    @Test
    @DisplayName("Hallucinated UUID in outputJson not present in inputRefs or DB returns INVALID")
    void hallucinatedReferenceReturnsInvalid() {
        UUID fakeId = UUID.randomUUID();

        AiJob job = new AiJob();
        job.setJobType(AiJob.JobType.REQUIREMENT_PLAN);
        job.setInputRefs(Map.of("schemaVersion", 1));

        // DB returns 0 for all checks
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(0);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(0);

        Map<String, Object> outputJson = Map.of(
                "items", List.of(
                        Map.of("tempKey", "TASK-1", "targetId", fakeId.toString())
                )
        );

        AiValidationGate.ValidationResult result = validationGate.validate(job, outputJson, projectId);
        assertThat(result.valid()).isFalse();
        assertThat(result.status()).isEqualTo(AiJob.ValidationStatus.INVALID);
        assertThat(result.report().get("hallucinatedReferences")).asList().contains(fakeId.toString());
    }

    @Test
    @DisplayName("UUID present in inputRefs is whitelisted and returns VALID")
    void whitelistedReferenceInInputRefsReturnsValid() {
        UUID validId = UUID.randomUUID();

        AiJob job = new AiJob();
        job.setJobType(AiJob.JobType.REQUIREMENT_PLAN);
        job.setInputRefs(Map.of(
                "schemaVersion", 1,
                "knownTaskIds", List.of(validId.toString())
        ));

        Map<String, Object> outputJson = Map.of(
                "items", List.of(
                        Map.of("tempKey", "TASK-1", "targetId", validId.toString())
                )
        );

        AiValidationGate.ValidationResult result = validationGate.validate(job, outputJson, projectId);
        assertThat(result.valid()).isTrue();
        assertThat(result.status()).isEqualTo(AiJob.ValidationStatus.VALID);
        assertThat(result.report().get("checkedReferencesCount")).isEqualTo(1);
    }

    @Test
    @DisplayName("UUID verified against database returns VALID")
    void databaseExistingReferenceReturnsValid() {
        UUID existingWorkItemId = UUID.randomUUID();

        AiJob job = new AiJob();
        job.setJobType(AiJob.JobType.REPORT_SUMMARY);
        job.setInputRefs(Map.of("schemaVersion", 1));

        // Mock DB: work item exists
        when(jdbcTemplate.queryForObject(
                contains("FROM work_items"),
                eq(Integer.class),
                eq(existingWorkItemId),
                eq(projectId)
        )).thenReturn(1);

        Map<String, Object> outputJson = Map.of(
                "summary", "Sprint completed cleanly",
                "highlightedTask", existingWorkItemId.toString()
        );

        AiValidationGate.ValidationResult result = validationGate.validate(job, outputJson, projectId);
        assertThat(result.valid()).isTrue();
        assertThat(result.status()).isEqualTo(AiJob.ValidationStatus.VALID);
    }
}

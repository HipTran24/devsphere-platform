package com.project.devsphere.codequality.application;

import com.project.devsphere.codequality.domain.CiRun;
import com.project.devsphere.codequality.infrastructure.persistence.CiRunRepository;
import com.project.devsphere.identity.application.AccessPolicy;
import com.project.devsphere.identity.application.SessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CiRunServiceTest {

    @Mock private CiRunRepository ciRunRepository;
    @Mock private AccessPolicy accessPolicy;
    @Mock private JdbcTemplate jdbcTemplate;

    private CiRunService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID repositoryId = UUID.randomUUID();
    private final UUID workItemId = UUID.randomUUID();
    private final String validSha = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2";
    private SessionService.Principal principal;

    @BeforeEach
    void setUp() {
        service = new CiRunService(ciRunRepository, accessPolicy, jdbcTemplate);
        principal = new SessionService.Principal(userId, sessionId, "Developer", "USER");

        when(jdbcTemplate.query(contains("FROM github_repositories"), any(RowMapper.class), eq(projectId)))
                .thenReturn(List.of(repositoryId));
    }

    @Test
    @DisplayName("recordCiRun succeeds with valid parameters and COMPLETED status")
    void recordCiRun_success() {
        Instant startedAt = Instant.now().minusSeconds(120);
        Instant completedAt = Instant.now();

        RecordCiRunCommand command = new RecordCiRunCommand(
                repositoryId,
                1001L,
                1,
                validSha,
                42,
                workItemId,
                "COMPLETED",
                "SUCCESS",
                List.of(Map.of("name", "build", "status", "SUCCESS")),
                Map.of("schemaVersion", 1, "coverage", 85.5),
                startedAt,
                completedAt
        );

        when(ciRunRepository.findByRepositoryIdAndGithubRunIdAndRunAttempt(repositoryId, 1001L, 1))
                .thenReturn(Optional.empty());
        when(ciRunRepository.save(any(CiRun.class))).thenAnswer(inv -> inv.getArgument(0));

        CiRun run = service.recordCiRun(principal, workspaceId, projectId, command);

        assertThat(run).isNotNull();
        assertThat(run.getProjectId()).isEqualTo(projectId);
        assertThat(run.getRepositoryId()).isEqualTo(repositoryId);
        assertThat(run.getGithubRunId()).isEqualTo(1001L);
        assertThat(run.getRunAttempt()).isEqualTo(1);
        assertThat(run.getHeadSha()).isEqualTo(validSha.toLowerCase());
        assertThat(run.getStatus()).isEqualTo(CiRun.Status.COMPLETED);
        assertThat(run.getConclusion()).isEqualTo(CiRun.Conclusion.SUCCESS);
        assertThat(run.getCompletedAt()).isEqualTo(completedAt);

        ArgumentCaptor<CiRun> captor = ArgumentCaptor.forClass(CiRun.class);
        verify(ciRunRepository).save(captor.capture());
        assertThat(captor.getValue().getConclusion()).isEqualTo(CiRun.Conclusion.SUCCESS);
    }

    @Test
    @DisplayName("recordCiRun rejects invalid or malformed headSha")
    void recordCiRun_invalidHeadSha_throwsException() {
        RecordCiRunCommand shortSha = new RecordCiRunCommand(
                1001L, 1, "abc1234", 1, "QUEUED", null, null, null, null, null
        );

        assertThatThrownBy(() -> service.recordCiRun(principal, workspaceId, projectId, shortSha))
                .isInstanceOf(CodeQualityException.class)
                .hasMessageContaining("40-character hex")
                .satisfies(ex -> assertThat(((CodeQualityException) ex).getCode()).isEqualTo("INVALID_HEAD_SHA"));

        verify(ciRunRepository, never()).save(any());
    }

    @Test
    @DisplayName("recordCiRun enforces constraint: COMPLETED status requires non-null conclusion")
    void recordCiRun_completedWithoutConclusion_throwsValidationFailed() {
        RecordCiRunCommand command = new RecordCiRunCommand(
                repositoryId, 1001L, 1, validSha, 1, workItemId, "COMPLETED", null, null, null, null, Instant.now()
        );

        assertThatThrownBy(() -> service.recordCiRun(principal, workspaceId, projectId, command))
                .isInstanceOf(CodeQualityException.class)
                .hasMessageContaining("COMPLETED status requires a non-null conclusion")
                .satisfies(ex -> assertThat(((CodeQualityException) ex).getCode()).isEqualTo("VALIDATION_FAILED"));

        verify(ciRunRepository, never()).save(any());
    }

    @Test
    @DisplayName("recordCiRun enforces constraint: completedAt must not be before startedAt")
    void recordCiRun_completedBeforeStarted_throwsValidationFailed() {
        Instant now = Instant.now();
        RecordCiRunCommand command = new RecordCiRunCommand(
                repositoryId, 1001L, 1, validSha, 1, workItemId, "COMPLETED", "SUCCESS", null, null, now, now.minusSeconds(60)
        );

        assertThatThrownBy(() -> service.recordCiRun(principal, workspaceId, projectId, command))
                .isInstanceOf(CodeQualityException.class)
                .hasMessageContaining("completedAt must be greater than or equal to startedAt");

        verify(ciRunRepository, never()).save(any());
    }

    @Test
    @DisplayName("recordCiRun updates existing record on duplicate delivery (idempotent upsert)")
    void recordCiRun_idempotentUpsert_updatesExisting() {
        CiRun existing = new CiRun();
        existing.setId(UUID.randomUUID());
        existing.setProjectId(projectId);
        existing.setRepositoryId(repositoryId);
        existing.setGithubRunId(1001L);
        existing.setRunAttempt(1);
        existing.setStatus(CiRun.Status.IN_PROGRESS);

        when(ciRunRepository.findByRepositoryIdAndGithubRunIdAndRunAttempt(repositoryId, 1001L, 1))
                .thenReturn(Optional.of(existing));
        when(ciRunRepository.save(any(CiRun.class))).thenAnswer(inv -> inv.getArgument(0));

        RecordCiRunCommand updateCmd = new RecordCiRunCommand(
                repositoryId, 1001L, 1, validSha, 42, workItemId, "COMPLETED", "SUCCESS", null, null, null, Instant.now()
        );

        CiRun updated = service.recordCiRun(principal, workspaceId, projectId, updateCmd);

        assertThat(updated.getStatus()).isEqualTo(CiRun.Status.COMPLETED);
        assertThat(updated.getConclusion()).isEqualTo(CiRun.Conclusion.SUCCESS);
        assertThat(updated.getHeadSha()).isEqualTo(validSha.toLowerCase());
    }

    @Test
    @DisplayName("getCiRun throws 404 when CI run is not found")
    void getCiRun_notFound_throws404() {
        UUID runId = UUID.randomUUID();
        when(ciRunRepository.findByIdAndProjectId(runId, projectId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getCiRun(principal, workspaceId, projectId, runId))
                .isInstanceOf(CodeQualityException.class)
                .hasMessageContaining("CI run not found")
                .satisfies(ex -> assertThat(((CodeQualityException) ex).getCode()).isEqualTo("CI_RUN_NOT_FOUND"));
    }
}

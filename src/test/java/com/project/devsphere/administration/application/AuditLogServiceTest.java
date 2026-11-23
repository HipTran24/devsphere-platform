package com.project.devsphere.administration.application;

import com.project.devsphere.administration.domain.AuditLog;
import com.project.devsphere.administration.infrastructure.persistence.AuditLogRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditLogServiceTest {

    @Mock private AuditLogRepository repository;
    @Mock private JdbcTemplate jdbcTemplate;

    private AuditLogService service;

    @BeforeEach
    void setUp() {
        service = new AuditLogService(repository, jdbcTemplate);
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void bindsActorWithParameterisedSetConfigAndPersistsTraceId() {
        UUID actor = UUID.randomUUID();
        UUID entity = UUID.randomUUID();
        MDC.put("traceId", "trace-123");
        when(repository.save(any(AuditLog.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AuditLog saved = service.record(actor, "  QUARTZ_JOB_TRIGGERED ", "quartz_job", entity, Map.of("jobName", "webhookRetry"));

        verify(jdbcTemplate).queryForObject("SELECT set_config('devsphere.actor_id', ?, true)", String.class, actor.toString());
        assertThat(saved.getActorId()).isEqualTo(actor);
        assertThat(saved.getAction()).isEqualTo("QUARTZ_JOB_TRIGGERED");
        assertThat(saved.getEntityType()).isEqualTo("quartz_job");
        assertThat(saved.getEntityId()).isEqualTo(entity);
        assertThat(saved.getTraceId()).isEqualTo("trace-123");
        assertThat(saved.getOccurredAt()).isNotNull();
        assertThat(saved.getMetadata())
                .containsEntry("schemaVersion", 1)
                .containsEntry("source", "application")
                .containsEntry("jobName", "webhookRetry");
    }

    @Test
    void systemEventsWithoutActorDoNotTouchSessionSettings() {
        when(repository.save(any(AuditLog.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AuditLog saved = service.record(null, "BACKUP_VERIFIED", "backup", null, null);

        verifyNoInteractions(jdbcTemplate);
        assertThat(saved.getActorId()).isNull();
        assertThat(saved.getTraceId()).isNull();
    }

    @Test
    void ignoresUnsafeTraceIdentifiersFromMdc() {
        MDC.put("traceId", "bad trace\nwith newline");
        when(repository.save(any(AuditLog.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AuditLog saved = service.record(null, "ACTION", "entity", null, Map.of());

        assertThat(saved.getTraceId()).isNull();
    }

    @Test
    void callerMetadataCannotOverrideReservedKeys() {
        when(repository.save(any(AuditLog.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AuditLog saved = service.record(null, "ACTION", "entity", null, Map.of("schemaVersion", 99, "source", "database"));

        assertThat(saved.getMetadata()).containsEntry("schemaVersion", 1).containsEntry("source", "application");
    }

    @Test
    void recordsTenantScopedBeforeAndAfterSnapshots() {
        UUID workspace = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        when(repository.save(captor.capture())).thenAnswer(invocation -> invocation.getArgument(0));

        service.record(new AuditLogService.AuditEvent(workspace, project, null, "STATUS_CHANGED", "work_item", null,
                Map.of("status", "TO_DO"), Map.of("status", "DONE"), null));

        AuditLog log = captor.getValue();
        assertThat(log.getWorkspaceId()).isEqualTo(workspace);
        assertThat(log.getProjectId()).isEqualTo(project);
        assertThat(log.getBeforeJson()).containsEntry("status", "TO_DO");
        assertThat(log.getAfterJson()).containsEntry("status", "DONE");
    }

    @Test
    void rejectsBlankOrOversizedActionAndEntityType() {
        assertThatThrownBy(() -> service.record(null, " ", "entity", null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("action");
        assertThatThrownBy(() -> service.record(null, null, "entity", null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("action");
        assertThatThrownBy(() -> service.record(null, "ACTION", "", null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("entityType");
        assertThatThrownBy(() -> service.record(null, "A".repeat(121), "entity", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.record(null, "ACTION", "e".repeat(101), null, null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).save(any());
        verify(jdbcTemplate, never()).queryForObject(anyString(), eq(String.class), any(Object[].class));
    }

    @Test
    void rejectsSecretBearingDocumentsAtAnyDepth() {
        assertThatThrownBy(() -> service.record(null, "ACTION", "entity", null, Map.of("api_key", "x")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("secret");
        assertThatThrownBy(() -> service.record(null, "ACTION", "entity", null,
                Map.of("nested", List.of(Map.of("accessToken", "y")))))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void projectScopedEventsRequireWorkspace() {
        assertThatThrownBy(() -> service.record(new AuditLogService.AuditEvent(null, UUID.randomUUID(), null,
                "ACTION", "entity", null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("workspaceId");
    }
}

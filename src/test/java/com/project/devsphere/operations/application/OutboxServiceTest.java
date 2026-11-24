package com.project.devsphere.operations.application;

import com.project.devsphere.operations.domain.BackgroundJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxServiceTest {

    private RecordingJdbc jdbc;
    private OutboxService service;

    @BeforeEach
    void setUp() {
        jdbc = new RecordingJdbc();
        service = new OutboxService(jdbc);
    }

    @Test
    void requestHashIsDeterministicAndIndependentOfKeyOrder() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("b", 2);
        first.put("a", Map.of("y", "2", "x", "1"));
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("a", Map.of("x", "1", "y", "2"));
        second.put("b", 2);

        String hash = OutboxService.requestHash(first);

        assertThat(hash).matches("^[0-9a-f]{64}$");
        assertThat(OutboxService.requestHash(second)).isEqualTo(hash);
        assertThat(OutboxService.requestHash(Map.of("b", 3))).isNotEqualTo(hash);
    }

    @Test
    void idempotencyKeysAreDeterministicAndBounded() {
        assertThat(OutboxService.idempotencyKey("notification-email", " abc ")).isEqualTo("notification-email:abc");
        assertThat(OutboxService.idempotencyKey("a", null, "c")).isEqualTo("a::c");

        String longKey = OutboxService.idempotencyKey("prefix", "x".repeat(400));
        assertThat(longKey).hasSize(OutboxService.MAX_IDEMPOTENCY_KEY_LENGTH).startsWith("prefix:xxx").contains("#");
        assertThat(OutboxService.idempotencyKey("prefix", "x".repeat(400))).isEqualTo(longKey);
        assertThat(OutboxService.idempotencyKey("prefix", "x".repeat(401))).isNotEqualTo(longKey);
    }

    @Test
    void enqueueInsertsJobWithCanonicalPayloadAndHash() {
        UUID workspace = UUID.randomUUID();
        UUID project = UUID.randomUUID();

        OutboxService.EnqueueResult result = service.enqueueJob(new OutboxService.EnqueueRequest(
                BackgroundJob.JobKind.REPORT_EXPORT, workspace, project, null, "daily-report:p:2026-11-22",
                Map.of("reportType", "DAILY", "periodDate", "2026-11-22"), null));

        assertThat(result.created()).isTrue();
        assertThat(result.jobId()).isNotNull();
        assertThat(result.requestHash()).matches("^[0-9a-f]{64}$");
        Object[] insert = jdbc.inserts.getFirst();
        assertThat(insert[0]).isEqualTo(workspace);
        assertThat(insert[1]).isEqualTo(project);
        assertThat(insert[3]).isEqualTo("REPORT_EXPORT");
        assertThat(insert[4]).isEqualTo("daily-report:p:2026-11-22");
        assertThat(insert[5]).isEqualTo(result.requestHash());
        assertThat((String) insert[6]).isEqualTo("{\"periodDate\":\"2026-11-22\",\"reportType\":\"DAILY\",\"schemaVersion\":1}");
    }

    @Test
    void replayWithSamePayloadReturnsExistingJob() {
        OutboxService.EnqueueRequest request = new OutboxService.EnqueueRequest(
                BackgroundJob.JobKind.BACKUP_CHECK, null, null, null, "backup-check:2026-11-23", Map.of("checkDate", "2026-11-23"), null);

        OutboxService.EnqueueResult first = service.enqueueJob(request);
        OutboxService.EnqueueResult replay = service.enqueueJob(request);

        assertThat(replay.created()).isFalse();
        assertThat(replay.jobId()).isEqualTo(first.jobId());
        assertThat(jdbc.rows).hasSize(1);
    }

    @Test
    void sameKeyWithDifferentPayloadIsAConflict() {
        service.enqueueJob(new OutboxService.EnqueueRequest(
                BackgroundJob.JobKind.BACKUP_CHECK, null, null, null, "backup-check:2026-11-23", Map.of("checkDate", "2026-11-23"), null));

        assertThatThrownBy(() -> service.enqueueJob(new OutboxService.EnqueueRequest(
                BackgroundJob.JobKind.BACKUP_CHECK, null, null, null, "backup-check:2026-11-23", Map.of("checkDate", "2026-11-24"), null)))
                .isInstanceOf(OperationsException.class)
                .satisfies(error -> assertThat(((OperationsException) error).getCode()).isEqualTo("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void outboxEventsDeriveKeyFromEventAggregateAndDiscriminator() {
        UUID workspace = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        UUID item = UUID.randomUUID();
        Map<String, Object> data = new HashMap<>();
        data.put("assigneeId", null);

        OutboxService.EnqueueResult result = service.enqueueOutboxEvent(
                workspace, project, null, "WORK_ITEM_OVERDUE", "work_item", item, "2026-11-23", data);

        assertThat(result.idempotencyKey()).isEqualTo("outbox:WORK_ITEM_OVERDUE:work_item:" + item + ":2026-11-23");
        assertThat((String) jdbc.inserts.getFirst()[6])
                .contains("\"eventType\":\"WORK_ITEM_OVERDUE\"")
                .contains("\"aggregateId\":\"" + item + "\"")
                .contains("\"data\":{\"assigneeId\":null}");
    }

    @Test
    void rejectsInvalidRequestsBeforeTouchingTheDatabase() {
        assertThatThrownBy(() -> service.enqueueJob(new OutboxService.EnqueueRequest(
                BackgroundJob.JobKind.AUTH_VERIFY_EMAIL, null, null, null, "k", Map.of(), null)))
                .isInstanceOf(OperationsException.class);
        assertThatThrownBy(() -> service.enqueueJob(new OutboxService.EnqueueRequest(
                BackgroundJob.JobKind.IDEMPOTENCY, null, null, null, "k", Map.of(), null)))
                .isInstanceOf(OperationsException.class);
        assertThatThrownBy(() -> service.enqueueJob(new OutboxService.EnqueueRequest(
                BackgroundJob.JobKind.OUTBOX_EVENT, null, UUID.randomUUID(), null, "k", Map.of(), null)))
                .hasMessageContaining("workspaceId");
        assertThatThrownBy(() -> service.enqueueJob(new OutboxService.EnqueueRequest(
                BackgroundJob.JobKind.OUTBOX_EVENT, null, null, null, " ", Map.of(), null)))
                .hasMessageContaining("idempotencyKey");
        assertThatThrownBy(() -> service.enqueueJob(new OutboxService.EnqueueRequest(
                BackgroundJob.JobKind.OUTBOX_EVENT, null, null, null, "k".repeat(181), Map.of(), null)))
                .hasMessageContaining("idempotencyKey");
        assertThatThrownBy(() -> service.enqueueJob(new OutboxService.EnqueueRequest(
                BackgroundJob.JobKind.OUTBOX_EVENT, null, null, null, "k", Map.of("nested", Map.of("client_secret", "s")), null)))
                .hasMessageContaining("secret");
        assertThatThrownBy(() -> service.enqueueOutboxEvent(null, null, null, "lower-case", "work_item", null, null, Map.of()))
                .hasMessageContaining("eventType");
        assertThat(jdbc.inserts).isEmpty();
    }

    /** Minimal in-memory stand-in for background_jobs honouring the uq_job_idempotency semantics. */
    private static final class RecordingJdbc extends JdbcTemplate {
        final List<Object[]> inserts = new ArrayList<>();
        final Map<List<Object>, Map<String, Object>> rows = new HashMap<>();

        @Override
        @SuppressWarnings("unchecked")
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            assertThat(sql).contains("insert into background_jobs").contains("on conflict");
            inserts.add(args);
            List<Object> key = Arrays.asList(args[0], args[1], args[3], args[4]);
            if (rows.containsKey(key)) {
                return List.of();
            }
            UUID id = UUID.randomUUID();
            Map<String, Object> row = new HashMap<>();
            row.put("id", id);
            row.put("request_hash", args[5]);
            rows.put(key, row);
            return (List<T>) List.of(id);
        }

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            assertThat(sql).contains("select id,request_hash from background_jobs");
            Map<String, Object> row = rows.get(Arrays.asList(args[0], args[1], args[2], args[3]));
            return row == null ? List.of() : List.of(row);
        }
    }
}

package com.project.devsphere.operations.application;

import com.project.devsphere.operations.domain.BackgroundJob;
import com.project.devsphere.operations.infrastructure.persistence.JobQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxDispatcherServiceTest {

    private JobQueue queue;
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        queue = mock(JobQueue.class);
        transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
    }

    @ParameterizedTest
    @CsvSource({"0,30", "1,60", "2,120", "3,240", "4,480", "5,960", "6,1800", "7,1800", "30,1800", "-1,30"})
    void backoffGrowsExponentiallyAndIsCappedAt1800Seconds(int attempts, int expected) {
        assertThat(OutboxDispatcherService.backoffSeconds(attempts)).isEqualTo(expected);
    }

    @Test
    void successfulJobsCompleteWithSerialisedResultInsideTheTransaction() {
        UUID jobId = UUID.randomUUID();
        RecordingHandler handler = new RecordingHandler(BackgroundJob.JobKind.BACKUP_CHECK,
                job -> JobResult.of(Map.of("outcome", "HEALTHY")));
        OutboxDispatcherService dispatcher = dispatcher(handler);
        stubSingleClaim(BackgroundJob.JobKind.BACKUP_CHECK, jobId, dispatcher.workerId(), 1, "{\"schemaVersion\":1,\"checkDate\":\"2026-11-23\"}");

        OutboxDispatcherService.DispatchSummary summary = dispatcher.dispatchKind(BackgroundJob.JobKind.BACKUP_CHECK, 10);

        assertThat(summary.claimed()).isEqualTo(1);
        assertThat(summary.succeeded()).isEqualTo(1);
        assertThat(summary.failed()).isZero();
        assertThat(summary.processedByKind()).containsEntry("BACKUP_CHECK", 1);
        assertThat(handler.seen.getFirst().payload()).containsEntry("checkDate", "2026-11-23");
        ArgumentCaptor<String> result = ArgumentCaptor.forClass(String.class);
        verify(queue).complete(eq(jobId), eq(dispatcher.workerId()), result.capture());
        assertThat(result.getValue()).contains("\"outcome\":\"HEALTHY\"").contains("\"schemaVersion\":1");
        verify(transactionManager).commit(any());
        verify(queue, never()).fail(any(), anyString(), anyString(), anyInt());
    }

    @Test
    void retryableFailuresRescheduleWithBackoff() {
        UUID jobId = UUID.randomUUID();
        OutboxDispatcherService dispatcher = dispatcher(new RecordingHandler(BackgroundJob.JobKind.NOTIFICATION_EMAIL,
                job -> { throw new IllegalStateException("MAIL_SEND_ERROR"); }));
        stubSingleClaim(BackgroundJob.JobKind.NOTIFICATION_EMAIL, jobId, dispatcher.workerId(), 2, "{}");

        OutboxDispatcherService.DispatchSummary summary = dispatcher.dispatchKind(BackgroundJob.JobKind.NOTIFICATION_EMAIL, 5);

        assertThat(summary.failed()).isEqualTo(1);
        assertThat(summary.deadLettered()).isZero();
        verify(queue).fail(jobId, dispatcher.workerId(), "IllegalStateException: MAIL_SEND_ERROR", 120);
        verify(queue, never()).complete(any(), anyString(), anyString());
        verify(transactionManager).rollback(any());
    }

    @Test
    void lastAllowedAttemptIsDeadLettered() {
        UUID jobId = UUID.randomUUID();
        OutboxDispatcherService dispatcher = dispatcher(new RecordingHandler(BackgroundJob.JobKind.WEBHOOK_RETRY,
                job -> { throw new IllegalStateException("still broken"); }));
        stubSingleClaim(BackgroundJob.JobKind.WEBHOOK_RETRY, jobId, dispatcher.workerId(), JobQueue.MAX_ATTEMPTS, "{}");

        OutboxDispatcherService.DispatchSummary summary = dispatcher.dispatchKind(BackgroundJob.JobKind.WEBHOOK_RETRY, 5);

        assertThat(summary.deadLettered()).isEqualTo(1);
        verify(queue).failPermanently(jobId, dispatcher.workerId(), "IllegalStateException: still broken");
        verify(queue, never()).fail(any(), anyString(), anyString(), anyInt());
    }

    @Test
    void nonRetryableFailuresAreDeadLetteredImmediately() {
        UUID jobId = UUID.randomUUID();
        OutboxDispatcherService dispatcher = dispatcher(new RecordingHandler(BackgroundJob.JobKind.REPORT_EXPORT,
                job -> job.requireString("reportType") == null ? null : JobResult.of(Map.of())));
        stubSingleClaim(BackgroundJob.JobKind.REPORT_EXPORT, jobId, dispatcher.workerId(), 1, "{\"schemaVersion\":1}");

        OutboxDispatcherService.DispatchSummary summary = dispatcher.dispatchKind(BackgroundJob.JobKind.REPORT_EXPORT, 5);

        assertThat(summary.deadLettered()).isEqualTo(1);
        verify(queue).failPermanently(eq(jobId), eq(dispatcher.workerId()), org.mockito.ArgumentMatchers.contains("reportType"));
    }

    @Test
    void malformedStoredPayloadIsDeadLettered() {
        UUID jobId = UUID.randomUUID();
        RecordingHandler handler = new RecordingHandler(BackgroundJob.JobKind.OUTBOX_EVENT, job -> JobResult.of(Map.of()));
        OutboxDispatcherService dispatcher = dispatcher(handler);
        stubSingleClaim(BackgroundJob.JobKind.OUTBOX_EVENT, jobId, dispatcher.workerId(), 1, "{not-json");

        dispatcher.dispatchKind(BackgroundJob.JobKind.OUTBOX_EVENT, 5);

        assertThat(handler.seen).isEmpty();
        verify(queue).failPermanently(eq(jobId), eq(dispatcher.workerId()), anyString());
    }

    @Test
    void batchIsBoundedAndRoundRobinsAcrossHandlerKinds() {
        AtomicInteger outboxCalls = new AtomicInteger();
        RecordingHandler outbox = new RecordingHandler(BackgroundJob.JobKind.OUTBOX_EVENT, job -> {
            outboxCalls.incrementAndGet();
            return JobResult.of(Map.of());
        });
        RecordingHandler backup = new RecordingHandler(BackgroundJob.JobKind.BACKUP_CHECK, job -> JobResult.of(Map.of()));
        OutboxDispatcherService dispatcher = dispatcher(outbox, backup);
        when(queue.claimByKind(any(), eq(dispatcher.workerId()), anyInt())).thenAnswer(invocation -> Optional.of(UUID.randomUUID()));
        when(queue.findLeased(any(), eq(dispatcher.workerId()))).thenAnswer(invocation -> {
            UUID id = invocation.getArgument(0);
            return Optional.of(new JobQueue.LeasedJob(id, BackgroundJob.JobKind.OUTBOX_EVENT, null, null, null, "k", "{}", 1));
        });

        OutboxDispatcherService.DispatchSummary summary = dispatcher.dispatchBatch(3);

        assertThat(summary.claimed()).isEqualTo(3);
        verify(queue, times(3)).claimByKind(any(), eq(dispatcher.workerId()), eq(OutboxDispatcherService.DEFAULT_LEASE_SECONDS));
        verify(queue, times(2)).claimByKind(eq(BackgroundJob.JobKind.OUTBOX_EVENT), anyString(), anyInt());
        verify(queue, times(1)).claimByKind(eq(BackgroundJob.JobKind.BACKUP_CHECK), anyString(), anyInt());
        assertThat(outboxCalls).hasValue(3);
    }

    @Test
    void stopsWhenEveryQueueIsEmpty() {
        OutboxDispatcherService dispatcher = dispatcher(
                new RecordingHandler(BackgroundJob.JobKind.OUTBOX_EVENT, job -> JobResult.of(Map.of())));
        when(queue.claimByKind(any(), anyString(), anyInt())).thenReturn(Optional.empty());

        OutboxDispatcherService.DispatchSummary summary = dispatcher.dispatchBatch(50);

        assertThat(summary.claimed()).isZero();
        verify(queue, times(1)).claimByKind(any(), anyString(), anyInt());
    }

    @Test
    void validatesBatchSizeAndRegisteredKinds() {
        OutboxDispatcherService dispatcher = dispatcher(
                new RecordingHandler(BackgroundJob.JobKind.OUTBOX_EVENT, job -> JobResult.of(Map.of())));

        assertThatThrownBy(() -> dispatcher.dispatchBatch(0)).isInstanceOf(OperationsException.class);
        assertThatThrownBy(() -> dispatcher.dispatchBatch(OutboxDispatcherService.MAX_BATCH_SIZE + 1)).isInstanceOf(OperationsException.class);
        assertThatThrownBy(() -> dispatcher.dispatchKind(BackgroundJob.JobKind.SCHEDULE_SOLVE, 1))
                .isInstanceOf(OperationsException.class).hasMessageContaining("No handler");
    }

    @Test
    void rejectsDuplicateOrNonDispatchableHandlers() {
        assertThatThrownBy(() -> dispatcher(
                new RecordingHandler(BackgroundJob.JobKind.OUTBOX_EVENT, job -> null),
                new RecordingHandler(BackgroundJob.JobKind.OUTBOX_EVENT, job -> null)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Duplicate");
        assertThatThrownBy(() -> dispatcher(new RecordingHandler(BackgroundJob.JobKind.AUTH_RESET_PASSWORD, job -> null)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("non-dispatchable");
    }

    private OutboxDispatcherService dispatcher(JobHandler... handlers) {
        return new OutboxDispatcherService(queue, List.of(handlers), transactionManager);
    }

    private void stubSingleClaim(BackgroundJob.JobKind kind, UUID jobId, String worker, int attempts, String payload) {
        when(queue.claimByKind(eq(kind), eq(worker), anyInt())).thenReturn(Optional.of(jobId), Optional.empty());
        when(queue.findLeased(jobId, worker)).thenReturn(Optional.of(
                new JobQueue.LeasedJob(jobId, kind, null, null, null, "key", payload, attempts)));
    }

    private interface Behaviour {
        JobResult apply(ClaimedJob job) throws Exception;
    }

    private static final class RecordingHandler implements JobHandler {
        private final BackgroundJob.JobKind kind;
        private final Behaviour behaviour;
        final List<ClaimedJob> seen = new ArrayList<>();

        RecordingHandler(BackgroundJob.JobKind kind, Behaviour behaviour) {
            this.kind = kind;
            this.behaviour = behaviour;
        }

        @Override
        public BackgroundJob.JobKind kind() {
            return kind;
        }

        @Override
        public JobResult handle(ClaimedJob job) throws Exception {
            seen.add(job);
            return behaviour.apply(job);
        }
    }
}

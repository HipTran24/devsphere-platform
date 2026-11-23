package com.project.devsphere.operations.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.project.devsphere.operations.domain.BackgroundJob;
import com.project.devsphere.operations.infrastructure.persistence.JobQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Bounded batch worker draining {@code background_jobs} through the registered {@link JobHandler}s.
 *
 * <p>Each job is claimed with a lease ({@code FOR UPDATE SKIP LOCKED}), handled and completed in a
 * single transaction, so handler side effects commit atomically with SUCCESS. Failures are recorded
 * in a separate transaction with exponential backoff
 * {@code min(1800, 30 * 2^min(attempts, 6))} seconds; non-retryable failures and the last allowed
 * attempt dead-letter the job.</p>
 */
@Service
public class OutboxDispatcherService {

    public static final int DEFAULT_LEASE_SECONDS = 300;
    public static final int MAX_BATCH_SIZE = 200;

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcherService.class);
    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final TypeReference<Map<String, Object>> PAYLOAD_TYPE = new TypeReference<>() {};

    private final JobQueue jobQueue;
    private final Map<BackgroundJob.JobKind, JobHandler> handlers;
    private final TransactionTemplate transactionTemplate;
    private final String workerId;

    public OutboxDispatcherService(
            JobQueue jobQueue,
            List<JobHandler> handlers,
            PlatformTransactionManager transactionManager) {
        this.jobQueue = jobQueue;
        Map<BackgroundJob.JobKind, JobHandler> byKind = new EnumMap<>(BackgroundJob.JobKind.class);
        for (JobHandler handler : handlers) {
            if (JobQueue.NON_DISPATCHABLE_KINDS.contains(handler.kind())) {
                throw new IllegalStateException("Handler registered for non-dispatchable kind " + handler.kind());
            }
            if (byKind.putIfAbsent(handler.kind(), handler) != null) {
                throw new IllegalStateException("Duplicate JobHandler for kind " + handler.kind());
            }
        }
        this.handlers = Collections.unmodifiableMap(byKind);
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.workerId = "dispatcher-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** Outcome of a dispatch run. */
    public record DispatchSummary(
            int claimed,
            int succeeded,
            int failed,
            int deadLettered,
            Map<String, Integer> processedByKind,
            long durationMillis) {
    }

    /** Exponential backoff in seconds for a job that has been attempted {@code attempts} times. */
    public static int backoffSeconds(int attempts) {
        int exponent = Math.min(Math.max(attempts, 0), 6);
        return Math.min(1800, 30 * (1 << exponent));
    }

    public Map<BackgroundJob.JobKind, JobHandler> handlers() {
        return handlers;
    }

    public String workerId() {
        return workerId;
    }

    /** Drains up to {@code maxJobs} jobs, round-robin across every kind that has a handler. */
    public DispatchSummary dispatchBatch(int maxJobs) {
        validateBatch(maxJobs);
        return run(maxJobs, List.copyOf(handlers.keySet()));
    }

    /** Drains up to {@code maxJobs} jobs of a single kind. */
    public DispatchSummary dispatchKind(BackgroundJob.JobKind kind, int maxJobs) {
        validateBatch(maxJobs);
        if (kind == null || !handlers.containsKey(kind)) {
            throw OperationsException.validationFailed("No handler is registered for job kind " + kind);
        }
        return run(maxJobs, List.of(kind));
    }

    private DispatchSummary run(int maxJobs, List<BackgroundJob.JobKind> kinds) {
        long started = System.nanoTime();
        Counters counters = new Counters();
        boolean progress = true;
        while (progress && counters.claimed < maxJobs) {
            progress = false;
            for (BackgroundJob.JobKind kind : kinds) {
                if (counters.claimed >= maxJobs) {
                    break;
                }
                Optional<UUID> claimed = jobQueue.claimByKind(kind, workerId, DEFAULT_LEASE_SECONDS);
                if (claimed.isEmpty()) {
                    continue;
                }
                progress = true;
                counters.claimed++;
                counters.processedByKind.merge(kind.name(), 1, Integer::sum);
                process(claimed.get(), counters);
            }
        }
        long durationMillis = (System.nanoTime() - started) / 1_000_000;
        return new DispatchSummary(counters.claimed, counters.succeeded, counters.failed, counters.deadLettered,
                Collections.unmodifiableMap(new LinkedHashMap<>(counters.processedByKind)), durationMillis);
    }

    private void process(UUID jobId, Counters counters) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                JobQueue.LeasedJob leased = jobQueue.findLeased(jobId, workerId)
                        .orElseThrow(() -> new IllegalStateException("Lease lost before processing"));
                JobHandler handler = handlers.get(leased.kind());
                ClaimedJob job = new ClaimedJob(leased.id(), leased.kind(), leased.workspaceId(), leased.projectId(),
                        leased.requestedBy(), leased.idempotencyKey(), parsePayload(leased.payloadJson()), leased.attempts());
                JobResult result = invoke(handler, job);
                jobQueue.complete(jobId, workerId, toJson(result.details()));
            });
            counters.succeeded++;
        } catch (RuntimeException failure) {
            recordFailure(jobId, currentAttempts(jobId), failure, counters);
        }
    }

    private JobResult invoke(JobHandler handler, ClaimedJob job) {
        try {
            JobResult result = handler.handle(job);
            return result != null ? result : JobResult.of(Map.of());
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException(ex.getClass().getSimpleName() + ": " + ex.getMessage(), ex);
        }
    }

    private void recordFailure(UUID jobId, int attempts, RuntimeException failure, Counters counters) {
        String error = describe(failure);
        boolean permanent = failure instanceof NonRetryableJobException || attempts >= JobQueue.MAX_ATTEMPTS;
        try {
            if (permanent) {
                jobQueue.failPermanently(jobId, workerId, error);
                counters.deadLettered++;
            } else {
                jobQueue.fail(jobId, workerId, error, backoffSeconds(attempts));
            }
            counters.failed++;
            log.warn("Background job {} failed (attempt {}, permanent={}): {}", jobId, attempts, permanent, error);
        } catch (IllegalStateException leaseLost) {
            counters.failed++;
            log.warn("Background job {} failed after its lease was lost: {}", jobId, error);
        }
    }

    private int currentAttempts(UUID jobId) {
        return jobQueue.findLeased(jobId, workerId).map(JobQueue.LeasedJob::attempts).orElse(JobQueue.MAX_ATTEMPTS);
    }

    static String describe(Throwable failure) {
        String message = failure.getMessage();
        return JobQueue.truncateError(failure.getClass().getSimpleName() + (message == null ? "" : ": " + message));
    }

    private static Map<String, Object> parsePayload(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return Map.of();
        }
        try {
            return JSON.readValue(payloadJson, PAYLOAD_TYPE);
        } catch (JsonProcessingException ex) {
            throw new NonRetryableJobException("Stored job payload is not valid JSON", ex);
        }
    }

    private static String toJson(Map<String, Object> details) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", 1);
        result.putAll(details);
        try {
            return JSON.writeValueAsString(result);
        } catch (JsonProcessingException ex) {
            throw new NonRetryableJobException("Job result cannot be serialised", ex);
        }
    }

    private static void validateBatch(int maxJobs) {
        if (maxJobs < 1 || maxJobs > MAX_BATCH_SIZE) {
            throw OperationsException.validationFailed("maxJobs must be between 1 and " + MAX_BATCH_SIZE);
        }
    }

    private static final class Counters {
        int claimed;
        int succeeded;
        int failed;
        int deadLettered;
        final Map<String, Integer> processedByKind = new LinkedHashMap<>();
    }
}

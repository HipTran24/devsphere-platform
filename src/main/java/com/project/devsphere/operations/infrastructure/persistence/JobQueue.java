package com.project.devsphere.operations.infrastructure.persistence;

import com.project.devsphere.operations.domain.BackgroundJob;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Internal worker adapter with expiring leases. Delivery is at least once;
 * consumers deduplicate using the persisted event/idempotency key.
 */
@Component
public class JobQueue {

    /** Attempts after which a job is no longer claimable (dead-lettered) until an operator retries it. */
    public static final int MAX_ATTEMPTS = 5;
    public static final int MAX_ERROR_LENGTH = 2000;
    public static final int MAX_BACKOFF_SECONDS = 86_400;

    /** Kinds owned by dedicated workers or used as idempotency records; never handed to generic workers. */
    public static final Set<BackgroundJob.JobKind> NON_DISPATCHABLE_KINDS = EnumSet.of(
            BackgroundJob.JobKind.IDEMPOTENCY,
            BackgroundJob.JobKind.AUTH_VERIFY_EMAIL,
            BackgroundJob.JobKind.AUTH_RESET_PASSWORD);

    private final JdbcTemplate jdbc;
    public JobQueue(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public Optional<UUID> claim(String worker, int leaseSeconds) {
        validateLease(worker, leaseSeconds);
        return jdbc.query("""
                with candidate as (
                  select id from background_jobs
                  where job_kind not in ('IDEMPOTENCY', 'AUTH_VERIFY_EMAIL', 'AUTH_RESET_PASSWORD') and attempts<5 and scheduled_at<=now()
                    and (status in ('QUEUED','FAILED') or (status='RUNNING' and locked_until<=now()))
                  order by scheduled_at,id for update skip locked limit 1
                )
                update background_jobs j set status='RUNNING',attempts=attempts+1,
                  locked_by=?,locked_until=now()+(? * interval '1 second'),started_at=now()
                from candidate c where j.id=c.id returning j.id
                """, (rs,n)->rs.getObject(1,UUID.class), worker, leaseSeconds).stream().findFirst();
    }

    /** Claims the oldest due job of one dispatchable kind, honouring the same lease rules as {@link #claim}. */
    @Transactional
    public Optional<UUID> claimByKind(BackgroundJob.JobKind kind, String worker, int leaseSeconds) {
        requireDispatchable(kind);
        validateLease(worker, leaseSeconds);
        return jdbc.query("""
                with candidate as (
                  select id from background_jobs
                  where job_kind=? and attempts<? and scheduled_at<=now()
                    and (status in ('QUEUED','FAILED') or (status='RUNNING' and locked_until<=now()))
                  order by scheduled_at,id for update skip locked limit 1
                )
                update background_jobs j set status='RUNNING',attempts=attempts+1,
                  locked_by=?,locked_until=now()+(? * interval '1 second'),started_at=now()
                from candidate c where j.id=c.id returning j.id
                """, (rs,n)->rs.getObject(1,UUID.class), kind.name(), MAX_ATTEMPTS, worker, leaseSeconds).stream().findFirst();
    }

    /** Loads the job currently leased by {@code worker}; empty when the lease was lost. */
    @Transactional(readOnly = true)
    public Optional<LeasedJob> findLeased(UUID id, String worker) {
        return jdbc.query("""
                select id,job_kind,workspace_id,project_id,requested_by,idempotency_key,payload::text as payload,attempts
                from background_jobs where id=? and locked_by=? and status='RUNNING'
                """, (rs, n) -> new LeasedJob(
                        rs.getObject("id", UUID.class),
                        BackgroundJob.JobKind.valueOf(rs.getString("job_kind")),
                        rs.getObject("workspace_id", UUID.class),
                        rs.getObject("project_id", UUID.class),
                        rs.getObject("requested_by", UUID.class),
                        rs.getString("idempotency_key"),
                        rs.getString("payload"),
                        rs.getInt("attempts")), id, worker).stream().findFirst();
    }

    @Transactional
    public void complete(UUID id, String worker) {
        int rows = jdbc.update("""
                update background_jobs set status='SUCCESS',finished_at=now(),locked_by=null,locked_until=null
                where id=? and locked_by=? and status='RUNNING' and locked_until>now()
                """, id, worker);
        if (rows!=1) throw new IllegalStateException("Worker does not hold an active lease");
    }

    /** Completes a leased job and stores its JSON object result. */
    @Transactional
    public void complete(UUID id, String worker, String resultJson) {
        if (resultJson == null) {
            complete(id, worker);
            return;
        }
        int rows = jdbc.update("""
                update background_jobs set status='SUCCESS',finished_at=now(),locked_by=null,locked_until=null,
                  last_error=null,result=?::jsonb
                where id=? and locked_by=? and status='RUNNING' and locked_until>now()
                """, resultJson, id, worker);
        if (rows!=1) throw new IllegalStateException("Worker does not hold an active lease");
    }

    /**
     * Releases a leased job as FAILED and reschedules it after {@code backoffSeconds}. The job stays
     * claimable until it reaches {@link #MAX_ATTEMPTS}; the error text is truncated to
     * {@link #MAX_ERROR_LENGTH} characters.
     */
    @Transactional
    public void fail(UUID id, String worker, String error, int backoffSeconds) {
        if (backoffSeconds < 0 || backoffSeconds > MAX_BACKOFF_SECONDS) {
            throw new IllegalArgumentException("Backoff must be 0.." + MAX_BACKOFF_SECONDS + " seconds");
        }
        int rows = jdbc.update("""
                update background_jobs set status='FAILED',last_error=?,locked_by=null,locked_until=null,
                  finished_at=case when attempts>=? then now() else null end,
                  scheduled_at=now()+(? * interval '1 second')
                where id=? and locked_by=? and status='RUNNING'
                """, truncateError(error), MAX_ATTEMPTS, backoffSeconds, id, worker);
        if (rows!=1) throw new IllegalStateException("Worker does not hold an active lease");
    }

    /** Dead-letters a leased job immediately (non-retryable failure); operators may still retry it. */
    @Transactional
    public void failPermanently(UUID id, String worker, String error) {
        int rows = jdbc.update("""
                update background_jobs set status='FAILED',last_error=?,locked_by=null,locked_until=null,
                  attempts=greatest(attempts,?),finished_at=now()
                where id=? and locked_by=? and status='RUNNING'
                """, truncateError(error), MAX_ATTEMPTS, id, worker);
        if (rows!=1) throw new IllegalStateException("Worker does not hold an active lease");
    }

    /** Extends an active lease; returns false when the worker no longer owns it. */
    @Transactional
    public boolean heartbeat(UUID id, String worker, int leaseSeconds) {
        validateLease(worker, leaseSeconds);
        return jdbc.update("""
                update background_jobs set locked_until=now()+(? * interval '1 second')
                where id=? and locked_by=? and status='RUNNING' and locked_until>now()
                """, leaseSeconds, id, worker) == 1;
    }

    public static String truncateError(String error) {
        String value = error == null || error.isBlank() ? "UNKNOWN_ERROR" : error;
        return value.length() > MAX_ERROR_LENGTH ? value.substring(0, MAX_ERROR_LENGTH) : value;
    }

    private static void requireDispatchable(BackgroundJob.JobKind kind) {
        if (kind == null || NON_DISPATCHABLE_KINDS.contains(kind)) {
            throw new IllegalArgumentException("Job kind is not dispatchable: " + kind);
        }
    }

    private static void validateLease(String worker, int leaseSeconds) {
        if (worker == null || worker.isBlank() || worker.length()>120 || leaseSeconds<1 || leaseSeconds>3600) {
            throw new IllegalArgumentException("Worker and lease of 1..3600 seconds are required");
        }
    }

    /** Snapshot of a job owned by the current worker. */
    public record LeasedJob(
            UUID id,
            BackgroundJob.JobKind kind,
            UUID workspaceId,
            UUID projectId,
            UUID requestedBy,
            String idempotencyKey,
            String payloadJson,
            int attempts) {
    }
}

package com.project.devsphere.operations.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;
import java.util.UUID;

/** Internal worker adapter with expiring leases. Delivery is at least once;
 * consumers deduplicate using the persisted event/idempotency key.
 */
@Component
public class JobQueue {
    private final JdbcTemplate jdbc;
    public JobQueue(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public Optional<UUID> claim(String worker, int leaseSeconds) {
        if (worker == null || worker.isBlank() || worker.length()>120 || leaseSeconds<1 || leaseSeconds>3600) {
            throw new IllegalArgumentException("Worker and lease of 1..3600 seconds are required");
        }
        return jdbc.query("""
                with candidate as (
                  select id from background_jobs
                  where job_kind not in ('IDEMPOTENCY', 'AUTH_VERIFY_EMAIL') and attempts<5 and scheduled_at<=now()
                    and (status in ('QUEUED','FAILED') or (status='RUNNING' and locked_until<=now()))
                  order by scheduled_at,id for update skip locked limit 1
                )
                update background_jobs j set status='RUNNING',attempts=attempts+1,
                  locked_by=?,locked_until=now()+(? * interval '1 second'),started_at=now()
                from candidate c where j.id=c.id returning j.id
                """, (rs,n)->rs.getObject(1,UUID.class), worker, leaseSeconds).stream().findFirst();
    }

    @Transactional
    public void complete(UUID id, String worker) {
        int rows = jdbc.update("""
                update background_jobs set status='SUCCESS',finished_at=now(),locked_by=null,locked_until=null
                where id=? and locked_by=? and status='RUNNING' and locked_until>now()
                """, id, worker);
        if (rows!=1) throw new IllegalStateException("Worker does not hold an active lease");
    }
}

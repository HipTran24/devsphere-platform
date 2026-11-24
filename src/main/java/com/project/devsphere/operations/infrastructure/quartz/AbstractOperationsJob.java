package com.project.devsphere.operations.infrastructure.quartz;

import com.project.devsphere.operations.infrastructure.metrics.OperationsMetrics;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.quartz.QuartzJobBean;

import java.util.Map;
import java.util.UUID;

/**
 * Common envelope for scheduled operations jobs: binds a {@code quartz-*} trace id to the MDC,
 * measures the run and publishes the job's summary as the Quartz execution result. Failures are
 * logged and rethrown as {@link JobExecutionException} without refiring immediately; the durable
 * {@code background_jobs} queue owns retries.
 */
public abstract class AbstractOperationsJob extends QuartzJobBean {

    private static final Logger log = LoggerFactory.getLogger(AbstractOperationsJob.class);
    static final String TRACE_ID_MDC_KEY = "traceId";

    private OperationsMetrics metrics;

    /** Optional telemetry recording devsphere_quartz_job_executions_total and job duration. */
    @Autowired(required = false)
    public void setOperationsMetrics(OperationsMetrics metrics) {
        this.metrics = metrics;
    }

    /** Stable job name used for the Quartz JobKey, metrics and audit records. */
    public abstract String jobName();

    /** Performs one scheduled run and returns a small summary of what happened. */
    protected abstract Map<String, Object> run(JobExecutionContext context) throws Exception;

    @Override
    protected final void executeInternal(JobExecutionContext context) throws JobExecutionException {
        String previousTrace = MDC.get(TRACE_ID_MDC_KEY);
        MDC.put(TRACE_ID_MDC_KEY, "quartz-" + jobName() + "-" + UUID.randomUUID().toString().substring(0, 8));
        long started = System.nanoTime();
        boolean success = false;
        try {
            Map<String, Object> summary = run(context);
            context.setResult(summary);
            success = true;
            log.info("Scheduled job {} finished in {} ms: {}", jobName(), elapsedMillis(started), summary);
        } catch (Exception ex) {
            log.error("Scheduled job {} failed after {} ms", jobName(), elapsedMillis(started), ex);
            throw new JobExecutionException(ex, false);
        } finally {
            afterRun(success, System.nanoTime() - started);
            if (previousTrace != null) {
                MDC.put(TRACE_ID_MDC_KEY, previousTrace);
            } else {
                MDC.remove(TRACE_ID_MDC_KEY);
            }
        }
    }

    /** Invoked after every run with its outcome and duration in nanoseconds. */
    protected void afterRun(boolean success, long durationNanos) {
        if (metrics != null) {
            metrics.recordQuartzJob(jobName(), success, durationNanos);
        }
    }

    private static long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}

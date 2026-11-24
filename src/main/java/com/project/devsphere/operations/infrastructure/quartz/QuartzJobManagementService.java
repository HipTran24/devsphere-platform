package com.project.devsphere.operations.infrastructure.quartz;

import com.project.devsphere.administration.application.AuditLogService;
import com.project.devsphere.identity.application.SessionService;
import com.project.devsphere.operations.application.OperationsException;
import org.quartz.CronTrigger;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Read and manual-trigger access to the operations Quartz jobs. Only names in
 * {@link QuartzSchedulerConfig#JOB_NAMES} are accepted and every manual trigger is audited.
 */
@Service
public class QuartzJobManagementService {

    private static final Pattern JOB_NAME = Pattern.compile("^[a-zA-Z]{1,64}$");

    private final ObjectProvider<Scheduler> schedulerProvider;
    private final AuditLogService auditLogService;

    public QuartzJobManagementService(ObjectProvider<Scheduler> schedulerProvider, AuditLogService auditLogService) {
        this.schedulerProvider = schedulerProvider;
        this.auditLogService = auditLogService;
    }

    /** One trigger of a whitelisted job. */
    public record TriggerInfo(
            String jobName,
            String triggerName,
            String group,
            String description,
            String cronExpression,
            String timeZone,
            String state,
            Instant nextFireTime,
            Instant previousFireTime) {
    }

    /** Scheduler lifecycle flags used by health reporting. */
    public record SchedulerStatus(boolean available, boolean started, boolean inStandby, boolean shutdown, int jobCount) {
    }

    public List<TriggerInfo> listTriggers() {
        Scheduler scheduler = requireScheduler();
        List<TriggerInfo> triggers = new ArrayList<>();
        try {
            for (String jobName : QuartzSchedulerConfig.JOB_NAMES) {
                for (Trigger trigger : scheduler.getTriggersOfJob(QuartzSchedulerConfig.jobKey(jobName))) {
                    triggers.add(describe(scheduler, jobName, trigger));
                }
            }
        } catch (SchedulerException ex) {
            throw OperationsException.schedulerUnavailable("Quartz scheduler cannot be queried");
        }
        return triggers;
    }

    /**
     * Fires a whitelisted job immediately (or as soon as the scheduler leaves standby) and records a
     * {@code QUARTZ_JOB_TRIGGERED} audit event attributed to {@code actor}.
     */
    public TriggerInfo triggerJob(String jobName, SessionService.Principal actor) {
        if (actor == null) {
            throw new IllegalArgumentException("actor is required");
        }
        if (jobName == null || !JOB_NAME.matcher(jobName).matches() || !QuartzSchedulerConfig.JOB_NAMES.contains(jobName)) {
            throw OperationsException.notFound("Unknown scheduled job");
        }
        Scheduler scheduler = requireScheduler();
        JobKey key = QuartzSchedulerConfig.jobKey(jobName);
        try {
            if (!scheduler.checkExists(key)) {
                throw OperationsException.notFound("Unknown scheduled job");
            }
            scheduler.triggerJob(key);
            auditLogService.record(actor.userId(), "QUARTZ_JOB_TRIGGERED", "quartz_job", null,
                    Map.of("jobName", jobName, "schedulerStarted", scheduler.isStarted() && !scheduler.isInStandbyMode()));
            List<Trigger> triggers = new ArrayList<>(scheduler.getTriggersOfJob(key));
            Trigger cron = triggers.stream().filter(CronTrigger.class::isInstance).findFirst()
                    .orElse(triggers.isEmpty() ? null : triggers.getFirst());
            return cron == null
                    ? new TriggerInfo(jobName, null, QuartzSchedulerConfig.GROUP, null, null, null, "NONE", null, null)
                    : describe(scheduler, jobName, cron);
        } catch (SchedulerException ex) {
            throw OperationsException.schedulerUnavailable("Quartz scheduler rejected the trigger request");
        }
    }

    public SchedulerStatus status() {
        Scheduler scheduler = schedulerProvider.getIfAvailable();
        if (scheduler == null) {
            return new SchedulerStatus(false, false, false, false, 0);
        }
        try {
            int jobs = 0;
            for (String jobName : QuartzSchedulerConfig.JOB_NAMES) {
                if (scheduler.checkExists(QuartzSchedulerConfig.jobKey(jobName))) {
                    jobs++;
                }
            }
            return new SchedulerStatus(true, scheduler.isStarted(), scheduler.isInStandbyMode(), scheduler.isShutdown(), jobs);
        } catch (SchedulerException ex) {
            return new SchedulerStatus(false, false, false, false, 0);
        }
    }

    private Scheduler requireScheduler() {
        Scheduler scheduler = schedulerProvider.getIfAvailable();
        if (scheduler == null) {
            throw OperationsException.schedulerUnavailable("Quartz scheduler is not configured");
        }
        return scheduler;
    }

    private static TriggerInfo describe(Scheduler scheduler, String jobName, Trigger trigger) throws SchedulerException {
        String cron = trigger instanceof CronTrigger cronTrigger ? cronTrigger.getCronExpression() : null;
        String zone = trigger instanceof CronTrigger cronTrigger ? cronTrigger.getTimeZone().getID() : null;
        return new TriggerInfo(
                jobName,
                trigger.getKey().getName(),
                trigger.getKey().getGroup(),
                trigger.getDescription(),
                cron,
                zone,
                scheduler.getTriggerState(trigger.getKey()).name(),
                toInstant(trigger.getNextFireTime()),
                toInstant(trigger.getPreviousFireTime()));
    }

    private static Instant toInstant(Date date) {
        return date == null ? null : date.toInstant();
    }
}

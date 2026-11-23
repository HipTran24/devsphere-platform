package com.project.devsphere.operations.infrastructure.quartz;

import org.quartz.CronScheduleBuilder;
import org.quartz.Job;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.TimeZone;

/**
 * Registers the six durable operations jobs and their cron triggers with the in-memory Quartz
 * scheduler. Cron expressions come from {@code devsphere.quartz.cron.*}; misfires are skipped
 * (the next run catches up through the durable queue) and all schedules are evaluated in UTC.
 */
@Configuration(proxyBeanMethods = false)
public class QuartzSchedulerConfig {

    public static final String GROUP = "devsphere-operations";
    public static final String TRIGGER_SUFFIX = "Trigger";

    /** Whitelist of jobs that exist in the scheduler and may be triggered manually. */
    public static final List<String> JOB_NAMES = List.of(
            OverdueWorkItemScanJob.NAME,
            WebhookRetryJob.NAME,
            OutboxAndEmailDispatchJob.NAME,
            GitHubBackfillSyncJob.NAME,
            DailyReportScheduleJob.NAME,
            NightlyBackupCheckJob.NAME);

    public static JobKey jobKey(String jobName) {
        return JobKey.jobKey(jobName, GROUP);
    }

    @Bean
    public JobDetail overdueWorkItemScanJobDetail() {
        return durableJob(OverdueWorkItemScanJob.class, OverdueWorkItemScanJob.NAME,
                "Enqueue WORK_ITEM_OVERDUE outbox events for overdue work items");
    }

    @Bean
    public Trigger overdueWorkItemScanTrigger(
            @Qualifier("overdueWorkItemScanJobDetail") JobDetail jobDetail,
            @Value("${devsphere.quartz.cron.overdue-scan:0 */5 * * * ?}") String cron) {
        return cronTrigger(jobDetail, cron);
    }

    @Bean
    public JobDetail webhookRetryJobDetail() {
        return durableJob(WebhookRetryJob.class, WebhookRetryJob.NAME,
                "Retry failed or stalled GitHub webhook deliveries");
    }

    @Bean
    public Trigger webhookRetryTrigger(
            @Qualifier("webhookRetryJobDetail") JobDetail jobDetail,
            @Value("${devsphere.quartz.cron.webhook-retry:0 */5 * * * ?}") String cron) {
        return cronTrigger(jobDetail, cron);
    }

    @Bean
    public JobDetail outboxDispatchJobDetail() {
        return durableJob(OutboxAndEmailDispatchJob.class, OutboxAndEmailDispatchJob.NAME,
                "Publish outbox events and deliver notification e-mails");
    }

    @Bean
    public Trigger outboxDispatchTrigger(
            @Qualifier("outboxDispatchJobDetail") JobDetail jobDetail,
            @Value("${devsphere.quartz.cron.outbox-dispatch:0 */5 * * * ?}") String cron) {
        return cronTrigger(jobDetail, cron);
    }

    @Bean
    public JobDetail githubBackfillSyncJobDetail() {
        return durableJob(GitHubBackfillSyncJob.class, GitHubBackfillSyncJob.NAME,
                "Backfill unprocessed GitHub activity for active repositories");
    }

    @Bean
    public Trigger githubBackfillSyncTrigger(
            @Qualifier("githubBackfillSyncJobDetail") JobDetail jobDetail,
            @Value("${devsphere.quartz.cron.github-backfill:0 */30 * * * ?}") String cron) {
        return cronTrigger(jobDetail, cron);
    }

    @Bean
    public JobDetail dailyReportScheduleJobDetail() {
        return durableJob(DailyReportScheduleJob.class, DailyReportScheduleJob.NAME,
                "Generate DAILY report drafts for active projects");
    }

    @Bean
    public Trigger dailyReportScheduleTrigger(
            @Qualifier("dailyReportScheduleJobDetail") JobDetail jobDetail,
            @Value("${devsphere.quartz.cron.daily-report:0 0 8 * * ?}") String cron) {
        return cronTrigger(jobDetail, cron);
    }

    @Bean
    public JobDetail nightlyBackupCheckJobDetail() {
        return durableJob(NightlyBackupCheckJob.class, NightlyBackupCheckJob.NAME,
                "Verify database health and backup freshness");
    }

    @Bean
    public Trigger nightlyBackupCheckTrigger(
            @Qualifier("nightlyBackupCheckJobDetail") JobDetail jobDetail,
            @Value("${devsphere.quartz.cron.nightly-backup:0 0 2 * * ?}") String cron) {
        return cronTrigger(jobDetail, cron);
    }

    private static JobDetail durableJob(Class<? extends Job> jobClass, String name, String description) {
        return JobBuilder.newJob(jobClass)
                .withIdentity(jobKey(name))
                .withDescription(description)
                .storeDurably()
                .requestRecovery(false)
                .build();
    }

    private static Trigger cronTrigger(JobDetail jobDetail, String cron) {
        return TriggerBuilder.newTrigger()
                .forJob(jobDetail)
                .withIdentity(jobDetail.getKey().getName() + TRIGGER_SUFFIX, GROUP)
                .withDescription(jobDetail.getDescription())
                .withSchedule(CronScheduleBuilder.cronSchedule(cron)
                        .inTimeZone(TimeZone.getTimeZone("UTC"))
                        .withMisfireHandlingInstructionDoNothing())
                .build();
    }
}

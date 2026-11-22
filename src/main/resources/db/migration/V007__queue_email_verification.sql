ALTER TABLE background_jobs DROP CONSTRAINT background_jobs_job_kind_check;

ALTER TABLE background_jobs ADD CONSTRAINT background_jobs_job_kind_check CHECK (
    job_kind IN ('OUTBOX_EVENT', 'WEBHOOK_RETRY', 'NOTIFICATION_EMAIL', 'GITHUB_BACKFILL',
                 'REPORT_EXPORT', 'SCHEDULE_SOLVE', 'IDEMPOTENCY', 'BACKUP_CHECK', 'AUTH_VERIFY_EMAIL')
);

CREATE INDEX idx_auth_tokens_user_verification
    ON auth_tokens (user_id, created_at DESC, id DESC)
    WHERE purpose = 'VERIFY_EMAIL';

ALTER TABLE auth_tokens DROP CONSTRAINT auth_tokens_purpose_check;

ALTER TABLE auth_tokens ADD CONSTRAINT auth_tokens_purpose_check
    CHECK (purpose IN ('VERIFY_EMAIL', 'RESET_PASSWORD', 'RESET_PASSWORD_GRANT'));

ALTER TABLE auth_tokens ADD CONSTRAINT auth_tokens_reset_grant_owner
    CHECK (purpose <> 'RESET_PASSWORD_GRANT' OR (user_id IS NOT NULL AND attempts = 0));

CREATE INDEX idx_auth_tokens_recovery_email
    ON auth_tokens (email, created_at DESC, id DESC)
    WHERE purpose = 'RESET_PASSWORD';

CREATE INDEX idx_auth_tokens_recovery_owner
    ON auth_tokens (user_id, created_at DESC, id DESC)
    WHERE purpose = 'RESET_PASSWORD' AND user_id IS NOT NULL;

CREATE UNIQUE INDEX uq_auth_tokens_reset_grant_digest
    ON auth_tokens (token_hash)
    WHERE purpose = 'RESET_PASSWORD_GRANT';

ALTER TABLE background_jobs DROP CONSTRAINT background_jobs_job_kind_check;

ALTER TABLE background_jobs ADD CONSTRAINT background_jobs_job_kind_check CHECK (
    job_kind IN ('OUTBOX_EVENT', 'WEBHOOK_RETRY', 'NOTIFICATION_EMAIL', 'GITHUB_BACKFILL',
                 'REPORT_EXPORT', 'SCHEDULE_SOLVE', 'IDEMPOTENCY', 'BACKUP_CHECK',
                 'AUTH_VERIFY_EMAIL', 'AUTH_RESET_PASSWORD')
);

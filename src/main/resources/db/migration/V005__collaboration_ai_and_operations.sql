-- Devsphere PostgreSQL business schema. All timestamps are UTC instants.

CREATE TABLE alerts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id uuid NOT NULL REFERENCES projects(id),
    work_item_id uuid,
    previous_alert_id uuid,
    fingerprint char(64) NOT NULL CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
    alert_code varchar(80) NOT NULL,
    severity varchar(12) NOT NULL CHECK (severity IN ('LOW','MEDIUM','HIGH','CRITICAL')),
    status varchar(16) NOT NULL DEFAULT 'NEW' CHECK (status IN ('NEW','ACKNOWLEDGED','IN_PROGRESS','RESOLVED')),
    title varchar(255) NOT NULL,
    evidence jsonb NOT NULL DEFAULT '{"schemaVersion":1}'::jsonb,
    impact_snapshot jsonb NOT NULL DEFAULT '{"schemaVersion":1}'::jsonb,
    assigned_to uuid REFERENCES users(id),
    acknowledged_by uuid REFERENCES users(id),
    detected_at timestamptz NOT NULL DEFAULT now(),
    resolved_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    CHECK ((status = 'RESOLVED') = (resolved_at IS NOT NULL)),
    CHECK (previous_alert_id IS DISTINCT FROM id),
    UNIQUE (project_id, id),
    CHECK (evidence IS NULL OR jsonb_typeof(evidence) = 'object'),
    CHECK (impact_snapshot IS NULL OR jsonb_typeof(impact_snapshot) = 'object')
);

CREATE TABLE chat_channels (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id uuid NOT NULL REFERENCES workspaces(id),
    project_id uuid,
    work_item_id uuid,
    type varchar(12) NOT NULL CHECK (type IN ('PROJECT','TEAM','TASK','PRIVATE','CLIENT_PM')),
    name varchar(150),
    created_by uuid NOT NULL REFERENCES users(id),
    archived_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    CHECK ((type = 'TASK') = (work_item_id IS NOT NULL)),
    CHECK (type NOT IN ('TASK','PROJECT','CLIENT_PM') OR project_id IS NOT NULL),
    UNIQUE (project_id, id),
    UNIQUE (workspace_id, id)
);

CREATE TABLE chat_channel_members (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id uuid NOT NULL REFERENCES workspaces(id),
    project_id uuid REFERENCES projects(id),
    channel_id uuid NOT NULL,
    user_id uuid NOT NULL REFERENCES users(id),
    joined_at timestamptz NOT NULL DEFAULT now(),
    muted boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    UNIQUE (channel_id, user_id),
    UNIQUE (project_id, id),
    UNIQUE (workspace_id, id)
);

CREATE TABLE chat_messages (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id uuid NOT NULL REFERENCES workspaces(id),
    project_id uuid REFERENCES projects(id),
    channel_id uuid NOT NULL,
    sender_id uuid REFERENCES users(id),
    sender_kind varchar(8) NOT NULL DEFAULT 'USER' CHECK (sender_kind IN ('USER','AI','SYSTEM')),
    ai_job_id uuid,
    reply_to_id uuid REFERENCES chat_messages(id),
    body text NOT NULL CHECK (length(btrim(body)) > 0),
    mentions uuid[] NOT NULL DEFAULT '{}',
    provenance jsonb NOT NULL DEFAULT '{"schemaVersion":1}'::jsonb,
    sent_at timestamptz NOT NULL DEFAULT now(),
    edited_at timestamptz,
    deleted_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    CHECK (reply_to_id IS DISTINCT FROM id),
    CHECK ((sender_kind = 'USER' AND sender_id IS NOT NULL) OR (sender_kind = 'AI' AND ai_job_id IS NOT NULL) OR sender_kind = 'SYSTEM'),
    UNIQUE (project_id, id),
    UNIQUE (workspace_id, id),
    CHECK (provenance IS NULL OR jsonb_typeof(provenance) = 'object')
);

CREATE TABLE notifications (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id uuid REFERENCES workspaces(id),
    user_id uuid NOT NULL REFERENCES users(id),
    project_id uuid,
    event_type varchar(100) NOT NULL,
    event_key varchar(180) NOT NULL,
    title varchar(180) NOT NULL,
    body text NOT NULL,
    deeplink text,
    read_at timestamptz,
    email_status varchar(16) NOT NULL DEFAULT 'NOT_REQUESTED' CHECK (email_status IN ('NOT_REQUESTED','PENDING','SENT','FAILED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    UNIQUE (user_id, event_key),
    UNIQUE (project_id, id),
    UNIQUE (workspace_id, id)
);

CREATE TABLE reports (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id uuid NOT NULL REFERENCES projects(id),
    type varchar(16) NOT NULL CHECK (type IN ('DAILY','WEEKLY','SPRINT','RISK','CODE_QUALITY','CLIENT_ROADMAP')),
    period_start date NOT NULL,
    period_end date NOT NULL,
    status varchar(12) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','APPROVED','PUBLISHED')),
    content jsonb NOT NULL,
    visible_to_client boolean NOT NULL DEFAULT false,
    milestone_id uuid,
    generated_by uuid REFERENCES users(id),
    ai_job_id uuid,
    approved_by uuid REFERENCES users(id),
    published_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    CHECK (period_end >= period_start),
    CHECK (status NOT IN ('APPROVED','PUBLISHED') OR approved_by IS NOT NULL),
    CHECK ((status = 'PUBLISHED') = (published_at IS NOT NULL)),
    UNIQUE (project_id, id),
    CHECK (content IS NULL OR jsonb_typeof(content) = 'object')
);

CREATE TABLE attachments (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id uuid NOT NULL REFERENCES workspaces(id),
    project_id uuid REFERENCES projects(id),
    work_item_id uuid,
    chat_message_id uuid REFERENCES chat_messages(id),
    report_id uuid,
    ci_run_id uuid,
    uploaded_by uuid REFERENCES users(id),
    kind varchar(16) NOT NULL CHECK (kind IN ('TASK_FILE','CHAT_FILE','REPORT_EXPORT','CI_ARTIFACT')),
    storage_key varchar(512) NOT NULL UNIQUE,
    filename varchar(255) NOT NULL,
    content_type varchar(120) NOT NULL,
    file_size bigint NOT NULL CHECK (file_size BETWEEN 0 AND 20971520),
    sha256 char(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    visibility varchar(16) NOT NULL DEFAULT 'INTERNAL' CHECK (visibility IN ('INTERNAL','CLIENT_SHARED')),
    scan_status varchar(12) NOT NULL DEFAULT 'PENDING' CHECK (scan_status IN ('PENDING','CLEAN','REJECTED')),
    deleted_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    CHECK (num_nonnulls(work_item_id,chat_message_id,report_id,ci_run_id) = 1),
    CHECK ((kind = 'TASK_FILE' AND work_item_id IS NOT NULL) OR (kind = 'CHAT_FILE' AND chat_message_id IS NOT NULL) OR (kind = 'REPORT_EXPORT' AND report_id IS NOT NULL) OR (kind = 'CI_ARTIFACT' AND ci_run_id IS NOT NULL)),
    CHECK (visibility <> 'CLIENT_SHARED' OR scan_status = 'CLEAN'),
    UNIQUE (project_id, id),
    UNIQUE (workspace_id, id)
);

CREATE TABLE ai_jobs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id uuid NOT NULL REFERENCES workspaces(id),
    project_id uuid,
    requested_by uuid REFERENCES users(id),
    job_type varchar(24) NOT NULL CHECK (job_type IN ('REQUIREMENT_PLAN','PUSH_REVIEW','PR_REVIEW','PROGRESS_EXPLAIN','ALERT_EXPLAIN','CHAT_ANSWER','REPORT_SUMMARY','AI_CRITIQUE')),
    status varchar(12) NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED','RUNNING','SUCCESS','FAILED')),
    model_name varchar(140) NOT NULL,
    connection_id varchar(80) NOT NULL,
    route_snapshot jsonb NOT NULL,
    prompt_version varchar(40) NOT NULL,
    output_schema_version varchar(40) NOT NULL DEFAULT '1',
    input_refs jsonb NOT NULL DEFAULT '{"schemaVersion":1}'::jsonb,
    output_json jsonb,
    validation_status varchar(16) NOT NULL DEFAULT 'PENDING' CHECK (validation_status IN ('PENDING','VALID','INVALID','NEEDS_REVIEW')),
    validation_report jsonb NOT NULL DEFAULT '{"schemaVersion":1}'::jsonb,
    validation_attempts smallint NOT NULL DEFAULT 0 CHECK (validation_attempts BETWEEN 0 AND 2),
    validated_by_model varchar(140),
    tokens_in integer CHECK (tokens_in >= 0),
    tokens_out integer CHECK (tokens_out >= 0),
    error_code varchar(100),
    started_at timestamptz,
    finished_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    CHECK (status <> 'SUCCESS' OR output_json IS NOT NULL),
    CHECK (validation_status = 'PENDING' OR output_json IS NOT NULL),
    CHECK (finished_at IS NULL OR started_at IS NULL OR finished_at >= started_at),
    UNIQUE (project_id, id),
    UNIQUE (workspace_id, id),
    CHECK (route_snapshot IS NULL OR jsonb_typeof(route_snapshot) = 'object'),
    CHECK (input_refs IS NULL OR jsonb_typeof(input_refs) = 'object'),
    CHECK (output_json IS NULL OR jsonb_typeof(output_json) = 'object'),
    CHECK (validation_report IS NULL OR jsonb_typeof(validation_report) = 'object')
);

CREATE TABLE audit_logs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id uuid REFERENCES workspaces(id),
    project_id uuid,
    actor_id uuid REFERENCES users(id),
    action varchar(120) NOT NULL,
    entity_type varchar(100) NOT NULL,
    entity_id uuid,
    before_json jsonb,
    after_json jsonb,
    metadata jsonb NOT NULL DEFAULT '{"schemaVersion":1}'::jsonb,
    occurred_at timestamptz NOT NULL DEFAULT now(),
    trace_id varchar(64),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (project_id, id),
    UNIQUE (workspace_id, id),
    CHECK (before_json IS NULL OR jsonb_typeof(before_json) = 'object'),
    CHECK (after_json IS NULL OR jsonb_typeof(after_json) = 'object'),
    CHECK (metadata IS NULL OR jsonb_typeof(metadata) = 'object')
);

CREATE TABLE background_jobs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id uuid REFERENCES workspaces(id),
    project_id uuid,
    requested_by uuid REFERENCES users(id),
    job_kind varchar(24) NOT NULL CHECK (job_kind IN ('OUTBOX_EVENT','WEBHOOK_RETRY','NOTIFICATION_EMAIL','GITHUB_BACKFILL','REPORT_EXPORT','SCHEDULE_SOLVE','IDEMPOTENCY','BACKUP_CHECK')),
    idempotency_key varchar(180),
    request_hash char(64),
    payload jsonb NOT NULL DEFAULT '{"schemaVersion":1}'::jsonb,
    result jsonb,
    status varchar(12) NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED','RUNNING','SUCCESS','FAILED')),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    scheduled_at timestamptz NOT NULL DEFAULT now(),
    started_at timestamptz,
    finished_at timestamptz,
    expires_at timestamptz,
    locked_by varchar(120),
    locked_until timestamptz,
    last_error text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    CHECK (project_id IS NULL OR workspace_id IS NOT NULL),
    CHECK ((locked_by IS NULL) = (locked_until IS NULL)),
    CHECK (request_hash IS NULL OR request_hash ~ '^[0-9a-f]{64}$'),
    UNIQUE (project_id, id),
    UNIQUE (workspace_id, id),
    CHECK (payload IS NULL OR jsonb_typeof(payload) = 'object'),
    CHECK (result IS NULL OR jsonb_typeof(result) = 'object')
);

CREATE INDEX idx_alerts_project_id ON alerts (project_id);

CREATE INDEX idx_alerts_work_item_id ON alerts (work_item_id);

CREATE INDEX idx_alerts_previous_alert_id ON alerts (previous_alert_id);

CREATE INDEX idx_alerts_assigned_to ON alerts (assigned_to);

CREATE INDEX idx_alerts_acknowledged_by ON alerts (acknowledged_by);

CREATE INDEX idx_chat_channels_workspace_id ON chat_channels (workspace_id);

CREATE INDEX idx_chat_channels_project_id ON chat_channels (project_id);

CREATE INDEX idx_chat_channels_work_item_id ON chat_channels (work_item_id);

CREATE INDEX idx_chat_channels_created_by ON chat_channels (created_by);

CREATE INDEX idx_chat_channel_members_workspace_id ON chat_channel_members (workspace_id);

CREATE INDEX idx_chat_channel_members_project_id ON chat_channel_members (project_id);

CREATE INDEX idx_chat_channel_members_channel_id ON chat_channel_members (channel_id);

CREATE INDEX idx_chat_channel_members_user_id ON chat_channel_members (user_id);

CREATE INDEX idx_chat_messages_workspace_id ON chat_messages (workspace_id);

CREATE INDEX idx_chat_messages_project_id ON chat_messages (project_id);

CREATE INDEX idx_chat_messages_channel_id ON chat_messages (channel_id);

CREATE INDEX idx_chat_messages_sender_id ON chat_messages (sender_id);

CREATE INDEX idx_chat_messages_ai_job_id ON chat_messages (ai_job_id);

CREATE INDEX idx_chat_messages_reply_to_id ON chat_messages (reply_to_id);

CREATE INDEX idx_notifications_workspace_id ON notifications (workspace_id);

CREATE INDEX idx_notifications_user_id ON notifications (user_id);

CREATE INDEX idx_notifications_project_id ON notifications (project_id);

CREATE INDEX idx_reports_project_id ON reports (project_id);

CREATE INDEX idx_reports_milestone_id ON reports (milestone_id);

CREATE INDEX idx_reports_generated_by ON reports (generated_by);

CREATE INDEX idx_reports_ai_job_id ON reports (ai_job_id);

CREATE INDEX idx_reports_approved_by ON reports (approved_by);

CREATE INDEX idx_attachments_workspace_id ON attachments (workspace_id);

CREATE INDEX idx_attachments_project_id ON attachments (project_id);

CREATE INDEX idx_attachments_work_item_id ON attachments (work_item_id);

CREATE INDEX idx_attachments_chat_message_id ON attachments (chat_message_id);

CREATE INDEX idx_attachments_report_id ON attachments (report_id);

CREATE INDEX idx_attachments_ci_run_id ON attachments (ci_run_id);

CREATE INDEX idx_attachments_uploaded_by ON attachments (uploaded_by);

CREATE INDEX idx_ai_jobs_workspace_id ON ai_jobs (workspace_id);

CREATE INDEX idx_ai_jobs_project_id ON ai_jobs (project_id);

CREATE INDEX idx_ai_jobs_requested_by ON ai_jobs (requested_by);

CREATE INDEX idx_audit_logs_workspace_id ON audit_logs (workspace_id);

CREATE INDEX idx_audit_logs_project_id ON audit_logs (project_id);

CREATE INDEX idx_audit_logs_actor_id ON audit_logs (actor_id);

CREATE INDEX idx_background_jobs_workspace_id ON background_jobs (workspace_id);

CREATE INDEX idx_background_jobs_project_id ON background_jobs (project_id);

CREATE INDEX idx_background_jobs_requested_by ON background_jobs (requested_by);

CREATE UNIQUE INDEX uq_open_alert_fingerprint ON alerts(project_id,fingerprint) WHERE status <> 'RESOLVED';

CREATE UNIQUE INDEX uq_task_channel ON chat_channels(project_id,work_item_id) WHERE type='TASK';

CREATE INDEX idx_message_cursor ON chat_messages(channel_id,sent_at,id);

CREATE INDEX idx_user_notifications ON notifications(user_id,created_at DESC) WHERE read_at IS NULL;

CREATE UNIQUE INDEX uq_job_idempotency ON background_jobs(workspace_id,project_id,job_kind,idempotency_key) NULLS NOT DISTINCT WHERE idempotency_key IS NOT NULL;

CREATE INDEX idx_job_claim ON background_jobs(scheduled_at,id) WHERE status IN ('QUEUED','FAILED');

CREATE INDEX idx_audit_entity ON audit_logs(workspace_id,project_id,entity_type,entity_id,occurred_at);

CREATE INDEX idx_audit_trace ON audit_logs(trace_id);

CREATE INDEX idx_client_reports ON reports(project_id,published_at DESC) WHERE status='PUBLISHED' AND visible_to_client;

CREATE INDEX idx_ai_job_status ON ai_jobs(workspace_id,project_id,status,created_at);

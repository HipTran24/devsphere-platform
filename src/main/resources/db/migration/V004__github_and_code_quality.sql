-- Devsphere PostgreSQL business schema. All timestamps are UTC instants.

CREATE TABLE github_repositories (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id uuid NOT NULL REFERENCES workspaces(id),
    project_id uuid NOT NULL,
    github_installation_id bigint NOT NULL CHECK (github_installation_id > 0),
    github_repo_id bigint NOT NULL UNIQUE CHECK (github_repo_id > 0),
    full_name varchar(255) NOT NULL,
    default_branch varchar(120) NOT NULL,
    private boolean NOT NULL DEFAULT true,
    active boolean NOT NULL DEFAULT true,
    sync_cursor text,
    last_synced_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    UNIQUE (project_id, id),
    UNIQUE (workspace_id, id)
);

CREATE TABLE github_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id uuid NOT NULL REFERENCES projects(id),
    repository_id uuid NOT NULL,
    delivery_id varchar(120) NOT NULL UNIQUE,
    event_type varchar(80) NOT NULL,
    action varchar(80),
    payload jsonb NOT NULL,
    process_status varchar(12) NOT NULL DEFAULT 'RECEIVED' CHECK (process_status IN ('RECEIVED','PROCESSING','DONE','FAILED')),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    received_at timestamptz NOT NULL DEFAULT now(),
    processed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    UNIQUE (project_id, id),
    CHECK (payload IS NULL OR jsonb_typeof(payload) = 'object')
);

CREATE TABLE github_activities (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id uuid NOT NULL REFERENCES projects(id),
    repository_id uuid NOT NULL,
    work_item_id uuid,
    kind varchar(16) NOT NULL CHECK (kind IN ('BRANCH','COMMIT','PULL_REQUEST','HUMAN_REVIEW','QUALITY_GATE')),
    github_object_id varchar(100) NOT NULL,
    commit_sha char(40) CHECK (commit_sha ~ '^[0-9a-f]{40}$'),
    pr_number integer CHECK (pr_number > 0),
    pr_head_sha char(40) CHECK (pr_head_sha ~ '^[0-9a-f]{40}$'),
    actor_login varchar(150),
    state varchar(60) NOT NULL,
    title text,
    payload jsonb NOT NULL DEFAULT '{}'::jsonb,
    happened_at timestamptz NOT NULL,
    synced_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    UNIQUE (repository_id, kind, github_object_id),
    UNIQUE (project_id, id),
    CHECK (payload IS NULL OR jsonb_typeof(payload) = 'object')
);

CREATE TABLE ci_runs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id uuid NOT NULL REFERENCES projects(id),
    repository_id uuid NOT NULL,
    work_item_id uuid,
    github_run_id bigint NOT NULL CHECK (github_run_id > 0),
    run_attempt integer NOT NULL DEFAULT 1 CHECK (run_attempt > 0),
    head_sha char(40) NOT NULL CHECK (head_sha ~ '^[0-9a-f]{40}$'),
    pr_number integer CHECK (pr_number > 0),
    status varchar(16) NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED','IN_PROGRESS','COMPLETED')),
    conclusion varchar(16) CHECK (conclusion IN ('SUCCESS','FAILURE','CANCELLED','SKIPPED','TIMED_OUT','NEUTRAL','ACTION_REQUIRED','STALE','STARTUP_FAILURE')),
    checks jsonb NOT NULL DEFAULT '[]'::jsonb,
    metrics jsonb NOT NULL DEFAULT '{"schemaVersion":1}'::jsonb,
    started_at timestamptz,
    completed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    UNIQUE (repository_id, github_run_id, run_attempt),
    CHECK ((status = 'COMPLETED') = (conclusion IS NOT NULL AND completed_at IS NOT NULL)),
    CHECK (completed_at IS NULL OR started_at IS NULL OR completed_at >= started_at),
    UNIQUE (project_id, id),
    CHECK (checks IS NULL OR jsonb_typeof(checks) = 'array'),
    CHECK (metrics IS NULL OR jsonb_typeof(metrics) = 'object')
);

CREATE TABLE code_reviews (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id uuid NOT NULL REFERENCES projects(id),
    repository_id uuid NOT NULL,
    work_item_id uuid,
    ai_job_id uuid,
    pr_number integer CHECK (pr_number > 0),
    head_sha char(40) NOT NULL CHECK (head_sha ~ '^[0-9a-f]{40}$'),
    context_version bigint NOT NULL DEFAULT 0 CHECK (context_version >= 0),
    scope varchar(8) NOT NULL CHECK (scope IN ('PUSH','PR')),
    reviewer_kind varchar(8) NOT NULL CHECK (reviewer_kind IN ('AI','HUMAN')),
    findings jsonb NOT NULL DEFAULT '[]'::jsonb,
    summary text,
    status varchar(24) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','RUNNING','PASS','FAIL','REQUIRES_CHANGES','UNAVAILABLE','STALE')),
    gate_snapshot jsonb NOT NULL DEFAULT '{"schemaVersion":1}'::jsonb,
    reviewer_id uuid REFERENCES users(id),
    model_name varchar(120),
    completed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    CHECK ((reviewer_kind = 'HUMAN' AND reviewer_id IS NOT NULL) OR reviewer_kind = 'AI'),
    CHECK (scope <> 'PR' OR pr_number IS NOT NULL),
    UNIQUE (project_id, id),
    CHECK (findings IS NULL OR jsonb_typeof(findings) = 'array'),
    CHECK (gate_snapshot IS NULL OR jsonb_typeof(gate_snapshot) = 'object')
);

CREATE INDEX idx_github_repositories_workspace_id ON github_repositories (workspace_id);

CREATE INDEX idx_github_repositories_project_id ON github_repositories (project_id);

CREATE INDEX idx_github_events_project_id ON github_events (project_id);

CREATE INDEX idx_github_events_repository_id ON github_events (repository_id);

CREATE INDEX idx_github_activities_project_id ON github_activities (project_id);

CREATE INDEX idx_github_activities_repository_id ON github_activities (repository_id);

CREATE INDEX idx_github_activities_work_item_id ON github_activities (work_item_id);

CREATE INDEX idx_ci_runs_project_id ON ci_runs (project_id);

CREATE INDEX idx_ci_runs_repository_id ON ci_runs (repository_id);

CREATE INDEX idx_ci_runs_work_item_id ON ci_runs (work_item_id);

CREATE INDEX idx_code_reviews_project_id ON code_reviews (project_id);

CREATE INDEX idx_code_reviews_repository_id ON code_reviews (repository_id);

CREATE INDEX idx_code_reviews_work_item_id ON code_reviews (work_item_id);

CREATE INDEX idx_code_reviews_ai_job_id ON code_reviews (ai_job_id);

CREATE INDEX idx_code_reviews_reviewer_id ON code_reviews (reviewer_id);

CREATE INDEX idx_activity_task_time ON github_activities(project_id,work_item_id,happened_at DESC);

CREATE INDEX idx_activity_pr ON github_activities(repository_id,pr_number);

CREATE INDEX idx_ci_head ON ci_runs(repository_id,head_sha,run_attempt DESC);

CREATE INDEX idx_review_head ON code_reviews(repository_id,pr_number,head_sha,context_version);

CREATE INDEX idx_github_inbox ON github_events(received_at) WHERE process_status IN ('RECEIVED','FAILED');

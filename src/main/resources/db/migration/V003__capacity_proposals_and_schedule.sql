-- Devsphere PostgreSQL business schema. All timestamps are UTC instants.

CREATE TABLE developer_capacity (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id uuid NOT NULL REFERENCES projects(id),
    user_id uuid NOT NULL REFERENCES users(id),
    skills jsonb NOT NULL DEFAULT '[]'::jsonb,
    weekly_slots jsonb NOT NULL DEFAULT '[]'::jsonb,
    time_off jsonb NOT NULL DEFAULT '[]'::jsonb,
    minutes_per_workday integer NOT NULL CHECK (minutes_per_workday BETWEEN 1 AND 1440),
    allocation_pct numeric(5,2) NOT NULL DEFAULT 100 CHECK (allocation_pct > 0 AND allocation_pct <= 100),
    effective_from date NOT NULL,
    effective_to date,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    UNIQUE (project_id, user_id, effective_from),
    CHECK (effective_to IS NULL OR effective_to > effective_from),
    UNIQUE (project_id, id),
    CHECK (skills IS NULL OR jsonb_typeof(skills) = 'array'),
    CHECK (weekly_slots IS NULL OR jsonb_typeof(weekly_slots) = 'array'),
    CHECK (time_off IS NULL OR jsonb_typeof(time_off) = 'array')
);

CREATE TABLE plan_proposals (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id uuid NOT NULL REFERENCES projects(id),
    kind varchar(24) NOT NULL CHECK (kind IN ('AI_REQUIREMENT','MANUAL_REPLAN','AUTO_REPLAN')),
    base_project_version bigint NOT NULL CHECK (base_project_version >= 0),
    base_schedule_version_id uuid,
    requirement_version_id uuid,
    change_request_id uuid,
    ai_job_id uuid,
    status varchar(24) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','PENDING_APPROVAL','APPROVED','REJECTED','APPLIED','STALE')),
    proposal_payload jsonb NOT NULL,
    solver_status varchar(16) NOT NULL DEFAULT 'NOT_RUN' CHECK (solver_status IN ('NOT_RUN','OPTIMAL','FEASIBLE','INFEASIBLE','UNKNOWN','ERROR')),
    solver_details jsonb NOT NULL DEFAULT '{"schemaVersion":1}'::jsonb,
    assumptions jsonb NOT NULL DEFAULT '[]'::jsonb,
    created_by uuid NOT NULL REFERENCES users(id),
    approved_by uuid REFERENCES users(id),
    approved_at timestamptz,
    changes_committed_deadline boolean NOT NULL DEFAULT false,
    proposed_committed_deadline date,
    deadline_confirmed_by uuid REFERENCES users(id),
    deadline_confirmed_at timestamptz,
    deadline_confirmation_version bigint,
    deadline_confirmed_project_version bigint,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    CHECK (status NOT IN ('APPROVED','APPLIED') OR (approved_by IS NOT NULL AND approved_at IS NOT NULL)),
    CHECK (status <> 'APPLIED' OR solver_status IN ('OPTIMAL','FEASIBLE')),
    CHECK ((deadline_confirmed_by IS NULL) = (deadline_confirmed_at IS NULL)),
    CHECK (proposal_payload ? 'schemaVersion' AND proposal_payload->>'schemaVersion' = '1'),
    UNIQUE (project_id, id),
    CHECK (proposal_payload IS NULL OR jsonb_typeof(proposal_payload) = 'object'),
    CHECK (solver_details IS NULL OR jsonb_typeof(solver_details) = 'object'),
    CHECK (assumptions IS NULL OR jsonb_typeof(assumptions) = 'array')
);

CREATE TABLE schedule_versions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id uuid NOT NULL REFERENCES projects(id),
    version_no integer NOT NULL CHECK (version_no > 0),
    source varchar(16) NOT NULL CHECK (source IN ('MANUAL','AI_PLAN','RESCHEDULE')),
    proposal_id uuid,
    approved_by uuid NOT NULL REFERENCES users(id),
    approved_at timestamptz NOT NULL,
    planned_finish date,
    forecast_finish date,
    milestone_delays jsonb NOT NULL DEFAULT '{"schemaVersion":1}'::jsonb,
    baseline_snapshot jsonb NOT NULL DEFAULT '{"schemaVersion":1}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (project_id, version_no),
    UNIQUE (project_id, id),
    CHECK (milestone_delays IS NULL OR jsonb_typeof(milestone_delays) = 'object'),
    CHECK (baseline_snapshot IS NULL OR jsonb_typeof(baseline_snapshot) = 'object')
);

CREATE TABLE schedule_entries (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id uuid NOT NULL REFERENCES projects(id),
    schedule_version_id uuid NOT NULL,
    work_item_id uuid NOT NULL,
    sprint_id uuid,
    assignee_id uuid REFERENCES users(id),
    start_at timestamptz NOT NULL,
    finish_at timestamptz NOT NULL,
    remaining_minutes_snapshot integer NOT NULL CHECK (remaining_minutes_snapshot >= 0),
    frozen boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (schedule_version_id, work_item_id),
    CHECK (finish_at >= start_at),
    UNIQUE (project_id, id)
);

CREATE INDEX idx_developer_capacity_project_id ON developer_capacity (project_id);

CREATE INDEX idx_developer_capacity_user_id ON developer_capacity (user_id);

CREATE INDEX idx_plan_proposals_project_id ON plan_proposals (project_id);

CREATE INDEX idx_plan_proposals_base_schedule_version_id ON plan_proposals (base_schedule_version_id);

CREATE INDEX idx_plan_proposals_requirement_version_id ON plan_proposals (requirement_version_id);

CREATE INDEX idx_plan_proposals_change_request_id ON plan_proposals (change_request_id);

CREATE INDEX idx_plan_proposals_ai_job_id ON plan_proposals (ai_job_id);

CREATE INDEX idx_plan_proposals_created_by ON plan_proposals (created_by);

CREATE INDEX idx_plan_proposals_approved_by ON plan_proposals (approved_by);

CREATE INDEX idx_plan_proposals_deadline_confirmed_by ON plan_proposals (deadline_confirmed_by);

CREATE INDEX idx_schedule_versions_project_id ON schedule_versions (project_id);

CREATE INDEX idx_schedule_versions_proposal_id ON schedule_versions (proposal_id);

CREATE INDEX idx_schedule_versions_approved_by ON schedule_versions (approved_by);

CREATE INDEX idx_schedule_entries_project_id ON schedule_entries (project_id);

CREATE INDEX idx_schedule_entries_schedule_version_id ON schedule_entries (schedule_version_id);

CREATE INDEX idx_schedule_entries_work_item_id ON schedule_entries (work_item_id);

CREATE INDEX idx_schedule_entries_sprint_id ON schedule_entries (sprint_id);

CREATE INDEX idx_schedule_entries_assignee_id ON schedule_entries (assignee_id);

ALTER TABLE developer_capacity ADD CONSTRAINT ex_capacity_effective_overlap EXCLUDE USING gist (project_id WITH =, user_id WITH =, daterange(effective_from,effective_to,'[)') WITH &&);

CREATE INDEX idx_schedule_timeline ON schedule_entries(project_id,schedule_version_id,start_at);

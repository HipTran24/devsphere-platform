-- Devsphere PostgreSQL business schema. All timestamps are UTC instants.

CREATE TABLE projects (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id uuid NOT NULL UNIQUE REFERENCES workspaces(id),
    project_key varchar(16) NOT NULL CHECK (project_key ~ '^[A-Z][A-Z0-9]{1,15}$'),
    name varchar(160) NOT NULL CHECK (length(btrim(name)) > 0),
    description text,
    objective text,
    status varchar(12) NOT NULL DEFAULT 'PLANNING' CHECK (status IN ('PLANNING','ACTIVE','ON_HOLD','COMPLETED','ARCHIVED')),
    timezone varchar(64) NOT NULL DEFAULT 'Asia/Ho_Chi_Minh',
    planned_start date,
    committed_deadline date,
    sprint_length_days integer NOT NULL DEFAULT 14 CHECK (sprint_length_days BETWEEN 1 AND 90),
    settings jsonb NOT NULL DEFAULT '{"schemaVersion":1}'::jsonb,
    active_schedule_version_id uuid,
    deadline_approver_id uuid REFERENCES users(id),
    next_item_number bigint NOT NULL DEFAULT 1 CHECK (next_item_number > 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    CHECK (committed_deadline IS NULL OR planned_start IS NULL OR committed_deadline >= planned_start),
    UNIQUE (workspace_id, id),
    CHECK (settings IS NULL OR jsonb_typeof(settings) = 'object')
);

CREATE TABLE project_members (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id uuid NOT NULL,
    workspace_id uuid NOT NULL REFERENCES workspaces(id),
    user_id uuid NOT NULL,
    status varchar(12) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','REMOVED')),
    joined_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    UNIQUE (project_id, user_id),
    UNIQUE (project_id, id),
    UNIQUE (workspace_id, id)
);

CREATE TABLE project_member_roles (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id uuid NOT NULL REFERENCES projects(id),
    project_member_id uuid NOT NULL,
    role varchar(12) NOT NULL CHECK (role IN ('PM','DEVELOPER','CLIENT','VIEWER')),
    granted_by uuid NOT NULL REFERENCES users(id),
    granted_at timestamptz NOT NULL DEFAULT now(),
    accepted_by uuid REFERENCES users(id),
    accepted_at timestamptz,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (revoked_at IS NULL OR revoked_at >= granted_at),
    CHECK (role <> 'PM' OR (accepted_by IS NOT NULL AND accepted_at IS NOT NULL AND accepted_at >= granted_at)),
    UNIQUE (project_id, id)
);

CREATE TABLE requirements (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id uuid NOT NULL REFERENCES projects(id),
    code varchar(32) NOT NULL,
    kind varchar(16) NOT NULL DEFAULT 'REQUIREMENT' CHECK (kind IN ('REQUIREMENT','CHANGE_REQUEST')),
    parent_requirement_id uuid,
    title varchar(220) NOT NULL CHECK (length(btrim(title)) > 0),
    description text NOT NULL DEFAULT '',
    status varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','APPROVED','ASSESSING','REJECTED','CHANGED','ARCHIVED')),
    change_request_status varchar(24) CHECK (change_request_status IN ('PENDING','APPROVED','REJECTED','DEFERRED','IMPLEMENTED')),
    current_version integer NOT NULL DEFAULT 1 CHECK (current_version > 0),
    requested_by uuid NOT NULL REFERENCES users(id),
    approved_by uuid REFERENCES users(id),
    impact_json jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    UNIQUE (project_id, code),
    CHECK (parent_requirement_id IS DISTINCT FROM id),
    CHECK ((kind = 'CHANGE_REQUEST') = (change_request_status IS NOT NULL)),
    UNIQUE (project_id, id),
    CHECK (impact_json IS NULL OR jsonb_typeof(impact_json) = 'object')
);

CREATE TABLE requirement_versions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id uuid NOT NULL REFERENCES projects(id),
    requirement_id uuid NOT NULL,
    version_no integer NOT NULL CHECK (version_no > 0),
    description text NOT NULL,
    acceptance_summary text NOT NULL DEFAULT '',
    change_reason text,
    approved_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (requirement_id, version_no),
    UNIQUE (project_id, id)
);

CREATE TABLE sprints (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id uuid NOT NULL REFERENCES projects(id),
    name varchar(120) NOT NULL,
    goal text NOT NULL DEFAULT '',
    start_date date NOT NULL,
    end_date date NOT NULL,
    status varchar(12) NOT NULL DEFAULT 'PLANNED' CHECK (status IN ('PLANNED','ACTIVE','CLOSED')),
    review_notes text,
    retrospective_notes text,
    committed_points numeric(8,2) CHECK (committed_points >= 0),
    completed_points numeric(8,2) CHECK (completed_points >= 0),
    closed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    CHECK (end_date > start_date),
    CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL)),
    UNIQUE (project_id, id)
);

CREATE TABLE milestones (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id uuid NOT NULL REFERENCES projects(id),
    code varchar(24) NOT NULL,
    name varchar(160) NOT NULL,
    description text,
    due_date date NOT NULL,
    forecast_date date,
    status varchar(24) NOT NULL DEFAULT 'PLANNED' CHECK (status IN ('PLANNED','AT_RISK','READY_FOR_ACCEPTANCE','ACCEPTED')),
    acceptance_status varchar(32) NOT NULL DEFAULT 'NOT_SUBMITTED' CHECK (acceptance_status IN ('NOT_SUBMITTED','PENDING_REVIEW','ACCEPTED','CHANGES_REQUESTED')),
    published_to_client boolean NOT NULL DEFAULT false,
    acceptance_history jsonb NOT NULL DEFAULT '[]'::jsonb,
    accepted_by uuid REFERENCES users(id),
    accepted_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    UNIQUE (project_id, code),
    CHECK ((acceptance_status = 'ACCEPTED') = (accepted_by IS NOT NULL AND accepted_at IS NOT NULL)),
    CHECK ((status = 'ACCEPTED') = (acceptance_status = 'ACCEPTED')),
    UNIQUE (project_id, id),
    CHECK (acceptance_history IS NULL OR jsonb_typeof(acceptance_history) = 'array')
);

CREATE TABLE work_items (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id uuid NOT NULL REFERENCES projects(id),
    item_key varchar(32) NOT NULL,
    parent_id uuid,
    requirement_id uuid,
    sprint_id uuid,
    milestone_id uuid,
    type varchar(16) NOT NULL CHECK (type IN ('EPIC','USER_STORY','TASK','SUBTASK','BUG')),
    work_kind varchar(12) NOT NULL DEFAULT 'CODE' CHECK (work_kind IN ('CODE','NON_CODE')),
    title varchar(255) NOT NULL CHECK (length(btrim(title)) > 0),
    description text NOT NULL DEFAULT '',
    status varchar(16) NOT NULL DEFAULT 'TO_DO' CHECK (status IN ('TO_DO','IN_PROGRESS','IN_REVIEW','DONE')),
    is_blocked boolean NOT NULL DEFAULT false,
    blocked_reason text,
    priority varchar(12) NOT NULL DEFAULT 'MEDIUM' CHECK (priority IN ('LOW','MEDIUM','HIGH','CRITICAL')),
    assignee_id uuid REFERENCES users(id),
    required_skills jsonb NOT NULL DEFAULT '[]'::jsonb,
    labels text[] NOT NULL DEFAULT '{}',
    backlog_rank numeric(24,8) NOT NULL DEFAULT 0,
    story_points numeric(5,2) CHECK (story_points >= 0),
    estimate_minutes integer CHECK (estimate_minutes >= 0),
    remaining_minutes integer CHECK (remaining_minutes >= 0),
    time_spent_minutes integer NOT NULL DEFAULT 0 CHECK (time_spent_minutes >= 0),
    claimed_progress numeric(5,2) NOT NULL DEFAULT 0 CHECK (claimed_progress BETWEEN 0 AND 100),
    verified_progress numeric(5,2) CHECK (verified_progress BETWEEN 0 AND 100),
    progress_evidence jsonb NOT NULL DEFAULT '{"schemaVersion":1}'::jsonb,
    started_at timestamptz,
    completed_at timestamptz,
    due_at timestamptz,
    accepted_by uuid REFERENCES users(id),
    deleted_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    UNIQUE (project_id, item_key),
    CHECK (parent_id IS DISTINCT FROM id),
    CHECK (NOT is_blocked OR (status <> 'DONE' AND blocked_reason IS NOT NULL AND length(btrim(blocked_reason)) > 0)),
    CHECK (is_blocked OR blocked_reason IS NULL),
    CHECK ((status = 'DONE') = (completed_at IS NOT NULL AND accepted_by IS NOT NULL)),
    CHECK (completed_at IS NULL OR started_at IS NULL OR completed_at >= started_at),
    UNIQUE (project_id, id),
    CHECK (required_skills IS NULL OR jsonb_typeof(required_skills) = 'array'),
    CHECK (progress_evidence IS NULL OR jsonb_typeof(progress_evidence) = 'object')
);

CREATE TABLE work_item_dependencies (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id uuid NOT NULL REFERENCES projects(id),
    predecessor_id uuid NOT NULL,
    successor_id uuid NOT NULL,
    link_type varchar(24) NOT NULL DEFAULT 'FINISH_TO_START' CHECK (link_type = 'FINISH_TO_START'),
    lag_minutes integer NOT NULL DEFAULT 0 CHECK (lag_minutes >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (predecessor_id, successor_id),
    CHECK (predecessor_id <> successor_id),
    UNIQUE (project_id, id)
);

CREATE TABLE acceptance_criteria (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id uuid NOT NULL REFERENCES projects(id),
    work_item_id uuid NOT NULL,
    position integer NOT NULL CHECK (position >= 0),
    description text NOT NULL CHECK (length(btrim(description)) > 0),
    evidence_kind varchar(16) NOT NULL CHECK (evidence_kind IN ('CI_TEST','PR','FILE','MANUAL')),
    weight numeric(8,3) NOT NULL DEFAULT 1 CHECK (weight > 0),
    status varchar(24) NOT NULL DEFAULT 'NOT_EVALUATED' CHECK (status IN ('NOT_EVALUATED','VERIFIED','NOT_MET','INSUFFICIENT_EVIDENCE')),
    evidence jsonb NOT NULL DEFAULT '[]'::jsonb,
    verified_by uuid REFERENCES users(id),
    verified_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    UNIQUE (work_item_id, position),
    CHECK ((status = 'VERIFIED') = (verified_by IS NOT NULL AND verified_at IS NOT NULL)),
    UNIQUE (project_id, id),
    CHECK (evidence IS NULL OR jsonb_typeof(evidence) = 'array')
);

CREATE INDEX idx_projects_workspace_id ON projects (workspace_id);

CREATE INDEX idx_projects_active_schedule_version_id ON projects (active_schedule_version_id);

CREATE INDEX idx_projects_deadline_approver_id ON projects (deadline_approver_id);

CREATE INDEX idx_project_members_project_id ON project_members (project_id);

CREATE INDEX idx_project_members_workspace_id ON project_members (workspace_id);

CREATE INDEX idx_project_members_user_id ON project_members (user_id);

CREATE INDEX idx_project_member_roles_project_id ON project_member_roles (project_id);

CREATE INDEX idx_project_member_roles_project_member_id ON project_member_roles (project_member_id);

CREATE INDEX idx_project_member_roles_granted_by ON project_member_roles (granted_by);

CREATE INDEX idx_project_member_roles_accepted_by ON project_member_roles (accepted_by);

CREATE INDEX idx_requirements_project_id ON requirements (project_id);

CREATE INDEX idx_requirements_parent_requirement_id ON requirements (parent_requirement_id);

CREATE INDEX idx_requirements_requested_by ON requirements (requested_by);

CREATE INDEX idx_requirements_approved_by ON requirements (approved_by);

CREATE INDEX idx_requirement_versions_project_id ON requirement_versions (project_id);

CREATE INDEX idx_requirement_versions_requirement_id ON requirement_versions (requirement_id);

CREATE INDEX idx_sprints_project_id ON sprints (project_id);

CREATE INDEX idx_milestones_project_id ON milestones (project_id);

CREATE INDEX idx_milestones_accepted_by ON milestones (accepted_by);

CREATE INDEX idx_work_items_project_id ON work_items (project_id);

CREATE INDEX idx_work_items_parent_id ON work_items (parent_id);

CREATE INDEX idx_work_items_requirement_id ON work_items (requirement_id);

CREATE INDEX idx_work_items_sprint_id ON work_items (sprint_id);

CREATE INDEX idx_work_items_milestone_id ON work_items (milestone_id);

CREATE INDEX idx_work_items_assignee_id ON work_items (assignee_id);

CREATE INDEX idx_work_items_accepted_by ON work_items (accepted_by);

CREATE INDEX idx_work_item_dependencies_project_id ON work_item_dependencies (project_id);

CREATE INDEX idx_work_item_dependencies_predecessor_id ON work_item_dependencies (predecessor_id);

CREATE INDEX idx_work_item_dependencies_successor_id ON work_item_dependencies (successor_id);

CREATE INDEX idx_acceptance_criteria_project_id ON acceptance_criteria (project_id);

CREATE INDEX idx_acceptance_criteria_work_item_id ON acceptance_criteria (work_item_id);

CREATE INDEX idx_acceptance_criteria_verified_by ON acceptance_criteria (verified_by);

CREATE UNIQUE INDEX uq_project_one_pm ON project_member_roles(project_id) WHERE role='PM' AND revoked_at IS NULL;

CREATE UNIQUE INDEX uq_member_active_role ON project_member_roles(project_member_id,role) WHERE revoked_at IS NULL;

CREATE UNIQUE INDEX uq_project_active_sprint ON sprints(project_id) WHERE status='ACTIVE';

CREATE INDEX idx_work_item_board ON work_items(project_id,sprint_id,status) WHERE deleted_at IS NULL;

CREATE INDEX idx_work_item_assignee ON work_items(project_id,assignee_id,status) WHERE deleted_at IS NULL;

CREATE INDEX idx_backlog_rank ON work_items(project_id,backlog_rank,id) WHERE deleted_at IS NULL;

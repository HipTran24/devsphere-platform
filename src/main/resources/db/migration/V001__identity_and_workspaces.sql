-- Devsphere PostgreSQL business schema. All timestamps are UTC instants.

CREATE EXTENSION IF NOT EXISTS citext;
CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE users (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email citext NOT NULL UNIQUE,
    password_hash varchar(255),
    display_name varchar(120) NOT NULL CHECK (length(btrim(display_name)) > 0),
    avatar_key text,
    system_role varchar(8) NOT NULL DEFAULT 'USER' CHECK (system_role IN ('ADMIN','USER')),
    status varchar(12) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','ACTIVE','LOCKED')),
    email_verified_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    CHECK (length(email::text) <= 320 AND position('@' IN email::text) > 1)
);

CREATE TABLE oauth_accounts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users(id),
    provider varchar(16) NOT NULL CHECK (provider IN ('GOOGLE','GITHUB')),
    provider_subject varchar(200) NOT NULL,
    provider_login varchar(160),
    linked_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (provider, provider_subject),
    UNIQUE (user_id, provider)
);

CREATE TABLE auth_tokens (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid REFERENCES users(id),
    email citext NOT NULL,
    purpose varchar(24) NOT NULL CHECK (purpose IN ('VERIFY_EMAIL','RESET_PASSWORD')),
    token_hash varchar(255) NOT NULL,
    expires_at timestamptz NOT NULL,
    used_at timestamptz,
    attempts smallint NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 5),
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (expires_at > created_at),
    CHECK (used_at IS NULL OR used_at >= created_at)
);

CREATE TABLE auth_sessions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users(id),
    refresh_hash varchar(255) NOT NULL UNIQUE,
    issued_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    user_agent text,
    ip inet,
    session_family_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (expires_at > issued_at),
    CHECK (revoked_at IS NULL OR revoked_at >= issued_at)
);

CREATE TABLE workspaces (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    slug varchar(80) NOT NULL UNIQUE CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    name varchar(160) NOT NULL CHECK (length(btrim(name)) > 0),
    owner_id uuid NOT NULL REFERENCES users(id),
    description text,
    status varchar(24) NOT NULL DEFAULT 'SETUP_INCOMPLETE' CHECK (status IN ('SETUP_INCOMPLETE','ACTIVE')),
    settings jsonb NOT NULL DEFAULT '{"schemaVersion":1}'::jsonb,
    ai_connections jsonb NOT NULL DEFAULT '[]'::jsonb,
    ai_task_routes jsonb NOT NULL DEFAULT '{"schemaVersion":1}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    CHECK (settings IS NULL OR jsonb_typeof(settings) = 'object'),
    CHECK (ai_connections IS NULL OR jsonb_typeof(ai_connections) = 'array'),
    CHECK (ai_task_routes IS NULL OR jsonb_typeof(ai_task_routes) = 'object')
);

CREATE TABLE workspace_members (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id uuid NOT NULL REFERENCES workspaces(id),
    user_id uuid NOT NULL REFERENCES users(id),
    role varchar(8) NOT NULL DEFAULT 'MEMBER' CHECK (role IN ('OWNER','MEMBER')),
    status varchar(12) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','REMOVED')),
    joined_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    UNIQUE (workspace_id, user_id),
    UNIQUE (workspace_id, id)
);

CREATE TABLE workspace_invitations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id uuid NOT NULL REFERENCES workspaces(id),
    email citext NOT NULL,
    token_hash varchar(255) NOT NULL UNIQUE,
    invited_by uuid NOT NULL REFERENCES users(id),
    role varchar(24) NOT NULL DEFAULT 'MEMBER' CHECK (role = 'MEMBER'),
    status varchar(12) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','ACCEPTED','DECLINED','EXPIRED','REVOKED')),
    expires_at timestamptz NOT NULL,
    accepted_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    CHECK (expires_at > created_at),
    CHECK ((status = 'ACCEPTED') = (accepted_at IS NOT NULL)),
    UNIQUE (workspace_id, id)
);

CREATE INDEX idx_oauth_accounts_user_id ON oauth_accounts (user_id);

CREATE INDEX idx_auth_tokens_user_id ON auth_tokens (user_id);

CREATE INDEX idx_auth_sessions_user_id ON auth_sessions (user_id);

CREATE INDEX idx_workspaces_owner_id ON workspaces (owner_id);

CREATE INDEX idx_workspace_members_workspace_id ON workspace_members (workspace_id);

CREATE INDEX idx_workspace_members_user_id ON workspace_members (user_id);

CREATE INDEX idx_workspace_invitations_workspace_id ON workspace_invitations (workspace_id);

CREATE INDEX idx_workspace_invitations_invited_by ON workspace_invitations (invited_by);

CREATE UNIQUE INDEX uq_workspace_one_owner ON workspace_members(workspace_id) WHERE role='OWNER' AND status='ACTIVE';

CREATE UNIQUE INDEX uq_pending_workspace_invitation ON workspace_invitations(workspace_id,email) WHERE status='PENDING';

CREATE INDEX idx_auth_tokens_lookup ON auth_tokens(email,purpose,created_at DESC);

CREATE INDEX idx_sessions_family ON auth_sessions(session_family_id);

CREATE INDEX idx_auth_tokens_expiry ON auth_tokens(expires_at) WHERE used_at IS NULL;

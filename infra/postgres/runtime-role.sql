-- Run by the official PostgreSQL image on first initialization, or explicitly
-- with `docker compose run --rm database-setup` for an existing volume.
-- Values are read from the process environment and quoted by psql.
\set ON_ERROR_STOP on
\set ECHO none
\getenv runtime_user DATABASE_USER
\getenv runtime_password DATABASE_PASSWORD

\if :{?runtime_user}
\else
    DO $$ BEGIN RAISE EXCEPTION 'DATABASE_USER is required'; END $$;
\endif
\if :{?runtime_password}
\else
    DO $$ BEGIN RAISE EXCEPTION 'DATABASE_PASSWORD is required'; END $$;
\endif

SELECT :'runtime_user' ~ '^[a-z_][a-z0-9_]{0,62}$'
    AND :'runtime_user' <> current_user
    AND length(:'runtime_password') > 0 AS valid_runtime_config
\gset
\if :valid_runtime_config
\else
    DO $$ BEGIN RAISE EXCEPTION 'Use a separate lowercase runtime role and a nonempty password'; END $$;
\endif

BEGIN;
SELECT format('CREATE ROLE %I LOGIN', :'runtime_user')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'runtime_user')
\gexec

ALTER ROLE :"runtime_user" WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
    NOINHERIT NOREPLICATION PASSWORD :'runtime_password';
GRANT CONNECT ON DATABASE :"DBNAME" TO :"runtime_user";
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO :"runtime_user";
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO :"runtime_user";
COMMIT;

-- Existing-table grants and immutable-history restrictions are applied by the
-- Java Flyway AFTER_MIGRATE callback, including runs without pending migrations.

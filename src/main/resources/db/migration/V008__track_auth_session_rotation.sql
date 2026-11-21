ALTER TABLE auth_sessions ADD COLUMN rotated_at timestamptz;

ALTER TABLE auth_sessions ADD CONSTRAINT auth_sessions_rotation_requires_revocation
    CHECK (rotated_at IS NULL OR (revoked_at IS NOT NULL AND rotated_at = revoked_at));

CREATE FUNCTION ds_auth_session_rotation_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.rotated_at IS NOT NULL AND NEW.rotated_at IS DISTINCT FROM OLD.rotated_at THEN
        RAISE EXCEPTION 'Session rotation cannot be undone' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER auth_session_rotation_immutable BEFORE UPDATE OF rotated_at ON auth_sessions
FOR EACH ROW EXECUTE FUNCTION ds_auth_session_rotation_immutable();

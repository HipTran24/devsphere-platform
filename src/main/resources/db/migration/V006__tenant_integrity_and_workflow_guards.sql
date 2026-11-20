-- Devsphere PostgreSQL business schema. All timestamps are UTC instants.

ALTER TABLE projects ADD CONSTRAINT fk_projects_active_schedule_version_id FOREIGN KEY (active_schedule_version_id) REFERENCES schedule_versions(id);

ALTER TABLE project_members ADD CONSTRAINT fk_project_members_project_id_scope FOREIGN KEY (workspace_id, project_id) REFERENCES projects(workspace_id, id);

ALTER TABLE project_members ADD CONSTRAINT fk_project_members_user_id_scope FOREIGN KEY (workspace_id, user_id) REFERENCES workspace_members(workspace_id, user_id);

ALTER TABLE project_member_roles ADD CONSTRAINT fk_project_member_roles_project_member_id_scope FOREIGN KEY (project_id, project_member_id) REFERENCES project_members(project_id, id);

ALTER TABLE requirements ADD CONSTRAINT fk_requirements_parent_requirement_id_scope FOREIGN KEY (project_id, parent_requirement_id) REFERENCES requirements(project_id, id);

ALTER TABLE requirement_versions ADD CONSTRAINT fk_requirement_versions_requirement_id_scope FOREIGN KEY (project_id, requirement_id) REFERENCES requirements(project_id, id);

ALTER TABLE work_items ADD CONSTRAINT fk_work_items_parent_id_scope FOREIGN KEY (project_id, parent_id) REFERENCES work_items(project_id, id);

ALTER TABLE work_items ADD CONSTRAINT fk_work_items_requirement_id_scope FOREIGN KEY (project_id, requirement_id) REFERENCES requirements(project_id, id);

ALTER TABLE work_items ADD CONSTRAINT fk_work_items_sprint_id_scope FOREIGN KEY (project_id, sprint_id) REFERENCES sprints(project_id, id);

ALTER TABLE work_items ADD CONSTRAINT fk_work_items_milestone_id_scope FOREIGN KEY (project_id, milestone_id) REFERENCES milestones(project_id, id);

ALTER TABLE work_item_dependencies ADD CONSTRAINT fk_work_item_dependencies_predecessor_id_scope FOREIGN KEY (project_id, predecessor_id) REFERENCES work_items(project_id, id);

ALTER TABLE work_item_dependencies ADD CONSTRAINT fk_work_item_dependencies_successor_id_scope FOREIGN KEY (project_id, successor_id) REFERENCES work_items(project_id, id);

ALTER TABLE acceptance_criteria ADD CONSTRAINT fk_acceptance_criteria_work_item_id_scope FOREIGN KEY (project_id, work_item_id) REFERENCES work_items(project_id, id);

ALTER TABLE plan_proposals ADD CONSTRAINT fk_plan_proposals_base_schedule_version_id_scope FOREIGN KEY (project_id, base_schedule_version_id) REFERENCES schedule_versions(project_id, id);

ALTER TABLE plan_proposals ADD CONSTRAINT fk_plan_proposals_requirement_version_id_scope FOREIGN KEY (project_id, requirement_version_id) REFERENCES requirement_versions(project_id, id);

ALTER TABLE plan_proposals ADD CONSTRAINT fk_plan_proposals_change_request_id_scope FOREIGN KEY (project_id, change_request_id) REFERENCES requirements(project_id, id);

ALTER TABLE plan_proposals ADD CONSTRAINT fk_plan_proposals_ai_job_id_scope FOREIGN KEY (project_id, ai_job_id) REFERENCES ai_jobs(project_id, id);

ALTER TABLE schedule_versions ADD CONSTRAINT fk_schedule_versions_proposal_id_scope FOREIGN KEY (project_id, proposal_id) REFERENCES plan_proposals(project_id, id);

ALTER TABLE schedule_entries ADD CONSTRAINT fk_schedule_entries_schedule_version_id_scope FOREIGN KEY (project_id, schedule_version_id) REFERENCES schedule_versions(project_id, id);

ALTER TABLE schedule_entries ADD CONSTRAINT fk_schedule_entries_work_item_id_scope FOREIGN KEY (project_id, work_item_id) REFERENCES work_items(project_id, id);

ALTER TABLE schedule_entries ADD CONSTRAINT fk_schedule_entries_sprint_id_scope FOREIGN KEY (project_id, sprint_id) REFERENCES sprints(project_id, id);

ALTER TABLE github_repositories ADD CONSTRAINT fk_github_repositories_project_id_scope FOREIGN KEY (workspace_id, project_id) REFERENCES projects(workspace_id, id);

ALTER TABLE github_events ADD CONSTRAINT fk_github_events_repository_id_scope FOREIGN KEY (project_id, repository_id) REFERENCES github_repositories(project_id, id);

ALTER TABLE github_activities ADD CONSTRAINT fk_github_activities_repository_id_scope FOREIGN KEY (project_id, repository_id) REFERENCES github_repositories(project_id, id);

ALTER TABLE github_activities ADD CONSTRAINT fk_github_activities_work_item_id_scope FOREIGN KEY (project_id, work_item_id) REFERENCES work_items(project_id, id);

ALTER TABLE ci_runs ADD CONSTRAINT fk_ci_runs_repository_id_scope FOREIGN KEY (project_id, repository_id) REFERENCES github_repositories(project_id, id);

ALTER TABLE ci_runs ADD CONSTRAINT fk_ci_runs_work_item_id_scope FOREIGN KEY (project_id, work_item_id) REFERENCES work_items(project_id, id);

ALTER TABLE code_reviews ADD CONSTRAINT fk_code_reviews_repository_id_scope FOREIGN KEY (project_id, repository_id) REFERENCES github_repositories(project_id, id);

ALTER TABLE code_reviews ADD CONSTRAINT fk_code_reviews_work_item_id_scope FOREIGN KEY (project_id, work_item_id) REFERENCES work_items(project_id, id);

ALTER TABLE code_reviews ADD CONSTRAINT fk_code_reviews_ai_job_id_scope FOREIGN KEY (project_id, ai_job_id) REFERENCES ai_jobs(project_id, id);

ALTER TABLE alerts ADD CONSTRAINT fk_alerts_work_item_id_scope FOREIGN KEY (project_id, work_item_id) REFERENCES work_items(project_id, id);

ALTER TABLE alerts ADD CONSTRAINT fk_alerts_previous_alert_id_scope FOREIGN KEY (project_id, previous_alert_id) REFERENCES alerts(project_id, id);

ALTER TABLE chat_channels ADD CONSTRAINT fk_chat_channels_project_id_scope FOREIGN KEY (workspace_id, project_id) REFERENCES projects(workspace_id, id);

ALTER TABLE chat_channels ADD CONSTRAINT fk_chat_channels_work_item_id_scope FOREIGN KEY (project_id, work_item_id) REFERENCES work_items(project_id, id);

ALTER TABLE chat_channel_members ADD CONSTRAINT fk_chat_channel_members_channel_id_scope FOREIGN KEY (workspace_id, channel_id) REFERENCES chat_channels(workspace_id, id);

ALTER TABLE chat_messages ADD CONSTRAINT fk_chat_messages_channel_id_scope FOREIGN KEY (workspace_id, channel_id) REFERENCES chat_channels(workspace_id, id);

ALTER TABLE chat_messages ADD CONSTRAINT fk_chat_messages_ai_job_id_scope FOREIGN KEY (workspace_id, ai_job_id) REFERENCES ai_jobs(workspace_id, id);

ALTER TABLE notifications ADD CONSTRAINT fk_notifications_project_id_scope FOREIGN KEY (workspace_id, project_id) REFERENCES projects(workspace_id, id);

ALTER TABLE reports ADD CONSTRAINT fk_reports_milestone_id_scope FOREIGN KEY (project_id, milestone_id) REFERENCES milestones(project_id, id);

ALTER TABLE reports ADD CONSTRAINT fk_reports_ai_job_id_scope FOREIGN KEY (project_id, ai_job_id) REFERENCES ai_jobs(project_id, id);

ALTER TABLE attachments ADD CONSTRAINT fk_attachments_work_item_id_scope FOREIGN KEY (project_id, work_item_id) REFERENCES work_items(project_id, id);

ALTER TABLE attachments ADD CONSTRAINT fk_attachments_report_id_scope FOREIGN KEY (project_id, report_id) REFERENCES reports(project_id, id);

ALTER TABLE attachments ADD CONSTRAINT fk_attachments_ci_run_id_scope FOREIGN KEY (project_id, ci_run_id) REFERENCES ci_runs(project_id, id);

ALTER TABLE ai_jobs ADD CONSTRAINT fk_ai_jobs_project_id_scope FOREIGN KEY (workspace_id, project_id) REFERENCES projects(workspace_id, id);

ALTER TABLE audit_logs ADD CONSTRAINT fk_audit_logs_project_id_scope FOREIGN KEY (workspace_id, project_id) REFERENCES projects(workspace_id, id);

ALTER TABLE background_jobs ADD CONSTRAINT fk_background_jobs_project_id_scope FOREIGN KEY (workspace_id, project_id) REFERENCES projects(workspace_id, id);

ALTER TABLE projects ADD CONSTRAINT fk_projects_active_schedule_scope FOREIGN KEY (id,active_schedule_version_id) REFERENCES schedule_versions(project_id,id);

-- Scope is immutable. Serialize writes through the Project row so concurrent graph
-- and membership changes cannot each validate against an obsolete snapshot.
CREATE FUNCTION ds_project_write_lock() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE p uuid; w uuid;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF to_jsonb(OLD)->>'project_id' IS DISTINCT FROM to_jsonb(NEW)->>'project_id'
           OR to_jsonb(OLD)->>'workspace_id' IS DISTINCT FROM to_jsonb(NEW)->>'workspace_id' THEN
            RAISE EXCEPTION 'Tenant scope is immutable' USING ERRCODE='23514';
        END IF;
    END IF;
    p := ((CASE WHEN TG_OP='DELETE' THEN to_jsonb(OLD) ELSE to_jsonb(NEW) END)->>'project_id')::uuid;
    IF p IS NOT NULL THEN
        SELECT workspace_id INTO w FROM projects WHERE id=p FOR UPDATE;
        IF w IS NOT NULL AND TG_OP <> 'DELETE' AND to_jsonb(NEW) ? 'workspace_id'
           AND (to_jsonb(NEW)->>'workspace_id')::uuid IS DISTINCT FROM w THEN
            RAISE EXCEPTION 'Workspace does not own Project' USING ERRCODE='23514';
        END IF;
    END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END $$;

CREATE FUNCTION ds_touch_version() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.version NOT IN (OLD.version,OLD.version+1) THEN
        RAISE EXCEPTION 'Invalid version increment' USING ERRCODE='23514';
    END IF;
    NEW.version := OLD.version+1;
    NEW.updated_at := clock_timestamp();
    RETURN NEW;
END $$;

CREATE FUNCTION ds_has_project_role(p uuid,u uuid,r text) RETURNS boolean
LANGUAGE sql STABLE AS $$
    SELECT EXISTS (
        SELECT 1 FROM project_members pm
        JOIN project_member_roles pr ON pr.project_member_id=pm.id AND pr.project_id=pm.project_id
        JOIN projects p0 ON p0.id=pm.project_id AND p0.workspace_id=pm.workspace_id
        JOIN workspace_members wm ON wm.workspace_id=p0.workspace_id AND wm.user_id=pm.user_id
        JOIN users usr ON usr.id=pm.user_id
        WHERE pm.project_id=p AND pm.user_id=u AND pm.status='ACTIVE'
          AND wm.status='ACTIVE' AND usr.status='ACTIVE' AND pr.revoked_at IS NULL AND pr.role=r
    )
$$;

CREATE FUNCTION ds_check_workspace_owner() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE w uuid; n integer; owner_user uuid;
BEGIN
    IF TG_TABLE_NAME='workspaces' THEN w:=COALESCE(NEW.id,OLD.id);
    ELSE w:=COALESCE(NEW.workspace_id,OLD.workspace_id); END IF;
    PERFORM 1 FROM workspaces WHERE id=w FOR UPDATE;
    SELECT owner_id INTO owner_user FROM workspaces WHERE id=w;
    IF NOT FOUND THEN RETURN NULL; END IF;
    SELECT count(*) INTO n FROM workspace_members
      WHERE workspace_id=w AND role='OWNER' AND status='ACTIVE' AND user_id=owner_user;
    IF n <> 1 THEN RAISE EXCEPTION 'Workspace must have its one active OWNER membership' USING ERRCODE='23514'; END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER workspace_owner_on_workspace AFTER INSERT OR UPDATE ON workspaces
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION ds_check_workspace_owner();
CREATE CONSTRAINT TRIGGER workspace_owner_on_membership AFTER INSERT OR UPDATE OR DELETE ON workspace_members
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION ds_check_workspace_owner();

CREATE FUNCTION ds_project_invariants() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE p uuid; n integer; record_project projects%ROWTYPE;
BEGIN
    IF TG_TABLE_NAME='projects' THEN p:=COALESCE(NEW.id,OLD.id);
    ELSE p:=COALESCE(NEW.project_id,OLD.project_id); END IF;
    SELECT * INTO record_project FROM projects WHERE id=p FOR UPDATE;
    IF NOT FOUND THEN RETURN NULL; END IF;
    IF EXISTS (
        SELECT 1 FROM project_members pm LEFT JOIN workspace_members wm
        ON wm.workspace_id=pm.workspace_id AND wm.user_id=pm.user_id
        WHERE pm.project_id=p AND pm.status='ACTIVE' AND wm.status IS DISTINCT FROM 'ACTIVE'
    ) THEN RAISE EXCEPTION 'Project member requires active Workspace membership' USING ERRCODE='23514'; END IF;
    IF EXISTS (
        SELECT 1 FROM project_member_roles r JOIN project_members m ON m.id=r.project_member_id
        WHERE r.project_id=p AND r.revoked_at IS NULL AND m.status<>'ACTIVE'
    ) THEN RAISE EXCEPTION 'Active roles require active Project membership' USING ERRCODE='23514'; END IF;
    IF record_project.status='ACTIVE' THEN
        SELECT count(*) INTO n FROM project_members m WHERE m.project_id=p AND ds_has_project_role(p,m.user_id,'PM');
        IF n<>1 THEN RAISE EXCEPTION 'ACTIVE Project requires exactly one eligible PM' USING ERRCODE='23514'; END IF;
    END IF;
    IF record_project.deadline_approver_id IS NOT NULL AND NOT ds_has_project_role(p,record_project.deadline_approver_id,'CLIENT') THEN
        RAISE EXCEPTION 'Deadline representative requires active CLIENT role' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER project_invariants_on_project AFTER INSERT OR UPDATE ON projects
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION ds_project_invariants();
CREATE CONSTRAINT TRIGGER project_invariants_on_members AFTER INSERT OR UPDATE OR DELETE ON project_members
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION ds_project_invariants();
CREATE CONSTRAINT TRIGGER project_invariants_on_roles AFTER INSERT OR UPDATE OR DELETE ON project_member_roles
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION ds_project_invariants();

CREATE FUNCTION ds_project_activation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_timezone_names WHERE name=NEW.timezone) THEN
        RAISE EXCEPTION 'Unknown Project timezone' USING ERRCODE='23514';
    END IF;
    IF TG_OP='UPDATE' AND NEW.workspace_id<>OLD.workspace_id THEN
        RAISE EXCEPTION 'Project Workspace is immutable' USING ERRCODE='23514';
    END IF;
    IF NEW.status='ACTIVE' AND (TG_OP='INSERT' OR OLD.status IS DISTINCT FROM 'ACTIVE') THEN
        IF NEW.objective IS NULL OR length(btrim(NEW.objective))=0 OR NEW.planned_start IS NULL
          OR NOT EXISTS (SELECT 1 FROM work_items WHERE project_id=NEW.id AND deleted_at IS NULL) THEN
            RAISE EXCEPTION 'Project activation requires objective, start date and Backlog' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER project_activation BEFORE INSERT OR UPDATE ON projects FOR EACH ROW EXECUTE FUNCTION ds_project_activation();

CREATE FUNCTION ds_user_lock() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.status='LOCKED' AND OLD.status<>'LOCKED' THEN
        UPDATE projects p SET status='ON_HOLD'
          WHERE p.status='ACTIVE' AND EXISTS (
            SELECT 1 FROM project_members m JOIN project_member_roles r ON r.project_member_id=m.id
            WHERE m.project_id=p.id AND m.user_id=NEW.id AND m.status='ACTIVE' AND r.role='PM' AND r.revoked_at IS NULL
          );
        UPDATE projects SET deadline_approver_id=NULL WHERE deadline_approver_id=NEW.id;
        UPDATE auth_sessions SET revoked_at=clock_timestamp() WHERE user_id=NEW.id AND revoked_at IS NULL;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER user_lock BEFORE UPDATE OF status ON users FOR EACH ROW EXECUTE FUNCTION ds_user_lock();

CREATE FUNCTION ds_workspace_member_removal() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.status='REMOVED' AND OLD.status='ACTIVE' THEN
        IF EXISTS (SELECT 1 FROM project_members m JOIN projects p ON p.id=m.project_id
          WHERE m.workspace_id=NEW.workspace_id AND m.user_id=NEW.user_id AND p.status='ACTIVE'
          AND ds_has_project_role(p.id,m.user_id,'PM')) THEN
            RAISE EXCEPTION 'Transfer PM before removing Workspace membership' USING ERRCODE='23514';
        END IF;
        UPDATE project_member_roles r SET revoked_at=clock_timestamp()
          FROM project_members m WHERE r.project_member_id=m.id AND m.workspace_id=NEW.workspace_id
          AND m.user_id=NEW.user_id AND r.revoked_at IS NULL;
        UPDATE projects SET deadline_approver_id=NULL WHERE workspace_id=NEW.workspace_id AND deadline_approver_id=NEW.user_id;
        UPDATE project_members SET status='REMOVED' WHERE workspace_id=NEW.workspace_id AND user_id=NEW.user_id AND status='ACTIVE';
        DELETE FROM chat_channel_members WHERE workspace_id=NEW.workspace_id AND user_id=NEW.user_id;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER workspace_member_removal BEFORE UPDATE OF status ON workspace_members
    FOR EACH ROW EXECUTE FUNCTION ds_workspace_member_removal();

CREATE FUNCTION ds_dependency_dag() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM 1 FROM projects WHERE id=NEW.project_id FOR UPDATE;
    IF EXISTS (
        WITH RECURSIVE reach(id) AS (
            SELECT NEW.successor_id
            UNION
            SELECT d.successor_id FROM work_item_dependencies d JOIN reach r ON d.predecessor_id=r.id
             WHERE d.project_id=NEW.project_id AND d.id<>NEW.id
        ) SELECT 1 FROM reach WHERE id=NEW.predecessor_id
    ) THEN RAISE EXCEPTION 'Dependency cycle' USING ERRCODE='23514'; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER dependency_dag BEFORE INSERT OR UPDATE ON work_item_dependencies FOR EACH ROW EXECUTE FUNCTION ds_dependency_dag();

CREATE FUNCTION ds_work_item_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE parent_type text; allowed boolean;
BEGIN
    IF NEW.parent_id IS NOT NULL THEN
        SELECT type INTO parent_type FROM work_items WHERE id=NEW.parent_id AND project_id=NEW.project_id AND deleted_at IS NULL;
        allowed := (NEW.type='USER_STORY' AND parent_type='EPIC')
          OR (NEW.type IN ('TASK','BUG') AND parent_type='USER_STORY')
          OR (NEW.type='SUBTASK' AND parent_type IN ('TASK','BUG'));
        IF NOT COALESCE(allowed,false) THEN RAISE EXCEPTION 'Invalid Work Item hierarchy' USING ERRCODE='23514'; END IF;
        IF EXISTS (WITH RECURSIVE ancestors(id,parent_id) AS (
          SELECT id,parent_id FROM work_items WHERE id=NEW.parent_id
          UNION SELECT w.id,w.parent_id FROM work_items w JOIN ancestors a ON w.id=a.parent_id
        ) SELECT 1 FROM ancestors WHERE id=NEW.id) THEN
          RAISE EXCEPTION 'Work Item hierarchy cycle' USING ERRCODE='23514';
        END IF;
    END IF;
    IF NEW.assignee_id IS NOT NULL AND NOT ds_has_project_role(NEW.project_id,NEW.assignee_id,'DEVELOPER') THEN
        -- Historical DONE assignments survive later role revocation.
        IF TG_OP='INSERT' OR NEW.assignee_id IS DISTINCT FROM OLD.assignee_id THEN
            RAISE EXCEPTION 'Assignee requires active DEVELOPER role' USING ERRCODE='23514';
        END IF;
    END IF;
    IF TG_OP='UPDATE' AND OLD.status='DONE' AND (
        NEW.status IS DISTINCT FROM OLD.status OR NEW.assignee_id IS DISTINCT FROM OLD.assignee_id
        OR NEW.estimate_minutes IS DISTINCT FROM OLD.estimate_minutes OR NEW.remaining_minutes IS DISTINCT FROM OLD.remaining_minutes
        OR NEW.parent_id IS DISTINCT FROM OLD.parent_id OR NEW.sprint_id IS DISTINCT FROM OLD.sprint_id
        OR NEW.requirement_id IS DISTINCT FROM OLD.requirement_id OR NEW.due_at IS DISTINCT FROM OLD.due_at
        OR NEW.deleted_at IS DISTINCT FROM OLD.deleted_at OR NEW.title IS DISTINCT FROM OLD.title
        OR NEW.description IS DISTINCT FROM OLD.description OR NEW.completed_at IS DISTINCT FROM OLD.completed_at
        OR NEW.accepted_by IS DISTINCT FROM OLD.accepted_by OR NEW.is_blocked) THEN
        RAISE EXCEPTION 'DONE Work Item scope and schedule are frozen' USING ERRCODE='23514';
    END IF;
    IF NEW.type IN ('TASK','SUBTASK','BUG') THEN
        IF TG_OP='INSERT' AND NEW.status<>'TO_DO' THEN
            RAISE EXCEPTION 'New Task starts TO_DO' USING ERRCODE='23514';
        END IF;
        IF TG_OP='UPDATE' AND NEW.status<>OLD.status AND NOT (
            (OLD.status='TO_DO' AND NEW.status='IN_PROGRESS') OR
            (OLD.status='IN_PROGRESS' AND NEW.status='IN_REVIEW') OR
            (OLD.status='IN_REVIEW' AND NEW.status IN ('DONE','IN_PROGRESS'))
        ) THEN RAISE EXCEPTION 'Invalid Task state transition' USING ERRCODE='23514'; END IF;
        IF NEW.status='DONE' AND (TG_OP='INSERT' OR OLD.status<>'DONE') AND NOT ds_has_project_role(NEW.project_id,NEW.accepted_by,'PM') THEN
            RAISE EXCEPTION 'Task acceptance requires active PM' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER work_item_guard BEFORE INSERT OR UPDATE ON work_items FOR EACH ROW EXECUTE FUNCTION ds_work_item_guard();

CREATE FUNCTION ds_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only; write a new version',TG_TABLE_NAME USING ERRCODE='23514';
END $$;
CREATE TRIGGER immutable_audit BEFORE UPDATE OR DELETE ON audit_logs FOR EACH ROW EXECUTE FUNCTION ds_immutable();
CREATE TRIGGER immutable_schedule_version BEFORE UPDATE OR DELETE ON schedule_versions FOR EACH ROW EXECUTE FUNCTION ds_immutable();
CREATE TRIGGER immutable_schedule_entry BEFORE UPDATE OR DELETE ON schedule_entries FOR EACH ROW EXECUTE FUNCTION ds_immutable();

CREATE FUNCTION ds_requirement_version_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.approved_at IS NOT NULL THEN RAISE EXCEPTION 'Approved Requirement Version is immutable' USING ERRCODE='23514'; END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER immutable_approved_requirement BEFORE UPDATE OR DELETE ON requirement_versions
    FOR EACH ROW EXECUTE FUNCTION ds_requirement_version_guard();

CREATE FUNCTION ds_chat_scope() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE c chat_channels%ROWTYPE;
BEGIN
    SELECT * INTO c FROM chat_channels WHERE id=NEW.channel_id;
    IF c.workspace_id IS DISTINCT FROM NEW.workspace_id OR c.project_id IS DISTINCT FROM NEW.project_id THEN
        RAISE EXCEPTION 'Channel scope mismatch' USING ERRCODE='23514';
    END IF;
    IF TG_TABLE_NAME='chat_channel_members' THEN
        IF NOT EXISTS (SELECT 1 FROM workspace_members wm JOIN users u ON u.id=wm.user_id
           WHERE wm.workspace_id=NEW.workspace_id AND wm.user_id=NEW.user_id AND wm.status='ACTIVE' AND u.status='ACTIVE') THEN
            RAISE EXCEPTION 'Channel member requires active Workspace membership' USING ERRCODE='23514';
        END IF;
        IF NEW.project_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM project_members WHERE project_id=NEW.project_id AND user_id=NEW.user_id AND status='ACTIVE') THEN
            RAISE EXCEPTION 'Channel member requires Project membership' USING ERRCODE='23514';
        END IF;
        IF c.type IN ('TEAM','TASK','PROJECT') AND ds_has_project_role(NEW.project_id,NEW.user_id,'CLIENT')
           AND NOT (ds_has_project_role(NEW.project_id,NEW.user_id,'PM') OR ds_has_project_role(NEW.project_id,NEW.user_id,'DEVELOPER') OR ds_has_project_role(NEW.project_id,NEW.user_id,'VIEWER')) THEN
            RAISE EXCEPTION 'CLIENT-only member cannot enter internal channel' USING ERRCODE='23514';
        END IF;
        IF c.type='CLIENT_PM' AND NOT (ds_has_project_role(NEW.project_id,NEW.user_id,'PM') OR ds_has_project_role(NEW.project_id,NEW.user_id,'CLIENT')) THEN
            RAISE EXCEPTION 'CLIENT_PM channel requires CLIENT or PM' USING ERRCODE='23514';
        END IF;
    ELSE
        IF NEW.sender_kind='USER' AND NOT EXISTS (
          SELECT 1 FROM chat_channel_members cm JOIN workspace_members wm ON wm.workspace_id=cm.workspace_id AND wm.user_id=cm.user_id
          JOIN users u ON u.id=cm.user_id WHERE cm.channel_id=NEW.channel_id AND cm.user_id=NEW.sender_id AND wm.status='ACTIVE' AND u.status='ACTIVE'
        ) THEN RAISE EXCEPTION 'Sender is not an active channel member' USING ERRCODE='23514'; END IF;
        IF EXISTS (SELECT 1 FROM unnest(NEW.mentions) u WHERE NOT EXISTS (
            SELECT 1 FROM chat_channel_members cm WHERE cm.channel_id=NEW.channel_id AND cm.user_id=u
        )) THEN RAISE EXCEPTION 'Mention must reference channel membership' USING ERRCODE='23514'; END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER channel_member_scope BEFORE INSERT OR UPDATE ON chat_channel_members FOR EACH ROW EXECUTE FUNCTION ds_chat_scope();
CREATE TRIGGER message_scope BEFORE INSERT OR UPDATE ON chat_messages FOR EACH ROW EXECUTE FUNCTION ds_chat_scope();
ALTER TABLE chat_messages ADD CONSTRAINT uq_message_channel UNIQUE(channel_id,id);
ALTER TABLE chat_messages ADD CONSTRAINT fk_reply_same_channel FOREIGN KEY(channel_id,reply_to_id) REFERENCES chat_messages(channel_id,id);
ALTER TABLE attachments ADD CONSTRAINT fk_attachment_chat_workspace FOREIGN KEY(workspace_id,chat_message_id) REFERENCES chat_messages(workspace_id,id);
ALTER TABLE attachments ADD CONSTRAINT ck_attachment_project_required CHECK (project_id IS NOT NULL OR (work_item_id IS NULL AND report_id IS NULL AND ci_run_id IS NULL));
CREATE FUNCTION ds_attachment_scope() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE m chat_messages%ROWTYPE;
BEGIN
    IF NEW.chat_message_id IS NOT NULL THEN
        SELECT * INTO m FROM chat_messages WHERE id=NEW.chat_message_id;
        IF m.workspace_id IS DISTINCT FROM NEW.workspace_id OR m.project_id IS DISTINCT FROM NEW.project_id THEN
            RAISE EXCEPTION 'Attachment must share message scope' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER attachment_scope BEFORE INSERT OR UPDATE ON attachments FOR EACH ROW EXECUTE FUNCTION ds_attachment_scope();

CREATE FUNCTION ds_json_has_secret(doc jsonb) RETURNS boolean LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE k text; v jsonb;
BEGIN
    IF jsonb_typeof(doc)='object' THEN
        FOR k,v IN SELECT * FROM jsonb_each(doc) LOOP
            IF lower(replace(k,'_','')) IN ('password','passwordhash','apikey','accesstoken','refreshtoken','secret','privatekey','clientsecret') AND v<>'null'::jsonb THEN RETURN true; END IF;
            IF ds_json_has_secret(v) THEN RETURN true; END IF;
        END LOOP;
    ELSIF jsonb_typeof(doc)='array' THEN
        FOR v IN SELECT * FROM jsonb_array_elements(doc) LOOP IF ds_json_has_secret(v) THEN RETURN true; END IF; END LOOP;
    END IF;
    RETURN false;
END $$;

CREATE FUNCTION ds_calendar_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE entry jsonb;
BEGIN
    IF NOT ds_has_project_role(NEW.project_id,NEW.user_id,'DEVELOPER') THEN
        IF TG_OP='INSERT' OR NEW.user_id IS DISTINCT FROM OLD.user_id THEN RAISE EXCEPTION 'Capacity requires Developer role' USING ERRCODE='23514'; END IF;
    END IF;
    FOR entry IN SELECT * FROM jsonb_array_elements(NEW.skills) LOOP
        IF jsonb_typeof(entry)<>'object' OR NOT (entry ?& ARRAY['skill','level'])
           OR length(btrim(entry->>'skill'))=0 OR jsonb_typeof(entry->'level')<>'number'
           OR (entry->>'level')::numeric NOT BETWEEN 1 AND 5 THEN
            RAISE EXCEPTION 'Invalid skill level' USING ERRCODE='23514';
        END IF;
    END LOOP;
    FOR entry IN SELECT * FROM jsonb_array_elements(NEW.weekly_slots) LOOP
        IF NOT (entry ?& ARRAY['dayOfWeek','startMinute','endMinute'])
           OR (entry->>'dayOfWeek')::integer NOT BETWEEN 1 AND 7
           OR (entry->>'startMinute')::integer NOT BETWEEN 0 AND 1439
           OR (entry->>'endMinute')::integer NOT BETWEEN 1 AND 1440
           OR (entry->>'endMinute')::integer <= (entry->>'startMinute')::integer THEN
            RAISE EXCEPTION 'Invalid weekly slot' USING ERRCODE='23514';
        END IF;
    END LOOP;
    IF EXISTS (SELECT 1 FROM jsonb_array_elements(NEW.weekly_slots) WITH ORDINALITY a(v,n)
        JOIN jsonb_array_elements(NEW.weekly_slots) WITH ORDINALITY b(v,n) ON a.n<b.n
        WHERE a.v->>'dayOfWeek'=b.v->>'dayOfWeek'
          AND int4range((a.v->>'startMinute')::integer,(a.v->>'endMinute')::integer,'[)') && int4range((b.v->>'startMinute')::integer,(b.v->>'endMinute')::integer,'[)')) THEN
        RAISE EXCEPTION 'Weekly slots overlap' USING ERRCODE='23514';
    END IF;
    FOR entry IN SELECT * FROM jsonb_array_elements(NEW.time_off) LOOP
        IF NOT (entry ?& ARRAY['startAt','endAt']) OR (entry->>'endAt')::timestamptz <= (entry->>'startAt')::timestamptz THEN
            RAISE EXCEPTION 'Invalid time off interval' USING ERRCODE='23514';
        END IF;
    END LOOP;
    IF EXISTS (SELECT 1 FROM jsonb_array_elements(NEW.time_off) WITH ORDINALITY a(v,n)
        JOIN jsonb_array_elements(NEW.time_off) WITH ORDINALITY b(v,n) ON a.n<b.n
        WHERE tstzrange((a.v->>'startAt')::timestamptz,(a.v->>'endAt')::timestamptz,'[)') && tstzrange((b.v->>'startAt')::timestamptz,(b.v->>'endAt')::timestamptz,'[)')) THEN
        RAISE EXCEPTION 'Time off intervals overlap' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER capacity_calendar BEFORE INSERT OR UPDATE ON developer_capacity FOR EACH ROW EXECUTE FUNCTION ds_calendar_guard();

CREATE FUNCTION ds_proposal_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='UPDATE' AND OLD.status='APPLIED' THEN RAISE EXCEPTION 'Applied proposal is immutable' USING ERRCODE='23514'; END IF;
    IF TG_OP='INSERT' AND NEW.status<>'DRAFT' THEN
        RAISE EXCEPTION 'Proposal starts DRAFT' USING ERRCODE='23514';
    END IF;
    IF TG_OP='UPDATE' AND ROW(NEW.proposal_payload,NEW.base_project_version,NEW.base_schedule_version_id,NEW.requirement_version_id,NEW.proposed_committed_deadline,NEW.changes_committed_deadline,NEW.solver_details)
       IS DISTINCT FROM ROW(OLD.proposal_payload,OLD.base_project_version,OLD.base_schedule_version_id,OLD.requirement_version_id,OLD.proposed_committed_deadline,OLD.changes_committed_deadline,OLD.solver_details) THEN
        NEW.status:='DRAFT'; NEW.solver_status:='NOT_RUN'; NEW.approved_by:=NULL; NEW.approved_at:=NULL;
        NEW.deadline_confirmed_by:=NULL; NEW.deadline_confirmed_at:=NULL; NEW.deadline_confirmation_version:=NULL; NEW.deadline_confirmed_project_version:=NULL;
    END IF;
    IF NEW.status IN ('APPROVED','APPLIED') AND NOT ds_has_project_role(NEW.project_id,NEW.approved_by,'PM') THEN
        RAISE EXCEPTION 'Proposal approval requires active PM' USING ERRCODE='23514';
    END IF;
    IF NEW.status='APPLIED' THEN
        IF NEW.kind='AI_REQUIREMENT' AND NOT EXISTS (SELECT 1 FROM ai_jobs WHERE id=NEW.ai_job_id AND project_id=NEW.project_id AND status='SUCCESS' AND validation_status='VALID') THEN
            RAISE EXCEPTION 'AI proposal requires validated AI output' USING ERRCODE='23514';
        END IF;
        IF NEW.changes_committed_deadline AND (
            NEW.deadline_confirmed_by IS NULL OR NEW.deadline_confirmed_at IS NULL
            OR NEW.deadline_confirmation_version IS DISTINCT FROM OLD.version
            OR NEW.deadline_confirmed_project_version IS DISTINCT FROM NEW.base_project_version
            OR NOT ds_has_project_role(NEW.project_id,NEW.deadline_confirmed_by,'CLIENT')
            OR NEW.deadline_confirmed_by=NEW.approved_by
            OR NOT EXISTS (SELECT 1 FROM projects WHERE id=NEW.project_id AND deadline_approver_id=NEW.deadline_confirmed_by)
        ) THEN RAISE EXCEPTION 'Deadline requires independent designated CLIENT confirmation for this proposal version' USING ERRCODE='23514'; END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER proposal_guard BEFORE INSERT OR UPDATE ON plan_proposals FOR EACH ROW EXECUTE FUNCTION ds_proposal_guard();

CREATE FUNCTION ds_deadline_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.committed_deadline IS DISTINCT FROM OLD.committed_deadline THEN
        IF NOT EXISTS (SELECT 1 FROM schedule_versions s JOIN plan_proposals p ON p.id=s.proposal_id
          WHERE s.id=NEW.active_schedule_version_id AND s.project_id=NEW.id AND p.status='APPLIED'
          AND p.changes_committed_deadline AND p.proposed_committed_deadline IS NOT DISTINCT FROM NEW.committed_deadline
          AND p.deadline_confirmed_by IS NOT NULL AND p.deadline_confirmed_by=NEW.deadline_approver_id
          AND p.deadline_confirmed_project_version=p.base_project_version
          AND p.deadline_confirmed_by<>p.approved_by
          AND ds_has_project_role(NEW.id,p.deadline_confirmed_by,'CLIENT')
          AND OLD.active_schedule_version_id IS DISTINCT FROM NEW.active_schedule_version_id) THEN
            RAISE EXCEPTION 'Committed deadline requires newly applied confirmed baseline' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER deadline_guard BEFORE UPDATE OF committed_deadline ON projects FOR EACH ROW EXECUTE FUNCTION ds_deadline_guard();

-- Validate shape and reject secret material recursively, including nested arrays.
DO $$ DECLARE c record;
BEGIN
    FOR c IN SELECT table_name,column_name FROM information_schema.columns WHERE table_schema='public' AND data_type='jsonb' LOOP
        EXECUTE format('ALTER TABLE %I ADD CONSTRAINT %I CHECK (%I IS NULL OR NOT ds_json_has_secret(%I))',c.table_name,'ck_no_secret_'||c.column_name,c.column_name,c.column_name);
    END LOOP;
END $$;

CREATE FUNCTION ds_audit_redact(doc jsonb) RETURNS jsonb LANGUAGE sql IMMUTABLE AS $$
    SELECT doc - ARRAY['password_hash','token_hash','refresh_hash','email','ip','user_agent']::text[]
$$;
CREATE FUNCTION ds_audit_change() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE data jsonb; previous jsonb; p uuid; w uuid; actor uuid;
BEGIN
    IF TG_OP<>'DELETE' THEN data:=to_jsonb(NEW); END IF;
    IF TG_OP<>'INSERT' THEN previous:=to_jsonb(OLD); END IF;
    p:=(COALESCE(data,previous)->>'project_id')::uuid;
    w:=(COALESCE(data,previous)->>'workspace_id')::uuid;
    IF TG_TABLE_NAME='projects' THEN p:=COALESCE(NEW.id,OLD.id); END IF;
    IF TG_TABLE_NAME='workspaces' THEN w:=COALESCE(NEW.id,OLD.id); END IF;
    IF p IS NOT NULL AND w IS NULL THEN SELECT workspace_id INTO w FROM projects WHERE id=p; END IF;
    actor:=NULLIF(current_setting('devsphere.actor_id',true),'')::uuid;
    INSERT INTO audit_logs(workspace_id,project_id,actor_id,action,entity_type,entity_id,before_json,after_json,metadata)
    VALUES(w,p,actor,TG_OP,TG_TABLE_NAME,(COALESCE(data,previous)->>'id')::uuid,ds_audit_redact(previous),ds_audit_redact(data),'{"schemaVersion":1,"source":"database"}'::jsonb);
    RETURN NULL;
END $$;

CREATE FUNCTION ds_bump_project_version() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE p uuid;
BEGIN
    p:=(CASE WHEN TG_OP='DELETE' THEN to_jsonb(OLD) ELSE to_jsonb(NEW) END->>'project_id')::uuid;
    UPDATE projects SET version=version+1 WHERE id=p;
    RETURN NULL;
END $$;

DO $$ DECLARE t record;
BEGIN
    FOR t IN SELECT table_name FROM information_schema.columns WHERE table_schema='public' AND column_name='project_id' LOOP
        EXECUTE format('CREATE TRIGGER a_project_scope BEFORE INSERT OR UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION ds_project_write_lock()',t.table_name);
    END LOOP;
    FOR t IN SELECT table_name FROM information_schema.columns WHERE table_schema='public' AND column_name='version' AND data_type='bigint' AND table_name<>'flyway_schema_history' LOOP
        EXECUTE format('CREATE TRIGGER z_touch_version BEFORE UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION ds_touch_version()',t.table_name);
    END LOOP;
    FOR t IN SELECT unnest(ARRAY['project_members','project_member_roles','requirements','requirement_versions','work_items','work_item_dependencies','acceptance_criteria','sprints','milestones','developer_capacity']) AS name LOOP
        EXECUTE format('CREATE TRIGGER project_source_changed AFTER INSERT OR UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION ds_bump_project_version()',t.name);
    END LOOP;
    FOR t IN SELECT unnest(ARRAY['users','workspaces','workspace_members','workspace_invitations','projects','project_members','project_member_roles','requirements','requirement_versions','work_items','work_item_dependencies','acceptance_criteria','sprints','milestones','developer_capacity','plan_proposals','reports','alerts']) AS name LOOP
        EXECUTE format('CREATE TRIGGER audit_change AFTER INSERT OR UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION ds_audit_change()',t.name);
    END LOOP;
END $$;

-- Queries must join current SHA and current Project context; a historic PASS is not current evidence.
CREATE VIEW current_pr_reviews AS
SELECT r.* FROM code_reviews r
JOIN github_activities a ON a.repository_id=r.repository_id AND a.pr_number=r.pr_number AND a.kind='PULL_REQUEST'
JOIN projects p ON p.id=r.project_id
WHERE r.head_sha=a.pr_head_sha AND r.context_version=p.version AND r.status IN ('PASS','FAIL','REQUIRES_CHANGES')
  AND (r.reviewer_kind='HUMAN' OR EXISTS (SELECT 1 FROM ai_jobs j WHERE j.id=r.ai_job_id AND j.status='SUCCESS' AND j.validation_status='VALID'));

CREATE VIEW client_published_reports AS
SELECT id,project_id,type,period_start,period_end,content,published_at FROM reports
WHERE status='PUBLISHED' AND visible_to_client;

COMMENT ON TABLE project_member_roles IS 'Multiple active roles per Project membership; at most one current PM grant per Project.';
COMMENT ON TABLE audit_logs IS 'Append-only business history; secret hashes and direct identity fields are omitted from snapshots.';
COMMENT ON TABLE background_jobs IS 'Transactional outbox, worker leases and tenant-scoped idempotency. Claim with FOR UPDATE SKIP LOCKED.';
COMMENT ON COLUMN work_items.required_skills IS 'Array of {skill,level}; resolves ambiguous work_items.work_items field from baseline.';
COMMENT ON COLUMN developer_capacity.skills IS 'Array of {skill,level}; resolves developer_capacity.developer_capacity typo.';
COMMENT ON COLUMN projects.version IS 'Planning source revision: bumped by mutable Project and its planning input tables.';

CREATE FUNCTION ds_role_receipt() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE recipient uuid;
BEGIN
    SELECT user_id INTO recipient FROM project_members WHERE id=NEW.project_member_id AND project_id=NEW.project_id;
    IF NEW.role='PM' AND NEW.accepted_by IS DISTINCT FROM recipient THEN
        RAISE EXCEPTION 'PM assignment must be accepted by its recipient' USING ERRCODE='23514';
    END IF;
    IF TG_OP='UPDATE' AND (
        ROW(NEW.project_member_id,NEW.role,NEW.granted_by,NEW.granted_at,NEW.accepted_by,NEW.accepted_at)
         IS DISTINCT FROM ROW(OLD.project_member_id,OLD.role,OLD.granted_by,OLD.granted_at,OLD.accepted_by,OLD.accepted_at)
        OR OLD.revoked_at IS NOT NULL AND NEW.revoked_at IS DISTINCT FROM OLD.revoked_at
    ) THEN RAISE EXCEPTION 'Role grant history is immutable; create a new grant' USING ERRCODE='23514'; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER role_acceptance_receipt BEFORE INSERT OR UPDATE ON project_member_roles FOR EACH ROW EXECUTE FUNCTION ds_role_receipt();

CREATE FUNCTION ds_propose_pm_assignment(p uuid, actor uuid, candidate uuid, assignment uuid, expected_version bigint) RETURNS void
LANGUAGE plpgsql AS $$
DECLARE pr projects%ROWTYPE;
BEGIN
    SELECT * INTO pr FROM projects WHERE id=p FOR UPDATE;
    IF NOT FOUND OR pr.version<>expected_version THEN RAISE EXCEPTION 'Project version conflict' USING ERRCODE='23514'; END IF;
    IF NOT EXISTS(SELECT 1 FROM workspaces w JOIN workspace_members m ON m.workspace_id=w.id AND m.user_id=w.owner_id JOIN users u ON u.id=m.user_id
       WHERE w.id=pr.workspace_id AND w.owner_id=actor AND m.status='ACTIVE' AND u.status='ACTIVE') THEN
        RAISE EXCEPTION 'PM nomination requires active OWNER' USING ERRCODE='23514';
    END IF;
    IF NOT EXISTS(SELECT 1 FROM workspace_members m JOIN users u ON u.id=m.user_id
       WHERE m.workspace_id=pr.workspace_id AND m.user_id=candidate AND m.status='ACTIVE' AND u.status='ACTIVE') THEN
        RAISE EXCEPTION 'PM candidate is ineligible' USING ERRCODE='23514';
    END IF;
    IF pr.settings#>>'{pmAssignment,status}'='PENDING' AND (pr.settings#>>'{pmAssignment,expiresAt}')::timestamptz>now() THEN
        RAISE EXCEPTION 'A PM assignment is already pending' USING ERRCODE='23514';
    END IF;
    PERFORM set_config('devsphere.actor_id',actor::text,true);
    UPDATE projects SET settings=jsonb_set(settings,'{pmAssignment}',jsonb_build_object('schemaVersion',1,'id',assignment,'candidateUserId',candidate,
      'proposedBy',actor,'proposedAt',now(),'expiresAt',now()+interval '7 days','status','PENDING')) WHERE id=p;
END $$;

CREATE FUNCTION ds_accept_pm_assignment(p uuid, actor uuid, assignment uuid, expected_version bigint) RETURNS void
LANGUAGE plpgsql AS $$
DECLARE pr projects%ROWTYPE; membership uuid; proposer uuid;
BEGIN
    SELECT * INTO pr FROM projects WHERE id=p FOR UPDATE;
    IF NOT FOUND OR pr.version<>expected_version THEN RAISE EXCEPTION 'Project version conflict' USING ERRCODE='23514'; END IF;
    IF pr.settings#>>'{pmAssignment,status}' IS DISTINCT FROM 'PENDING'
      OR (pr.settings#>>'{pmAssignment,id}')::uuid IS DISTINCT FROM assignment
      OR (pr.settings#>>'{pmAssignment,candidateUserId}')::uuid IS DISTINCT FROM actor
      OR (pr.settings#>>'{pmAssignment,expiresAt}')::timestamptz<=now() THEN
        RAISE EXCEPTION 'PM invitation is invalid or expired' USING ERRCODE='23514';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM workspace_members m JOIN users u ON u.id=m.user_id
      WHERE m.workspace_id=pr.workspace_id AND m.user_id=actor AND m.status='ACTIVE' AND u.status='ACTIVE') THEN
        RAISE EXCEPTION 'PM candidate is no longer eligible' USING ERRCODE='23514';
    END IF;
    proposer:=(pr.settings#>>'{pmAssignment,proposedBy}')::uuid;
    IF NOT EXISTS(SELECT 1 FROM workspaces WHERE id=pr.workspace_id AND owner_id=proposer) THEN
        RAISE EXCEPTION 'PM nomination must be renewed after OWNER transfer' USING ERRCODE='23514';
    END IF;
    PERFORM set_config('devsphere.actor_id',actor::text,true);
    INSERT INTO project_members(project_id,workspace_id,user_id,status) VALUES(p,pr.workspace_id,actor,'ACTIVE')
      ON CONFLICT (project_id,user_id) DO UPDATE SET status='ACTIVE' RETURNING id INTO membership;
    UPDATE project_member_roles SET revoked_at=now() WHERE project_id=p AND role='PM' AND revoked_at IS NULL;
    INSERT INTO project_member_roles(project_id,project_member_id,role,granted_by,accepted_by,accepted_at)
      VALUES(p,membership,'PM',proposer,actor,now());
    UPDATE projects SET settings=jsonb_set(settings,'{pmAssignment}',(settings->'pmAssignment')||jsonb_build_object('status','ACCEPTED','acceptedBy',actor,'acceptedAt',now())) WHERE id=p;
END $$;

CREATE FUNCTION ds_proposal_document() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE item jsonb; edge jsonb; keys text[]; k text;
BEGIN
    IF NOT (NEW.proposal_payload ? 'items') OR jsonb_typeof(NEW.proposal_payload->'items')<>'array' THEN
        RAISE EXCEPTION 'Proposal requires items array' USING ERRCODE='23514';
    END IF;
    keys:=ARRAY[]::text[];
    FOR item IN SELECT * FROM jsonb_array_elements(NEW.proposal_payload->'items') LOOP
        k:=item->>'tempKey';
        IF k IS NULL OR length(btrim(k))=0 OR k=ANY(keys) THEN RAISE EXCEPTION 'Proposal tempKey must be unique and nonempty' USING ERRCODE='23514'; END IF;
        keys:=array_append(keys,k);
        IF item ? 'estimateMinutes' AND (jsonb_typeof(item->'estimateMinutes')<>'number' OR (item->>'estimateMinutes')::numeric<0) THEN
            RAISE EXCEPTION 'Proposal estimate must be nonnegative' USING ERRCODE='23514';
        END IF;
        IF item->>'targetId' IS NOT NULL AND NOT EXISTS(SELECT 1 FROM work_items WHERE id=(item->>'targetId')::uuid AND project_id=NEW.project_id AND deleted_at IS NULL) THEN
            RAISE EXCEPTION 'Proposal target is outside Project' USING ERRCODE='23514';
        END IF;
    END LOOP;
    IF NEW.proposal_payload ? 'dependencies' THEN
        IF jsonb_typeof(NEW.proposal_payload->'dependencies')<>'array' THEN RAISE EXCEPTION 'Dependencies must be an array' USING ERRCODE='23514'; END IF;
        FOR edge IN SELECT * FROM jsonb_array_elements(NEW.proposal_payload->'dependencies') LOOP
            IF edge->>'predecessorKey' IS NULL OR edge->>'successorKey' IS NULL
              OR NOT (edge->>'predecessorKey'=ANY(keys) AND edge->>'successorKey'=ANY(keys)) THEN
                RAISE EXCEPTION 'Proposal dependency references unknown tempKey' USING ERRCODE='23514';
            END IF;
        END LOOP;
        IF EXISTS (WITH RECURSIVE edges(a,b) AS (
          SELECT e->>'predecessorKey',e->>'successorKey' FROM jsonb_array_elements(NEW.proposal_payload->'dependencies') e
        ), paths(a,b) AS (
          SELECT a,b FROM edges UNION SELECT p.a,e.b FROM paths p JOIN edges e ON p.b=e.a
        ) SELECT 1 FROM paths WHERE a=b) THEN RAISE EXCEPTION 'Proposal dependency cycle' USING ERRCODE='23514'; END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER proposal_document BEFORE INSERT OR UPDATE ON plan_proposals FOR EACH ROW EXECUTE FUNCTION ds_proposal_document();

ALTER TABLE requirements ADD CONSTRAINT fk_requirement_current_version FOREIGN KEY(id,current_version)
    REFERENCES requirement_versions(requirement_id,version_no) DEFERRABLE INITIALLY DEFERRED;

CREATE FUNCTION ds_done_scope() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status='DONE' AND (to_jsonb(NEW)-ARRAY['version','updated_at']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['version','updated_at']) THEN
        RAISE EXCEPTION 'DONE scope is immutable; create a Change Request' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER done_scope BEFORE UPDATE ON work_items FOR EACH ROW EXECUTE FUNCTION ds_done_scope();

ALTER TABLE schedule_entries ADD CONSTRAINT ex_schedule_assignee_overlap EXCLUDE USING gist
    (schedule_version_id WITH =,assignee_id WITH =,tstzrange(start_at,finish_at,'[)') WITH &&) WHERE(assignee_id IS NOT NULL);
CREATE FUNCTION ds_schedule_freeze() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE old_entry schedule_entries%ROWTYPE; task_status text;
BEGIN
    SELECT e.* INTO old_entry FROM projects p JOIN schedule_entries e ON e.schedule_version_id=p.active_schedule_version_id
      WHERE p.id=NEW.project_id AND e.work_item_id=NEW.work_item_id;
    SELECT status INTO task_status FROM work_items WHERE id=NEW.work_item_id;
    IF FOUND AND old_entry.id IS NOT NULL AND (old_entry.frozen OR task_status='DONE')
      AND ROW(NEW.assignee_id,NEW.sprint_id,NEW.start_at,NEW.finish_at,NEW.remaining_minutes_snapshot,NEW.frozen)
      IS DISTINCT FROM ROW(old_entry.assignee_id,old_entry.sprint_id,old_entry.start_at,old_entry.finish_at,old_entry.remaining_minutes_snapshot,old_entry.frozen) THEN
        RAISE EXCEPTION 'Frozen schedule entry must be copied unchanged into a new baseline' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER schedule_freeze BEFORE INSERT ON schedule_entries FOR EACH ROW EXECUTE FUNCTION ds_schedule_freeze();

CREATE FUNCTION ds_sprint_close() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.status='CLOSED' AND OLD.status<>'CLOSED' THEN
        NEW.closed_at:=COALESCE(NEW.closed_at,now());
        SELECT COALESCE(sum(story_points),0) INTO NEW.completed_points FROM work_items
          WHERE sprint_id=NEW.id AND status='DONE' AND deleted_at IS NULL;
        UPDATE work_items SET sprint_id=NULL WHERE sprint_id=NEW.id AND status<>'DONE' AND deleted_at IS NULL;
    END IF;
    IF OLD.status='CLOSED' AND ROW(NEW.status,NEW.start_date,NEW.end_date,NEW.committed_points,NEW.completed_points,NEW.closed_at)
       IS DISTINCT FROM ROW(OLD.status,OLD.start_date,OLD.end_date,OLD.committed_points,OLD.completed_points,OLD.closed_at) THEN
        RAISE EXCEPTION 'Closed Sprint metrics and dates are immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER sprint_close BEFORE UPDATE ON sprints FOR EACH ROW EXECUTE FUNCTION ds_sprint_close();

CREATE FUNCTION ds_milestone_acceptance() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.acceptance_status='ACCEPTED' AND (TG_OP='INSERT' OR OLD.acceptance_status<>'ACCEPTED') THEN
        IF NOT NEW.published_to_client OR NOT ds_has_project_role(NEW.project_id,NEW.accepted_by,'CLIENT') THEN
            RAISE EXCEPTION 'Milestone acceptance requires published scope and CLIENT' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER milestone_acceptance BEFORE INSERT OR UPDATE ON milestones FOR EACH ROW EXECUTE FUNCTION ds_milestone_acceptance();

CREATE FUNCTION ds_report_approval() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='UPDATE' AND ROW(NEW.content,NEW.type,NEW.period_start,NEW.period_end,NEW.ai_job_id)
       IS DISTINCT FROM ROW(OLD.content,OLD.type,OLD.period_start,OLD.period_end,OLD.ai_job_id) THEN
        NEW.status:='DRAFT'; NEW.approved_by:=NULL; NEW.published_at:=NULL;
    END IF;
    IF NEW.status IN ('APPROVED','PUBLISHED') THEN
        IF NOT ds_has_project_role(NEW.project_id,NEW.approved_by,'PM') THEN
            RAISE EXCEPTION 'Report approval requires active PM' USING ERRCODE='23514';
        END IF;
        IF NEW.ai_job_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM ai_jobs WHERE id=NEW.ai_job_id AND project_id=NEW.project_id AND status='SUCCESS' AND validation_status='VALID') THEN
            RAISE EXCEPTION 'AI report requires validated output' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER report_approval BEFORE INSERT OR UPDATE ON reports FOR EACH ROW EXECUTE FUNCTION ds_report_approval();

CREATE FUNCTION ds_review_evidence() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.status='PASS' THEN
        IF NEW.reviewer_kind='AI' AND NOT EXISTS(SELECT 1 FROM ai_jobs WHERE id=NEW.ai_job_id AND project_id=NEW.project_id AND status='SUCCESS' AND validation_status='VALID') THEN
            RAISE EXCEPTION 'AI review PASS requires validated output' USING ERRCODE='23514';
        END IF;
        IF NEW.reviewer_kind='HUMAN' THEN
            IF NOT ds_has_project_role(NEW.project_id,NEW.reviewer_id,'DEVELOPER') THEN
                RAISE EXCEPTION 'Human reviewer requires Developer role' USING ERRCODE='23514';
            END IF;
            IF NEW.scope='PR' AND (
                NOT EXISTS(SELECT 1 FROM oauth_accounts WHERE user_id=NEW.reviewer_id AND provider='GITHUB') OR
                EXISTS(SELECT 1 FROM github_activities a JOIN oauth_accounts o ON lower(o.provider_login)=lower(a.actor_login)
                  WHERE a.repository_id=NEW.repository_id AND a.pr_number=NEW.pr_number AND a.kind='PULL_REQUEST'
                    AND o.provider='GITHUB' AND o.user_id=NEW.reviewer_id)
            ) THEN RAISE EXCEPTION 'Human PR approval requires an independent GitHub identity' USING ERRCODE='23514'; END IF;
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER review_evidence BEFORE INSERT OR UPDATE ON code_reviews FOR EACH ROW EXECUTE FUNCTION ds_review_evidence();

CREATE FUNCTION ds_work_item_key() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE prefix text; sequence_no bigint;
BEGIN
    IF TG_OP='UPDATE' AND NEW.item_key IS DISTINCT FROM OLD.item_key THEN
        RAISE EXCEPTION 'Work Item key is immutable' USING ERRCODE='23514';
    END IF;
    IF TG_OP='INSERT' THEN
        SELECT project_key INTO prefix FROM projects WHERE id=NEW.project_id FOR UPDATE;
        IF NEW.item_key IS NULL THEN
            UPDATE projects SET next_item_number=next_item_number+1 WHERE id=NEW.project_id RETURNING next_item_number-1 INTO sequence_no;
            NEW.item_key:=prefix||'-'||sequence_no;
        ELSE
            IF NEW.item_key !~ ('^'||prefix||'-[1-9][0-9]*$') THEN
                RAISE EXCEPTION 'Work Item key must match Project key and positive sequence' USING ERRCODE='23514';
            END IF;
            sequence_no:=substring(NEW.item_key FROM length(prefix)+2)::bigint;
            UPDATE projects SET next_item_number=greatest(next_item_number,sequence_no+1) WHERE id=NEW.project_id;
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER work_item_key BEFORE INSERT OR UPDATE ON work_items FOR EACH ROW EXECUTE FUNCTION ds_work_item_key();

CREATE FUNCTION ds_stable_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.id<>OLD.id OR to_jsonb(NEW)->>'workspace_id' IS DISTINCT FROM to_jsonb(OLD)->>'workspace_id'
       OR to_jsonb(NEW)->>'project_id' IS DISTINCT FROM to_jsonb(OLD)->>'project_id' THEN
        RAISE EXCEPTION 'Identity and tenant bindings are immutable' USING ERRCODE='23514';
    END IF;
    IF TG_TABLE_NAME IN ('oauth_accounts','auth_sessions','workspace_members','project_members','developer_capacity')
       AND to_jsonb(NEW)->>'user_id' IS DISTINCT FROM to_jsonb(OLD)->>'user_id' THEN
        RAISE EXCEPTION 'User binding is immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
DO $$ DECLARE t record;
BEGIN
    FOR t IN SELECT table_name FROM information_schema.columns WHERE table_schema='public' AND column_name='id' AND table_name IN (SELECT tablename FROM pg_tables WHERE schemaname='public') AND table_name<>'flyway_schema_history' LOOP
        EXECUTE format('CREATE TRIGGER a_identity_stable BEFORE UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION ds_stable_identity()',t.table_name);
    END LOOP;
END $$;

CREATE FUNCTION ds_token_consumption() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.attempts<OLD.attempts OR (OLD.used_at IS NOT NULL AND NEW.used_at IS DISTINCT FROM OLD.used_at)
       OR NEW.token_hash<>OLD.token_hash OR NEW.purpose<>OLD.purpose OR NEW.expires_at<>OLD.expires_at THEN
        RAISE EXCEPTION 'Token consumption cannot be reset or extended' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER token_consumption BEFORE UPDATE ON auth_tokens FOR EACH ROW EXECUTE FUNCTION ds_token_consumption();

CREATE FUNCTION ds_valid_skills(doc jsonb) RETURNS boolean LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE entry jsonb; names text[]:=ARRAY[]::text[]; name text; level numeric;
BEGIN
    IF jsonb_typeof(doc) IS DISTINCT FROM 'array' THEN RETURN false; END IF;
    FOR entry IN SELECT * FROM jsonb_array_elements(doc) LOOP
        IF jsonb_typeof(entry->'skill') IS DISTINCT FROM 'string' OR jsonb_typeof(entry->'level') IS DISTINCT FROM 'number' THEN RETURN false; END IF;
        name:=lower(btrim(entry->>'skill'));level:=(entry->>'level')::numeric;
        IF length(name)=0 OR name=ANY(names) OR level<1 OR level>5 OR level<>trunc(level) THEN RETURN false; END IF;
        names:=array_append(names,name);
    END LOOP;
    RETURN true;
END $$;
ALTER TABLE developer_capacity ADD CONSTRAINT ck_capacity_skill_contract CHECK(ds_valid_skills(skills));
ALTER TABLE work_items ADD CONSTRAINT ck_task_skill_contract CHECK(ds_valid_skills(required_skills));

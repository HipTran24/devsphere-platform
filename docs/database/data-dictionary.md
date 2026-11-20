# Devsphere data dictionary

Captured from the running PostgreSQL catalog. SQL migrations are the source of schema changes.

## acceptance_criteria

| Column | PostgreSQL type | Nullable | Default |
|---|---|---|---|
| `id` PK | `uuid` | False | `gen_random_uuid()` |
| `project_id` | `uuid` | False | `` |
| `work_item_id` | `uuid` | False | `` |
| `position` | `integer` | False | `` |
| `description` | `text` | False | `` |
| `evidence_kind` | `character varying(16)` | False | `` |
| `weight` | `numeric(8,3)` | False | `1` |
| `status` | `character varying(24)` | False | `'NOT_EVALUATED'::character varying` |
| `evidence` | `jsonb` | False | `'[]'::jsonb` |
| `verified_by` | `uuid` | True | `` |
| `verified_at` | `timestamp with time zone` | True | `` |
| `created_at` | `timestamp with time zone` | False | `now()` |
| `updated_at` | `timestamp with time zone` | False | `now()` |
| `version` | `bigint` | False | `0` |

Constraints:

- `acceptance_criteria_check`: `CHECK ((((status)::text = 'VERIFIED'::text) = ((verified_by IS NOT NULL) AND (verified_at IS NOT NULL))))`
- `acceptance_criteria_created_at_not_null`: `NOT NULL created_at`
- `acceptance_criteria_description_check`: `CHECK ((length(btrim(description)) > 0))`
- `acceptance_criteria_description_not_null`: `NOT NULL description`
- `acceptance_criteria_evidence_check`: `CHECK (((evidence IS NULL) OR (jsonb_typeof(evidence) = 'array'::text)))`
- `acceptance_criteria_evidence_kind_check`: `CHECK (((evidence_kind)::text = ANY ((ARRAY['CI_TEST'::character varying, 'PR'::character varying, 'FILE'::character varying, 'MANUAL'::character varying])::text[])))`
- `acceptance_criteria_evidence_kind_not_null`: `NOT NULL evidence_kind`
- `acceptance_criteria_evidence_not_null`: `NOT NULL evidence`
- `acceptance_criteria_id_not_null`: `NOT NULL id`
- `acceptance_criteria_pkey`: `PRIMARY KEY (id)`
- `acceptance_criteria_position_check`: `CHECK (("position" >= 0))`
- `acceptance_criteria_position_not_null`: `NOT NULL "position"`
- `acceptance_criteria_project_id_fkey`: `FOREIGN KEY (project_id) REFERENCES projects(id)`
- `acceptance_criteria_project_id_id_key`: `UNIQUE (project_id, id)`
- `acceptance_criteria_project_id_not_null`: `NOT NULL project_id`
- `acceptance_criteria_status_check`: `CHECK (((status)::text = ANY ((ARRAY['NOT_EVALUATED'::character varying, 'VERIFIED'::character varying, 'NOT_MET'::character varying, 'INSUFFICIENT_EVIDENCE'::character varying])::text[])))`
- `acceptance_criteria_status_not_null`: `NOT NULL status`
- `acceptance_criteria_updated_at_not_null`: `NOT NULL updated_at`
- `acceptance_criteria_verified_by_fkey`: `FOREIGN KEY (verified_by) REFERENCES users(id)`
- `acceptance_criteria_version_check`: `CHECK ((version >= 0))`
- `acceptance_criteria_version_not_null`: `NOT NULL version`
- `acceptance_criteria_weight_check`: `CHECK ((weight > (0)::numeric))`
- `acceptance_criteria_weight_not_null`: `NOT NULL weight`
- `acceptance_criteria_work_item_id_not_null`: `NOT NULL work_item_id`
- `acceptance_criteria_work_item_id_position_key`: `UNIQUE (work_item_id, "position")`
- `ck_no_secret_evidence`: `CHECK (((evidence IS NULL) OR (NOT ds_json_has_secret(evidence))))`
- `fk_acceptance_criteria_work_item_id_scope`: `FOREIGN KEY (project_id, work_item_id) REFERENCES work_items(project_id, id)`

## ai_jobs

| Column | PostgreSQL type | Nullable | Default |
|---|---|---|---|
| `id` PK | `uuid` | False | `gen_random_uuid()` |
| `workspace_id` | `uuid` | False | `` |
| `project_id` | `uuid` | True | `` |
| `requested_by` | `uuid` | True | `` |
| `job_type` | `character varying(24)` | False | `` |
| `status` | `character varying(12)` | False | `'QUEUED'::character varying` |
| `model_name` | `character varying(140)` | False | `` |
| `connection_id` | `character varying(80)` | False | `` |
| `route_snapshot` | `jsonb` | False | `` |
| `prompt_version` | `character varying(40)` | False | `` |
| `output_schema_version` | `character varying(40)` | False | `'1'::character varying` |
| `input_refs` | `jsonb` | False | `'{"schemaVersion": 1}'::jsonb` |
| `output_json` | `jsonb` | True | `` |
| `validation_status` | `character varying(16)` | False | `'PENDING'::character varying` |
| `validation_report` | `jsonb` | False | `'{"schemaVersion": 1}'::jsonb` |
| `validation_attempts` | `smallint` | False | `0` |
| `validated_by_model` | `character varying(140)` | True | `` |
| `tokens_in` | `integer` | True | `` |
| `tokens_out` | `integer` | True | `` |
| `error_code` | `character varying(100)` | True | `` |
| `started_at` | `timestamp with time zone` | True | `` |
| `finished_at` | `timestamp with time zone` | True | `` |
| `created_at` | `timestamp with time zone` | False | `now()` |
| `updated_at` | `timestamp with time zone` | False | `now()` |
| `version` | `bigint` | False | `0` |

Constraints:

- `ai_jobs_check`: `CHECK ((((status)::text <> 'SUCCESS'::text) OR (output_json IS NOT NULL)))`
- `ai_jobs_check1`: `CHECK ((((validation_status)::text = 'PENDING'::text) OR (output_json IS NOT NULL)))`
- `ai_jobs_check2`: `CHECK (((finished_at IS NULL) OR (started_at IS NULL) OR (finished_at >= started_at)))`
- `ai_jobs_connection_id_not_null`: `NOT NULL connection_id`
- `ai_jobs_created_at_not_null`: `NOT NULL created_at`
- `ai_jobs_id_not_null`: `NOT NULL id`
- `ai_jobs_input_refs_check`: `CHECK (((input_refs IS NULL) OR (jsonb_typeof(input_refs) = 'object'::text)))`
- `ai_jobs_input_refs_not_null`: `NOT NULL input_refs`
- `ai_jobs_job_type_check`: `CHECK (((job_type)::text = ANY ((ARRAY['REQUIREMENT_PLAN'::character varying, 'PUSH_REVIEW'::character varying, 'PR_REVIEW'::character varying, 'PROGRESS_EXPLAIN'::character varying, 'ALERT_EXPLAIN'::character varying, 'CHAT_ANSWER'::character varying, 'REPORT_SUMMARY'::character varying, 'AI_CRITIQUE'::character varying])::text[])))`
- `ai_jobs_job_type_not_null`: `NOT NULL job_type`
- `ai_jobs_model_name_not_null`: `NOT NULL model_name`
- `ai_jobs_output_json_check`: `CHECK (((output_json IS NULL) OR (jsonb_typeof(output_json) = 'object'::text)))`
- `ai_jobs_output_schema_version_not_null`: `NOT NULL output_schema_version`
- `ai_jobs_pkey`: `PRIMARY KEY (id)`
- `ai_jobs_project_id_id_key`: `UNIQUE (project_id, id)`
- `ai_jobs_prompt_version_not_null`: `NOT NULL prompt_version`
- `ai_jobs_requested_by_fkey`: `FOREIGN KEY (requested_by) REFERENCES users(id)`
- `ai_jobs_route_snapshot_check`: `CHECK (((route_snapshot IS NULL) OR (jsonb_typeof(route_snapshot) = 'object'::text)))`
- `ai_jobs_route_snapshot_not_null`: `NOT NULL route_snapshot`
- `ai_jobs_status_check`: `CHECK (((status)::text = ANY ((ARRAY['QUEUED'::character varying, 'RUNNING'::character varying, 'SUCCESS'::character varying, 'FAILED'::character varying])::text[])))`
- `ai_jobs_status_not_null`: `NOT NULL status`
- `ai_jobs_tokens_in_check`: `CHECK ((tokens_in >= 0))`
- `ai_jobs_tokens_out_check`: `CHECK ((tokens_out >= 0))`
- `ai_jobs_updated_at_not_null`: `NOT NULL updated_at`
- `ai_jobs_validation_attempts_check`: `CHECK (((validation_attempts >= 0) AND (validation_attempts <= 2)))`
- `ai_jobs_validation_attempts_not_null`: `NOT NULL validation_attempts`
- `ai_jobs_validation_report_check`: `CHECK (((validation_report IS NULL) OR (jsonb_typeof(validation_report) = 'object'::text)))`
- `ai_jobs_validation_report_not_null`: `NOT NULL validation_report`
- `ai_jobs_validation_status_check`: `CHECK (((validation_status)::text = ANY ((ARRAY['PENDING'::character varying, 'VALID'::character varying, 'INVALID'::character varying, 'NEEDS_REVIEW'::character varying])::text[])))`
- `ai_jobs_validation_status_not_null`: `NOT NULL validation_status`
- `ai_jobs_version_check`: `CHECK ((version >= 0))`
- `ai_jobs_version_not_null`: `NOT NULL version`
- `ai_jobs_workspace_id_fkey`: `FOREIGN KEY (workspace_id) REFERENCES workspaces(id)`
- `ai_jobs_workspace_id_id_key`: `UNIQUE (workspace_id, id)`
- `ai_jobs_workspace_id_not_null`: `NOT NULL workspace_id`
- `ck_no_secret_input_refs`: `CHECK (((input_refs IS NULL) OR (NOT ds_json_has_secret(input_refs))))`
- `ck_no_secret_output_json`: `CHECK (((output_json IS NULL) OR (NOT ds_json_has_secret(output_json))))`
- `ck_no_secret_route_snapshot`: `CHECK (((route_snapshot IS NULL) OR (NOT ds_json_has_secret(route_snapshot))))`
- `ck_no_secret_validation_report`: `CHECK (((validation_report IS NULL) OR (NOT ds_json_has_secret(validation_report))))`
- `fk_ai_jobs_project_id_scope`: `FOREIGN KEY (workspace_id, project_id) REFERENCES projects(workspace_id, id)`

## alerts

| Column | PostgreSQL type | Nullable | Default |
|---|---|---|---|
| `id` PK | `uuid` | False | `gen_random_uuid()` |
| `project_id` | `uuid` | False | `` |
| `work_item_id` | `uuid` | True | `` |
| `previous_alert_id` | `uuid` | True | `` |
| `fingerprint` | `character(64)` | False | `` |
| `alert_code` | `character varying(80)` | False | `` |
| `severity` | `character varying(12)` | False | `` |
| `status` | `character varying(16)` | False | `'NEW'::character varying` |
| `title` | `character varying(255)` | False | `` |
| `evidence` | `jsonb` | False | `'{"schemaVersion": 1}'::jsonb` |
| `impact_snapshot` | `jsonb` | False | `'{"schemaVersion": 1}'::jsonb` |
| `assigned_to` | `uuid` | True | `` |
| `acknowledged_by` | `uuid` | True | `` |
| `detected_at` | `timestamp with time zone` | False | `now()` |
| `resolved_at` | `timestamp with time zone` | True | `` |
| `created_at` | `timestamp with time zone` | False | `now()` |
| `updated_at` | `timestamp with time zone` | False | `now()` |
| `version` | `bigint` | False | `0` |

Constraints:

- `alerts_acknowledged_by_fkey`: `FOREIGN KEY (acknowledged_by) REFERENCES users(id)`
- `alerts_alert_code_not_null`: `NOT NULL alert_code`
- `alerts_assigned_to_fkey`: `FOREIGN KEY (assigned_to) REFERENCES users(id)`
- `alerts_check`: `CHECK ((((status)::text = 'RESOLVED'::text) = (resolved_at IS NOT NULL)))`
- `alerts_check1`: `CHECK ((previous_alert_id IS DISTINCT FROM id))`
- `alerts_created_at_not_null`: `NOT NULL created_at`
- `alerts_detected_at_not_null`: `NOT NULL detected_at`
- `alerts_evidence_check`: `CHECK (((evidence IS NULL) OR (jsonb_typeof(evidence) = 'object'::text)))`
- `alerts_evidence_not_null`: `NOT NULL evidence`
- `alerts_fingerprint_check`: `CHECK ((fingerprint ~ '^[0-9a-f]{64}$'::text))`
- `alerts_fingerprint_not_null`: `NOT NULL fingerprint`
- `alerts_id_not_null`: `NOT NULL id`
- `alerts_impact_snapshot_check`: `CHECK (((impact_snapshot IS NULL) OR (jsonb_typeof(impact_snapshot) = 'object'::text)))`
- `alerts_impact_snapshot_not_null`: `NOT NULL impact_snapshot`
- `alerts_pkey`: `PRIMARY KEY (id)`
- `alerts_project_id_fkey`: `FOREIGN KEY (project_id) REFERENCES projects(id)`
- `alerts_project_id_id_key`: `UNIQUE (project_id, id)`
- `alerts_project_id_not_null`: `NOT NULL project_id`
- `alerts_severity_check`: `CHECK (((severity)::text = ANY ((ARRAY['LOW'::character varying, 'MEDIUM'::character varying, 'HIGH'::character varying, 'CRITICAL'::character varying])::text[])))`
- `alerts_severity_not_null`: `NOT NULL severity`
- `alerts_status_check`: `CHECK (((status)::text = ANY ((ARRAY['NEW'::character varying, 'ACKNOWLEDGED'::character varying, 'IN_PROGRESS'::character varying, 'RESOLVED'::character varying])::text[])))`
- `alerts_status_not_null`: `NOT NULL status`
- `alerts_title_not_null`: `NOT NULL title`
- `alerts_updated_at_not_null`: `NOT NULL updated_at`
- `alerts_version_check`: `CHECK ((version >= 0))`
- `alerts_version_not_null`: `NOT NULL version`
- `ck_no_secret_evidence`: `CHECK (((evidence IS NULL) OR (NOT ds_json_has_secret(evidence))))`
- `ck_no_secret_impact_snapshot`: `CHECK (((impact_snapshot IS NULL) OR (NOT ds_json_has_secret(impact_snapshot))))`
- `fk_alerts_previous_alert_id_scope`: `FOREIGN KEY (project_id, previous_alert_id) REFERENCES alerts(project_id, id)`
- `fk_alerts_work_item_id_scope`: `FOREIGN KEY (project_id, work_item_id) REFERENCES work_items(project_id, id)`

## attachments

| Column | PostgreSQL type | Nullable | Default |
|---|---|---|---|
| `id` PK | `uuid` | False | `gen_random_uuid()` |
| `workspace_id` | `uuid` | False | `` |
| `project_id` | `uuid` | True | `` |
| `work_item_id` | `uuid` | True | `` |
| `chat_message_id` | `uuid` | True | `` |
| `report_id` | `uuid` | True | `` |
| `ci_run_id` | `uuid` | True | `` |
| `uploaded_by` | `uuid` | True | `` |
| `kind` | `character varying(16)` | False | `` |
| `storage_key` | `character varying(512)` | False | `` |
| `filename` | `character varying(255)` | False | `` |
| `content_type` | `character varying(120)` | False | `` |
| `file_size` | `bigint` | False | `` |
| `sha256` | `character(64)` | False | `` |
| `visibility` | `character varying(16)` | False | `'INTERNAL'::character varying` |
| `scan_status` | `character varying(12)` | False | `'PENDING'::character varying` |
| `deleted_at` | `timestamp with time zone` | True | `` |
| `created_at` | `timestamp with time zone` | False | `now()` |
| `updated_at` | `timestamp with time zone` | False | `now()` |
| `version` | `bigint` | False | `0` |

Constraints:

- `attachments_chat_message_id_fkey`: `FOREIGN KEY (chat_message_id) REFERENCES chat_messages(id)`
- `attachments_check`: `CHECK ((num_nonnulls(work_item_id, chat_message_id, report_id, ci_run_id) = 1))`
- `attachments_check1`: `CHECK (((((kind)::text = 'TASK_FILE'::text) AND (work_item_id IS NOT NULL)) OR (((kind)::text = 'CHAT_FILE'::text) AND (chat_message_id IS NOT NULL)) OR (((kind)::text = 'REPORT_EXPORT'::text) AND (report_id IS NOT NULL)) OR (((kind)::text = 'CI_ARTIFACT'::text) AND (ci_run_id IS NOT NULL))))`
- `attachments_check2`: `CHECK ((((visibility)::text <> 'CLIENT_SHARED'::text) OR ((scan_status)::text = 'CLEAN'::text)))`
- `attachments_content_type_not_null`: `NOT NULL content_type`
- `attachments_created_at_not_null`: `NOT NULL created_at`
- `attachments_file_size_check`: `CHECK (((file_size >= 0) AND (file_size <= 20971520)))`
- `attachments_file_size_not_null`: `NOT NULL file_size`
- `attachments_filename_not_null`: `NOT NULL filename`
- `attachments_id_not_null`: `NOT NULL id`
- `attachments_kind_check`: `CHECK (((kind)::text = ANY ((ARRAY['TASK_FILE'::character varying, 'CHAT_FILE'::character varying, 'REPORT_EXPORT'::character varying, 'CI_ARTIFACT'::character varying])::text[])))`
- `attachments_kind_not_null`: `NOT NULL kind`
- `attachments_pkey`: `PRIMARY KEY (id)`
- `attachments_project_id_fkey`: `FOREIGN KEY (project_id) REFERENCES projects(id)`
- `attachments_project_id_id_key`: `UNIQUE (project_id, id)`
- `attachments_scan_status_check`: `CHECK (((scan_status)::text = ANY ((ARRAY['PENDING'::character varying, 'CLEAN'::character varying, 'REJECTED'::character varying])::text[])))`
- `attachments_scan_status_not_null`: `NOT NULL scan_status`
- `attachments_sha256_check`: `CHECK ((sha256 ~ '^[0-9a-f]{64}$'::text))`
- `attachments_sha256_not_null`: `NOT NULL sha256`
- `attachments_storage_key_key`: `UNIQUE (storage_key)`
- `attachments_storage_key_not_null`: `NOT NULL storage_key`
- `attachments_updated_at_not_null`: `NOT NULL updated_at`
- `attachments_uploaded_by_fkey`: `FOREIGN KEY (uploaded_by) REFERENCES users(id)`
- `attachments_version_check`: `CHECK ((version >= 0))`
- `attachments_version_not_null`: `NOT NULL version`
- `attachments_visibility_check`: `CHECK (((visibility)::text = ANY ((ARRAY['INTERNAL'::character varying, 'CLIENT_SHARED'::character varying])::text[])))`
- `attachments_visibility_not_null`: `NOT NULL visibility`
- `attachments_workspace_id_fkey`: `FOREIGN KEY (workspace_id) REFERENCES workspaces(id)`
- `attachments_workspace_id_id_key`: `UNIQUE (workspace_id, id)`
- `attachments_workspace_id_not_null`: `NOT NULL workspace_id`
- `ck_attachment_project_required`: `CHECK (((project_id IS NOT NULL) OR ((work_item_id IS NULL) AND (report_id IS NULL) AND (ci_run_id IS NULL))))`
- `fk_attachment_chat_workspace`: `FOREIGN KEY (workspace_id, chat_message_id) REFERENCES chat_messages(workspace_id, id)`
- `fk_attachments_ci_run_id_scope`: `FOREIGN KEY (project_id, ci_run_id) REFERENCES ci_runs(project_id, id)`
- `fk_attachments_report_id_scope`: `FOREIGN KEY (project_id, report_id) REFERENCES reports(project_id, id)`
- `fk_attachments_work_item_id_scope`: `FOREIGN KEY (project_id, work_item_id) REFERENCES work_items(project_id, id)`

## audit_logs

| Column | PostgreSQL type | Nullable | Default |
|---|---|---|---|
| `id` PK | `uuid` | False | `gen_random_uuid()` |
| `workspace_id` | `uuid` | True | `` |
| `project_id` | `uuid` | True | `` |
| `actor_id` | `uuid` | True | `` |
| `action` | `character varying(120)` | False | `` |
| `entity_type` | `character varying(100)` | False | `` |
| `entity_id` | `uuid` | True | `` |
| `before_json` | `jsonb` | True | `` |
| `after_json` | `jsonb` | True | `` |
| `metadata` | `jsonb` | False | `'{"schemaVersion": 1}'::jsonb` |
| `occurred_at` | `timestamp with time zone` | False | `now()` |
| `trace_id` | `character varying(64)` | True | `` |
| `created_at` | `timestamp with time zone` | False | `now()` |

Constraints:

- `audit_logs_action_not_null`: `NOT NULL action`
- `audit_logs_actor_id_fkey`: `FOREIGN KEY (actor_id) REFERENCES users(id)`
- `audit_logs_after_json_check`: `CHECK (((after_json IS NULL) OR (jsonb_typeof(after_json) = 'object'::text)))`
- `audit_logs_before_json_check`: `CHECK (((before_json IS NULL) OR (jsonb_typeof(before_json) = 'object'::text)))`
- `audit_logs_created_at_not_null`: `NOT NULL created_at`
- `audit_logs_entity_type_not_null`: `NOT NULL entity_type`
- `audit_logs_id_not_null`: `NOT NULL id`
- `audit_logs_metadata_check`: `CHECK (((metadata IS NULL) OR (jsonb_typeof(metadata) = 'object'::text)))`
- `audit_logs_metadata_not_null`: `NOT NULL metadata`
- `audit_logs_occurred_at_not_null`: `NOT NULL occurred_at`
- `audit_logs_pkey`: `PRIMARY KEY (id)`
- `audit_logs_project_id_id_key`: `UNIQUE (project_id, id)`
- `audit_logs_workspace_id_fkey`: `FOREIGN KEY (workspace_id) REFERENCES workspaces(id)`
- `audit_logs_workspace_id_id_key`: `UNIQUE (workspace_id, id)`
- `ck_no_secret_after_json`: `CHECK (((after_json IS NULL) OR (NOT ds_json_has_secret(after_json))))`
- `ck_no_secret_before_json`: `CHECK (((before_json IS NULL) OR (NOT ds_json_has_secret(before_json))))`
- `ck_no_secret_metadata`: `CHECK (((metadata IS NULL) OR (NOT ds_json_has_secret(metadata))))`
- `fk_audit_logs_project_id_scope`: `FOREIGN KEY (workspace_id, project_id) REFERENCES projects(workspace_id, id)`

## auth_sessions

| Column | PostgreSQL type | Nullable | Default |
|---|---|---|---|
| `id` PK | `uuid` | False | `gen_random_uuid()` |
| `user_id` | `uuid` | False | `` |
| `refresh_hash` | `character varying(255)` | False | `` |
| `issued_at` | `timestamp with time zone` | False | `now()` |
| `expires_at` | `timestamp with time zone` | False | `` |
| `revoked_at` | `timestamp with time zone` | True | `` |
| `user_agent` | `text` | True | `` |
| `ip` | `inet` | True | `` |
| `session_family_id` | `uuid` | False | `` |
| `created_at` | `timestamp with time zone` | False | `now()` |

Constraints:

- `auth_sessions_check`: `CHECK ((expires_at > issued_at))`
- `auth_sessions_check1`: `CHECK (((revoked_at IS NULL) OR (revoked_at >= issued_at)))`
- `auth_sessions_created_at_not_null`: `NOT NULL created_at`
- `auth_sessions_expires_at_not_null`: `NOT NULL expires_at`
- `auth_sessions_id_not_null`: `NOT NULL id`
- `auth_sessions_issued_at_not_null`: `NOT NULL issued_at`
- `auth_sessions_pkey`: `PRIMARY KEY (id)`
- `auth_sessions_refresh_hash_key`: `UNIQUE (refresh_hash)`
- `auth_sessions_refresh_hash_not_null`: `NOT NULL refresh_hash`
- `auth_sessions_session_family_id_not_null`: `NOT NULL session_family_id`
- `auth_sessions_user_id_fkey`: `FOREIGN KEY (user_id) REFERENCES users(id)`
- `auth_sessions_user_id_not_null`: `NOT NULL user_id`

## auth_tokens

| Column | PostgreSQL type | Nullable | Default |
|---|---|---|---|
| `id` PK | `uuid` | False | `gen_random_uuid()` |
| `user_id` | `uuid` | True | `` |
| `email` | `citext` | False | `` |
| `purpose` | `character varying(24)` | False | `` |
| `token_hash` | `character varying(255)` | False | `` |
| `expires_at` | `timestamp with time zone` | False | `` |
| `used_at` | `timestamp with time zone` | True | `` |
| `attempts` | `smallint` | False | `0` |
| `created_at` | `timestamp with time zone` | False | `now()` |

Constraints:

- `auth_tokens_attempts_check`: `CHECK (((attempts >= 0) AND (attempts <= 5)))`
- `auth_tokens_attempts_not_null`: `NOT NULL attempts`
- `auth_tokens_check`: `CHECK ((expires_at > created_at))`
- `auth_tokens_check1`: `CHECK (((used_at IS NULL) OR (used_at >= created_at)))`
- `auth_tokens_created_at_not_null`: `NOT NULL created_at`
- `auth_tokens_email_not_null`: `NOT NULL email`
- `auth_tokens_expires_at_not_null`: `NOT NULL expires_at`
- `auth_tokens_id_not_null`: `NOT NULL id`
- `auth_tokens_pkey`: `PRIMARY KEY (id)`
- `auth_tokens_purpose_check`: `CHECK (((purpose)::text = ANY ((ARRAY['VERIFY_EMAIL'::character varying, 'RESET_PASSWORD'::character varying])::text[])))`
- `auth_tokens_purpose_not_null`: `NOT NULL purpose`
- `auth_tokens_token_hash_not_null`: `NOT NULL token_hash`
- `auth_tokens_user_id_fkey`: `FOREIGN KEY (user_id) REFERENCES users(id)`

## background_jobs

| Column | PostgreSQL type | Nullable | Default |
|---|---|---|---|
| `id` PK | `uuid` | False | `gen_random_uuid()` |
| `workspace_id` | `uuid` | True | `` |
| `project_id` | `uuid` | True | `` |
| `requested_by` | `uuid` | True | `` |
| `job_kind` | `character varying(24)` | False | `` |
| `idempotency_key` | `character varying(180)` | True | `` |
| `request_hash` | `character(64)` | True | `` |
| `payload` | `jsonb` | False | `'{"schemaVersion": 1}'::jsonb` |
| `result` | `jsonb` | True | `` |
| `status` | `character varying(12)` | False | `'QUEUED'::character varying` |
| `attempts` | `integer` | False | `0` |
| `scheduled_at` | `timestamp with time zone` | False | `now()` |
| `started_at` | `timestamp with time zone` | True | `` |
| `finished_at` | `timestamp with time zone` | True | `` |
| `expires_at` | `timestamp with time zone` | True | `` |
| `locked_by` | `character varying(120)` | True | `` |
| `locked_until` | `timestamp with time zone` | True | `` |
| `last_error` | `text` | True | `` |
| `created_at` | `timestamp with time zone` | False | `now()` |
| `updated_at` | `timestamp with time zone` | False | `now()` |
| `version` | `bigint` | False | `0` |

Constraints:

- `background_jobs_attempts_check`: `CHECK ((attempts >= 0))`
- `background_jobs_attempts_not_null`: `NOT NULL attempts`
- `background_jobs_check`: `CHECK (((project_id IS NULL) OR (workspace_id IS NOT NULL)))`
- `background_jobs_check1`: `CHECK (((locked_by IS NULL) = (locked_until IS NULL)))`
- `background_jobs_created_at_not_null`: `NOT NULL created_at`
- `background_jobs_id_not_null`: `NOT NULL id`
- `background_jobs_job_kind_check`: `CHECK (((job_kind)::text = ANY ((ARRAY['OUTBOX_EVENT'::character varying, 'WEBHOOK_RETRY'::character varying, 'NOTIFICATION_EMAIL'::character varying, 'GITHUB_BACKFILL'::character varying, 'REPORT_EXPORT'::character varying, 'SCHEDULE_SOLVE'::character varying, 'IDEMPOTENCY'::character varying, 'BACKUP_CHECK'::character varying])::text[])))`
- `background_jobs_job_kind_not_null`: `NOT NULL job_kind`
- `background_jobs_payload_check`: `CHECK (((payload IS NULL) OR (jsonb_typeof(payload) = 'object'::text)))`
- `background_jobs_payload_not_null`: `NOT NULL payload`
- `background_jobs_pkey`: `PRIMARY KEY (id)`
- `background_jobs_project_id_id_key`: `UNIQUE (project_id, id)`
- `background_jobs_request_hash_check`: `CHECK (((request_hash IS NULL) OR (request_hash ~ '^[0-9a-f]{64}$'::text)))`
- `background_jobs_requested_by_fkey`: `FOREIGN KEY (requested_by) REFERENCES users(id)`
- `background_jobs_result_check`: `CHECK (((result IS NULL) OR (jsonb_typeof(result) = 'object'::text)))`
- `background_jobs_scheduled_at_not_null`: `NOT NULL scheduled_at`
- `background_jobs_status_check`: `CHECK (((status)::text = ANY ((ARRAY['QUEUED'::character varying, 'RUNNING'::character varying, 'SUCCESS'::character varying, 'FAILED'::character varying])::text[])))`
- `background_jobs_status_not_null`: `NOT NULL status`
- `background_jobs_updated_at_not_null`: `NOT NULL updated_at`
- `background_jobs_version_check`: `CHECK ((version >= 0))`
- `background_jobs_version_not_null`: `NOT NULL version`
- `background_jobs_workspace_id_fkey`: `FOREIGN KEY (workspace_id) REFERENCES workspaces(id)`
- `background_jobs_workspace_id_id_key`: `UNIQUE (workspace_id, id)`
- `ck_no_secret_payload`: `CHECK (((payload IS NULL) OR (NOT ds_json_has_secret(payload))))`
- `ck_no_secret_result`: `CHECK (((result IS NULL) OR (NOT ds_json_has_secret(result))))`
- `fk_background_jobs_project_id_scope`: `FOREIGN KEY (workspace_id, project_id) REFERENCES projects(workspace_id, id)`

## chat_channel_members

| Column | PostgreSQL type | Nullable | Default |
|---|---|---|---|
| `id` PK | `uuid` | False | `gen_random_uuid()` |
| `workspace_id` | `uuid` | False | `` |
| `project_id` | `uuid` | True | `` |
| `channel_id` | `uuid` | False | `` |
| `user_id` | `uuid` | False | `` |
| `joined_at` | `timestamp with time zone` | False | `now()` |
| `muted` | `boolean` | False | `false` |
| `created_at` | `timestamp with time zone` | False | `now()` |
| `updated_at` | `timestamp with time zone` | False | `now()` |
| `version` | `bigint` | False | `0` |

Constraints:

- `chat_channel_members_channel_id_not_null`: `NOT NULL channel_id`
- `chat_channel_members_channel_id_user_id_key`: `UNIQUE (channel_id, user_id)`
- `chat_channel_members_created_at_not_null`: `NOT NULL created_at`
- `chat_channel_members_id_not_null`: `NOT NULL id`
- `chat_channel_members_joined_at_not_null`: `NOT NULL joined_at`
- `chat_channel_members_muted_not_null`: `NOT NULL muted`
- `chat_channel_members_pkey`: `PRIMARY KEY (id)`
- `chat_channel_members_project_id_fkey`: `FOREIGN KEY (project_id) REFERENCES projects(id)`
- `chat_channel_members_project_id_id_key`: `UNIQUE (project_id, id)`
- `chat_channel_members_updated_at_not_null`: `NOT NULL updated_at`
- `chat_channel_members_user_id_fkey`: `FOREIGN KEY (user_id) REFERENCES users(id)`
- `chat_channel_members_user_id_not_null`: `NOT NULL user_id`
- `chat_channel_members_version_check`: `CHECK ((version >= 0))`
- `chat_channel_members_version_not_null`: `NOT NULL version`
- `chat_channel_members_workspace_id_fkey`: `FOREIGN KEY (workspace_id) REFERENCES workspaces(id)`
- `chat_channel_members_workspace_id_id_key`: `UNIQUE (workspace_id, id)`
- `chat_channel_members_workspace_id_not_null`: `NOT NULL workspace_id`
- `fk_chat_channel_members_channel_id_scope`: `FOREIGN KEY (workspace_id, channel_id) REFERENCES chat_channels(workspace_id, id)`

## chat_channels

| Column | PostgreSQL type | Nullable | Default |
|---|---|---|---|
| `id` PK | `uuid` | False | `gen_random_uuid()` |
| `workspace_id` | `uuid` | False | `` |
| `project_id` | `uuid` | True | `` |
| `work_item_id` | `uuid` | True | `` |
| `type` | `character varying(12)` | False | `` |
| `name` | `character varying(150)` | True | `` |
| `created_by` | `uuid` | False | `` |
| `archived_at` | `timestamp with time zone` | True | `` |
| `created_at` | `timestamp with time zone` | False | `now()` |
| `updated_at` | `timestamp with time zone` | False | `now()` |
| `version` | `bigint` | False | `0` |

Constraints:

- `chat_channels_check`: `CHECK ((((type)::text = 'TASK'::text) = (work_item_id IS NOT NULL)))`
- `chat_channels_check1`: `CHECK ((((type)::text <> ALL ((ARRAY['TASK'::character varying, 'PROJECT'::character varying, 'CLIENT_PM'::character varying])::text[])) OR (project_id IS NOT NULL)))`
- `chat_channels_created_at_not_null`: `NOT NULL created_at`
- `chat_channels_created_by_fkey`: `FOREIGN KEY (created_by) REFERENCES users(id)`
- `chat_channels_created_by_not_null`: `NOT NULL created_by`
- `chat_channels_id_not_null`: `NOT NULL id`
- `chat_channels_pkey`: `PRIMARY KEY (id)`
- `chat_channels_project_id_id_key`: `UNIQUE (project_id, id)`
- `chat_channels_type_check`: `CHECK (((type)::text = ANY ((ARRAY['PROJECT'::character varying, 'TEAM'::character varying, 'TASK'::character varying, 'PRIVATE'::character varying, 'CLIENT_PM'::character varying])::text[])))`
- `chat_channels_type_not_null`: `NOT NULL type`
- `chat_channels_updated_at_not_null`: `NOT NULL updated_at`
- `chat_channels_version_check`: `CHECK ((version >= 0))`
- `chat_channels_version_not_null`: `NOT NULL version`
- `chat_channels_workspace_id_fkey`: `FOREIGN KEY (workspace_id) REFERENCES workspaces(id)`
- `chat_channels_workspace_id_id_key`: `UNIQUE (workspace_id, id)`
- `chat_channels_workspace_id_not_null`: `NOT NULL workspace_id`
- `fk_chat_channels_project_id_scope`: `FOREIGN KEY (workspace_id, project_id) REFERENCES projects(workspace_id, id)`
- `fk_chat_channels_work_item_id_scope`: `FOREIGN KEY (project_id, work_item_id) REFERENCES work_items(project_id, id)`

## chat_messages

| Column | PostgreSQL type | Nullable | Default |
|---|---|---|---|
| `id` PK | `uuid` | False | `gen_random_uuid()` |
| `workspace_id` | `uuid` | False | `` |
| `project_id` | `uuid` | True | `` |
| `channel_id` | `uuid` | False | `` |
| `sender_id` | `uuid` | True | `` |
| `sender_kind` | `character varying(8)` | False | `'USER'::character varying` |
| `ai_job_id` | `uuid` | True | `` |
| `reply_to_id` | `uuid` | True | `` |
| `body` | `text` | False | `` |
| `mentions` | `uuid[]` | False | `'{}'::uuid[]` |
| `provenance` | `jsonb` | False | `'{"schemaVersion": 1}'::jsonb` |
| `sent_at` | `timestamp with time zone` | False | `now()` |
| `edited_at` | `timestamp with time zone` | True | `` |
| `deleted_at` | `timestamp with time zone` | True | `` |
| `created_at` | `timestamp with time zone` | False | `now()` |
| `updated_at` | `timestamp with time zone` | False | `now()` |
| `version` | `bigint` | False | `0` |

Constraints:

- `chat_messages_body_check`: `CHECK ((length(btrim(body)) > 0))`
- `chat_messages_body_not_null`: `NOT NULL body`
- `chat_messages_channel_id_not_null`: `NOT NULL channel_id`
- `chat_messages_check`: `CHECK ((reply_to_id IS DISTINCT FROM id))`
- `chat_messages_check1`: `CHECK (((((sender_kind)::text = 'USER'::text) AND (sender_id IS NOT NULL)) OR (((sender_kind)::text = 'AI'::text) AND (ai_job_id IS NOT NULL)) OR ((sender_kind)::text = 'SYSTEM'::text)))`
- `chat_messages_created_at_not_null`: `NOT NULL created_at`
- `chat_messages_id_not_null`: `NOT NULL id`
- `chat_messages_mentions_not_null`: `NOT NULL mentions`
- `chat_messages_pkey`: `PRIMARY KEY (id)`
- `chat_messages_project_id_fkey`: `FOREIGN KEY (project_id) REFERENCES projects(id)`
- `chat_messages_project_id_id_key`: `UNIQUE (project_id, id)`
- `chat_messages_provenance_check`: `CHECK (((provenance IS NULL) OR (jsonb_typeof(provenance) = 'object'::text)))`
- `chat_messages_provenance_not_null`: `NOT NULL provenance`
- `chat_messages_reply_to_id_fkey`: `FOREIGN KEY (reply_to_id) REFERENCES chat_messages(id)`
- `chat_messages_sender_id_fkey`: `FOREIGN KEY (sender_id) REFERENCES users(id)`
- `chat_messages_sender_kind_check`: `CHECK (((sender_kind)::text = ANY ((ARRAY['USER'::character varying, 'AI'::character varying, 'SYSTEM'::character varying])::text[])))`
- `chat_messages_sender_kind_not_null`: `NOT NULL sender_kind`
- `chat_messages_sent_at_not_null`: `NOT NULL sent_at`
- `chat_messages_updated_at_not_null`: `NOT NULL updated_at`
- `chat_messages_version_check`: `CHECK ((version >= 0))`
- `chat_messages_version_not_null`: `NOT NULL version`
- `chat_messages_workspace_id_fkey`: `FOREIGN KEY (workspace_id) REFERENCES workspaces(id)`
- `chat_messages_workspace_id_id_key`: `UNIQUE (workspace_id, id)`
- `chat_messages_workspace_id_not_null`: `NOT NULL workspace_id`
- `ck_no_secret_provenance`: `CHECK (((provenance IS NULL) OR (NOT ds_json_has_secret(provenance))))`
- `fk_chat_messages_ai_job_id_scope`: `FOREIGN KEY (workspace_id, ai_job_id) REFERENCES ai_jobs(workspace_id, id)`
- `fk_chat_messages_channel_id_scope`: `FOREIGN KEY (workspace_id, channel_id) REFERENCES chat_channels(workspace_id, id)`
- `fk_reply_same_channel`: `FOREIGN KEY (channel_id, reply_to_id) REFERENCES chat_messages(channel_id, id)`
- `uq_message_channel`: `UNIQUE (channel_id, id)`

## ci_runs

| Column | PostgreSQL type | Nullable | Default |
|---|---|---|---|
| `id` PK | `uuid` | False | `gen_random_uuid()` |
| `project_id` | `uuid` | False | `` |
| `repository_id` | `uuid` | False | `` |
| `work_item_id` | `uuid` | True | `` |
| `github_run_id` | `bigint` | False | `` |
| `run_attempt` | `integer` | False | `1` |
| `head_sha` | `character(40)` | False | `` |
| `pr_number` | `integer` | True | `` |
| `status` | `character varying(16)` | False | `'QUEUED'::character varying` |
| `conclusion` | `character varying(16)` | True | `` |
| `checks` | `jsonb` | False | `'[]'::jsonb` |
| `metrics` | `jsonb` | False | `'{"schemaVersion": 1}'::jsonb` |
| `started_at` | `timestamp with time zone` | True | `` |
| `completed_at` | `timestamp with time zone` | True | `` |
| `created_at` | `timestamp with time zone` | False | `now()` |
| `updated_at` | `timestamp with time zone` | False | `now()` |
| `version` | `bigint` | False | `0` |

Constraints:

- `ci_runs_check`: `CHECK ((((status)::text = 'COMPLETED'::text) = ((conclusion IS NOT NULL) AND (completed_at IS NOT NULL))))`
- `ci_runs_check1`: `CHECK (((completed_at IS NULL) OR (started_at IS NULL) OR (completed_at >= started_at)))`
- `ci_runs_checks_check`: `CHECK (((checks IS NULL) OR (jsonb_typeof(checks) = 'array'::text)))`
- `ci_runs_checks_not_null`: `NOT NULL checks`
- `ci_runs_conclusion_check`: `CHECK (((conclusion)::text = ANY ((ARRAY['SUCCESS'::character varying, 'FAILURE'::character varying, 'CANCELLED'::character varying, 'SKIPPED'::character varying, 'TIMED_OUT'::character varying, 'NEUTRAL'::character varying, 'ACTION_REQUIRED'::character varying, 'STALE'::character varying, 'STARTUP_FAILURE'::character varying])::text[])))`
- `ci_runs_created_at_not_null`: `NOT NULL created_at`
- `ci_runs_github_run_id_check`: `CHECK ((github_run_id > 0))`
- `ci_runs_github_run_id_not_null`: `NOT NULL github_run_id`
- `ci_runs_head_sha_check`: `CHECK ((head_sha ~ '^[0-9a-f]{40}$'::text))`
- `ci_runs_head_sha_not_null`: `NOT NULL head_sha`
- `ci_runs_id_not_null`: `NOT NULL id`
- `ci_runs_metrics_check`: `CHECK (((metrics IS NULL) OR (jsonb_typeof(metrics) = 'object'::text)))`
- `ci_runs_metrics_not_null`: `NOT NULL metrics`
- `ci_runs_pkey`: `PRIMARY KEY (id)`
- `ci_runs_pr_number_check`: `CHECK ((pr_number > 0))`
- `ci_runs_project_id_fkey`: `FOREIGN KEY (project_id) REFERENCES projects(id)`
- `ci_runs_project_id_id_key`: `UNIQUE (project_id, id)`
- `ci_runs_project_id_not_null`: `NOT NULL project_id`
- `ci_runs_repository_id_github_run_id_run_attempt_key`: `UNIQUE (repository_id, github_run_id, run_attempt)`
- `ci_runs_repository_id_not_null`: `NOT NULL repository_id`
- `ci_runs_run_attempt_check`: `CHECK ((run_attempt > 0))`
- `ci_runs_run_attempt_not_null`: `NOT NULL run_attempt`
- `ci_runs_status_check`: `CHECK (((status)::text = ANY ((ARRAY['QUEUED'::character varying, 'IN_PROGRESS'::character varying, 'COMPLETED'::character varying])::text[])))`
- `ci_runs_status_not_null`: `NOT NULL status`
- `ci_runs_updated_at_not_null`: `NOT NULL updated_at`
- `ci_runs_version_check`: `CHECK ((version >= 0))`
- `ci_runs_version_not_null`: `NOT NULL version`
- `ck_no_secret_checks`: `CHECK (((checks IS NULL) OR (NOT ds_json_has_secret(checks))))`
- `ck_no_secret_metrics`: `CHECK (((metrics IS NULL) OR (NOT ds_json_has_secret(metrics))))`
- `fk_ci_runs_repository_id_scope`: `FOREIGN KEY (project_id, repository_id) REFERENCES github_repositories(project_id, id)`
- `fk_ci_runs_work_item_id_scope`: `FOREIGN KEY (project_id, work_item_id) REFERENCES work_items(project_id, id)`

## code_reviews

| Column | PostgreSQL type | Nullable | Default |
|---|---|---|---|
| `id` PK | `uuid` | False | `gen_random_uuid()` |
| `project_id` | `uuid` | False | `` |
| `repository_id` | `uuid` | False | `` |
| `work_item_id` | `uuid` | True | `` |
| `ai_job_id` | `uuid` | True | `` |
| `pr_number` | `integer` | True | `` |
| `head_sha` | `character(40)` | False | `` |
| `context_version` | `bigint` | False | `0` |
| `scope` | `character varying(8)` | False | `` |
| `reviewer_kind` | `character varying(8)` | False | `` |
| `findings` | `jsonb` | False | `'[]'::jsonb` |
| `summary` | `text` | True | `` |
| `status` | `character varying(24)` | False | `'PENDING'::character varying` |
| `gate_snapshot` | `jsonb` | False | `'{"schemaVersion": 1}'::jsonb` |
| `reviewer_id` | `uuid` | True | `` |
| `model_name` | `character varying(120)` | True | `` |
| `completed_at` | `timestamp with time zone` | True | `` |
| `created_at` | `timestamp with time zone` | False | `now()` |
| `updated_at` | `timestamp with time zone` | False | `now()` |
| `version` | `bigint` | False | `0` |

Constraints:

- `ck_no_secret_findings`: `CHECK (((findings IS NULL) OR (NOT ds_json_has_secret(findings))))`
- `ck_no_secret_gate_snapshot`: `CHECK (((gate_snapshot IS NULL) OR (NOT ds_json_has_secret(gate_snapshot))))`
- `code_reviews_check`: `CHECK (((((reviewer_kind)::text = 'HUMAN'::text) AND (reviewer_id IS NOT NULL)) OR ((reviewer_kind)::text = 'AI'::text)))`
- `code_reviews_check1`: `CHECK ((((scope)::text <> 'PR'::text) OR (pr_number IS NOT NULL)))`
- `code_reviews_context_version_check`: `CHECK ((context_version >= 0))`
- `code_reviews_context_version_not_null`: `NOT NULL context_version`
- `code_reviews_created_at_not_null`: `NOT NULL created_at`
- `code_reviews_findings_check`: `CHECK (((findings IS NULL) OR (jsonb_typeof(findings) = 'array'::text)))`
- `code_reviews_findings_not_null`: `NOT NULL findings`
- `code_reviews_gate_snapshot_check`: `CHECK (((gate_snapshot IS NULL) OR (jsonb_typeof(gate_snapshot) = 'object'::text)))`
- `code_reviews_gate_snapshot_not_null`: `NOT NULL gate_snapshot`
- `code_reviews_head_sha_check`: `CHECK ((head_sha ~ '^[0-9a-f]{40}$'::text))`
- `code_reviews_head_sha_not_null`: `NOT NULL head_sha`
- `code_reviews_id_not_null`: `NOT NULL id`
- `code_reviews_pkey`: `PRIMARY KEY (id)`
- `code_reviews_pr_number_check`: `CHECK ((pr_number > 0))`
- `code_reviews_project_id_fkey`: `FOREIGN KEY (project_id) REFERENCES projects(id)`
- `code_reviews_project_id_id_key`: `UNIQUE (project_id, id)`
- `code_reviews_project_id_not_null`: `NOT NULL project_id`
- `code_reviews_repository_id_not_null`: `NOT NULL repository_id`
- `code_reviews_reviewer_id_fkey`: `FOREIGN KEY (reviewer_id) REFERENCES users(id)`
- `code_reviews_reviewer_kind_check`: `CHECK (((reviewer_kind)::text = ANY ((ARRAY['AI'::character varying, 'HUMAN'::character varying])::text[])))`
- `code_reviews_reviewer_kind_not_null`: `NOT NULL reviewer_kind`
- `code_reviews_scope_check`: `CHECK (((scope)::text = ANY ((ARRAY['PUSH'::character varying, 'PR'::character varying])::text[])))`
- `code_reviews_scope_not_null`: `NOT NULL scope`
- `code_reviews_status_check`: `CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'RUNNING'::character varying, 'PASS'::character varying, 'FAIL'::character varying, 'REQUIRES_CHANGES'::character varying, 'UNAVAILABLE'::character varying, 'STALE'::character varying])::text[])))`
- `code_reviews_status_not_null`: `NOT NULL status`
- `code_reviews_updated_at_not_null`: `NOT NULL updated_at`
- `code_reviews_version_check`: `CHECK ((version >= 0))`
- `code_reviews_version_not_null`: `NOT NULL version`
- `fk_code_reviews_ai_job_id_scope`: `FOREIGN KEY (project_id, ai_job_id) REFERENCES ai_jobs(project_id, id)`
- `fk_code_reviews_repository_id_scope`: `FOREIGN KEY (project_id, repository_id) REFERENCES github_repositories(project_id, id)`
- `fk_code_reviews_work_item_id_scope`: `FOREIGN KEY (project_id, work_item_id) REFERENCES work_items(project_id, id)`

## developer_capacity

| Column | PostgreSQL type | Nullable | Default |
|---|---|---|---|
| `id` PK | `uuid` | False | `gen_random_uuid()` |
| `project_id` | `uuid` | False | `` |
| `user_id` | `uuid` | False | `` |
| `skills` | `jsonb` | False | `'[]'::jsonb` |
| `weekly_slots` | `jsonb` | False | `'[]'::jsonb` |
| `time_off` | `jsonb` | False | `'[]'::jsonb` |
| `minutes_per_workday` | `integer` | False | `` |
| `allocation_pct` | `numeric(5,2)` | False | `100` |
| `effective_from` | `date` | False | `` |
| `effective_to` | `date` | True | `` |
| `created_at` | `timestamp with time zone` | False | `now()` |
| `updated_at` | `timestamp with time zone` | False | `now()` |
| `version` | `bigint` | False | `0` |

Constraints:

- `ck_capacity_skill_contract`: `CHECK (ds_valid_skills(skills))`
- `ck_no_secret_skills`: `CHECK (((skills IS NULL) OR (NOT ds_json_has_secret(skills))))`
- `ck_no_secret_time_off`: `CHECK (((time_off IS NULL) OR (NOT ds_json_has_secret(time_off))))`
- `ck_no_secret_weekly_slots`: `CHECK (((weekly_slots IS NULL) OR (NOT ds_json_has_secret(weekly_slots))))`
- `developer_capacity_allocation_pct_check`: `CHECK (((allocation_pct > (0)::numeric) AND (allocation_pct <= (100)::numeric)))`
- `developer_capacity_allocation_pct_not_null`: `NOT NULL allocation_pct`
- `developer_capacity_check`: `CHECK (((effective_to IS NULL) OR (effective_to > effective_from)))`
- `developer_capacity_created_at_not_null`: `NOT NULL created_at`
- `developer_capacity_effective_from_not_null`: `NOT NULL effective_from`
- `developer_capacity_id_not_null`: `NOT NULL id`
- `developer_capacity_minutes_per_workday_check`: `CHECK (((minutes_per_workday >= 1) AND (minutes_per_workday <= 1440)))`
- `developer_capacity_minutes_per_workday_not_null`: `NOT NULL minutes_per_workday`
- `developer_capacity_pkey`: `PRIMARY KEY (id)`
- `developer_capacity_project_id_fkey`: `FOREIGN KEY (project_id) REFERENCES projects(id)`
- `developer_capacity_project_id_id_key`: `UNIQUE (project_id, id)`
- `developer_capacity_project_id_not_null`: `NOT NULL project_id`
- `developer_capacity_project_id_user_id_effective_from_key`: `UNIQUE (project_id, user_id, effective_from)`
- `developer_capacity_skills_check`: `CHECK (((skills IS NULL) OR (jsonb_typeof(skills) = 'array'::text)))`
- `developer_capacity_skills_not_null`: `NOT NULL skills`
- `developer_capacity_time_off_check`: `CHECK (((time_off IS NULL) OR (jsonb_typeof(time_off) = 'array'::text)))`
- `developer_capacity_time_off_not_null`: `NOT NULL time_off`
- `developer_capacity_updated_at_not_null`: `NOT NULL updated_at`
- `developer_capacity_user_id_fkey`: `FOREIGN KEY (user_id) REFERENCES users(id)`
- `developer_capacity_user_id_not_null`: `NOT NULL user_id`
- `developer_capacity_version_check`: `CHECK ((version >= 0))`
- `developer_capacity_version_not_null`: `NOT NULL version`
- `developer_capacity_weekly_slots_check`: `CHECK (((weekly_slots IS NULL) OR (jsonb_typeof(weekly_slots) = 'array'::text)))`
- `developer_capacity_weekly_slots_not_null`: `NOT NULL weekly_slots`
- `ex_capacity_effective_overlap`: `EXCLUDE USING gist (project_id WITH =, user_id WITH =, daterange(effective_from, effective_to, '[)'::text) WITH &&)`

## github_activities

| Column | PostgreSQL type | Nullable | Default |
|---|---|---|---|
| `id` PK | `uuid` | False | `gen_random_uuid()` |
| `project_id` | `uuid` | False | `` |
| `repository_id` | `uuid` | False | `` |
| `work_item_id` | `uuid` | True | `` |
| `kind` | `character varying(16)` | False | `` |
| `github_object_id` | `character varying(100)` | False | `` |
| `commit_sha` | `character(40)` | True | `` |
| `pr_number` | `integer` | True | `` |
| `pr_head_sha` | `character(40)` | True | `` |
| `actor_login` | `character varying(150)` | True | `` |
| `state` | `character varying(60)` | False | `` |
| `title` | `text` | True | `` |
| `payload` | `jsonb` | False | `'{}'::jsonb` |
| `happened_at` | `timestamp with time zone` | False | `` |
| `synced_at` | `timestamp with time zone` | False | `now()` |
| `created_at` | `timestamp with time zone` | False | `now()` |
| `updated_at` | `timestamp with time zone` | False | `now()` |
| `version` | `bigint` | False | `0` |

Constraints:

- `ck_no_secret_payload`: `CHECK (((payload IS NULL) OR (NOT ds_json_has_secret(payload))))`
- `fk_github_activities_repository_id_scope`: `FOREIGN KEY (project_id, repository_id) REFERENCES github_repositories(project_id, id)`
- `fk_github_activities_work_item_id_scope`: `FOREIGN KEY (project_id, work_item_id) REFERENCES work_items(project_id, id)`
- `github_activities_commit_sha_check`: `CHECK ((commit_sha ~ '^[0-9a-f]{40}$'::text))`
- `github_activities_created_at_not_null`: `NOT NULL created_at`
- `github_activities_github_object_id_not_null`: `NOT NULL github_object_id`
- `github_activities_happened_at_not_null`: `NOT NULL happened_at`
- `github_activities_id_not_null`: `NOT NULL id`
- `github_activities_kind_check`: `CHECK (((kind)::text = ANY ((ARRAY['BRANCH'::character varying, 'COMMIT'::character varying, 'PULL_REQUEST'::character varying, 'HUMAN_REVIEW'::character varying, 'QUALITY_GATE'::character varying])::text[])))`
- `github_activities_kind_not_null`: `NOT NULL kind`
- `github_activities_payload_check`: `CHECK (((payload IS NULL) OR (jsonb_typeof(payload) = 'object'::text)))`
- `github_activities_payload_not_null`: `NOT NULL payload`
- `github_activities_pkey`: `PRIMARY KEY (id)`
- `github_activities_pr_head_sha_check`: `CHECK ((pr_head_sha ~ '^[0-9a-f]{40}$'::text))`
- `github_activities_pr_number_check`: `CHECK ((pr_number > 0))`
- `github_activities_project_id_fkey`: `FOREIGN KEY (project_id) REFERENCES projects(id)`
- `github_activities_project_id_id_key`: `UNIQUE (project_id, id)`
- `github_activities_project_id_not_null`: `NOT NULL project_id`
- `github_activities_repository_id_kind_github_object_id_key`: `UNIQUE (repository_id, kind, github_object_id)`
- `github_activities_repository_id_not_null`: `NOT NULL repository_id`
- `github_activities_state_not_null`: `NOT NULL state`
- `github_activities_synced_at_not_null`: `NOT NULL synced_at`
- `github_activities_updated_at_not_null`: `NOT NULL updated_at`
- `github_activities_version_check`: `CHECK ((version >= 0))`
- `github_activities_version_not_null`: `NOT NULL version`

## github_events

| Column | PostgreSQL type | Nullable | Default |
|---|---|---|---|
| `id` PK | `uuid` | False | `gen_random_uuid()` |
| `project_id` | `uuid` | False | `` |
| `repository_id` | `uuid` | False | `` |
| `delivery_id` | `character varying(120)` | False | `` |
| `event_type` | `character varying(80)` | False | `` |
| `action` | `character varying(80)` | True | `` |
| `payload` | `jsonb` | False | `` |
| `process_status` | `character varying(12)` | False | `'RECEIVED'::character varying` |
| `attempts` | `integer` | False | `0` |
| `received_at` | `timestamp with time zone` | False | `now()` |
| `processed_at` | `timestamp with time zone` | True | `` |
| `created_at` | `timestamp with time zone` | False | `now()` |
| `updated_at` | `timestamp with time zone` | False | `now()` |
| `version` | `bigint` | False | `0` |

Constraints:

- `ck_no_secret_payload`: `CHECK (((payload IS NULL) OR (NOT ds_json_has_secret(payload))))`
- `fk_github_events_repository_id_scope`: `FOREIGN KEY (project_id, repository_id) REFERENCES github_repositories(project_id, id)`
- `github_events_attempts_check`: `CHECK ((attempts >= 0))`
- `github_events_attempts_not_null`: `NOT NULL attempts`
- `github_events_created_at_not_null`: `NOT NULL created_at`
- `github_events_delivery_id_key`: `UNIQUE (delivery_id)`
- `github_events_delivery_id_not_null`: `NOT NULL delivery_id`
- `github_events_event_type_not_null`: `NOT NULL event_type`
- `github_events_id_not_null`: `NOT NULL id`
- `github_events_payload_check`: `CHECK (((payload IS NULL) OR (jsonb_typeof(payload) = 'object'::text)))`
- `github_events_payload_not_null`: `NOT NULL payload`
- `github_events_pkey`: `PRIMARY KEY (id)`
- `github_events_process_status_check`: `CHECK (((process_status)::text = ANY ((ARRAY['RECEIVED'::character varying, 'PROCESSING'::character varying, 'DONE'::character varying, 'FAILED'::character varying])::text[])))`
- `github_events_process_status_not_null`: `NOT NULL process_status`
- `github_events_project_id_fkey`: `FOREIGN KEY (project_id) REFERENCES projects(id)`
- `github_events_project_id_id_key`: `UNIQUE (project_id, id)`
- `github_events_project_id_not_null`: `NOT NULL project_id`
- `github_events_received_at_not_null`: `NOT NULL received_at`
- `github_events_repository_id_not_null`: `NOT NULL repository_id`
- `github_events_updated_at_not_null`: `NOT NULL updated_at`
- `github_events_version_check`: `CHECK ((version >= 0))`
- `github_events_version_not_null`: `NOT NULL version`

## github_repositories

| Column | PostgreSQL type | Nullable | Default |
|---|---|---|---|
| `id` PK | `uuid` | False | `gen_random_uuid()` |
| `workspace_id` | `uuid` | False | `` |
| `project_id` | `uuid` | False | `` |
| `github_installation_id` | `bigint` | False | `` |
| `github_repo_id` | `bigint` | False | `` |
| `full_name` | `character varying(255)` | False | `` |
| `default_branch` | `character varying(120)` | False | `` |
| `private` | `boolean` | False | `true` |
| `active` | `boolean` | False | `true` |
| `sync_cursor` | `text` | True | `` |
| `last_synced_at` | `timestamp with time zone` | True | `` |
| `created_at` | `timestamp with time zone` | False | `now()` |
| `updated_at` | `timestamp with time zone` | False | `now()` |
| `version` | `bigint` | False | `0` |

Constraints:

- `fk_github_repositories_project_id_scope`: `FOREIGN KEY (workspace_id, project_id) REFERENCES projects(workspace_id, id)`
- `github_repositories_active_not_null`: `NOT NULL active`
- `github_repositories_created_at_not_null`: `NOT NULL created_at`
- `github_repositories_default_branch_not_null`: `NOT NULL default_branch`
- `github_repositories_full_name_not_null`: `NOT NULL full_name`
- `github_repositories_github_installation_id_check`: `CHECK ((github_installation_id > 0))`
- `github_repositories_github_installation_id_not_null`: `NOT NULL github_installation_id`
- `github_repositories_github_repo_id_check`: `CHECK ((github_repo_id > 0))`
- `github_repositories_github_repo_id_key`: `UNIQUE (github_repo_id)`
- `github_repositories_github_repo_id_not_null`: `NOT NULL github_repo_id`
- `github_repositories_id_not_null`: `NOT NULL id`
- `github_repositories_pkey`: `PRIMARY KEY (id)`
- `github_repositories_private_not_null`: `NOT NULL private`
- `github_repositories_project_id_id_key`: `UNIQUE (project_id, id)`
- `github_repositories_project_id_not_null`: `NOT NULL project_id`
- `github_repositories_updated_at_not_null`: `NOT NULL updated_at`
- `github_repositories_version_check`: `CHECK ((version >= 0))`
- `github_repositories_version_not_null`: `NOT NULL version`
- `github_repositories_workspace_id_fkey`: `FOREIGN KEY (workspace_id) REFERENCES workspaces(id)`
- `github_repositories_workspace_id_id_key`: `UNIQUE (workspace_id, id)`
- `github_repositories_workspace_id_not_null`: `NOT NULL workspace_id`

## milestones

| Column | PostgreSQL type | Nullable | Default |
|---|---|---|---|
| `id` PK | `uuid` | False | `gen_random_uuid()` |
| `project_id` | `uuid` | False | `` |
| `code` | `character varying(24)` | False | `` |
| `name` | `character varying(160)` | False | `` |
| `description` | `text` | True | `` |
| `due_date` | `date` | False | `` |
| `forecast_date` | `date` | True | `` |
| `status` | `character varying(24)` | False | `'PLANNED'::character varying` |
| `acceptance_status` | `character varying(32)` | False | `'NOT_SUBMITTED'::character varying` |
| `published_to_client` | `boolean` | False | `false` |
| `acceptance_history` | `jsonb` | False | `'[]'::jsonb` |
| `accepted_by` | `uuid` | True | `` |
| `accepted_at` | `timestamp with time zone` | True | `` |
| `created_at` | `timestamp with time zone` | False | `now()` |
| `updated_at` | `timestamp with time zone` | False | `now()` |
| `version` | `bigint` | False | `0` |

Constraints:

- `ck_no_secret_acceptance_history`: `CHECK (((acceptance_history IS NULL) OR (NOT ds_json_has_secret(acceptance_history))))`
- `milestones_acceptance_history_check`: `CHECK (((acceptance_history IS NULL) OR (jsonb_typeof(acceptance_history) = 'array'::text)))`
- `milestones_acceptance_history_not_null`: `NOT NULL acceptance_history`
- `milestones_acceptance_status_check`: `CHECK (((acceptance_status)::text = ANY ((ARRAY['NOT_SUBMITTED'::character varying, 'PENDING_REVIEW'::character varying, 'ACCEPTED'::character varying, 'CHANGES_REQUESTED'::character varying])::text[])))`
- `milestones_acceptance_status_not_null`: `NOT NULL acceptance_status`
- `milestones_accepted_by_fkey`: `FOREIGN KEY (accepted_by) REFERENCES users(id)`
- `milestones_check`: `CHECK ((((acceptance_status)::text = 'ACCEPTED'::text) = ((accepted_by IS NOT NULL) AND (accepted_at IS NOT NULL))))`
- `milestones_check1`: `CHECK ((((status)::text = 'ACCEPTED'::text) = ((acceptance_status)::text = 'ACCEPTED'::text)))`
- `milestones_code_not_null`: `NOT NULL code`
- `milestones_created_at_not_null`: `NOT NULL created_at`
- `milestones_due_date_not_null`: `NOT NULL due_date`
- `milestones_id_not_null`: `NOT NULL id`
- `milestones_name_not_null`: `NOT NULL name`
- `milestones_pkey`: `PRIMARY KEY (id)`
- `milestones_project_id_code_key`: `UNIQUE (project_id, code)`
- `milestones_project_id_fkey`: `FOREIGN KEY (project_id) REFERENCES projects(id)`
- `milestones_project_id_id_key`: `UNIQUE (project_id, id)`
- `milestones_project_id_not_null`: `NOT NULL project_id`
- `milestones_published_to_client_not_null`: `NOT NULL published_to_client`
- `milestones_status_check`: `CHECK (((status)::text = ANY ((ARRAY['PLANNED'::character varying, 'AT_RISK'::character varying, 'READY_FOR_ACCEPTANCE'::character varying, 'ACCEPTED'::character varying])::text[])))`
- `milestones_status_not_null`: `NOT NULL status`
- `milestones_updated_at_not_null`: `NOT NULL updated_at`
- `milestones_version_check`: `CHECK ((version >= 0))`
- `milestones_version_not_null`: `NOT NULL version`

## notifications

| Column | PostgreSQL type | Nullable | Default |
|---|---|---|---|
| `id` PK | `uuid` | False | `gen_random_uuid()` |
| `workspace_id` | `uuid` | True | `` |
| `user_id` | `uuid` | False | `` |
| `project_id` | `uuid` | True | `` |
| `event_type` | `character varying(100)` | False | `` |
| `event_key` | `character varying(180)` | False | `` |
| `title` | `character varying(180)` | False | `` |
| `body` | `text` | False | `` |
| `deeplink` | `text` | True | `` |
| `read_at` | `timestamp with time zone` | True | `` |
| `email_status` | `character varying(16)` | False | `'NOT_REQUESTED'::character varying` |
| `created_at` | `timestamp with time zone` | False | `now()` |
| `updated_at` | `timestamp with time zone` | False | `now()` |
| `version` | `bigint` | False | `0` |

Constraints:

- `fk_notifications_project_id_scope`: `FOREIGN KEY (workspace_id, project_id) REFERENCES projects(workspace_id, id)`
- `notifications_body_not_null`: `NOT NULL body`
- `notifications_created_at_not_null`: `NOT NULL created_at`
- `notifications_email_status_check`: `CHECK (((email_status)::text = ANY ((ARRAY['NOT_REQUESTED'::character varying, 'PENDING'::character varying, 'SENT'::character varying, 'FAILED'::character varying])::text[])))`
- `notifications_email_status_not_null`: `NOT NULL email_status`
- `notifications_event_key_not_null`: `NOT NULL event_key`
- `notifications_event_type_not_null`: `NOT NULL event_type`
- `notifications_id_not_null`: `NOT NULL id`
- `notifications_pkey`: `PRIMARY KEY (id)`
- `notifications_project_id_id_key`: `UNIQUE (project_id, id)`
- `notifications_title_not_null`: `NOT NULL title`
- `notifications_updated_at_not_null`: `NOT NULL updated_at`
- `notifications_user_id_event_key_key`: `UNIQUE (user_id, event_key)`
- `notifications_user_id_fkey`: `FOREIGN KEY (user_id) REFERENCES users(id)`
- `notifications_user_id_not_null`: `NOT NULL user_id`
- `notifications_version_check`: `CHECK ((version >= 0))`
- `notifications_version_not_null`: `NOT NULL version`
- `notifications_workspace_id_fkey`: `FOREIGN KEY (workspace_id) REFERENCES workspaces(id)`
- `notifications_workspace_id_id_key`: `UNIQUE (workspace_id, id)`

## oauth_accounts

| Column | PostgreSQL type | Nullable | Default |
|---|---|---|---|
| `id` PK | `uuid` | False | `gen_random_uuid()` |
| `user_id` | `uuid` | False | `` |
| `provider` | `character varying(16)` | False | `` |
| `provider_subject` | `character varying(200)` | False | `` |
| `provider_login` | `character varying(160)` | True | `` |
| `linked_at` | `timestamp with time zone` | False | `now()` |
| `created_at` | `timestamp with time zone` | False | `now()` |

Constraints:

- `oauth_accounts_created_at_not_null`: `NOT NULL created_at`
- `oauth_accounts_id_not_null`: `NOT NULL id`
- `oauth_accounts_linked_at_not_null`: `NOT NULL linked_at`
- `oauth_accounts_pkey`: `PRIMARY KEY (id)`
- `oauth_accounts_provider_check`: `CHECK (((provider)::text = ANY ((ARRAY['GOOGLE'::character varying, 'GITHUB'::character varying])::text[])))`
- `oauth_accounts_provider_not_null`: `NOT NULL provider`
- `oauth_accounts_provider_provider_subject_key`: `UNIQUE (provider, provider_subject)`
- `oauth_accounts_provider_subject_not_null`: `NOT NULL provider_subject`
- `oauth_accounts_user_id_fkey`: `FOREIGN KEY (user_id) REFERENCES users(id)`
- `oauth_accounts_user_id_not_null`: `NOT NULL user_id`
- `oauth_accounts_user_id_provider_key`: `UNIQUE (user_id, provider)`

## plan_proposals

| Column | PostgreSQL type | Nullable | Default |
|---|---|---|---|
| `id` PK | `uuid` | False | `gen_random_uuid()` |
| `project_id` | `uuid` | False | `` |
| `kind` | `character varying(24)` | False | `` |
| `base_project_version` | `bigint` | False | `` |
| `base_schedule_version_id` | `uuid` | True | `` |
| `requirement_version_id` | `uuid` | True | `` |
| `change_request_id` | `uuid` | True | `` |
| `ai_job_id` | `uuid` | True | `` |
| `status` | `character varying(24)` | False | `'DRAFT'::character varying` |
| `proposal_payload` | `jsonb` | False | `` |
| `solver_status` | `character varying(16)` | False | `'NOT_RUN'::character varying` |
| `solver_details` | `jsonb` | False | `'{"schemaVersion": 1}'::jsonb` |
| `assumptions` | `jsonb` | False | `'[]'::jsonb` |
| `created_by` | `uuid` | False | `` |
| `approved_by` | `uuid` | True | `` |
| `approved_at` | `timestamp with time zone` | True | `` |
| `changes_committed_deadline` | `boolean` | False | `false` |
| `proposed_committed_deadline` | `date` | True | `` |
| `deadline_confirmed_by` | `uuid` | True | `` |
| `deadline_confirmed_at` | `timestamp with time zone` | True | `` |
| `deadline_confirmation_version` | `bigint` | True | `` |
| `deadline_confirmed_project_version` | `bigint` | True | `` |
| `created_at` | `timestamp with time zone` | False | `now()` |
| `updated_at` | `timestamp with time zone` | False | `now()` |
| `version` | `bigint` | False | `0` |

Constraints:

- `ck_no_secret_assumptions`: `CHECK (((assumptions IS NULL) OR (NOT ds_json_has_secret(assumptions))))`
- `ck_no_secret_proposal_payload`: `CHECK (((proposal_payload IS NULL) OR (NOT ds_json_has_secret(proposal_payload))))`
- `ck_no_secret_solver_details`: `CHECK (((solver_details IS NULL) OR (NOT ds_json_has_secret(solver_details))))`
- `fk_plan_proposals_ai_job_id_scope`: `FOREIGN KEY (project_id, ai_job_id) REFERENCES ai_jobs(project_id, id)`
- `fk_plan_proposals_base_schedule_version_id_scope`: `FOREIGN KEY (project_id, base_schedule_version_id) REFERENCES schedule_versions(project_id, id)`
- `fk_plan_proposals_change_request_id_scope`: `FOREIGN KEY (project_id, change_request_id) REFERENCES requirements(project_id, id)`
- `fk_plan_proposals_requirement_version_id_scope`: `FOREIGN KEY (project_id, requirement_version_id) REFERENCES requirement_versions(project_id, id)`
- `plan_proposals_approved_by_fkey`: `FOREIGN KEY (approved_by) REFERENCES users(id)`
- `plan_proposals_assumptions_check`: `CHECK (((assumptions IS NULL) OR (jsonb_typeof(assumptions) = 'array'::text)))`
- `plan_proposals_assumptions_not_null`: `NOT NULL assumptions`
- `plan_proposals_base_project_version_check`: `CHECK ((base_project_version >= 0))`
- `plan_proposals_base_project_version_not_null`: `NOT NULL base_project_version`
- `plan_proposals_changes_committed_deadline_not_null`: `NOT NULL changes_committed_deadline`
- `plan_proposals_check`: `CHECK ((((status)::text <> ALL ((ARRAY['APPROVED'::character varying, 'APPLIED'::character varying])::text[])) OR ((approved_by IS NOT NULL) AND (approved_at IS NOT NULL))))`
- `plan_proposals_check1`: `CHECK ((((status)::text <> 'APPLIED'::text) OR ((solver_status)::text = ANY ((ARRAY['OPTIMAL'::character varying, 'FEASIBLE'::character varying])::text[]))))`
- `plan_proposals_check2`: `CHECK (((deadline_confirmed_by IS NULL) = (deadline_confirmed_at IS NULL)))`
- `plan_proposals_created_at_not_null`: `NOT NULL created_at`
- `plan_proposals_created_by_fkey`: `FOREIGN KEY (created_by) REFERENCES users(id)`
- `plan_proposals_created_by_not_null`: `NOT NULL created_by`
- `plan_proposals_deadline_confirmed_by_fkey`: `FOREIGN KEY (deadline_confirmed_by) REFERENCES users(id)`
- `plan_proposals_id_not_null`: `NOT NULL id`
- `plan_proposals_kind_check`: `CHECK (((kind)::text = ANY ((ARRAY['AI_REQUIREMENT'::character varying, 'MANUAL_REPLAN'::character varying, 'AUTO_REPLAN'::character varying])::text[])))`
- `plan_proposals_kind_not_null`: `NOT NULL kind`
- `plan_proposals_pkey`: `PRIMARY KEY (id)`
- `plan_proposals_project_id_fkey`: `FOREIGN KEY (project_id) REFERENCES projects(id)`
- `plan_proposals_project_id_id_key`: `UNIQUE (project_id, id)`
- `plan_proposals_project_id_not_null`: `NOT NULL project_id`
- `plan_proposals_proposal_payload_check`: `CHECK (((proposal_payload ? 'schemaVersion'::text) AND ((proposal_payload ->> 'schemaVersion'::text) = '1'::text)))`
- `plan_proposals_proposal_payload_check1`: `CHECK (((proposal_payload IS NULL) OR (jsonb_typeof(proposal_payload) = 'object'::text)))`
- `plan_proposals_proposal_payload_not_null`: `NOT NULL proposal_payload`
- `plan_proposals_solver_details_check`: `CHECK (((solver_details IS NULL) OR (jsonb_typeof(solver_details) = 'object'::text)))`
- `plan_proposals_solver_details_not_null`: `NOT NULL solver_details`
- `plan_proposals_solver_status_check`: `CHECK (((solver_status)::text = ANY ((ARRAY['NOT_RUN'::character varying, 'OPTIMAL'::character varying, 'FEASIBLE'::character varying, 'INFEASIBLE'::character varying, 'UNKNOWN'::character varying, 'ERROR'::character varying])::text[])))`
- `plan_proposals_solver_status_not_null`: `NOT NULL solver_status`
- `plan_proposals_status_check`: `CHECK (((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'PENDING_APPROVAL'::character varying, 'APPROVED'::character varying, 'REJECTED'::character varying, 'APPLIED'::character varying, 'STALE'::character varying])::text[])))`
- `plan_proposals_status_not_null`: `NOT NULL status`
- `plan_proposals_updated_at_not_null`: `NOT NULL updated_at`
- `plan_proposals_version_check`: `CHECK ((version >= 0))`
- `plan_proposals_version_not_null`: `NOT NULL version`

## project_member_roles

| Column | PostgreSQL type | Nullable | Default |
|---|---|---|---|
| `id` PK | `uuid` | False | `gen_random_uuid()` |
| `project_id` | `uuid` | False | `` |
| `project_member_id` | `uuid` | False | `` |
| `role` | `character varying(12)` | False | `` |
| `granted_by` | `uuid` | False | `` |
| `granted_at` | `timestamp with time zone` | False | `now()` |
| `accepted_by` | `uuid` | True | `` |
| `accepted_at` | `timestamp with time zone` | True | `` |
| `revoked_at` | `timestamp with time zone` | True | `` |
| `created_at` | `timestamp with time zone` | False | `now()` |

Constraints:

- `fk_project_member_roles_project_member_id_scope`: `FOREIGN KEY (project_id, project_member_id) REFERENCES project_members(project_id, id)`
- `project_invariants_on_roles`: `TRIGGER DEFERRABLE INITIALLY DEFERRED`
- `project_member_roles_accepted_by_fkey`: `FOREIGN KEY (accepted_by) REFERENCES users(id)`
- `project_member_roles_check`: `CHECK (((revoked_at IS NULL) OR (revoked_at >= granted_at)))`
- `project_member_roles_check1`: `CHECK ((((role)::text <> 'PM'::text) OR ((accepted_by IS NOT NULL) AND (accepted_at IS NOT NULL) AND (accepted_at >= granted_at))))`
- `project_member_roles_created_at_not_null`: `NOT NULL created_at`
- `project_member_roles_granted_at_not_null`: `NOT NULL granted_at`
- `project_member_roles_granted_by_fkey`: `FOREIGN KEY (granted_by) REFERENCES users(id)`
- `project_member_roles_granted_by_not_null`: `NOT NULL granted_by`
- `project_member_roles_id_not_null`: `NOT NULL id`
- `project_member_roles_pkey`: `PRIMARY KEY (id)`
- `project_member_roles_project_id_fkey`: `FOREIGN KEY (project_id) REFERENCES projects(id)`
- `project_member_roles_project_id_id_key`: `UNIQUE (project_id, id)`
- `project_member_roles_project_id_not_null`: `NOT NULL project_id`
- `project_member_roles_project_member_id_not_null`: `NOT NULL project_member_id`
- `project_member_roles_role_check`: `CHECK (((role)::text = ANY ((ARRAY['PM'::character varying, 'DEVELOPER'::character varying, 'CLIENT'::character varying, 'VIEWER'::character varying])::text[])))`
- `project_member_roles_role_not_null`: `NOT NULL role`

## project_members

| Column | PostgreSQL type | Nullable | Default |
|---|---|---|---|
| `id` PK | `uuid` | False | `gen_random_uuid()` |
| `project_id` | `uuid` | False | `` |
| `workspace_id` | `uuid` | False | `` |
| `user_id` | `uuid` | False | `` |
| `status` | `character varying(12)` | False | `'ACTIVE'::character varying` |
| `joined_at` | `timestamp with time zone` | False | `now()` |
| `created_at` | `timestamp with time zone` | False | `now()` |
| `updated_at` | `timestamp with time zone` | False | `now()` |
| `version` | `bigint` | False | `0` |

Constraints:

- `fk_project_members_project_id_scope`: `FOREIGN KEY (workspace_id, project_id) REFERENCES projects(workspace_id, id)`
- `fk_project_members_user_id_scope`: `FOREIGN KEY (workspace_id, user_id) REFERENCES workspace_members(workspace_id, user_id)`
- `project_invariants_on_members`: `TRIGGER DEFERRABLE INITIALLY DEFERRED`
- `project_members_created_at_not_null`: `NOT NULL created_at`
- `project_members_id_not_null`: `NOT NULL id`
- `project_members_joined_at_not_null`: `NOT NULL joined_at`
- `project_members_pkey`: `PRIMARY KEY (id)`
- `project_members_project_id_id_key`: `UNIQUE (project_id, id)`
- `project_members_project_id_not_null`: `NOT NULL project_id`
- `project_members_project_id_user_id_key`: `UNIQUE (project_id, user_id)`
- `project_members_status_check`: `CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'REMOVED'::character varying])::text[])))`
- `project_members_status_not_null`: `NOT NULL status`
- `project_members_updated_at_not_null`: `NOT NULL updated_at`
- `project_members_user_id_not_null`: `NOT NULL user_id`
- `project_members_version_check`: `CHECK ((version >= 0))`
- `project_members_version_not_null`: `NOT NULL version`
- `project_members_workspace_id_fkey`: `FOREIGN KEY (workspace_id) REFERENCES workspaces(id)`
- `project_members_workspace_id_id_key`: `UNIQUE (workspace_id, id)`
- `project_members_workspace_id_not_null`: `NOT NULL workspace_id`

## projects

| Column | PostgreSQL type | Nullable | Default |
|---|---|---|---|
| `id` PK | `uuid` | False | `gen_random_uuid()` |
| `workspace_id` | `uuid` | False | `` |
| `project_key` | `character varying(16)` | False | `` |
| `name` | `character varying(160)` | False | `` |
| `description` | `text` | True | `` |
| `objective` | `text` | True | `` |
| `status` | `character varying(12)` | False | `'PLANNING'::character varying` |
| `timezone` | `character varying(64)` | False | `'Asia/Ho_Chi_Minh'::character varying` |
| `planned_start` | `date` | True | `` |
| `committed_deadline` | `date` | True | `` |
| `sprint_length_days` | `integer` | False | `14` |
| `settings` | `jsonb` | False | `'{"schemaVersion": 1}'::jsonb` |
| `active_schedule_version_id` | `uuid` | True | `` |
| `deadline_approver_id` | `uuid` | True | `` |
| `next_item_number` | `bigint` | False | `1` |
| `created_at` | `timestamp with time zone` | False | `now()` |
| `updated_at` | `timestamp with time zone` | False | `now()` |
| `version` | `bigint` | False | `0` |

Constraints:

- `ck_no_secret_settings`: `CHECK (((settings IS NULL) OR (NOT ds_json_has_secret(settings))))`
- `fk_projects_active_schedule_scope`: `FOREIGN KEY (id, active_schedule_version_id) REFERENCES schedule_versions(project_id, id)`
- `fk_projects_active_schedule_version_id`: `FOREIGN KEY (active_schedule_version_id) REFERENCES schedule_versions(id)`
- `project_invariants_on_project`: `TRIGGER DEFERRABLE INITIALLY DEFERRED`
- `projects_check`: `CHECK (((committed_deadline IS NULL) OR (planned_start IS NULL) OR (committed_deadline >= planned_start)))`
- `projects_created_at_not_null`: `NOT NULL created_at`
- `projects_deadline_approver_id_fkey`: `FOREIGN KEY (deadline_approver_id) REFERENCES users(id)`
- `projects_id_not_null`: `NOT NULL id`
- `projects_name_check`: `CHECK ((length(btrim((name)::text)) > 0))`
- `projects_name_not_null`: `NOT NULL name`
- `projects_next_item_number_check`: `CHECK ((next_item_number > 0))`
- `projects_next_item_number_not_null`: `NOT NULL next_item_number`
- `projects_pkey`: `PRIMARY KEY (id)`
- `projects_project_key_check`: `CHECK (((project_key)::text ~ '^[A-Z][A-Z0-9]{1,15}$'::text))`
- `projects_project_key_not_null`: `NOT NULL project_key`
- `projects_settings_check`: `CHECK (((settings IS NULL) OR (jsonb_typeof(settings) = 'object'::text)))`
- `projects_settings_not_null`: `NOT NULL settings`
- `projects_sprint_length_days_check`: `CHECK (((sprint_length_days >= 1) AND (sprint_length_days <= 90)))`
- `projects_sprint_length_days_not_null`: `NOT NULL sprint_length_days`
- `projects_status_check`: `CHECK (((status)::text = ANY ((ARRAY['PLANNING'::character varying, 'ACTIVE'::character varying, 'ON_HOLD'::character varying, 'COMPLETED'::character varying, 'ARCHIVED'::character varying])::text[])))`
- `projects_status_not_null`: `NOT NULL status`
- `projects_timezone_not_null`: `NOT NULL timezone`
- `projects_updated_at_not_null`: `NOT NULL updated_at`
- `projects_version_check`: `CHECK ((version >= 0))`
- `projects_version_not_null`: `NOT NULL version`
- `projects_workspace_id_fkey`: `FOREIGN KEY (workspace_id) REFERENCES workspaces(id)`
- `projects_workspace_id_id_key`: `UNIQUE (workspace_id, id)`
- `projects_workspace_id_key`: `UNIQUE (workspace_id)`
- `projects_workspace_id_not_null`: `NOT NULL workspace_id`

## reports

| Column | PostgreSQL type | Nullable | Default |
|---|---|---|---|
| `id` PK | `uuid` | False | `gen_random_uuid()` |
| `project_id` | `uuid` | False | `` |
| `type` | `character varying(16)` | False | `` |
| `period_start` | `date` | False | `` |
| `period_end` | `date` | False | `` |
| `status` | `character varying(12)` | False | `'DRAFT'::character varying` |
| `content` | `jsonb` | False | `` |
| `visible_to_client` | `boolean` | False | `false` |
| `milestone_id` | `uuid` | True | `` |
| `generated_by` | `uuid` | True | `` |
| `ai_job_id` | `uuid` | True | `` |
| `approved_by` | `uuid` | True | `` |
| `published_at` | `timestamp with time zone` | True | `` |
| `created_at` | `timestamp with time zone` | False | `now()` |
| `updated_at` | `timestamp with time zone` | False | `now()` |
| `version` | `bigint` | False | `0` |

Constraints:

- `ck_no_secret_content`: `CHECK (((content IS NULL) OR (NOT ds_json_has_secret(content))))`
- `fk_reports_ai_job_id_scope`: `FOREIGN KEY (project_id, ai_job_id) REFERENCES ai_jobs(project_id, id)`
- `fk_reports_milestone_id_scope`: `FOREIGN KEY (project_id, milestone_id) REFERENCES milestones(project_id, id)`
- `reports_approved_by_fkey`: `FOREIGN KEY (approved_by) REFERENCES users(id)`
- `reports_check`: `CHECK ((period_end >= period_start))`
- `reports_check1`: `CHECK ((((status)::text <> ALL ((ARRAY['APPROVED'::character varying, 'PUBLISHED'::character varying])::text[])) OR (approved_by IS NOT NULL)))`
- `reports_check2`: `CHECK ((((status)::text = 'PUBLISHED'::text) = (published_at IS NOT NULL)))`
- `reports_content_check`: `CHECK (((content IS NULL) OR (jsonb_typeof(content) = 'object'::text)))`
- `reports_content_not_null`: `NOT NULL content`
- `reports_created_at_not_null`: `NOT NULL created_at`
- `reports_generated_by_fkey`: `FOREIGN KEY (generated_by) REFERENCES users(id)`
- `reports_id_not_null`: `NOT NULL id`
- `reports_period_end_not_null`: `NOT NULL period_end`
- `reports_period_start_not_null`: `NOT NULL period_start`
- `reports_pkey`: `PRIMARY KEY (id)`
- `reports_project_id_fkey`: `FOREIGN KEY (project_id) REFERENCES projects(id)`
- `reports_project_id_id_key`: `UNIQUE (project_id, id)`
- `reports_project_id_not_null`: `NOT NULL project_id`
- `reports_status_check`: `CHECK (((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'APPROVED'::character varying, 'PUBLISHED'::character varying])::text[])))`
- `reports_status_not_null`: `NOT NULL status`
- `reports_type_check`: `CHECK (((type)::text = ANY ((ARRAY['DAILY'::character varying, 'WEEKLY'::character varying, 'SPRINT'::character varying, 'RISK'::character varying, 'CODE_QUALITY'::character varying, 'CLIENT_ROADMAP'::character varying])::text[])))`
- `reports_type_not_null`: `NOT NULL type`
- `reports_updated_at_not_null`: `NOT NULL updated_at`
- `reports_version_check`: `CHECK ((version >= 0))`
- `reports_version_not_null`: `NOT NULL version`
- `reports_visible_to_client_not_null`: `NOT NULL visible_to_client`

## requirement_versions

| Column | PostgreSQL type | Nullable | Default |
|---|---|---|---|
| `id` PK | `uuid` | False | `gen_random_uuid()` |
| `project_id` | `uuid` | False | `` |
| `requirement_id` | `uuid` | False | `` |
| `version_no` | `integer` | False | `` |
| `description` | `text` | False | `` |
| `acceptance_summary` | `text` | False | `''::text` |
| `change_reason` | `text` | True | `` |
| `approved_at` | `timestamp with time zone` | True | `` |
| `created_at` | `timestamp with time zone` | False | `now()` |

Constraints:

- `fk_requirement_versions_requirement_id_scope`: `FOREIGN KEY (project_id, requirement_id) REFERENCES requirements(project_id, id)`
- `requirement_versions_acceptance_summary_not_null`: `NOT NULL acceptance_summary`
- `requirement_versions_created_at_not_null`: `NOT NULL created_at`
- `requirement_versions_description_not_null`: `NOT NULL description`
- `requirement_versions_id_not_null`: `NOT NULL id`
- `requirement_versions_pkey`: `PRIMARY KEY (id)`
- `requirement_versions_project_id_fkey`: `FOREIGN KEY (project_id) REFERENCES projects(id)`
- `requirement_versions_project_id_id_key`: `UNIQUE (project_id, id)`
- `requirement_versions_project_id_not_null`: `NOT NULL project_id`
- `requirement_versions_requirement_id_not_null`: `NOT NULL requirement_id`
- `requirement_versions_requirement_id_version_no_key`: `UNIQUE (requirement_id, version_no)`
- `requirement_versions_version_no_check`: `CHECK ((version_no > 0))`
- `requirement_versions_version_no_not_null`: `NOT NULL version_no`

## requirements

| Column | PostgreSQL type | Nullable | Default |
|---|---|---|---|
| `id` PK | `uuid` | False | `gen_random_uuid()` |
| `project_id` | `uuid` | False | `` |
| `code` | `character varying(32)` | False | `` |
| `kind` | `character varying(16)` | False | `'REQUIREMENT'::character varying` |
| `parent_requirement_id` | `uuid` | True | `` |
| `title` | `character varying(220)` | False | `` |
| `description` | `text` | False | `''::text` |
| `status` | `character varying(16)` | False | `'DRAFT'::character varying` |
| `change_request_status` | `character varying(24)` | True | `` |
| `current_version` | `integer` | False | `1` |
| `requested_by` | `uuid` | False | `` |
| `approved_by` | `uuid` | True | `` |
| `impact_json` | `jsonb` | True | `` |
| `created_at` | `timestamp with time zone` | False | `now()` |
| `updated_at` | `timestamp with time zone` | False | `now()` |
| `version` | `bigint` | False | `0` |

Constraints:

- `ck_no_secret_impact_json`: `CHECK (((impact_json IS NULL) OR (NOT ds_json_has_secret(impact_json))))`
- `fk_requirement_current_version`: `FOREIGN KEY (id, current_version) REFERENCES requirement_versions(requirement_id, version_no) DEFERRABLE INITIALLY DEFERRED`
- `fk_requirements_parent_requirement_id_scope`: `FOREIGN KEY (project_id, parent_requirement_id) REFERENCES requirements(project_id, id)`
- `requirements_approved_by_fkey`: `FOREIGN KEY (approved_by) REFERENCES users(id)`
- `requirements_change_request_status_check`: `CHECK (((change_request_status)::text = ANY ((ARRAY['PENDING'::character varying, 'APPROVED'::character varying, 'REJECTED'::character varying, 'DEFERRED'::character varying, 'IMPLEMENTED'::character varying])::text[])))`
- `requirements_check`: `CHECK ((parent_requirement_id IS DISTINCT FROM id))`
- `requirements_check1`: `CHECK ((((kind)::text = 'CHANGE_REQUEST'::text) = (change_request_status IS NOT NULL)))`
- `requirements_code_not_null`: `NOT NULL code`
- `requirements_created_at_not_null`: `NOT NULL created_at`
- `requirements_current_version_check`: `CHECK ((current_version > 0))`
- `requirements_current_version_not_null`: `NOT NULL current_version`
- `requirements_description_not_null`: `NOT NULL description`
- `requirements_id_not_null`: `NOT NULL id`
- `requirements_impact_json_check`: `CHECK (((impact_json IS NULL) OR (jsonb_typeof(impact_json) = 'object'::text)))`
- `requirements_kind_check`: `CHECK (((kind)::text = ANY ((ARRAY['REQUIREMENT'::character varying, 'CHANGE_REQUEST'::character varying])::text[])))`
- `requirements_kind_not_null`: `NOT NULL kind`
- `requirements_pkey`: `PRIMARY KEY (id)`
- `requirements_project_id_code_key`: `UNIQUE (project_id, code)`
- `requirements_project_id_fkey`: `FOREIGN KEY (project_id) REFERENCES projects(id)`
- `requirements_project_id_id_key`: `UNIQUE (project_id, id)`
- `requirements_project_id_not_null`: `NOT NULL project_id`
- `requirements_requested_by_fkey`: `FOREIGN KEY (requested_by) REFERENCES users(id)`
- `requirements_requested_by_not_null`: `NOT NULL requested_by`
- `requirements_status_check`: `CHECK (((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'APPROVED'::character varying, 'ASSESSING'::character varying, 'REJECTED'::character varying, 'CHANGED'::character varying, 'ARCHIVED'::character varying])::text[])))`
- `requirements_status_not_null`: `NOT NULL status`
- `requirements_title_check`: `CHECK ((length(btrim((title)::text)) > 0))`
- `requirements_title_not_null`: `NOT NULL title`
- `requirements_updated_at_not_null`: `NOT NULL updated_at`
- `requirements_version_check`: `CHECK ((version >= 0))`
- `requirements_version_not_null`: `NOT NULL version`

## schedule_entries

| Column | PostgreSQL type | Nullable | Default |
|---|---|---|---|
| `id` PK | `uuid` | False | `gen_random_uuid()` |
| `project_id` | `uuid` | False | `` |
| `schedule_version_id` | `uuid` | False | `` |
| `work_item_id` | `uuid` | False | `` |
| `sprint_id` | `uuid` | True | `` |
| `assignee_id` | `uuid` | True | `` |
| `start_at` | `timestamp with time zone` | False | `` |
| `finish_at` | `timestamp with time zone` | False | `` |
| `remaining_minutes_snapshot` | `integer` | False | `` |
| `frozen` | `boolean` | False | `false` |
| `created_at` | `timestamp with time zone` | False | `now()` |

Constraints:

- `ex_schedule_assignee_overlap`: `EXCLUDE USING gist (schedule_version_id WITH =, assignee_id WITH =, tstzrange(start_at, finish_at, '[)'::text) WITH &&) WHERE ((assignee_id IS NOT NULL))`
- `fk_schedule_entries_schedule_version_id_scope`: `FOREIGN KEY (project_id, schedule_version_id) REFERENCES schedule_versions(project_id, id)`
- `fk_schedule_entries_sprint_id_scope`: `FOREIGN KEY (project_id, sprint_id) REFERENCES sprints(project_id, id)`
- `fk_schedule_entries_work_item_id_scope`: `FOREIGN KEY (project_id, work_item_id) REFERENCES work_items(project_id, id)`
- `schedule_entries_assignee_id_fkey`: `FOREIGN KEY (assignee_id) REFERENCES users(id)`
- `schedule_entries_check`: `CHECK ((finish_at >= start_at))`
- `schedule_entries_created_at_not_null`: `NOT NULL created_at`
- `schedule_entries_finish_at_not_null`: `NOT NULL finish_at`
- `schedule_entries_frozen_not_null`: `NOT NULL frozen`
- `schedule_entries_id_not_null`: `NOT NULL id`
- `schedule_entries_pkey`: `PRIMARY KEY (id)`
- `schedule_entries_project_id_fkey`: `FOREIGN KEY (project_id) REFERENCES projects(id)`
- `schedule_entries_project_id_id_key`: `UNIQUE (project_id, id)`
- `schedule_entries_project_id_not_null`: `NOT NULL project_id`
- `schedule_entries_remaining_minutes_snapshot_check`: `CHECK ((remaining_minutes_snapshot >= 0))`
- `schedule_entries_remaining_minutes_snapshot_not_null`: `NOT NULL remaining_minutes_snapshot`
- `schedule_entries_schedule_version_id_not_null`: `NOT NULL schedule_version_id`
- `schedule_entries_schedule_version_id_work_item_id_key`: `UNIQUE (schedule_version_id, work_item_id)`
- `schedule_entries_start_at_not_null`: `NOT NULL start_at`
- `schedule_entries_work_item_id_not_null`: `NOT NULL work_item_id`

## schedule_versions

| Column | PostgreSQL type | Nullable | Default |
|---|---|---|---|
| `id` PK | `uuid` | False | `gen_random_uuid()` |
| `project_id` | `uuid` | False | `` |
| `version_no` | `integer` | False | `` |
| `source` | `character varying(16)` | False | `` |
| `proposal_id` | `uuid` | True | `` |
| `approved_by` | `uuid` | False | `` |
| `approved_at` | `timestamp with time zone` | False | `` |
| `planned_finish` | `date` | True | `` |
| `forecast_finish` | `date` | True | `` |
| `milestone_delays` | `jsonb` | False | `'{"schemaVersion": 1}'::jsonb` |
| `baseline_snapshot` | `jsonb` | False | `'{"schemaVersion": 1}'::jsonb` |
| `created_at` | `timestamp with time zone` | False | `now()` |

Constraints:

- `ck_no_secret_baseline_snapshot`: `CHECK (((baseline_snapshot IS NULL) OR (NOT ds_json_has_secret(baseline_snapshot))))`
- `ck_no_secret_milestone_delays`: `CHECK (((milestone_delays IS NULL) OR (NOT ds_json_has_secret(milestone_delays))))`
- `fk_schedule_versions_proposal_id_scope`: `FOREIGN KEY (project_id, proposal_id) REFERENCES plan_proposals(project_id, id)`
- `schedule_versions_approved_at_not_null`: `NOT NULL approved_at`
- `schedule_versions_approved_by_fkey`: `FOREIGN KEY (approved_by) REFERENCES users(id)`
- `schedule_versions_approved_by_not_null`: `NOT NULL approved_by`
- `schedule_versions_baseline_snapshot_check`: `CHECK (((baseline_snapshot IS NULL) OR (jsonb_typeof(baseline_snapshot) = 'object'::text)))`
- `schedule_versions_baseline_snapshot_not_null`: `NOT NULL baseline_snapshot`
- `schedule_versions_created_at_not_null`: `NOT NULL created_at`
- `schedule_versions_id_not_null`: `NOT NULL id`
- `schedule_versions_milestone_delays_check`: `CHECK (((milestone_delays IS NULL) OR (jsonb_typeof(milestone_delays) = 'object'::text)))`
- `schedule_versions_milestone_delays_not_null`: `NOT NULL milestone_delays`
- `schedule_versions_pkey`: `PRIMARY KEY (id)`
- `schedule_versions_project_id_fkey`: `FOREIGN KEY (project_id) REFERENCES projects(id)`
- `schedule_versions_project_id_id_key`: `UNIQUE (project_id, id)`
- `schedule_versions_project_id_not_null`: `NOT NULL project_id`
- `schedule_versions_project_id_version_no_key`: `UNIQUE (project_id, version_no)`
- `schedule_versions_source_check`: `CHECK (((source)::text = ANY ((ARRAY['MANUAL'::character varying, 'AI_PLAN'::character varying, 'RESCHEDULE'::character varying])::text[])))`
- `schedule_versions_source_not_null`: `NOT NULL source`
- `schedule_versions_version_no_check`: `CHECK ((version_no > 0))`
- `schedule_versions_version_no_not_null`: `NOT NULL version_no`

## sprints

| Column | PostgreSQL type | Nullable | Default |
|---|---|---|---|
| `id` PK | `uuid` | False | `gen_random_uuid()` |
| `project_id` | `uuid` | False | `` |
| `name` | `character varying(120)` | False | `` |
| `goal` | `text` | False | `''::text` |
| `start_date` | `date` | False | `` |
| `end_date` | `date` | False | `` |
| `status` | `character varying(12)` | False | `'PLANNED'::character varying` |
| `review_notes` | `text` | True | `` |
| `retrospective_notes` | `text` | True | `` |
| `committed_points` | `numeric(8,2)` | True | `` |
| `completed_points` | `numeric(8,2)` | True | `` |
| `closed_at` | `timestamp with time zone` | True | `` |
| `created_at` | `timestamp with time zone` | False | `now()` |
| `updated_at` | `timestamp with time zone` | False | `now()` |
| `version` | `bigint` | False | `0` |

Constraints:

- `sprints_check`: `CHECK ((end_date > start_date))`
- `sprints_check1`: `CHECK ((((status)::text = 'CLOSED'::text) = (closed_at IS NOT NULL)))`
- `sprints_committed_points_check`: `CHECK ((committed_points >= (0)::numeric))`
- `sprints_completed_points_check`: `CHECK ((completed_points >= (0)::numeric))`
- `sprints_created_at_not_null`: `NOT NULL created_at`
- `sprints_end_date_not_null`: `NOT NULL end_date`
- `sprints_goal_not_null`: `NOT NULL goal`
- `sprints_id_not_null`: `NOT NULL id`
- `sprints_name_not_null`: `NOT NULL name`
- `sprints_pkey`: `PRIMARY KEY (id)`
- `sprints_project_id_fkey`: `FOREIGN KEY (project_id) REFERENCES projects(id)`
- `sprints_project_id_id_key`: `UNIQUE (project_id, id)`
- `sprints_project_id_not_null`: `NOT NULL project_id`
- `sprints_start_date_not_null`: `NOT NULL start_date`
- `sprints_status_check`: `CHECK (((status)::text = ANY ((ARRAY['PLANNED'::character varying, 'ACTIVE'::character varying, 'CLOSED'::character varying])::text[])))`
- `sprints_status_not_null`: `NOT NULL status`
- `sprints_updated_at_not_null`: `NOT NULL updated_at`
- `sprints_version_check`: `CHECK ((version >= 0))`
- `sprints_version_not_null`: `NOT NULL version`

## users

| Column | PostgreSQL type | Nullable | Default |
|---|---|---|---|
| `id` PK | `uuid` | False | `gen_random_uuid()` |
| `email` | `citext` | False | `` |
| `password_hash` | `character varying(255)` | True | `` |
| `display_name` | `character varying(120)` | False | `` |
| `avatar_key` | `text` | True | `` |
| `system_role` | `character varying(8)` | False | `'USER'::character varying` |
| `status` | `character varying(12)` | False | `'PENDING'::character varying` |
| `email_verified_at` | `timestamp with time zone` | True | `` |
| `created_at` | `timestamp with time zone` | False | `now()` |
| `updated_at` | `timestamp with time zone` | False | `now()` |
| `version` | `bigint` | False | `0` |

Constraints:

- `users_created_at_not_null`: `NOT NULL created_at`
- `users_display_name_check`: `CHECK ((length(btrim((display_name)::text)) > 0))`
- `users_display_name_not_null`: `NOT NULL display_name`
- `users_email_check`: `CHECK (((length((email)::text) <= 320) AND (POSITION(('@'::text) IN ((email)::text)) > 1)))`
- `users_email_key`: `UNIQUE (email)`
- `users_email_not_null`: `NOT NULL email`
- `users_id_not_null`: `NOT NULL id`
- `users_pkey`: `PRIMARY KEY (id)`
- `users_status_check`: `CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'ACTIVE'::character varying, 'LOCKED'::character varying])::text[])))`
- `users_status_not_null`: `NOT NULL status`
- `users_system_role_check`: `CHECK (((system_role)::text = ANY ((ARRAY['ADMIN'::character varying, 'USER'::character varying])::text[])))`
- `users_system_role_not_null`: `NOT NULL system_role`
- `users_updated_at_not_null`: `NOT NULL updated_at`
- `users_version_check`: `CHECK ((version >= 0))`
- `users_version_not_null`: `NOT NULL version`

## work_item_dependencies

| Column | PostgreSQL type | Nullable | Default |
|---|---|---|---|
| `id` PK | `uuid` | False | `gen_random_uuid()` |
| `project_id` | `uuid` | False | `` |
| `predecessor_id` | `uuid` | False | `` |
| `successor_id` | `uuid` | False | `` |
| `link_type` | `character varying(24)` | False | `'FINISH_TO_START'::character varying` |
| `lag_minutes` | `integer` | False | `0` |
| `created_at` | `timestamp with time zone` | False | `now()` |

Constraints:

- `fk_work_item_dependencies_predecessor_id_scope`: `FOREIGN KEY (project_id, predecessor_id) REFERENCES work_items(project_id, id)`
- `fk_work_item_dependencies_successor_id_scope`: `FOREIGN KEY (project_id, successor_id) REFERENCES work_items(project_id, id)`
- `work_item_dependencies_check`: `CHECK ((predecessor_id <> successor_id))`
- `work_item_dependencies_created_at_not_null`: `NOT NULL created_at`
- `work_item_dependencies_id_not_null`: `NOT NULL id`
- `work_item_dependencies_lag_minutes_check`: `CHECK ((lag_minutes >= 0))`
- `work_item_dependencies_lag_minutes_not_null`: `NOT NULL lag_minutes`
- `work_item_dependencies_link_type_check`: `CHECK (((link_type)::text = 'FINISH_TO_START'::text))`
- `work_item_dependencies_link_type_not_null`: `NOT NULL link_type`
- `work_item_dependencies_pkey`: `PRIMARY KEY (id)`
- `work_item_dependencies_predecessor_id_not_null`: `NOT NULL predecessor_id`
- `work_item_dependencies_predecessor_id_successor_id_key`: `UNIQUE (predecessor_id, successor_id)`
- `work_item_dependencies_project_id_fkey`: `FOREIGN KEY (project_id) REFERENCES projects(id)`
- `work_item_dependencies_project_id_id_key`: `UNIQUE (project_id, id)`
- `work_item_dependencies_project_id_not_null`: `NOT NULL project_id`
- `work_item_dependencies_successor_id_not_null`: `NOT NULL successor_id`

## work_items

| Column | PostgreSQL type | Nullable | Default |
|---|---|---|---|
| `id` PK | `uuid` | False | `gen_random_uuid()` |
| `project_id` | `uuid` | False | `` |
| `item_key` | `character varying(32)` | False | `` |
| `parent_id` | `uuid` | True | `` |
| `requirement_id` | `uuid` | True | `` |
| `sprint_id` | `uuid` | True | `` |
| `milestone_id` | `uuid` | True | `` |
| `type` | `character varying(16)` | False | `` |
| `work_kind` | `character varying(12)` | False | `'CODE'::character varying` |
| `title` | `character varying(255)` | False | `` |
| `description` | `text` | False | `''::text` |
| `status` | `character varying(16)` | False | `'TO_DO'::character varying` |
| `is_blocked` | `boolean` | False | `false` |
| `blocked_reason` | `text` | True | `` |
| `priority` | `character varying(12)` | False | `'MEDIUM'::character varying` |
| `assignee_id` | `uuid` | True | `` |
| `required_skills` | `jsonb` | False | `'[]'::jsonb` |
| `labels` | `text[]` | False | `'{}'::text[]` |
| `backlog_rank` | `numeric(24,8)` | False | `0` |
| `story_points` | `numeric(5,2)` | True | `` |
| `estimate_minutes` | `integer` | True | `` |
| `remaining_minutes` | `integer` | True | `` |
| `time_spent_minutes` | `integer` | False | `0` |
| `claimed_progress` | `numeric(5,2)` | False | `0` |
| `verified_progress` | `numeric(5,2)` | True | `` |
| `progress_evidence` | `jsonb` | False | `'{"schemaVersion": 1}'::jsonb` |
| `started_at` | `timestamp with time zone` | True | `` |
| `completed_at` | `timestamp with time zone` | True | `` |
| `due_at` | `timestamp with time zone` | True | `` |
| `accepted_by` | `uuid` | True | `` |
| `deleted_at` | `timestamp with time zone` | True | `` |
| `created_at` | `timestamp with time zone` | False | `now()` |
| `updated_at` | `timestamp with time zone` | False | `now()` |
| `version` | `bigint` | False | `0` |

Constraints:

- `ck_no_secret_progress_evidence`: `CHECK (((progress_evidence IS NULL) OR (NOT ds_json_has_secret(progress_evidence))))`
- `ck_no_secret_required_skills`: `CHECK (((required_skills IS NULL) OR (NOT ds_json_has_secret(required_skills))))`
- `ck_task_skill_contract`: `CHECK (ds_valid_skills(required_skills))`
- `fk_work_items_milestone_id_scope`: `FOREIGN KEY (project_id, milestone_id) REFERENCES milestones(project_id, id)`
- `fk_work_items_parent_id_scope`: `FOREIGN KEY (project_id, parent_id) REFERENCES work_items(project_id, id)`
- `fk_work_items_requirement_id_scope`: `FOREIGN KEY (project_id, requirement_id) REFERENCES requirements(project_id, id)`
- `fk_work_items_sprint_id_scope`: `FOREIGN KEY (project_id, sprint_id) REFERENCES sprints(project_id, id)`
- `work_items_accepted_by_fkey`: `FOREIGN KEY (accepted_by) REFERENCES users(id)`
- `work_items_assignee_id_fkey`: `FOREIGN KEY (assignee_id) REFERENCES users(id)`
- `work_items_backlog_rank_not_null`: `NOT NULL backlog_rank`
- `work_items_check`: `CHECK ((parent_id IS DISTINCT FROM id))`
- `work_items_check1`: `CHECK (((NOT is_blocked) OR (((status)::text <> 'DONE'::text) AND (blocked_reason IS NOT NULL) AND (length(btrim(blocked_reason)) > 0))))`
- `work_items_check2`: `CHECK ((is_blocked OR (blocked_reason IS NULL)))`
- `work_items_check3`: `CHECK ((((status)::text = 'DONE'::text) = ((completed_at IS NOT NULL) AND (accepted_by IS NOT NULL))))`
- `work_items_check4`: `CHECK (((completed_at IS NULL) OR (started_at IS NULL) OR (completed_at >= started_at)))`
- `work_items_claimed_progress_check`: `CHECK (((claimed_progress >= (0)::numeric) AND (claimed_progress <= (100)::numeric)))`
- `work_items_claimed_progress_not_null`: `NOT NULL claimed_progress`
- `work_items_created_at_not_null`: `NOT NULL created_at`
- `work_items_description_not_null`: `NOT NULL description`
- `work_items_estimate_minutes_check`: `CHECK ((estimate_minutes >= 0))`
- `work_items_id_not_null`: `NOT NULL id`
- `work_items_is_blocked_not_null`: `NOT NULL is_blocked`
- `work_items_item_key_not_null`: `NOT NULL item_key`
- `work_items_labels_not_null`: `NOT NULL labels`
- `work_items_pkey`: `PRIMARY KEY (id)`
- `work_items_priority_check`: `CHECK (((priority)::text = ANY ((ARRAY['LOW'::character varying, 'MEDIUM'::character varying, 'HIGH'::character varying, 'CRITICAL'::character varying])::text[])))`
- `work_items_priority_not_null`: `NOT NULL priority`
- `work_items_progress_evidence_check`: `CHECK (((progress_evidence IS NULL) OR (jsonb_typeof(progress_evidence) = 'object'::text)))`
- `work_items_progress_evidence_not_null`: `NOT NULL progress_evidence`
- `work_items_project_id_fkey`: `FOREIGN KEY (project_id) REFERENCES projects(id)`
- `work_items_project_id_id_key`: `UNIQUE (project_id, id)`
- `work_items_project_id_item_key_key`: `UNIQUE (project_id, item_key)`
- `work_items_project_id_not_null`: `NOT NULL project_id`
- `work_items_remaining_minutes_check`: `CHECK ((remaining_minutes >= 0))`
- `work_items_required_skills_check`: `CHECK (((required_skills IS NULL) OR (jsonb_typeof(required_skills) = 'array'::text)))`
- `work_items_required_skills_not_null`: `NOT NULL required_skills`
- `work_items_status_check`: `CHECK (((status)::text = ANY ((ARRAY['TO_DO'::character varying, 'IN_PROGRESS'::character varying, 'IN_REVIEW'::character varying, 'DONE'::character varying])::text[])))`
- `work_items_status_not_null`: `NOT NULL status`
- `work_items_story_points_check`: `CHECK ((story_points >= (0)::numeric))`
- `work_items_time_spent_minutes_check`: `CHECK ((time_spent_minutes >= 0))`
- `work_items_time_spent_minutes_not_null`: `NOT NULL time_spent_minutes`
- `work_items_title_check`: `CHECK ((length(btrim((title)::text)) > 0))`
- `work_items_title_not_null`: `NOT NULL title`
- `work_items_type_check`: `CHECK (((type)::text = ANY ((ARRAY['EPIC'::character varying, 'USER_STORY'::character varying, 'TASK'::character varying, 'SUBTASK'::character varying, 'BUG'::character varying])::text[])))`
- `work_items_type_not_null`: `NOT NULL type`
- `work_items_updated_at_not_null`: `NOT NULL updated_at`
- `work_items_verified_progress_check`: `CHECK (((verified_progress >= (0)::numeric) AND (verified_progress <= (100)::numeric)))`
- `work_items_version_check`: `CHECK ((version >= 0))`
- `work_items_version_not_null`: `NOT NULL version`
- `work_items_work_kind_check`: `CHECK (((work_kind)::text = ANY ((ARRAY['CODE'::character varying, 'NON_CODE'::character varying])::text[])))`
- `work_items_work_kind_not_null`: `NOT NULL work_kind`

## workspace_invitations

| Column | PostgreSQL type | Nullable | Default |
|---|---|---|---|
| `id` PK | `uuid` | False | `gen_random_uuid()` |
| `workspace_id` | `uuid` | False | `` |
| `email` | `citext` | False | `` |
| `token_hash` | `character varying(255)` | False | `` |
| `invited_by` | `uuid` | False | `` |
| `role` | `character varying(24)` | False | `'MEMBER'::character varying` |
| `status` | `character varying(12)` | False | `'PENDING'::character varying` |
| `expires_at` | `timestamp with time zone` | False | `` |
| `accepted_at` | `timestamp with time zone` | True | `` |
| `created_at` | `timestamp with time zone` | False | `now()` |
| `updated_at` | `timestamp with time zone` | False | `now()` |
| `version` | `bigint` | False | `0` |

Constraints:

- `workspace_invitations_check`: `CHECK ((expires_at > created_at))`
- `workspace_invitations_check1`: `CHECK ((((status)::text = 'ACCEPTED'::text) = (accepted_at IS NOT NULL)))`
- `workspace_invitations_created_at_not_null`: `NOT NULL created_at`
- `workspace_invitations_email_not_null`: `NOT NULL email`
- `workspace_invitations_expires_at_not_null`: `NOT NULL expires_at`
- `workspace_invitations_id_not_null`: `NOT NULL id`
- `workspace_invitations_invited_by_fkey`: `FOREIGN KEY (invited_by) REFERENCES users(id)`
- `workspace_invitations_invited_by_not_null`: `NOT NULL invited_by`
- `workspace_invitations_pkey`: `PRIMARY KEY (id)`
- `workspace_invitations_role_check`: `CHECK (((role)::text = 'MEMBER'::text))`
- `workspace_invitations_role_not_null`: `NOT NULL role`
- `workspace_invitations_status_check`: `CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'ACCEPTED'::character varying, 'DECLINED'::character varying, 'EXPIRED'::character varying, 'REVOKED'::character varying])::text[])))`
- `workspace_invitations_status_not_null`: `NOT NULL status`
- `workspace_invitations_token_hash_key`: `UNIQUE (token_hash)`
- `workspace_invitations_token_hash_not_null`: `NOT NULL token_hash`
- `workspace_invitations_updated_at_not_null`: `NOT NULL updated_at`
- `workspace_invitations_version_check`: `CHECK ((version >= 0))`
- `workspace_invitations_version_not_null`: `NOT NULL version`
- `workspace_invitations_workspace_id_fkey`: `FOREIGN KEY (workspace_id) REFERENCES workspaces(id)`
- `workspace_invitations_workspace_id_id_key`: `UNIQUE (workspace_id, id)`
- `workspace_invitations_workspace_id_not_null`: `NOT NULL workspace_id`

## workspace_members

| Column | PostgreSQL type | Nullable | Default |
|---|---|---|---|
| `id` PK | `uuid` | False | `gen_random_uuid()` |
| `workspace_id` | `uuid` | False | `` |
| `user_id` | `uuid` | False | `` |
| `role` | `character varying(8)` | False | `'MEMBER'::character varying` |
| `status` | `character varying(12)` | False | `'ACTIVE'::character varying` |
| `joined_at` | `timestamp with time zone` | False | `now()` |
| `created_at` | `timestamp with time zone` | False | `now()` |
| `updated_at` | `timestamp with time zone` | False | `now()` |
| `version` | `bigint` | False | `0` |

Constraints:

- `workspace_members_created_at_not_null`: `NOT NULL created_at`
- `workspace_members_id_not_null`: `NOT NULL id`
- `workspace_members_joined_at_not_null`: `NOT NULL joined_at`
- `workspace_members_pkey`: `PRIMARY KEY (id)`
- `workspace_members_role_check`: `CHECK (((role)::text = ANY ((ARRAY['OWNER'::character varying, 'MEMBER'::character varying])::text[])))`
- `workspace_members_role_not_null`: `NOT NULL role`
- `workspace_members_status_check`: `CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'REMOVED'::character varying])::text[])))`
- `workspace_members_status_not_null`: `NOT NULL status`
- `workspace_members_updated_at_not_null`: `NOT NULL updated_at`
- `workspace_members_user_id_fkey`: `FOREIGN KEY (user_id) REFERENCES users(id)`
- `workspace_members_user_id_not_null`: `NOT NULL user_id`
- `workspace_members_version_check`: `CHECK ((version >= 0))`
- `workspace_members_version_not_null`: `NOT NULL version`
- `workspace_members_workspace_id_fkey`: `FOREIGN KEY (workspace_id) REFERENCES workspaces(id)`
- `workspace_members_workspace_id_id_key`: `UNIQUE (workspace_id, id)`
- `workspace_members_workspace_id_not_null`: `NOT NULL workspace_id`
- `workspace_members_workspace_id_user_id_key`: `UNIQUE (workspace_id, user_id)`
- `workspace_owner_on_membership`: `TRIGGER DEFERRABLE INITIALLY DEFERRED`

## workspaces

| Column | PostgreSQL type | Nullable | Default |
|---|---|---|---|
| `id` PK | `uuid` | False | `gen_random_uuid()` |
| `slug` | `character varying(80)` | False | `` |
| `name` | `character varying(160)` | False | `` |
| `owner_id` | `uuid` | False | `` |
| `description` | `text` | True | `` |
| `status` | `character varying(24)` | False | `'SETUP_INCOMPLETE'::character varying` |
| `settings` | `jsonb` | False | `'{"schemaVersion": 1}'::jsonb` |
| `ai_connections` | `jsonb` | False | `'[]'::jsonb` |
| `ai_task_routes` | `jsonb` | False | `'{"schemaVersion": 1}'::jsonb` |
| `created_at` | `timestamp with time zone` | False | `now()` |
| `updated_at` | `timestamp with time zone` | False | `now()` |
| `version` | `bigint` | False | `0` |

Constraints:

- `ck_no_secret_ai_connections`: `CHECK (((ai_connections IS NULL) OR (NOT ds_json_has_secret(ai_connections))))`
- `ck_no_secret_ai_task_routes`: `CHECK (((ai_task_routes IS NULL) OR (NOT ds_json_has_secret(ai_task_routes))))`
- `ck_no_secret_settings`: `CHECK (((settings IS NULL) OR (NOT ds_json_has_secret(settings))))`
- `workspace_owner_on_workspace`: `TRIGGER DEFERRABLE INITIALLY DEFERRED`
- `workspaces_ai_connections_check`: `CHECK (((ai_connections IS NULL) OR (jsonb_typeof(ai_connections) = 'array'::text)))`
- `workspaces_ai_connections_not_null`: `NOT NULL ai_connections`
- `workspaces_ai_task_routes_check`: `CHECK (((ai_task_routes IS NULL) OR (jsonb_typeof(ai_task_routes) = 'object'::text)))`
- `workspaces_ai_task_routes_not_null`: `NOT NULL ai_task_routes`
- `workspaces_created_at_not_null`: `NOT NULL created_at`
- `workspaces_id_not_null`: `NOT NULL id`
- `workspaces_name_check`: `CHECK ((length(btrim((name)::text)) > 0))`
- `workspaces_name_not_null`: `NOT NULL name`
- `workspaces_owner_id_fkey`: `FOREIGN KEY (owner_id) REFERENCES users(id)`
- `workspaces_owner_id_not_null`: `NOT NULL owner_id`
- `workspaces_pkey`: `PRIMARY KEY (id)`
- `workspaces_settings_check`: `CHECK (((settings IS NULL) OR (jsonb_typeof(settings) = 'object'::text)))`
- `workspaces_settings_not_null`: `NOT NULL settings`
- `workspaces_slug_check`: `CHECK (((slug)::text ~ '^[a-z0-9]+(-[a-z0-9]+)*$'::text))`
- `workspaces_slug_key`: `UNIQUE (slug)`
- `workspaces_slug_not_null`: `NOT NULL slug`
- `workspaces_status_check`: `CHECK (((status)::text = ANY ((ARRAY['SETUP_INCOMPLETE'::character varying, 'ACTIVE'::character varying])::text[])))`
- `workspaces_status_not_null`: `NOT NULL status`
- `workspaces_updated_at_not_null`: `NOT NULL updated_at`
- `workspaces_version_check`: `CHECK ((version >= 0))`
- `workspaces_version_not_null`: `NOT NULL version`

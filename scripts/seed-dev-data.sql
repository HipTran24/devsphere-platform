-- DevSphere Development Seed Dataset (Idempotent)
-- Milestone 7: Full-Stack Real Integration

BEGIN;

-- 1. Seed Users (Admin, PM, Dev, QA)
INSERT INTO users (id, email, password_hash, display_name, system_role, status, email_verified_at, created_at, updated_at)
VALUES
  ('11111111-1111-1111-1111-111111111111', 'admin@devsphere.io', '$argon2id$v=19$m=19456,t=2,p=1$GnF4AAoK6Wi9h++kWiNCbw$sO/wGY+ET+n0YxZGPP55DK6E4d8IqhkRI6r3mF6DC2g', 'Admin User', 'ADMIN', 'ACTIVE', NOW(), NOW(), NOW()),
  ('22222222-2222-2222-2222-222222222222', 'pm@devsphere.io', '$argon2id$v=19$m=19456,t=2,p=1$+HzI+PUq3P159bqSp4JFtw$En2EGovPpWvtGVzEIrb9+e/u4yxkafipRYznSFLCcdc', 'Sarah PM', 'USER', 'ACTIVE', NOW(), NOW(), NOW()),
  ('33333333-3333-3333-3333-333333333333', 'dev@devsphere.io', '$argon2id$v=19$m=19456,t=2,p=1$Co62LuIjqHGBjcOfblzi1Q$9l/V4ja00Wi7aM73gfY16G6RSX2YVjypxYyyqvYand8', 'Alex Dev', 'USER', 'ACTIVE', NOW(), NOW(), NOW()),
  ('44444444-4444-4444-4444-444444444444', 'qa@devsphere.io', '$argon2id$v=19$m=19456,t=2,p=1$U6amjXWRr8kiVA2Lgmc+hA$ebHUXvKtl4MWEGU4p2mTG4kC6QsLEdc+w82PBUcIPZU', 'Quinn QA', 'USER', 'ACTIVE', NOW(), NOW(), NOW())
ON CONFLICT (email) DO UPDATE SET
  password_hash = EXCLUDED.password_hash,
  display_name = EXCLUDED.display_name,
  system_role = EXCLUDED.system_role,
  status = 'ACTIVE',
  email_verified_at = COALESCE(users.email_verified_at, NOW());

-- 2. Seed Workspace
INSERT INTO workspaces (id, slug, name, owner_id, description, status, settings, ai_connections, created_at, updated_at)
VALUES
  ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'devsphere-core', 'DevSphere Core', '11111111-1111-1111-1111-111111111111', 'Primary engineering workspace for DevSphere Platform', 'ACTIVE', '{"schemaVersion":1}'::jsonb, '[]'::jsonb, NOW(), NOW())
ON CONFLICT (slug) DO UPDATE SET
  name = EXCLUDED.name,
  description = EXCLUDED.description,
  status = 'ACTIVE';

-- Workspace Memberships
INSERT INTO workspace_memberships (workspace_id, user_id, role, status, joined_at)
VALUES
  ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', '11111111-1111-1111-1111-111111111111', 'OWNER', 'ACTIVE', NOW()),
  ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', '22222222-2222-2222-2222-222222222222', 'MEMBER', 'ACTIVE', NOW()),
  ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', '33333333-3333-3333-3333-333333333333', 'MEMBER', 'ACTIVE', NOW()),
  ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', '44444444-4444-4444-4444-444444444444', 'MEMBER', 'ACTIVE', NOW())
ON CONFLICT (workspace_id, user_id) DO NOTHING;

-- 3. Seed Project
INSERT INTO projects (id, workspace_id, project_key, name, description, objective, status, timezone, planned_start, committed_deadline, sprint_length_days, settings, next_item_number, created_at, updated_at)
VALUES
  ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'DSP', 'DevSphere Platform', 'Core DevSphere multi-tenant platform', 'Ship resilient, autonomous engineering system with real-time quality gates', 'ACTIVE', 'Asia/Ho_Chi_Minh', '2026-11-19', '2027-01-23', 14, '{"schemaVersion":1}'::jsonb, 15, NOW(), NOW())
ON CONFLICT (workspace_id, id) DO UPDATE SET
  status = 'ACTIVE',
  next_item_number = GREATEST(projects.next_item_number, 15);

-- Project Members & Roles
INSERT INTO project_members (id, project_id, workspace_id, user_id, status, joined_at)
VALUES
  (gen_random_uuid(), 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', '11111111-1111-1111-1111-111111111111', 'ACTIVE', NOW()),
  (gen_random_uuid(), 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', '22222222-2222-2222-2222-222222222222', 'ACTIVE', NOW()),
  (gen_random_uuid(), 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', '33333333-3333-3333-3333-333333333333', 'ACTIVE', NOW()),
  (gen_random_uuid(), 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', '44444444-4444-4444-4444-444444444444', 'ACTIVE', NOW())
ON CONFLICT (project_id, user_id) DO NOTHING;

INSERT INTO project_member_roles (project_id, project_member_id, role, granted_by, accepted_by, accepted_at)
SELECT pm.project_id, pm.id, 'PM', '11111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222222', NOW()
FROM project_members pm WHERE pm.project_id = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb' AND pm.user_id = '22222222-2222-2222-2222-222222222222'
ON CONFLICT DO NOTHING;

INSERT INTO project_member_roles (project_id, project_member_id, role, granted_by)
SELECT pm.project_id, pm.id, 'DEVELOPER', '11111111-1111-1111-1111-111111111111'
FROM project_members pm WHERE pm.project_id = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb' AND pm.user_id = '33333333-3333-3333-3333-333333333333'
ON CONFLICT DO NOTHING;

-- 4. Seed Requirements (REQ-1 to REQ-4)
INSERT INTO requirements (id, project_id, code, kind, title, description, status, current_version, requested_by, created_at, updated_at)
VALUES
  ('c1111111-1111-1111-1111-111111111111', 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'REQ-1', 'REQUIREMENT', 'Multi-tenant Session & Authentication Security', 'Stateless JWT bearer tokens with silent refresh and Argon2id hash password storage.', 'APPROVED', 1, '11111111-1111-1111-1111-111111111111', NOW(), NOW()),
  ('c2222222-2222-2222-2222-222222222222', 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'REQ-2', 'CP-SAT Algorithmic Schedule Optimization', 'Automated CP-SAT constraint satisfaction engine for developer capacity and deadline tracking.', 'APPROVED', 1, '22222222-2222-2222-2222-222222222222', NOW(), NOW()),
  ('c3333333-3333-3333-3333-333333333333', 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'REQ-3', 'Real-Time Kanban & Quality Gates', 'Dynamic 4-column sprint board with PM acceptance gates and PR verification.', 'APPROVED', 1, '22222222-2222-2222-2222-222222222222', NOW(), NOW()),
  ('c4444444-4444-4444-4444-444444444444', 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'REQ-4', 'Automated CI/CD Pipeline & Audit Trails', 'Continuous verification with tamper-proof event logging and GitHub webhook ingestion.', 'APPROVED', 1, '33333333-3333-3333-3333-333333333333', NOW(), NOW())
ON CONFLICT (project_id, code) DO NOTHING;

-- 5. Seed Sprint 1
INSERT INTO sprints (id, project_id, name, goal, start_date, end_date, status, committed_points, completed_points, created_at, updated_at)
VALUES
  ('d1111111-1111-1111-1111-111111111111', 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'Foundations & Core Engine', 'Establish core API proxies, authentication lifecycle, and interactive Kanban board.', '2026-11-19', '2026-12-03', 'ACTIVE', 34, 10, NOW(), NOW())
ON CONFLICT (project_id, id) DO NOTHING;

-- 6. Seed Backlog Work Items (Epics, Stories, Tasks)
-- Epics
INSERT INTO work_items (id, project_id, item_key, type, title, description, status, priority, backlog_rank, story_points, created_at, updated_at)
VALUES
  ('e1111111-1111-1111-1111-111111111111', 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'DSP-1', 'EPIC', 'Core Platform Infrastructure & Security', 'Foundation services for session management, scheduling, and multi-tenancy.', 'IN_PROGRESS', 'CRITICAL', 1.0, 40, NOW(), NOW()),
  ('e2222222-2222-2222-2222-222222222222', 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'DSP-2', 'EPIC', 'Agile Project Management Engine', 'Backlog hierarchy, Kanban visualization, quality gates, and Gantt charts.', 'IN_PROGRESS', 'HIGH', 2.0, 35, NOW(), NOW())
ON CONFLICT (project_id, item_key) DO NOTHING;

-- Stories
INSERT INTO work_items (id, project_id, item_key, parent_id, requirement_id, sprint_id, type, title, description, status, priority, backlog_rank, story_points, created_at, updated_at)
VALUES
  ('s3333333-3333-3333-3333-333333333333', 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'DSP-3', 'e1111111-1111-1111-1111-111111111111', 'c1111111-1111-1111-1111-111111111111', 'd1111111-1111-1111-1111-111111111111', 'USER_STORY', 'Stateless JWT & CSRF Session Management', 'Implement secure session lifecycle with HttpOnly cookies and silent refresh.', 'DONE', 'CRITICAL', 1.1, 10, NOW(), NOW()),
  ('s4444444-4444-4444-4444-444444444444', 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'DSP-4', 'e1111111-1111-1111-1111-111111111111', 'c2222222-2222-2222-2222-222222222222', 'd1111111-1111-1111-1111-111111111111', 'USER_STORY', 'CP-SAT Schedule Formulation & Constraint Solver', 'Formulate schedule optimization using Google OR-Tools CP-SAT solver.', 'IN_PROGRESS', 'HIGH', 1.2, 13, NOW(), NOW()),
  ('s5555555-5555-5555-5555-555555555555', 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'DSP-5', 'e2222222-2222-2222-2222-222222222222', 'c3333333-3333-3333-3333-333333333333', 'd1111111-1111-1111-1111-111111111111', 'USER_STORY', 'Interactive Kanban Board with Quality Gates', 'Visual board displaying task status, quality gates, and PM acceptance flow.', 'IN_PROGRESS', 'HIGH', 2.1, 13, NOW(), NOW()),
  ('s6666666-6666-6666-6666-666666666666', 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'DSP-6', 'e2222222-2222-2222-2222-222222222222', 'c4444444-4444-4444-4444-444444444444', 'd1111111-1111-1111-1111-111111111111', 'USER_STORY', 'Automated CI Status & Code Review Verification', 'Integrate GitHub PR webhook events and automated quality evaluation.', 'IN_REVIEW', 'MEDIUM', 2.2, 8, NOW(), NOW())
ON CONFLICT (project_id, item_key) DO UPDATE SET
  completed_at = CASE WHEN work_items.status = 'DONE' THEN COALESCE(work_items.completed_at, NOW()) ELSE NULL END,
  accepted_by = CASE WHEN work_items.status = 'DONE' THEN COALESCE(work_items.accepted_by, '22222222-2222-2222-2222-222222222222') ELSE NULL END;

-- Tasks (DSP-7 to DSP-14)
INSERT INTO work_items (id, project_id, item_key, parent_id, requirement_id, sprint_id, type, work_kind, title, description, status, priority, assignee_id, backlog_rank, story_points, estimate_minutes, time_spent_minutes, completed_at, accepted_by, created_at, updated_at)
VALUES
  ('t7777777-7777-7777-7777-777777777777', 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'DSP-7', 's3333333-3333-3333-3333-333333333333', 'c1111111-1111-1111-1111-111111111111', 'd1111111-1111-1111-1111-111111111111', 'TASK', 'CODE', 'Configure Spring Security Bearer filter and CSRF token repository', 'Configure stateless security filter chain and CookieCsrfTokenRepository.', 'DONE', 'CRITICAL', '33333333-3333-3333-3333-333333333333', 1.11, 5, 300, 300, NOW(), '22222222-2222-2222-2222-222222222222', NOW(), NOW()),
  ('t8888888-8888-8888-8888-888888888888', 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'DSP-8', 's3333333-3333-3333-3333-333333333333', 'c1111111-1111-1111-1111-111111111111', 'd1111111-1111-1111-1111-111111111111', 'TASK', 'CODE', 'Implement Next.js proxy rewrite and silent refresh client', 'Configure Next.js proxy rewrites and api-client refresh interceptor.', 'DONE', 'HIGH', '33333333-3333-3333-3333-333333333333', 1.12, 5, 300, 300, NOW(), '22222222-2222-2222-2222-222222222222', NOW(), NOW()),
  ('t9999999-9999-9999-9999-999999999999', 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'DSP-9', 's4444444-4444-4444-4444-444444444444', 'c2222222-2222-2222-2222-222222222222', 'd1111111-1111-1111-1111-111111111111', 'TASK', 'CODE', 'Formulate integer linear programming constraints in OR-Tools', 'Encode precedence constraints and developer capacity bounds in CP-SAT solver.', 'IN_PROGRESS', 'CRITICAL', '33333333-3333-3333-3333-333333333333', 1.21, 8, 480, 240, NULL, NULL, NOW(), NOW()),
  ('t1010101-1010-1010-1010-101010101010', 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'DSP-10', 's4444444-4444-4444-4444-444444444444', 'c2222222-2222-2222-2222-222222222222', NULL, 'TASK', 'CODE', 'Implement plan proposal diff preview and diagnostics', 'Build UI and API endpoints for previewing schedule shifts.', 'TO_DO', 'HIGH', NULL, 1.22, 5, 300, 0, NULL, NULL, NOW(), NOW()),
  ('t1111111-1111-1111-1111-111111111111', 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'DSP-11', 's5555555-5555-5555-5555-555555555555', 'c3333333-3333-3333-3333-333333333333', 'd1111111-1111-1111-1111-111111111111', 'TASK', 'CODE', 'Build Kanban 4-column board with drag and keyboard navigation', 'Implement columns (TODO, IN_PROGRESS, IN_REVIEW, DONE) with WCAG focus management.', 'IN_PROGRESS', 'HIGH', '33333333-3333-3333-3333-333333333333', 2.11, 5, 300, 180, NULL, NULL, NOW(), NOW()),
  ('t1212121-1212-1212-1212-121212121212', 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'DSP-12', 's5555555-5555-5555-5555-555555555555', 'c3333333-3333-3333-3333-333333333333', 'd1111111-1111-1111-1111-111111111111', 'TASK', 'CODE', 'Integrate PM Acceptance dialog with Quality Gate evaluation', 'Enforce PM-only acceptance with automated validation of CI, review, and criteria.', 'IN_REVIEW', 'CRITICAL', '33333333-3333-3333-3333-333333333333', 2.12, 8, 480, 420, NULL, NULL, NOW(), NOW()),
  ('t1313131-1313-1313-1313-131313131313', 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'DSP-13', 's6666666-6666-6666-6666-666666666666', 'c4444444-4444-4444-4444-444444444444', 'd1111111-1111-1111-1111-111111111111', 'TASK', 'CODE', 'Create GitHub webhook ingestion and CI run persistence', 'Process check_suite and pull_request webhook payloads.', 'DONE', 'MEDIUM', '44444444-4444-4444-4444-444444444444', 2.21, 3, 180, 180, NOW(), '22222222-2222-2222-2222-222222222222', NOW(), NOW()),
  ('t1414141-1414-1414-1414-141414141414', 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'DSP-14', 's6666666-6666-6666-6666-666666666666', 'c4444444-4444-4444-4444-444444444444', 'd1111111-1111-1111-1111-111111111111', 'TASK', 'CODE', 'Implement quality gate criteria checks (CI, Review, Acceptance)', 'Aggregate quality gate status across CI runs and reviews.', 'IN_REVIEW', 'HIGH', '44444444-4444-4444-4444-444444444444', 2.22, 5, 300, 240, NULL, NULL, NOW(), NOW())
ON CONFLICT (project_id, item_key) DO UPDATE SET
  status = EXCLUDED.status,
  completed_at = EXCLUDED.completed_at,
  accepted_by = EXCLUDED.accepted_by;

-- 7. Seed Dependencies
INSERT INTO work_item_dependencies (id, project_id, predecessor_id, successor_id, link_type, lag_minutes)
VALUES
  (gen_random_uuid(), 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 't7777777-7777-7777-7777-777777777777', 't8888888-8888-8888-8888-888888888888', 'FINISH_TO_START', 0),
  (gen_random_uuid(), 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 't9999999-9999-9999-9999-999999999999', 't1010101-1010-1010-1010-101010101010', 'FINISH_TO_START', 0),
  (gen_random_uuid(), 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 't1111111-1111-1111-1111-111111111111', 't1212121-1212-1212-1212-121212121212', 'FINISH_TO_START', 0),
  (gen_random_uuid(), 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 't1313131-1313-1313-1313-131313131313', 't1414141-1414-1414-1414-141414141414', 'FINISH_TO_START', 0)
ON CONFLICT (predecessor_id, successor_id) DO NOTHING;

-- 8. Seed Acceptance Criteria for DSP-12 (Quality Gate pass)
INSERT INTO acceptance_criteria (id, project_id, work_item_id, title, description, status, created_at, updated_at)
VALUES
  (gen_random_uuid(), 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 't1212121-1212-1212-1212-121212121212', 'All automated CI checks must pass', 'GitHub Actions workflow runs to completion with SUCCESS.', 'VERIFIED', NOW(), NOW()),
  (gen_random_uuid(), 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 't1212121-1212-1212-1212-121212121212', 'Peer code review must be approved', 'Human code review approved by Lead Developer or PM.', 'VERIFIED', NOW(), NOW())
ON CONFLICT DO NOTHING;

-- 9. Seed GitHub Integration & Quality Gate Evidence for DSP-12
INSERT INTO github_repositories (id, workspace_id, project_id, github_installation_id, github_repo_id, full_name, default_branch, private, active)
VALUES
  ('f1111111-1111-1111-1111-111111111111', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 10001, 20002, 'devsphere/platform', 'main', true, true)
ON CONFLICT (github_repo_id) DO NOTHING;

INSERT INTO github_activities (id, project_id, repository_id, work_item_id, kind, github_object_id, pr_number, pr_head_sha, actor_login, state, title, happened_at)
VALUES
  (gen_random_uuid(), 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'f1111111-1111-1111-1111-111111111111', 't1212121-1212-1212-1212-121212121212', 'PULL_REQUEST', 'pr-42', 42, '4242424242424242424242424242424242424242', 'alexdev', 'OPEN', 'feat(quality-gate): PM Acceptance Dialog and criteria validator', NOW() - INTERVAL '2 hours')
ON CONFLICT (repository_id, kind, github_object_id) DO NOTHING;

INSERT INTO ci_runs (id, project_id, repository_id, work_item_id, github_run_id, run_attempt, head_sha, pr_number, status, conclusion, started_at, completed_at)
VALUES
  (gen_random_uuid(), 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'f1111111-1111-1111-1111-111111111111', 't1212121-1212-1212-1212-121212121212', 987654321, 1, '4242424242424242424242424242424242424242', 42, 'COMPLETED', 'SUCCESS', NOW() - INTERVAL '1 hour', NOW() - INTERVAL '50 minutes')
ON CONFLICT (repository_id, github_run_id, run_attempt) DO NOTHING;

INSERT INTO code_reviews (id, project_id, repository_id, work_item_id, pr_number, head_sha, context_version, scope, reviewer_kind, reviewer_id, status, summary, completed_at)
VALUES
  (gen_random_uuid(), 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'f1111111-1111-1111-1111-111111111111', 't1212121-1212-1212-1212-121212121212', 42, '4242424242424242424242424242424242424242', 0, 'PR', 'HUMAN', '22222222-2222-2222-2222-222222222222', 'PASS', 'Thorough validation of PM acceptance flow with full test coverage.', NOW() - INTERVAL '30 minutes')
ON CONFLICT DO NOTHING;

-- 10. Seed Plan Proposal (Solved CP-SAT proposal ready for review)
INSERT INTO plan_proposals (
  id, project_id, kind, base_project_version, status, solver_status,
  proposal_payload, solver_details, created_by, created_at, updated_at
)
VALUES (
  'p1111111-1111-1111-1111-111111111111',
  'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
  'AUTO_REPLAN',
  0,
  'PENDING_APPROVAL',
  'OPTIMAL',
  '{"schemaVersion": "1", "proposedSchedule": [{"itemKey": "DSP-9", "start": "2026-11-19", "finish": "2026-11-24"}, {"itemKey": "DSP-10", "start": "2026-11-24", "finish": "2026-11-29"}]}'::jsonb,
  '{"schemaVersion": 1, "makespanDays": 12, "optimal": true, "solverIterations": 42}'::jsonb,
  '22222222-2222-2222-2222-222222222222',
  NOW(),
  NOW()
)
ON CONFLICT (project_id, id) DO NOTHING;

COMMIT;

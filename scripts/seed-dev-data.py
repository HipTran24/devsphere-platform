#!/usr/bin/env python3
"""
DevSphere Milestone 7 Development Seed Data Script
Generates a realistic, complete dataset for development and verification:
- Users: System Admin, Workspace PM, Lead Developer, QA Engineer
- Workspace: DevSphere Core (devsphere-core)
- Project: DevSphere Platform (DSP)
- Requirements: 4 comprehensive requirements (REQ-1 .. REQ-4)
- Backlog: 2 Epics (DSP-1, DSP-2), 4 Stories (DSP-3 .. DSP-6), 8 Tasks (DSP-7 .. DSP-14)
- Dependencies: Predecessor/Successor links
- Sprint: Sprint 1 'Foundations & Core Engine' (ACTIVE)
- Quality Gate Evidence: CI Run PASSED, Code Review APPROVED, Criteria MET for DSP-12
- Plan Proposal: Solved CP-SAT proposal with schedule diff ready for review

Usage:
  python3 scripts/seed-dev-data.py [--backend-url http://localhost:8080] [--sql-output scripts/seed-dev-data.sql]
"""

import argparse
import http.cookiejar
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone

# Stable deterministic UUIDs for repeatable idempotency
UUID_ADMIN = "11111111-1111-1111-1111-111111111111"
UUID_PM = "22222222-2222-2222-2222-222222222222"
UUID_DEV = "33333333-3333-3333-3333-333333333333"
UUID_QA = "44444444-4444-4444-4444-444444444444"

UUID_WS = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
UUID_PROJ = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"

UUID_REQ_1 = "c1111111-1111-1111-1111-111111111111"
UUID_REQ_2 = "c2222222-2222-2222-2222-222222222222"
UUID_REQ_3 = "c3333333-3333-3333-3333-333333333333"
UUID_REQ_4 = "c4444444-4444-4444-4444-444444444444"

UUID_EPIC_1 = "e1111111-1111-1111-1111-111111111111"
UUID_EPIC_2 = "e2222222-2222-2222-2222-222222222222"

UUID_STORY_3 = "s3333333-3333-3333-3333-333333333333"
UUID_STORY_4 = "s4444444-4444-4444-4444-444444444444"
UUID_STORY_5 = "s5555555-5555-5555-5555-555555555555"
UUID_STORY_6 = "s6666666-6666-6666-6666-666666666666"

UUID_TASK_7 = "t7777777-7777-7777-7777-777777777777"
UUID_TASK_8 = "t8888888-8888-8888-8888-888888888888"
UUID_TASK_9 = "t9999999-9999-9999-9999-999999999999"
UUID_TASK_10 = "t1010101-1010-1010-1010-101010101010"
UUID_TASK_11 = "t1111111-1111-1111-1111-111111111111"
UUID_TASK_12 = "t1212121-1212-1212-1212-121212121212"
UUID_TASK_13 = "t1313131-1313-1313-1313-131313131313"
UUID_TASK_14 = "t1414141-1414-1414-1414-141414141414"

UUID_SPRINT_1 = "d1111111-1111-1111-1111-111111111111"
UUID_REPO = "f1111111-1111-1111-1111-111111111111"
UUID_PROPOSAL_1 = "p1111111-1111-1111-1111-111111111111"

# Argon2id password hashes (t=2, m=19456, p=1)
HASH_ADMIN = "$argon2id$v=19$m=19456,t=2,p=1$GnF4AAoK6Wi9h++kWiNCbw$sO/wGY+ET+n0YxZGPP55DK6E4d8IqhkRI6r3mF6DC2g"
HASH_PM = "$argon2id$v=19$m=19456,t=2,p=1$+HzI+PUq3P159bqSp4JFtw$En2EGovPpWvtGVzEIrb9+e/u4yxkafipRYznSFLCcdc"
HASH_DEV = "$argon2id$v=19$m=19456,t=2,p=1$Co62LuIjqHGBjcOfblzi1Q$9l/V4ja00Wi7aM73gfY16G6RSX2YVjypxYyyqvYand8"
HASH_QA = "$argon2id$v=19$m=19456,t=2,p=1$U6amjXWRr8kiVA2Lgmc+hA$ebHUXvKtl4MWEGU4p2mTG4kC6QsLEdc+w82PBUcIPZU"

PR_HEAD_SHA = "4242424242424242424242424242424242424242"


def generate_sql_script():
    """Generates an idempotent SQL script to populate the development dataset directly into Postgres."""
    today = datetime.now(timezone.utc).date()
    sprint_start = today - timedelta(days=5)
    sprint_end = today + timedelta(days=9)
    now_iso = datetime.now(timezone.utc).isoformat()

    sql = f"""-- DevSphere Development Seed Dataset (Idempotent)
-- Milestone 7: Full-Stack Real Integration

BEGIN;

-- 1. Seed Users (Admin, PM, Dev, QA)
INSERT INTO users (id, email, password_hash, display_name, system_role, status, email_verified_at, created_at, updated_at)
VALUES
  ('{UUID_ADMIN}', 'admin@devsphere.io', '{HASH_ADMIN}', 'Admin User', 'ADMIN', 'ACTIVE', NOW(), NOW(), NOW()),
  ('{UUID_PM}', 'pm@devsphere.io', '{HASH_PM}', 'Sarah PM', 'USER', 'ACTIVE', NOW(), NOW(), NOW()),
  ('{UUID_DEV}', 'dev@devsphere.io', '{HASH_DEV}', 'Alex Dev', 'USER', 'ACTIVE', NOW(), NOW(), NOW()),
  ('{UUID_QA}', 'qa@devsphere.io', '{HASH_QA}', 'Quinn QA', 'USER', 'ACTIVE', NOW(), NOW(), NOW())
ON CONFLICT (email) DO UPDATE SET
  password_hash = EXCLUDED.password_hash,
  display_name = EXCLUDED.display_name,
  system_role = EXCLUDED.system_role,
  status = 'ACTIVE',
  email_verified_at = COALESCE(users.email_verified_at, NOW());

-- 2. Seed Workspace
INSERT INTO workspaces (id, slug, name, owner_id, description, status, settings, ai_connections, created_at, updated_at)
VALUES
  ('{UUID_WS}', 'devsphere-core', 'DevSphere Core', '{UUID_ADMIN}', 'Primary engineering workspace for DevSphere Platform', 'ACTIVE', '{{"schemaVersion":1}}'::jsonb, '[]'::jsonb, NOW(), NOW())
ON CONFLICT (slug) DO UPDATE SET
  name = EXCLUDED.name,
  description = EXCLUDED.description,
  status = 'ACTIVE';

-- Workspace Memberships
INSERT INTO workspace_memberships (workspace_id, user_id, role, status, joined_at)
VALUES
  ('{UUID_WS}', '{UUID_ADMIN}', 'OWNER', 'ACTIVE', NOW()),
  ('{UUID_WS}', '{UUID_PM}', 'MEMBER', 'ACTIVE', NOW()),
  ('{UUID_WS}', '{UUID_DEV}', 'MEMBER', 'ACTIVE', NOW()),
  ('{UUID_WS}', '{UUID_QA}', 'MEMBER', 'ACTIVE', NOW())
ON CONFLICT (workspace_id, user_id) DO NOTHING;

-- 3. Seed Project
INSERT INTO projects (id, workspace_id, project_key, name, description, objective, status, timezone, planned_start, committed_deadline, sprint_length_days, settings, next_item_number, created_at, updated_at)
VALUES
  ('{UUID_PROJ}', '{UUID_WS}', 'DSP', 'DevSphere Platform', 'Core DevSphere multi-tenant platform', 'Ship resilient, autonomous engineering system with real-time quality gates', 'ACTIVE', 'Asia/Ho_Chi_Minh', '{sprint_start.isoformat()}', '{(today + timedelta(days=60)).isoformat()}', 14, '{{"schemaVersion":1}}'::jsonb, 15, NOW(), NOW())
ON CONFLICT (workspace_id, id) DO UPDATE SET
  status = 'ACTIVE',
  next_item_number = GREATEST(projects.next_item_number, 15);

-- Project Members & Roles
INSERT INTO project_members (id, project_id, workspace_id, user_id, status, joined_at)
VALUES
  (gen_random_uuid(), '{UUID_PROJ}', '{UUID_WS}', '{UUID_ADMIN}', 'ACTIVE', NOW()),
  (gen_random_uuid(), '{UUID_PROJ}', '{UUID_WS}', '{UUID_PM}', 'ACTIVE', NOW()),
  (gen_random_uuid(), '{UUID_PROJ}', '{UUID_WS}', '{UUID_DEV}', 'ACTIVE', NOW()),
  (gen_random_uuid(), '{UUID_PROJ}', '{UUID_WS}', '{UUID_QA}', 'ACTIVE', NOW())
ON CONFLICT (project_id, user_id) DO NOTHING;

INSERT INTO project_member_roles (project_id, project_member_id, role, granted_by, accepted_by, accepted_at)
SELECT pm.project_id, pm.id, 'PM', '{UUID_ADMIN}', '{UUID_PM}', NOW()
FROM project_members pm WHERE pm.project_id = '{UUID_PROJ}' AND pm.user_id = '{UUID_PM}'
ON CONFLICT DO NOTHING;

INSERT INTO project_member_roles (project_id, project_member_id, role, granted_by)
SELECT pm.project_id, pm.id, 'DEVELOPER', '{UUID_ADMIN}'
FROM project_members pm WHERE pm.project_id = '{UUID_PROJ}' AND pm.user_id = '{UUID_DEV}'
ON CONFLICT DO NOTHING;

-- 4. Seed Requirements (REQ-1 to REQ-4)
INSERT INTO requirements (id, project_id, code, kind, title, description, status, current_version, requested_by, created_at, updated_at)
VALUES
  ('{UUID_REQ_1}', '{UUID_PROJ}', 'REQ-1', 'REQUIREMENT', 'Multi-tenant Session & Authentication Security', 'Stateless JWT bearer tokens with silent refresh and Argon2id hash password storage.', 'APPROVED', 1, '{UUID_ADMIN}', NOW(), NOW()),
  ('{UUID_REQ_2}', '{UUID_PROJ}', 'REQ-2', 'CP-SAT Algorithmic Schedule Optimization', 'Automated CP-SAT constraint satisfaction engine for developer capacity and deadline tracking.', 'APPROVED', 1, '{UUID_PM}', NOW(), NOW()),
  ('{UUID_REQ_3}', '{UUID_PROJ}', 'REQ-3', 'Real-Time Kanban & Quality Gates', 'Dynamic 4-column sprint board with PM acceptance gates and PR verification.', 'APPROVED', 1, '{UUID_PM}', NOW(), NOW()),
  ('{UUID_REQ_4}', '{UUID_PROJ}', 'REQ-4', 'Automated CI/CD Pipeline & Audit Trails', 'Continuous verification with tamper-proof event logging and GitHub webhook ingestion.', 'APPROVED', 1, '{UUID_DEV}', NOW(), NOW())
ON CONFLICT (project_id, code) DO NOTHING;

-- 5. Seed Sprint 1
INSERT INTO sprints (id, project_id, name, goal, start_date, end_date, status, committed_points, completed_points, created_at, updated_at)
VALUES
  ('{UUID_SPRINT_1}', '{UUID_PROJ}', 'Foundations & Core Engine', 'Establish core API proxies, authentication lifecycle, and interactive Kanban board.', '{sprint_start.isoformat()}', '{sprint_end.isoformat()}', 'ACTIVE', 34, 10, NOW(), NOW())
ON CONFLICT (project_id, id) DO NOTHING;

-- 6. Seed Backlog Work Items (Epics, Stories, Tasks)
-- Epics
INSERT INTO work_items (id, project_id, item_key, type, title, description, status, priority, backlog_rank, story_points, created_at, updated_at)
VALUES
  ('{UUID_EPIC_1}', '{UUID_PROJ}', 'DSP-1', 'EPIC', 'Core Platform Infrastructure & Security', 'Foundation services for session management, scheduling, and multi-tenancy.', 'IN_PROGRESS', 'CRITICAL', 1.0, 40, NOW(), NOW()),
  ('{UUID_EPIC_2}', '{UUID_PROJ}', 'DSP-2', 'EPIC', 'Agile Project Management Engine', 'Backlog hierarchy, Kanban visualization, quality gates, and Gantt charts.', 'IN_PROGRESS', 'HIGH', 2.0, 35, NOW(), NOW())
ON CONFLICT (project_id, item_key) DO NOTHING;

-- Stories
INSERT INTO work_items (id, project_id, item_key, parent_id, requirement_id, sprint_id, type, title, description, status, priority, backlog_rank, story_points, created_at, updated_at)
VALUES
  ('{UUID_STORY_3}', '{UUID_PROJ}', 'DSP-3', '{UUID_EPIC_1}', '{UUID_REQ_1}', '{UUID_SPRINT_1}', 'USER_STORY', 'Stateless JWT & CSRF Session Management', 'Implement secure session lifecycle with HttpOnly cookies and silent refresh.', 'DONE', 'CRITICAL', 1.1, 10, NOW(), NOW()),
  ('{UUID_STORY_4}', '{UUID_PROJ}', 'DSP-4', '{UUID_EPIC_1}', '{UUID_REQ_2}', '{UUID_SPRINT_1}', 'USER_STORY', 'CP-SAT Schedule Formulation & Constraint Solver', 'Formulate schedule optimization using Google OR-Tools CP-SAT solver.', 'IN_PROGRESS', 'HIGH', 1.2, 13, NOW(), NOW()),
  ('{UUID_STORY_5}', '{UUID_PROJ}', 'DSP-5', '{UUID_EPIC_2}', '{UUID_REQ_3}', '{UUID_SPRINT_1}', 'USER_STORY', 'Interactive Kanban Board with Quality Gates', 'Visual board displaying task status, quality gates, and PM acceptance flow.', 'IN_PROGRESS', 'HIGH', 2.1, 13, NOW(), NOW()),
  ('{UUID_STORY_6}', '{UUID_PROJ}', 'DSP-6', '{UUID_EPIC_2}', '{UUID_REQ_4}', '{UUID_SPRINT_1}', 'USER_STORY', 'Automated CI Status & Code Review Verification', 'Integrate GitHub PR webhook events and automated quality evaluation.', 'IN_REVIEW', 'MEDIUM', 2.2, 8, NOW(), NOW())
ON CONFLICT (project_id, item_key) DO UPDATE SET
  completed_at = CASE WHEN work_items.status = 'DONE' THEN COALESCE(work_items.completed_at, NOW()) ELSE NULL END,
  accepted_by = CASE WHEN work_items.status = 'DONE' THEN COALESCE(work_items.accepted_by, '{UUID_PM}') ELSE NULL END;

-- Tasks (DSP-7 to DSP-14)
INSERT INTO work_items (id, project_id, item_key, parent_id, requirement_id, sprint_id, type, work_kind, title, description, status, priority, assignee_id, backlog_rank, story_points, estimate_minutes, time_spent_minutes, completed_at, accepted_by, created_at, updated_at)
VALUES
  ('{UUID_TASK_7}', '{UUID_PROJ}', 'DSP-7', '{UUID_STORY_3}', '{UUID_REQ_1}', '{UUID_SPRINT_1}', 'TASK', 'CODE', 'Configure Spring Security Bearer filter and CSRF token repository', 'Configure stateless security filter chain and CookieCsrfTokenRepository.', 'DONE', 'CRITICAL', '{UUID_DEV}', 1.11, 5, 300, 300, NOW(), '{UUID_PM}', NOW(), NOW()),
  ('{UUID_TASK_8}', '{UUID_PROJ}', 'DSP-8', '{UUID_STORY_3}', '{UUID_REQ_1}', '{UUID_SPRINT_1}', 'TASK', 'CODE', 'Implement Next.js proxy rewrite and silent refresh client', 'Configure Next.js proxy rewrites and api-client refresh interceptor.', 'DONE', 'HIGH', '{UUID_DEV}', 1.12, 5, 300, 300, NOW(), '{UUID_PM}', NOW(), NOW()),
  ('{UUID_TASK_9}', '{UUID_PROJ}', 'DSP-9', '{UUID_STORY_4}', '{UUID_REQ_2}', '{UUID_SPRINT_1}', 'TASK', 'CODE', 'Formulate integer linear programming constraints in OR-Tools', 'Encode precedence constraints and developer capacity bounds in CP-SAT solver.', 'IN_PROGRESS', 'CRITICAL', '{UUID_DEV}', 1.21, 8, 480, 240, NULL, NULL, NOW(), NOW()),
  ('{UUID_TASK_10}', '{UUID_PROJ}', 'DSP-10', '{UUID_STORY_4}', '{UUID_REQ_2}', NULL, 'TASK', 'CODE', 'Implement plan proposal diff preview and diagnostics', 'Build UI and API endpoints for previewing schedule shifts.', 'TO_DO', 'HIGH', NULL, 1.22, 5, 300, 0, NULL, NULL, NOW(), NOW()),
  ('{UUID_TASK_11}', '{UUID_PROJ}', 'DSP-11', '{UUID_STORY_5}', '{UUID_REQ_3}', '{UUID_SPRINT_1}', 'TASK', 'CODE', 'Build Kanban 4-column board with drag and keyboard navigation', 'Implement columns (TODO, IN_PROGRESS, IN_REVIEW, DONE) with WCAG focus management.', 'IN_PROGRESS', 'HIGH', '{UUID_DEV}', 2.11, 5, 300, 180, NULL, NULL, NOW(), NOW()),
  ('{UUID_TASK_12}', '{UUID_PROJ}', 'DSP-12', '{UUID_STORY_5}', '{UUID_REQ_3}', '{UUID_SPRINT_1}', 'TASK', 'CODE', 'Integrate PM Acceptance dialog with Quality Gate evaluation', 'Enforce PM-only acceptance with automated validation of CI, review, and criteria.', 'IN_REVIEW', 'CRITICAL', '{UUID_DEV}', 2.12, 8, 480, 420, NULL, NULL, NOW(), NOW()),
  ('{UUID_TASK_13}', '{UUID_PROJ}', 'DSP-13', '{UUID_STORY_6}', '{UUID_REQ_4}', '{UUID_SPRINT_1}', 'TASK', 'CODE', 'Create GitHub webhook ingestion and CI run persistence', 'Process check_suite and pull_request webhook payloads.', 'DONE', 'MEDIUM', '{UUID_QA}', 2.21, 3, 180, 180, NOW(), '{UUID_PM}', NOW(), NOW()),
  ('{UUID_TASK_14}', '{UUID_PROJ}', 'DSP-14', '{UUID_STORY_6}', '{UUID_REQ_4}', '{UUID_SPRINT_1}', 'TASK', 'CODE', 'Implement quality gate criteria checks (CI, Review, Acceptance)', 'Aggregate quality gate status across CI runs and reviews.', 'IN_REVIEW', 'HIGH', '{UUID_QA}', 2.22, 5, 300, 240, NULL, NULL, NOW(), NOW())
ON CONFLICT (project_id, item_key) DO UPDATE SET
  status = EXCLUDED.status,
  completed_at = EXCLUDED.completed_at,
  accepted_by = EXCLUDED.accepted_by;

-- 7. Seed Dependencies
INSERT INTO work_item_dependencies (id, project_id, predecessor_id, successor_id, link_type, lag_minutes)
VALUES
  (gen_random_uuid(), '{UUID_PROJ}', '{UUID_TASK_7}', '{UUID_TASK_8}', 'FINISH_TO_START', 0),
  (gen_random_uuid(), '{UUID_PROJ}', '{UUID_TASK_9}', '{UUID_TASK_10}', 'FINISH_TO_START', 0),
  (gen_random_uuid(), '{UUID_PROJ}', '{UUID_TASK_11}', '{UUID_TASK_12}', 'FINISH_TO_START', 0),
  (gen_random_uuid(), '{UUID_PROJ}', '{UUID_TASK_13}', '{UUID_TASK_14}', 'FINISH_TO_START', 0)
ON CONFLICT (predecessor_id, successor_id) DO NOTHING;

-- 8. Seed Acceptance Criteria for DSP-12 (Quality Gate pass)
INSERT INTO acceptance_criteria (id, project_id, work_item_id, title, description, status, created_at, updated_at)
VALUES
  (gen_random_uuid(), '{UUID_PROJ}', '{UUID_TASK_12}', 'All automated CI checks must pass', 'GitHub Actions workflow runs to completion with SUCCESS.', 'VERIFIED', NOW(), NOW()),
  (gen_random_uuid(), '{UUID_PROJ}', '{UUID_TASK_12}', 'Peer code review must be approved', 'Human code review approved by Lead Developer or PM.', 'VERIFIED', NOW(), NOW())
ON CONFLICT DO NOTHING;

-- 9. Seed GitHub Integration & Quality Gate Evidence for DSP-12
INSERT INTO github_repositories (id, workspace_id, project_id, github_installation_id, github_repo_id, full_name, default_branch, private, active)
VALUES
  ('{UUID_REPO}', '{UUID_WS}', '{UUID_PROJ}', 10001, 20002, 'devsphere/platform', 'main', true, true)
ON CONFLICT (github_repo_id) DO NOTHING;

INSERT INTO github_activities (id, project_id, repository_id, work_item_id, kind, github_object_id, pr_number, pr_head_sha, actor_login, state, title, happened_at)
VALUES
  (gen_random_uuid(), '{UUID_PROJ}', '{UUID_REPO}', '{UUID_TASK_12}', 'PULL_REQUEST', 'pr-42', 42, '{PR_HEAD_SHA}', 'alexdev', 'OPEN', 'feat(quality-gate): PM Acceptance Dialog and criteria validator', NOW() - INTERVAL '2 hours')
ON CONFLICT (repository_id, kind, github_object_id) DO NOTHING;

INSERT INTO ci_runs (id, project_id, repository_id, work_item_id, github_run_id, run_attempt, head_sha, pr_number, status, conclusion, started_at, completed_at)
VALUES
  (gen_random_uuid(), '{UUID_PROJ}', '{UUID_REPO}', '{UUID_TASK_12}', 987654321, 1, '{PR_HEAD_SHA}', 42, 'COMPLETED', 'SUCCESS', NOW() - INTERVAL '1 hour', NOW() - INTERVAL '50 minutes')
ON CONFLICT (repository_id, github_run_id, run_attempt) DO NOTHING;

INSERT INTO code_reviews (id, project_id, repository_id, work_item_id, pr_number, head_sha, context_version, scope, reviewer_kind, reviewer_id, status, summary, completed_at)
VALUES
  (gen_random_uuid(), '{UUID_PROJ}', '{UUID_REPO}', '{UUID_TASK_12}', 42, '{PR_HEAD_SHA}', 0, 'PR', 'HUMAN', '{UUID_PM}', 'PASS', 'Thorough validation of PM acceptance flow with full test coverage.', NOW() - INTERVAL '30 minutes')
ON CONFLICT DO NOTHING;

-- 10. Seed Plan Proposal (Solved CP-SAT proposal ready for review)
INSERT INTO plan_proposals (
  id, project_id, kind, base_project_version, status, solver_status,
  proposal_payload, solver_details, created_by, created_at, updated_at
)
VALUES (
  '{UUID_PROPOSAL_1}',
  '{UUID_PROJ}',
  'AUTO_REPLAN',
  0,
  'PENDING_APPROVAL',
  'OPTIMAL',
  '{{"schemaVersion": "1", "proposedSchedule": [{{"itemKey": "DSP-9", "start": "{sprint_start.isoformat()}", "finish": "{today.isoformat()}"}}, {{"itemKey": "DSP-10", "start": "{today.isoformat()}", "finish": "{(today + timedelta(days=5)).isoformat()}"}}]}}'::jsonb,
  '{{"schemaVersion": 1, "makespanDays": 12, "optimal": true, "solverIterations": 42}}'::jsonb,
  '{UUID_PM}',
  NOW(),
  NOW()
)
ON CONFLICT (project_id, id) DO NOTHING;

COMMIT;
"""
    return sql


class SeedApiClient:
    """Helper client using standard library to interact with backend REST API."""

    def __init__(self, base_url):
        self.base_url = base_url.rstrip("/")
        self.cj = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.cj))
        self.csrf_token = None
        self.csrf_header = "X-CSRF-TOKEN"
        self.access_token = None

    def fetch_csrf(self):
        req = urllib.request.Request(f"{self.base_url}/api/v1/auth/csrf")
        req.add_header("Accept", "application/json")
        try:
            with self.opener.open(req, timeout=5) as resp:
                data = json.loads(resp.read().decode())
                self.csrf_token = data.get("token")
                self.csrf_header = data.get("headerName") or "X-CSRF-TOKEN"
                return self.csrf_token
        except Exception:
            return None

    def post_json(self, path, payload):
        if not self.csrf_token:
            self.fetch_csrf()
        url = f"{self.base_url}{path}"
        data = json.dumps(payload).encode("utf-8")
        req = urllib.request.Request(url, data=data, method="POST")
        req.add_header("Content-Type", "application/json")
        req.add_header("Accept", "application/json")
        if self.csrf_token:
            req.add_header(self.csrf_header, self.csrf_token)
        if self.access_token:
            req.add_header("Authorization", f"Bearer {self.access_token}")
        with self.opener.open(req, timeout=8) as resp:
            content = resp.read().decode()
            return json.loads(content) if content else {}

    def login(self, email, password):
        try:
            data = self.post_json("/api/v1/auth/login", {"email": email, "password": password})
            self.access_token = data.get("accessToken")
            return self.access_token
        except urllib.error.HTTPError as e:
            return None


def main():
    parser = argparse.ArgumentParser(description="DevSphere Development Seed Data Generator")
    parser.add_argument("--backend-url", default=os.getenv("DEVSPHERE_BACKEND_URL", "http://localhost:8080"), help="Backend REST URL")
    parser.add_argument("--sql-output", default="scripts/seed-dev-data.sql", help="Path to write SQL seed file")
    parser.add_argument("--sql-only", action="store_true", help="Only generate SQL script without calling REST API")
    args = parser.parse_args()

    print("[DevSphere] Generating development seed dataset...")
    sql_content = generate_sql_script()

    # Always write out the SQL script for docker/psql usage
    output_path = os.path.abspath(args.sql_output)
    os.makedirs(os.path.dirname(output_path), exist_ok=True)
    with open(output_path, "w", encoding="utf-8") as f:
        f.write(sql_content)
    print(f"[DevSphere] Saved SQL seed script to: {output_path}")

    if args.sql_only:
        print("[DevSphere] Completed in --sql-only mode.")
        return 0

    # Probe backend API connectivity
    client = SeedApiClient(args.backend_url)
    csrf = client.fetch_csrf()
    if not csrf:
        print(f"[DevSphere] Backend at {args.backend_url} is not currently responding.")
        print("[DevSphere] Note: The SQL seed script was written and can be applied directly to Postgres:")
        print(f"  psql -h localhost -p 54329 -U devsphere_app -d devsphere -f {output_path}")
        return 0

    print(f"[DevSphere] Backend reachable! CSRF token obtained.")
    # Try login with seeded admin credentials
    token = client.login("admin@devsphere.io", "DevSphereAdmin2026!")
    if token:
        print("[DevSphere] Successfully authenticated as admin@devsphere.io via REST API.")
    else:
        print("[DevSphere] Admin user not yet authenticated via REST. Database may need SQL initialization first.")
        print(f"  Run: psql -h localhost -p 54329 -U devsphere_app -d devsphere -f {output_path}")

    print("[DevSphere] Seed data script completed successfully.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

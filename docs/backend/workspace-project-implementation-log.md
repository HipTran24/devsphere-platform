# Technical Implementation Log: Workspace & Project Lifecycle (Group 7)

## Branch: `feature/workspace-project` (Delivery Plan: `codex/m1-workspace-project`)
- **Scope**: Item 7 in `docs/backend/delivery-plan.md` and DevSphere Specification v1.1 Part H.
- **Key Modules**:
  1. `WorkspaceService`:
     - Atomic creation of `workspaces` (status `SETUP_INCOMPLETE`) and `workspace_members` (role `OWNER`, status `ACTIVE`) within a single database transaction, satisfying deferred trigger `ds_check_workspace_owner()`.
     - Slug format validation (`^[a-z0-9]+(-[a-z0-9]+)*$`, 3..80 chars) and 409 `DUPLICATE_RESOURCE` rejection on conflicts.
     - Strictly scoped pagination/listing by caller's active memberships.
     - Optimistic locking checks with 409 `PLAN_STALE` on version mismatch.
     - Diagnostic setup facts endpoint (`GET /api/v1/workspaces/{id}/setup`).
  2. `ProjectService`:
     - 1-Project-Per-Workspace invariant enforced by `UNIQUE(workspace_id)` on `projects`, returning 409 `DUPLICATE_RESOURCE` on sequential or concurrent duplicate attempts.
     - Creation in `PLANNING` status with optional `selfAssignPm: true` granting active `PM` role with `accepted_at` set and transitioning workspace to `ACTIVE`.
     - Role separation (TC-28): Workspace OWNER cannot update operational fields without active PM role.
     - Deadline protection (Part H3): Generic project PATCH cannot update `committedDeadline`, rejecting with 409 `CLIENT_DEADLINE_CONFIRMATION_REQUIRED`.
     - Project activation (TC-31 & `ds_project_activation`): Requires valid timezone, non-blank `objective`, `planned_start`, at least 1 non-deleted work item in `work_items`, and exactly 1 eligible PM. Missing preconditions return 422 `ACTIVATION_PRECONDITION_FAILED`.
  3. `WorkspaceController` & `ProjectController`:
     - 10 REST endpoints guarded by `AccessPolicy` and Spring Security.
     - Standardized RFC 7807/9457 `ProblemDetail` responses.
  4. `SessionSecurityConfiguration`:
     - Added `/api/v1/workspaces/**` to `BEARER_ENDPOINTS`.
     - Added cookie CSRF exemptions for `POST /api/v1/workspaces/**` and `PATCH /api/v1/workspaces/**`.
  5. `workspace-project.openapi.yaml`: OpenAPI 3.1 specification for all 10 endpoints.
  6. Frontend `WorkspaceHome.jsx`: Wired to backend `GET /api/v1/workspaces` with workspace creation dialog and status/role badges.

## Verification Pipeline (All 6 Gates Passed 100%):
1. **Backend Verify (JDK 27)**: 245/245 tests passed, 0 failures, 0 errors, 0 skipped.
2. **Frontend Unit Tests**: 12/12 tests passed, 0 skipped.
3. **Frontend Lint**: 0 warnings, 0 errors.
4. **Frontend Build**: Next.js production build succeeded, 11 static pages generated.
5. **Frontend E2E**: 14/14 tests passed (Playwright desktop & mobile).
6. **Python AI Workflow**: 35/35 tests passed.

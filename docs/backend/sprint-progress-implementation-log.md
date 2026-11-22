# Technical Implementation Log: Sprint Planning, Lifecycle & Closure Snapshot (Group 11)

## Branch: `feature/sprint-progress` (Delivery Plan: `codex/m1-sprint-progress`)
- **Scope**: Item 11 in `docs/backend/delivery-plan.md` and DevSphere Specification v1.1 Part H.
- **Key Modules**:
  1. `SprintService`:
     - Creates sprints with validated chronological dates (`endDate > startDate`).
     - Starts sprint with initial `committed_points` calculation from assigned work items.
     - Single active sprint invariant: enforced via DB unique partial index `uq_project_active_sprint` and application check (409 `ACTIVE_SPRINT_EXISTS`).
     - Assigns/removes backlog items to/from `PLANNED` or `ACTIVE` sprints. Rejects assignments to `CLOSED` sprints (409 `SPRINT_CLOSED`).
     - Frozen DONE item protection: trigger `ds_work_item_guard()` and `ds_done_scope()` prevent unlinking or moving `DONE` items (409 `SCOPE_LOCKED`).
     - Closes sprint: database trigger `ds_sprint_close()` stamps `closed_at`, computes `completed_points = sum(story_points)` of `DONE` items, kicks incomplete items back to backlog (`sprint_id = NULL`), and enforces immutability of closed sprint metrics and dates.
  2. `SprintProgressService`:
     - Computes real-time progress metrics: committed points, completed points, remaining points, status distribution (`TO_DO`, `IN_PROGRESS`, `IN_REVIEW`, `DONE`), time tracking (estimate, remaining, spent minutes), and blocked items count.
  3. `SprintController`:
     - 8 REST endpoints under `/api/v1/workspaces/{wId}/projects/{pId}/sprints` with RFC 7807 `ProblemDetail` handlers.
  4. `sprint-progress.openapi.yaml`: OpenAPI 3.1 specification for all 8 endpoints.

## Verification Pipeline (All 6 Gates Passed 100%):
1. **Backend Verify (JDK 27)**: 348/348 tests passed, 0 failures, 0 errors, 0 skipped.
2. **Frontend Unit Tests**: 12/12 tests passed, 0 skipped.
3. **Frontend Lint**: 0 warnings, 0 errors.
4. **Frontend Build**: Next.js production build succeeded, 11 static pages generated.
5. **Frontend E2E**: 14/14 tests passed (Playwright desktop & mobile).
6. **Python AI Workflow**: 35/35 tests passed.

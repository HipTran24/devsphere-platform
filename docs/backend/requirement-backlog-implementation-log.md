# Technical Implementation Log: Requirements, Backlog & DAG Dependencies (Groups 9 & 10)

## Branch: `feature/requirement-backlog` (Delivery Plan: `codex/m1-requirement-backlog`)
- **Scope**: Items 9 & 10 in `docs/backend/delivery-plan.md` and DevSphere Specification v1.1 Part H.
- **Key Modules**:
  1. `RequirementService`:
     - Monotonic requirement coding `REQ-<seq>`, initial version 1 (`DRAFT`).
     - Approval stamps `approved_at = now()`, trigger `immutable_approved_requirement` (`ds_requirement_version_guard()`) enforces complete immutability on approved versions.
     - Editing approved requirements appends a new monotonic version (`version_no = current_version + 1`) with `change_reason`, preserving prior approved versions.
  2. `ChangeRequestService`:
     - Client or PM can submit CRs (`kind='CHANGE_REQUEST'`). Scoped visibility: client sees only their own; PM sees all project CRs.
     - TC-33 Invariant: Approving a CR creates a draft proposal in `plan_proposals(kind='MANUAL_REPLAN', status='DRAFT', solver_status='NOT_RUN')`, preserving official requirements and project baseline without mutations.
  3. `WorkItemService`:
     - Monotonic key generation `${projectKey}-${seq}` via trigger `ds_work_item_key()`.
     - Hierarchy validation via trigger `ds_work_item_guard()`: `EPIC` -> `USER_STORY` -> `TASK`/`BUG` -> `SUBTASK`. Rejects circular ancestry (409 `HIERARCHY_CYCLE`) and cross-project parents.
     - Assignee validation: assignee must hold active `DEVELOPER` role on the project (400 `ASSIGNEE_NOT_DEVELOPER`).
     - State machine: `TO_DO` -> `IN_PROGRESS` -> `IN_REVIEW` -> `DONE`. Transition to `DONE` requires PM acceptance.
     - Frozen DONE: modifications to core scope fields or deleting a `DONE` item are rejected (409 `SCOPE_LOCKED`).
     - Soft delete (`deleted_at = now()`).
  4. `DependencyService`:
     - Validates same-project dependency links.
     - Detects and prevents dependency cycles via trigger `dependency_dag` (`ds_dependency_dag()`), returning 409 `DEPENDENCY_CYCLE`. Rejects self-dependency.
  5. `AcceptanceCriterionService`:
     - Multi-criterion support with weights and evidence kinds (`CI_TEST`, `PR`, `FILE`, `MANUAL`).
     - PM verification required for criteria status transitions.
  6. `RequirementController`, `ChangeRequestController`, `WorkItemController`, `DependencyController`, `AcceptanceCriterionController`:
     - 18 REST endpoints with RFC 7807 `ProblemDetail` handlers.
  7. `requirement-backlog.openapi.yaml`: OpenAPI 3.1 specification for all 18 endpoints.

## Verification Pipeline (All 6 Gates Passed 100%):
1. **Backend Verify (JDK 27)**: 326/326 tests passed, 0 failures, 0 errors, 0 skipped.
2. **Frontend Unit Tests**: 12/12 tests passed, 0 skipped.
3. **Frontend Lint**: 0 warnings, 0 errors.
4. **Frontend Build**: Next.js production build succeeded, 11 static pages generated.
5. **Frontend E2E**: 14/14 tests passed (Playwright desktop & mobile).
6. **Python AI Workflow**: 35/35 tests passed.

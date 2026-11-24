# DevSphere Milestone 7 (Week 7) Execution Plan
## Full-Stack Real Integration: M1-M3 Core UI & Real API Adapter

**Target Release Branch:** `feature/m7-fullstack-release`  
**Execution Model:** Sequential 4-Worker Swarm Pipeline + Independent Verification Gates  
**Status:** APPROVED FOR IMPLEMENTATION  

---

## 1. Executive Summary & Objective

In Milestones 1 through 6, DevSphere engineered a robust, battle-tested Spring Boot backend with 36 database tables, 36 JPA entities, 10 Flyway migrations (`V001..V010`), 841 automated tests, hardened RBAC, Argon2id/JWT/refresh-cookie session management, CP-SAT solver integration, and AWS S3/Quartz outbox infrastructure. In parallel, Milestone 6 established a baseline frontend with WCAG 2.1 AA accessibility, comprehensive UI states, and mock authentication.

**Milestone 7 Objective:** Bridge the backend and frontend into an integrated, production-grade full-stack platform:
1. **Real API Adapter & Authentication Lifecycle:** Remove mock-only limitations while preserving non-breaking mock fallbacks. Implement Next.js proxy rewrites, CSRF exchange (`GET /api/v1/auth/csrf`), stateless JWT Bearer token management with automatic silent refresh on expiration/401, HttpOnly SameSite Lax refresh cookies, and a comprehensive development seed dataset.
2. **Backlog & Requirements UI (Milestone 1 Core UI):** Full-featured backlog at `/workspaces/[id]/backlog` and `/p/[key]/backlog` with Epics, Stories, Tasks, Subtasks hierarchy, interactive Dependency DAG, status badges, and CRUD modals.
3. **Sprint Board & Kanban UI (Milestone 2 Core UI):** Dynamic Scrum/Kanban board at `/p/[key]/sprints` with columns (`TODO`, `IN_PROGRESS`, `IN_REVIEW`, `DONE`), task drawer, GitHub PR linkages, real-time Quality Gate validation, and the PM acceptance flow (`IN_REVIEW` -> `DONE`).
4. **Gantt Chart & Plan Approval UI (Milestone 3 Core UI):** Interactive Gantt timeline at `/p/[key]/gantt` and CP-SAT Solver proposal diff viewer at `/p/[key]/plan-approval` with before/after comparisons, milestone impact tracking, and required client/PM deadline confirmation dialogs for slipping schedules.

---

## 2. Core Architectural Invariants

Every worker must adhere strictly to the following system constraints:

1. **Database Schema & Entity Freeze:**
   - **CRITICAL INVARIANT:** `DevsphereApplicationTests.migratesThirtySixTablesAndValidatesEveryJpaEntity()` enforces exactly **36 tables, 36 JPA entities, and 10 Flyway migrations (`V001..V010`)**.
   - **NEVER** add `V011__*.sql` or create new `@Entity` classes.
   - Any seed data or dev fixtures must use existing repositories, services, or SQL scripts/APIs without modifying the migration catalog.
2. **Backward-Compatible Backend APIs:**
   - Backend controllers already expose all required REST endpoints (`SessionController`, `WorkspaceController`, `ProjectController`, `RequirementController`, `WorkItemController`, `DependencyController`, `SprintController`, `QualityGateController`, `PlanProposalController`).
   - Do not break existing method signatures, constructor contracts, or JUnit tests.
3. **Dual-Mode Frontend Adapter (Real API + Mock Fallback):**
   - In production or when connected to a running backend (`NEXT_PUBLIC_AUTH_MOCK=false`), frontend communicates with real backend endpoints via `/api/...` proxied through Next.js.
   - In offline test environments (e.g. standalone Playwright with `NEXT_PUBLIC_AUTH_MOCK=true`), the adapter must seamlessly fallback to demo/mock data so existing test suites remain 100% green without external dependencies.
4. **Security & Session Hygiene:**
   - Tokens must never be stored in `localStorage` where they are vulnerable to XSS. Access tokens live in memory with automatic silent refresh using HttpOnly `__Secure-devsphere-refresh` cookies.
   - State-changing auth requests (`login`, `refresh`, `logout`) must supply the `X-CSRF-TOKEN` header and `__Host-devsphere-csrf` cookie.
   - Zero hardcoded secrets or credentials in git. Never log or leak passwords or auth tokens.
5. **Accessibility & Code Quality:**
   - WCAG 2.1 AA compliance: all interactive dialogs must use `useFocusTrap` with Escape to close, all lists must support keyboard arrows via `useKeyboardList`, semantic headings and landmarks (`<main id="main-content">`, `<nav>`, `<aside>`), and polite/assertive ARIA live regions for async states.
   - ESLint 9: zero errors, zero warnings (`--max-warnings=0`).
   - React 19 / Next.js 16 App Router compliance: proper `'use client'` boundaries and clean hooks dependencies.

---

## 3. Worker Breakdown & Branching Pipeline

```
main (e8ae1e6)
  └── feature/m6-operations-release
        └── feature/m7-fullstack-release (Coordinator baseline)
              │
              ├── [Worker 1] feature/m7-backend-integration
              │     └── Real API Client, Next.js Proxy, Token Lifecycle, Seed Data
              │
              ├── [Worker 2] feature/m7-backlog-requirements-ui
              │     └── Backlog, Epics/Stories Hierarchy, Dependency DAG, CRUD Modals
              │
              ├── [Worker 3] feature/m7-sprint-kanban-ui
              │     └── Sprint Kanban, Task Drawer, PM Acceptance, Quality Gate Badges
              │
              └── [Worker 4] feature/m7-gantt-plan-approval-ui
                    └── Gantt Chart, CP-SAT Proposal Diff, Deadline Confirmation Dialog
```

---

### Worker 1: Real API Client, Next.js Proxy Rewrite & Seed Data
- **Worker Name:** `backend-integration`
- **Target Branch:** `feature/m7-backend-integration` (branched from `feature/m7-fullstack-release`)
- **Target Commit Count:** ~16-20 atomic conventional commits (`feat(api): ...`, `feat(auth): ...`, `test(api): ...`)

#### Scope & Deliverables
1. **Next.js Proxy Rewrite (`frontend/next.config.js`):**
   - Configure Next.js rewrites to proxy `/api/:path*` to `process.env.DEVSPHERE_BACKEND_URL || 'http://localhost:8080/api/:path*'`.
   - Preserve existing `distDir: process.env.NEXT_DIST_DIR || '.next'`.
2. **Spring Boot CORS & Filter Alignment (`SessionSecurityConfiguration.java`):**
   - Verify and ensure CORS allows `http://localhost:3000` (and `http://127.0.0.1:3000`, `http://127.0.0.1:3200`) with `allowCredentials=true`, headers `Authorization`, `X-CSRF-TOKEN`, `Content-Type`, `Accept`.
   - Ensure `SessionBearerAuthenticationFilter` and CSRF ignore-matchers seamlessly support frontend requests.
3. **Real API Client & Token Lifecycle (`frontend/src/lib/api-client.js` & `frontend/src/features/auth/api.js`):**
   - CSRF Exchange: fetch and cache CSRF token from `GET /api/v1/auth/csrf` (`headerName`, `token`).
   - Login: `POST /api/v1/auth/login` with credentials and CSRF header, setting `__Secure-devsphere-refresh` cookie and returning `{ accessToken, expiresAt }`.
   - Silent Refresh Interceptor: when an access token expires or an API call returns `401 Unauthorized`, invoke `POST /api/v1/auth/refresh` to get a new access token, then transparently retry the queued request.
   - Session Hydration: on initial page load, verify session via `GET /api/v1/auth/me` with Bearer token.
   - Logout: `POST /api/v1/auth/logout` clearing cookies and in-memory session.
   - Mock Mode Switch: if `NEXT_PUBLIC_AUTH_MOCK=true` or backend is unreachable, gracefully fall back to existing mock responses so offline tests never fail.
   - Domain API Client Functions:
     * `fetchWorkspaces()`, `createWorkspace({ name, slug, description })`, `getWorkspace(id)`, `getWorkspaceSetup(id)`
     * `fetchProjects(workspaceId)`, `getProject(workspaceId, projectId)`, `createProject(...)`
     * `fetchRequirements(workspaceId, projectId)`
     * `fetchWorkItems(workspaceId, projectId, { sprintId, type, status })`, `fetchBacklog(workspaceId, projectId)`
     * `createWorkItem(...)`, `updateWorkItem(...)`, `transitionWorkItemStatus(...)`, `blockWorkItem(...)`
     * `fetchDependencies(workspaceId, projectId)`, `createDependency(...)`, `deleteDependency(...)`
     * `fetchSprints(workspaceId, projectId)`, `getSprintProgress(...)`
     * `fetchQualityGate(workspaceId, projectId, workItemId)`
     * `fetchPlanProposals(...)`, `previewPlanProposal(...)`, `solvePlanProposal(...)`, `approvePlanProposal(...)`, `confirmDeadline(...)`, `applyPlanProposal(...)`
4. **Seed Data Generator (`scripts/seed-dev-data.py` & dev runner):**
   - Python script communicating with the backend REST API (or direct DB via existing models) to populate a complete demo workspace:
     * Users:
       - System Admin: `admin@devsphere.io` / `DevSphereAdmin2026!`
       - Workspace PM: `pm@devsphere.io` / `DevSpherePm2026!`
       - Lead Developer: `dev@devsphere.io` / `DevSphereDev2026!`
       - QA Engineer: `qa@devsphere.io` / `DevSphereQa2026!`
     * Workspace: `DevSphere Core` (`devsphere-core`)
     * Project: `DevSphere Platform` (Key: `DSP`)
     * Requirements: 4 realistic items (REQ-1: Multi-tenant Auth, REQ-2: CP-SAT Scheduler, REQ-3: Real-time Kanban, REQ-4: Code Quality Gate).
     * Backlog Work Items:
       - 2 Epics (`DSP-1`, `DSP-2`)
       - 4 Stories (`DSP-3` to `DSP-6`)
       - 8 Tasks & Subtasks across statuses: `TO_DO`, `IN_PROGRESS`, `IN_REVIEW`, `DONE`.
       - Dependencies (e.g., Task `DSP-5` depends on `DSP-4`).
     * Sprint 1 ("Foundations & Core Engine"): active sprint with tasks in each column.
     * Quality Gate evidence for an `IN_REVIEW` task (`DSP-5` has CI PASSED, Review APPROVED, Criteria MET).
     * Solved Plan Proposal with CP-SAT output and milestone impact ready for preview and approval.
5. **Update Workspace Home Card Interaction (`frontend/src/features/auth/components/WorkspaceHome.jsx`):**
   - Make workspace cards interactive buttons/links navigating to `/workspaces/${ws.id}/backlog` or `/p/${projectKey}/backlog`.
6. **Tests:**
   - `frontend/tests/api-client.test.js`: token lifecycle, CSRF caching, error translation, retry queue.
   - Ensure `npm test`, `npm run lint`, `npm run build` pass.

#### Allowed Files for Worker 1
- `frontend/next.config.js`
- `frontend/src/lib/api-client.js`
- `frontend/src/features/auth/api.js`
- `frontend/src/features/auth/components/AuthProvider.jsx`
- `frontend/src/features/auth/components/WorkspaceHome.jsx`
- `frontend/tests/api-client.test.js`
- `scripts/seed-dev-data.py`
- `src/main/java/com/project/devsphere/identity/api/SessionSecurityConfiguration.java` (only if CORS/filter adjustments needed)
- `src/main/resources/application.properties` (CORS settings if needed)

#### Excluded Files for Worker 1
- `src/main/resources/db/migration/**` (STRICT FREEZE)
- `frontend/src/features/backlog/**`, `frontend/src/features/sprints/**`, `frontend/src/features/planning/**` (owned by Workers 2-4)
- `.env`

---

### Worker 2: Backlog & Requirements UI
- **Worker Name:** `backlog-coder`
- **Target Branch:** `feature/m7-backlog-requirements-ui` (branched from `feature/m7-backend-integration`)
- **Target Commit Count:** ~18-22 atomic conventional commits (`feat(backlog): ...`, `test(backlog): ...`)

#### Scope & Deliverables
1. **Routing & Pages:**
   - `frontend/src/app/workspaces/[id]/backlog/page.jsx`
   - `frontend/src/app/p/[key]/backlog/page.jsx`
   - Dynamic project resolution: load project key or workspace ID and hydrate context.
2. **Project Navigation Bar (`frontend/src/features/project/components/ProjectNavBar.jsx`):**
   - Sub-header tabs with active indicator: Backlog (`/p/[key]/backlog`), Sprints (`/p/[key]/sprints`), Gantt (`/p/[key]/gantt`), Plan Approval (`/p/[key]/plan-approval`).
   - Project breadcrumbs: `Workspaces > [Workspace Name] > [Project Name] ([KEY])`.
3. **Backlog Hierarchy Tree & Table (`frontend/src/features/backlog/components/WorkItemTree.jsx` & `WorkItemRow.jsx`):**
   - Visual multi-level hierarchy: `EPIC` -> `USER_STORY` -> `TASK` -> `SUBTASK`.
   - Collapsible chevron rows with child count badges.
   - Item row attributes:
     * Item Key badge (`DSP-101`)
     * Title with inline edit or click to open detail
     * Type badge with icons (Epic = purple, Story = green, Task = blue, Bug = red)
     * Priority badge (`CRITICAL`, `HIGH`, `MEDIUM`, `LOW`)
     * Status pill (`TO_DO`, `IN_PROGRESS`, `IN_REVIEW`, `DONE`)
     * Assignee avatar/name
     * Story points / estimate minutes badge
     * Blocked flag (with reason tooltip)
4. **Interactive Dependency DAG Visualizer (`frontend/src/features/backlog/components/DependencyDag.jsx`):**
   - Graph/flow representation or dependency matrix showing Predecessor -> Successor relationships.
   - Displays link type (`FINISH_TO_START`), lag minutes, and flags critical blocking dependencies.
   - Add/Remove dependency actions with validation (prevents self-dependencies).
5. **Work Item Modals (`CreateWorkItemModal.jsx` & `EditWorkItemModal.jsx`):**
   - Create Work Item Modal:
     * Inputs: Title, Description, Type, Priority, Parent selector, Requirement selector, Assignee, Estimate, Labels.
     * Uses `useFocusTrap`, ARIA dialog attributes, validation error handling.
   - Detail / Edit Modal:
     * Full editing, status transitions, Acceptance Criteria list, Blocked reason toggle.
6. **Filter Toolbar:**
   - Search by text, filter by Type, Status, Assignee, Priority.
7. **Tests:**
   - `frontend/tests/backlog.test.js`: hierarchy tree nesting, dependency sorting, filter logic.
   - `frontend/tests/e2e/backlog.spec.js`: Playwright tests for viewing backlog, expanding/collapsing nodes, opening create dialog, filtering.

#### Allowed Files for Worker 2
- `frontend/src/app/workspaces/[id]/backlog/**`
- `frontend/src/app/p/[key]/backlog/**`
- `frontend/src/features/project/**`
- `frontend/src/features/backlog/**`
- `frontend/tests/backlog.test.js`
- `frontend/tests/e2e/backlog.spec.js`

#### Excluded Files for Worker 2
- `src/**` (Strictly read-only)
- `frontend/src/features/sprints/**`, `frontend/src/features/planning/**`
- `frontend/src/features/auth/**` (except importing client hooks)

---

### Worker 3: Sprint Board & Kanban UI
- **Worker Name:** `kanban-coder`
- **Target Branch:** `feature/m7-sprint-kanban-ui` (branched from `feature/m7-backlog-requirements-ui`)
- **Target Commit Count:** ~18-22 atomic conventional commits (`feat(kanban): ...`, `test(kanban): ...`)

#### Scope & Deliverables
1. **Routing & Pages:**
   - `frontend/src/app/p/[key]/sprints/page.jsx`
   - `frontend/src/app/workspaces/[id]/sprints/page.jsx`
2. **Sprint Header & Progress Metrics (`frontend/src/features/sprints/components/SprintHeader.jsx`):**
   - Sprint selector (switch between active and past sprints), Sprint Name, Goal, Date Range.
   - Progress bar: Completed vs Committed Story Points, remaining work items, total spent hours.
   - Sprint status badge (`ACTIVE`, `PLANNED`, `CLOSED`).
3. **Kanban Board (`frontend/src/features/sprints/components/SprintBoard.jsx` & `KanbanColumn.jsx`):**
   - 4 Columns: `TODO` (`TO_DO`), `IN_PROGRESS`, `IN_REVIEW`, `DONE`.
   - Column header with card counter and points tally.
   - Cards (`KanbanCard.jsx`):
     * Key, title, priority, assignee avatar, work kind badge (`CODE`/`NON_CODE`).
     * GitHub PR badge (`#42` with link/icon).
     * Quality Gate badge (`PASSED`, `FAILED`, `PENDING`).
     * Blocked status indicator (prominent warning border and tooltip).
     * Action menu: Move to Next/Previous column, Open Drawer.
4. **Slide-Over Task Drawer (`frontend/src/features/sprints/components/TaskDrawer.jsx`):**
   - Accessible slide-over drawer triggered on card click.
   - Detailed task information: Description, Acceptance Criteria checklist with check/uncheck persistence, Log time spent.
   - Block / Unblock action form with `blockedReason`.
5. **Quality Gate Badges & Details (`frontend/src/features/sprints/components/QualityGateBadge.jsx`):**
   - Calls `GET /api/v1/workspaces/{wId}/projects/{pId}/work-items/{id}/quality-gate`.
   - Displays status: Green check (`PASSED`), Red alert (`FAILED`), Yellow clock (`PENDING`).
   - Detailed flyout: CI Build status, Peer Review approval, Acceptance Criteria satisfaction, Blockers list.
6. **PM Acceptance Flow (`IN_REVIEW` -> `DONE`) (`PmAcceptanceDialog.jsx`):**
   - Enforce PM business rule: Only users with PM role can accept items from `IN_REVIEW` into `DONE`.
   - Before allowing acceptance, evaluate Quality Gate:
     * If `gate.passed == true`: Enable "PM Accept & Complete" button calling `POST /api/v1/.../work-items/{id}/accept`.
     * If `gate.passed == false`: Display prominent blocker alert with failed criteria. Provide "PM Reject" button calling `POST /api/v1/.../work-items/{id}/reject` with rejection note to move item back to `IN_PROGRESS` or `TO_DO`.
7. **Tests:**
   - `frontend/tests/sprints.test.js`: card column partitioning, progress computation, PM acceptance gate checks.
   - `frontend/tests/e2e/sprints.spec.js`: Playwright tests for viewing sprint board, dragging/moving cards, opening task drawer, checking quality gate badge.

#### Allowed Files for Worker 3
- `frontend/src/app/p/[key]/sprints/**`
- `frontend/src/app/workspaces/[id]/sprints/**`
- `frontend/src/features/sprints/**`
- `frontend/tests/sprints.test.js`
- `frontend/tests/e2e/sprints.spec.js`

#### Excluded Files for Worker 3
- `src/**` (Strictly read-only)
- `frontend/src/features/backlog/**`, `frontend/src/features/planning/**`

---

### Worker 4: Gantt Chart & Plan Approval UI
- **Worker Name:** `gantt-coder`
- **Target Branch:** `feature/m7-gantt-plan-approval-ui` (branched from `feature/m7-sprint-kanban-ui`)
- **Target Commit Count:** ~18-22 atomic conventional commits (`feat(gantt): ...`, `test(gantt): ...`)

#### Scope & Deliverables
1. **Routing & Pages:**
   - `frontend/src/app/p/[key]/gantt/page.jsx`
   - `frontend/src/app/p/[key]/plan-approval/page.jsx`
2. **Gantt Chart Component (`frontend/src/features/planning/components/GanttChart.jsx`):**
   - Timeline header: Days / Weeks horizontal grid with current date marker ("Today").
   - Sprint boundary overlay (highlighting Sprint 1, Sprint 2 windows).
   - Task schedule bars:
     * Bar position and width calculated from `startAt` and `finishAt`.
     * Progress fill (% complete).
     * Assignee name and task title on or alongside bar.
     * Critical path styling (tasks with 0 total slack).
   - Dependency connection lines: SVG arrows connecting predecessor bar end to successor bar start.
   - Milestone diamond markers with vertical deadline markers and slip warnings.
   - Zoom controls (Day, Week, Month views) and panning.
3. **CP-SAT Proposal Diff Before/After Viewer (`ProposalDiffViewer.jsx`):**
   - Executive solver proposal overview:
     * Status pill (`OPTIMAL`, `FEASIBLE`, `INFEASIBLE`).
     * Proposed task count, total hours, forecast finish timestamp.
     * Committed deadline comparison: Current Deadline vs Proposed Finish.
   - Schedule Before/After Diff Table:
     * Task-by-task comparison: Original Start/End vs Proposed Start/End.
     * Shift column showing delta in days/hours (`+2d slip` or `-1d advanced`).
   - Milestone Impact Table:
     * List of project milestones affected by solver replanning with buffer status.
4. **Plan Approval & Committed Deadline Confirmation Flow (`PlanApprovalDialog.jsx`):**
   - Two distinct execution paths matching backend business rules:
     * Path A (Non-slipping): When `changesCommittedDeadline == false`, provide a single-click "Approve & Apply Plan" action calling `POST /api/v1/.../plan-proposals/{id}/approve` then `POST .../apply`.
     * Path B (Slipping committed deadline): When `changesCommittedDeadline == true`, show high-priority warning:
       > "Warning: This schedule proposal moves the committed client deadline from [Current] to [Proposed]."
       Require PM/Client deadline confirmation checkbox before unlocking "Confirm Deadline & Apply", calling `POST /api/v1/.../plan-proposals/{id}/deadline-confirmation` then `POST .../apply`.
   - Optimistic concurrency control: include `expectedProjectVersion` to defend against concurrent updates.
5. **Shared Navigation Integration:**
   - Verify all project routes (`/p/[key]/backlog`, `/p/[key]/sprints`, `/p/[key]/gantt`, `/p/[key]/plan-approval`) share the unified `ProjectNavBar` with correct active tab indicators.
6. **Tests:**
   - `frontend/tests/planning.test.js`: Gantt coordinate calculations, schedule diff formatting, deadline slip detection.
   - `frontend/tests/e2e/planning.spec.js`: Playwright tests for viewing Gantt timeline, switching zoom, viewing proposal diff, triggering deadline confirmation dialog.

#### Allowed Files for Worker 4
- `frontend/src/app/p/[key]/gantt/**`
- `frontend/src/app/p/[key]/plan-approval/**`
- `frontend/src/features/planning/**`
- `frontend/tests/planning.test.js`
- `frontend/tests/e2e/planning.spec.js`

#### Excluded Files for Worker 4
- `src/**` (Strictly read-only)
- `frontend/src/features/backlog/**`, `frontend/src/features/sprints/**`

---

## 4. API Endpoints & DTO Contract Reference

| Domain | Method | Path | Request Body | Response Body |
|---|---|---|---|---|
| **Auth** | `GET` | `/api/v1/auth/csrf` | - | `CsrfResponse{headerName, parameterName, token}` |
| **Auth** | `POST` | `/api/v1/auth/login` | `LoginRequest{email, password}` | `AccessResponse{accessToken, expiresAt}` + Cookie `__Secure-devsphere-refresh` |
| **Auth** | `POST` | `/api/v1/auth/refresh` | Cookie `__Secure-devsphere-refresh` | `AccessResponse{accessToken, expiresAt}` + Rotated Cookie |
| **Auth** | `POST` | `/api/v1/auth/logout` | Cookie `__Secure-devsphere-refresh` | `204 No Content` + Cleared Cookie |
| **Auth** | `GET` | `/api/v1/auth/me` | Bearer Token | `UserPrincipalResponse{userId, sessionId, displayName, systemRole}` |
| **Workspaces** | `GET` | `/api/v1/workspaces` | Bearer Token | `WorkspacePageResponse<WorkspaceResponse>` |
| **Workspaces** | `POST` | `/api/v1/workspaces` | `CreateWorkspaceRequest{name, slug, description}` | `WorkspaceResponse{id, slug, name, status, role}` |
| **Projects** | `GET` | `/api/v1/workspaces/{wId}/projects` | Bearer Token | `List<ProjectResponse>` |
| **Projects** | `GET` | `/api/v1/workspaces/{wId}/projects/{pId}` | Bearer Token | `ProjectResponse{id, projectKey, name, ...}` |
| **Requirements** | `GET` | `/api/v1/workspaces/{wId}/projects/{pId}/requirements` | Bearer Token | `List<RequirementResponse>` |
| **WorkItems** | `GET` | `/api/v1/workspaces/{wId}/projects/{pId}/work-items` | Query: `sprintId`, `type`, `status` | `List<WorkItemResponse>` |
| **WorkItems** | `POST` | `/api/v1/workspaces/{wId}/projects/{pId}/work-items` | `CreateWorkItemRequest{title, type, ...}` | `WorkItemResponse` |
| **WorkItems** | `PATCH` | `/api/v1/workspaces/{wId}/projects/{pId}/work-items/{id}/status` | `TransitionStatusRequest{status, expectedVersion}` | `WorkItemResponse` |
| **WorkItems** | `POST` | `/api/v1/workspaces/{wId}/projects/{pId}/work-items/{id}/accept` | PM Bearer Token | `WorkItemResponse` (Status: `DONE`) |
| **WorkItems** | `POST` | `/api/v1/workspaces/{wId}/projects/{pId}/work-items/{id}/reject` | `RejectWorkItemRequest{notes, returnStatus}` | `WorkItemResponse` |
| **Dependencies** | `GET` | `/api/v1/workspaces/{wId}/projects/{pId}/dependencies` | Bearer Token | `List<WorkItemDependencyResponse>` |
| **Dependencies** | `POST` | `/api/v1/workspaces/{wId}/projects/{pId}/dependencies` | `CreateDependencyRequest{predecessorId, successorId, type, lagMinutes}` | `WorkItemDependencyResponse` |
| **Sprints** | `GET` | `/api/v1/workspaces/{wId}/projects/{pId}/sprints` | Bearer Token | `List<SprintResponse>` |
| **Sprints** | `GET` | `/api/v1/workspaces/{wId}/projects/{pId}/sprints/{id}/progress` | Bearer Token | `SprintProgressResponse{committedPoints, completedPoints, ...}` |
| **Quality Gate** | `GET` | `/api/v1/workspaces/{wId}/projects/{pId}/work-items/{id}/quality-gate` | Bearer Token | `QualityGateResponse{status, passed, ciPassed, reviewPassed, criteriaPassed, blockers}` |
| **Planning** | `GET` | `/api/v1/workspaces/{wId}/projects/{pId}/plan-proposals/{id}/preview` | Bearer Token | `PlanProposalPreviewResponse{solverStatus, changesCommittedDeadline, tasks, milestoneImpacts}` |
| **Planning** | `POST` | `/api/v1/workspaces/{wId}/projects/{pId}/plan-proposals/{id}/approve` | `ApprovePlanProposalRequest{expectedProjectVersion}` | `PlanProposalResponse` |
| **Planning** | `POST` | `/api/v1/workspaces/{wId}/projects/{pId}/plan-proposals/{id}/deadline-confirmation` | `DeadlineConfirmationRequest{expectedProjectVersion, notes}` | `DeadlineConsentResponse` |
| **Planning** | `POST` | `/api/v1/workspaces/{wId}/projects/{pId}/plan-proposals/{id}/apply` | `ApplyPlanRequest{expectedProjectVersion}` | `ApplyPlanResponse` |

---

## 5. Strict Verification Gates (Mandatory for Handoff)

Before authorizing any merge into `feature/m7-fullstack-release`, all 7 verification gates must PASS:

1. **Gate 1 — Backend JUnit & Verification:**
   ```sh
   JAVA_HOME=/Users/ProM2/Documents/devsphere/.tools/jdk-27/Contents/Home ./mvnw -B -Dmaven.repo.local=/Users/ProM2/Documents/devsphere/.tools/m2/repository verify
   ```
   *Requirement:* 841/841 PASS, 0 failures, 0 skipped.
2. **Gate 2 — Frontend Unit & Component Tests:**
   ```sh
   cd frontend && npm test
   ```
   *Requirement:* All unit tests PASS (including auth, session, ui-states, api-client, backlog, sprints, planning), 0 failures.
3. **Gate 3 — Frontend ESLint 9 Standards:**
   ```sh
   cd frontend && npm run lint
   ```
   *Requirement:* 0 errors, 0 warnings (`--max-warnings=0`).
4. **Gate 4 — Production Build Generation:**
   ```sh
   cd frontend && npm run build
   ```
   *Requirement:* All static and dynamic routes compiled successfully without syntax, type, or prerender errors.
5. **Gate 5 — Playwright End-to-End Suite:**
   ```sh
   cd frontend && npm run test:e2e
   ```
   *Requirement:* All E2E tests PASS across desktop and mobile Chrome viewports.
6. **Gate 6 — Python AI & Infrastructure Checks:**
   ```sh
   python3 -m unittest discover -s tests/ai -p "test_*.py"
   python3 -m unittest discover -s infra/scripts/tests -p "test_*.py"
   ```
   *Requirement:* 47/47 PASS.
7. **Gate 7 — Independent QA & Security Review:**
   *Requirement:* Read-only audit confirming zero schema mutations, zero hardcoded credentials, clean WCAG accessibility, and full PM acceptance/deadline consent enforcement.

---

## 6. Execution Sequence & Handoff Checklist

1. **Step 1:** Lead Planner creates plan artifact `docs/plans/m7-fullstack-plan.md` and posts summary to the Swarm Board.
2. **Step 2:** Coordinator activates **Worker 1 (`backend-integration`)** on branch `feature/m7-backend-integration`. Worker 1 finishes API client, Next.js proxy, seed script, and passes unit tests.
3. **Step 3:** Coordinator activates **Worker 2 (`backlog-coder`)** on branch `feature/m7-backlog-requirements-ui`. Worker 2 implements Backlog tree, Dependency DAG, CRUD modals, and tests.
4. **Step 4:** Coordinator activates **Worker 3 (`kanban-coder`)** on branch `feature/m7-sprint-kanban-ui`. Worker 3 implements Kanban board, task drawer, PM acceptance gate, and tests.
5. **Step 5:** Coordinator activates **Worker 4 (`gantt-coder`)** on branch `feature/m7-gantt-plan-approval-ui`. Worker 4 implements Gantt timeline, proposal diff viewer, deadline confirmation, and tests.
6. **Step 6:** QA & Security subagents perform final independent audit on consolidated `feature/m7-fullstack-release`.
7. **Step 7:** Coordinator merges and pushes to origin.

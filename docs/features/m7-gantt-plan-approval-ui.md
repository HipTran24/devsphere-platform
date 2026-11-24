# DevSphere Milestone 7: Gantt Timeline & Plan Approval UI Architecture

## 1. Overview
The Gantt Timeline & Plan Approval UI (`feature/m7-gantt-plan-approval-ui`) completes Milestone 3's core frontend interfaces for DevSphere. It equips engineering leaders and PMs with an interactive Gantt chart schedule viewer, algorithmic Critical Path Method (CPM) bottleneck highlighting, precedence dependency connectors, a CP-SAT solver before/after schedule diff viewer, and a mandatory client/PM deadline confirmation governance dialog for slipping project schedules.

## 2. Key Components & Architecture

### 1. Routing & Unified Navigation
- **Project Routes:** `/p/[key]/gantt` and `/p/[key]/plan-approval`.
- **Workspace Routes:** `/workspaces/[id]/gantt` and `/workspaces/[id]/plan-approval`.
- Integrated seamlessly with `ProjectNavBar` across all 4 project tabs: Backlog, Sprints, Gantt, and Plan Approval.
- Standardized UI state management with `LoadingState`, `ErrorState` with retry action, and `EmptyState`.

### 2. Gantt Timeline Chart (`GanttChart.jsx`, `GanttBar.jsx`)
- **Timeline Grid & Time Scales:**
  - View scale segmented toggle: Days, Weeks, and Sprints.
  - Sticky left column with work item key badge, title, and CPM indicator.
  - "Today" vertical reference marker line spanning the entire timeline height.
  - Sprint boundary tracks highlighting active and planned sprint envelopes.
- **Critical Path Method (CPM) Highlighting:**
  - Forward and backward pass algorithmic calculation of early start (ES), early finish (EF), late start (LS), late finish (LF), and total float (slack).
  - Tasks with `totalFloat === 0` highlighted in high-contrast red/amber with CPM badges.
  - Critical Path filter toggle: instant filter to display only bottleneck items.
- **Milestone Markers:**
  - Diamond-shaped markers (`transform: rotate(45deg)`) positioned at committed target dates.
  - Interactive tooltips showing milestone name, target date, and buffer status.
- **Precedence Dependency Connectors:**
  - SVG canvas overlay rendering finish-to-start dependency connector paths between predecessor finish edge and successor start edge.
  - Arrow markers at successor endpoints.
  - Critical path dependency links rendered with bold dashed red styling.
- **Zoom & Navigation:**
  - 3 zoom levels: Compact (20px bars), Normal (28px bars), Detailed (36px bars).

### 3. CP-SAT Proposal Before/After Diff Viewer (`ProposalDiffViewer.jsx`)
- **Executive Solver Overview:**
  - Solver status pill (`OPTIMAL`, `FEASIBLE`, `INFEASIBLE`).
  - Proposal switcher dropdown supporting multiple saved solver proposals.
  - Key metrics: Rescheduled task count, Total planned effort duration, Forecast finish timestamp, and Committed deadline comparison.
  - Slipping schedule warning banner when proposal shifts the committed deadline.
- **Schedule Before/After Comparison Table:**
  - Work item key and title.
  - Original baseline dates (`startAt` → `finishAt`) vs Proposed CP-SAT dates.
  - Shift delta badges: `+Xd delay` (red), `-Yd advanced` (green), `On time` (neutral).
  - Assignee change detection (e.g. Alex Dev → Quinn QA).
  - Filter controls: All, Shifted, Delayed, Earlier.
- **Milestone Impact Table:**
  - Lists project milestones affected by solver replanning.
  - Compares committed target date vs solver projected finish date.
  - Buffer status: `ON_SCHEDULE` vs `SLIPPING`.

### 4. Mandatory Deadline Confirmation Dialog (`DeadlineConfirmationDialog.jsx`)
- Accessible modal dialog using `useFocusTrap`, `Escape` to close, `role="dialog"`, and `aria-modal="true"`.
- **Dual Execution Paths:**
  - **Path A (Non-slipping):** When `changesCommittedDeadline == false`, enables a single-click "Approve & Apply Schedule" action.
  - **Path B (Slipping committed deadline):** When `changesCommittedDeadline == true`:
    - Displays high-priority warning alert: "Schedule Extension Requires PM/Client Consent".
    - Lists slipping milestones with delay amounts.
    - Gated by mandatory acknowledgment checkbox: "I explicitly acknowledge and confirm the committed client deadline shift...".
    - Rationale / notes input textarea.
    - Submit button remains strictly disabled until acknowledgment checkbox is verified.
- **Optimistic Concurrency Control:** Transmits `expectedProjectVersion` and `expectedProposalVersion` to backend endpoints to prevent race conditions.

## 3. Testing & Verification
- **Unit Tests (`frontend/tests/planning.test.js`):**
  - 13 comprehensive unit tests covering CPM forward/backward passes, total float calculation, sequential chain makespan, timeline boundary math, Days/Weeks/Sprints interval generation, coordinate percentage mapping, schedule diff computation, and deadline slip gating logic.
- **Playwright E2E Tests (`frontend/tests/e2e/planning.spec.js`):**
  - 20 Playwright tests across Desktop Chrome and Mobile viewports (390x844).
  - Tests verify Gantt chart rendering, view scale switching, CPM filter toggle, zoom switching, proposal diff viewing, proposal switching, slipping deadline confirmation gating, proposal rejection, cross-tab navigation, and workspace route rendering.
- **Package Scripts:**
  - `npm test`: Runs all 65 frontend unit tests.
  - `npm run test:planning`: Runs planning unit test suite.
  - `npm run test:e2e:planning`: Runs planning Playwright E2E suite.
  - `npm run lint`: Zero ESLint 9 errors or warnings.
  - `npm run build`: Production build compiling all dynamic App Router routes cleanly.

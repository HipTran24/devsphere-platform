# DevSphere Milestone 7: Sprint Board & Kanban UI Architecture

## 1. Overview
The Sprint Board & Kanban UI (`feature/m7-sprint-kanban-ui`) delivers Milestone 2's core frontend interfaces for DevSphere. It enables agile teams to visualize sprint work, track story points burndown, inspect automated Quality Gates, and execute governance-enforced PM acceptance sign-offs.

## 2. Key Components
- **SprintView (`frontend/src/features/sprints/components/SprintView.jsx`):**
  - Page orchestrator rendered at `/p/[key]/sprints` and `/workspaces/[id]/sprints`.
  - Integrates `ProjectNavBar` with active "Sprints" tab.
  - Handles loading, error with retry, and empty sprint states.
- **SprintHeader (`frontend/src/features/sprints/components/SprintHeader.jsx`):**
  - Sprint selector dropdown supporting active, planned, and closed sprints.
  - Sprint name, goal, and date range.
  - Story points burndown progress bar (`role="progressbar"` with ARIA percentages).
  - Metrics summary: completed vs committed points, remaining items, and total spent hours.
  - Embedded role simulator for PM / Developer / QA testing.
- **KanbanBoard & Columns (`SprintBoard.jsx`, `KanbanColumn.jsx`):**
  - 4 Kanban columns: `To Do` (`TO_DO`), `In Progress` (`IN_PROGRESS`), `In Review` (`IN_REVIEW`), and `Done` (`DONE`).
  - Column headers display card counts and story points tally badges.
- **KanbanCard (`KanbanCard.jsx`):**
  - Displays work item key, title, priority pill, work kind (`CODE`/`NON_CODE`), and assignee avatar.
  - GitHub PR badge (`#42`) and Quality Gate indicator.
  - Prominent blocked status border and reason banner.
  - Keyboard-accessible Next and Prev column transition buttons.
- **TaskDrawer (`TaskDrawer.jsx`):**
  - Slide-over panel with `useFocusTrap` and Escape key handling.
  - Interactive Acceptance Criteria checklist with state persistence.
  - Log Time Spent form with duration validation.
  - Block/Unblock action with mandatory reason enforcement.
- **QualityGateBadge & Flyout (`QualityGateBadge.jsx`):**
  - Status indicators: `PASSED` (green), `FAILED` (red), `PENDING` (yellow).
  - Interactive details flyout showing CI Build, Peer Review, Acceptance Criteria satisfaction, and active blockers list.
- **PmAcceptanceDialog (`PmAcceptanceDialog.jsx`):**
  - PM role enforcement: only PMs/Admins can transition items from `IN_REVIEW` to `DONE`.
  - Quality Gate audit: if PASSED, enables "PM Accept & Complete"; if FAILED/PENDING, disables acceptance, shows blocker alerts, and provides "PM Reject" flow returning task to `IN_PROGRESS`.

## 3. Testing & Verification
- **Unit Tests:** `frontend/tests/sprints.test.js` (11 unit tests covering partitioning, progress math, quality gate evaluation, role validation, duration formatting).
- **E2E Tests:** `frontend/tests/e2e/sprints.spec.js` (12 Playwright tests covering board rendering, column transitions, drawer, flyout, and PM acceptance flow across desktop and mobile Chrome viewports).
- **Scripts:** `npm test`, `npm run test:sprints`, `npm run test:e2e:sprints`, `npm run lint`, `npm run build`.

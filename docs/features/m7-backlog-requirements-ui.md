# DevSphere Backlog & Requirements UI (Milestone 7)

## Overview & Architecture

The **Backlog & Requirements UI** implements the primary project breakdown and scheduling view for DevSphere Milestone 7. It bridges the Spring Boot backend (`WorkItemController`, `DependencyController`, `RequirementController`, `ProjectController`) with Next.js 16 App Router frontend, maintaining dual-mode execution (stateless real API proxy with automatic offline mock fallback).

```
ProjectNavBar (/p/[key]/backlog, /p/[key]/sprints, /p/[key]/gantt, /p/[key]/plan-approval)
  ├── BacklogFilterToolbar (Search, Type, Status, Priority, Assignee, View Switcher)
  └── BacklogView
        ├── WorkItemTree (Hierarchy Tree: EPIC -> USER_STORY -> TASK -> SUBTASK)
        │     └── WorkItemRow (Collapsible chevrons, Type icons, Status pills, Blocked flag)
        ├── DependencyDag (Predecessor -> Successor DAG, Cycle detection, Critical path)
        ├── CreateWorkItemModal (useFocusTrap, Keyboard traps, ARIA dialog)
        └── EditWorkItemModal (Transitions, Optimistic concurrency, Blocked reasons)
```

---

## 1. Routing & Dynamic Project Resolution

1. **Project Key Route:** `frontend/src/app/p/[key]/backlog/page.jsx`
   - Resolves dynamic `[key]` parameter (e.g. `DSP`).
   - Hydrates project context and queries project-scoped work items, requirements, and dependencies.
2. **Workspace Backlog Route:** `frontend/src/app/workspaces/[id]/backlog/page.jsx`
   - Resolves workspace ID or slug (e.g. `devsphere-core`).
   - Automatically resolves default project and renders full backlog hierarchy.
3. **Navigation Integration:**
   - Unified `ProjectNavBar` provides breadcrumbs: `Workspaces > [Workspace Name] > [Project Name] ([KEY])`.
   - Sub-header tabs with active status indicators:
     * Backlog (`/p/[key]/backlog`)
     * Sprints (`/p/[key]/sprints`)
     * Gantt (`/p/[key]/gantt`)
     * Plan Approval (`/p/[key]/plan-approval`)

---

## 2. Backlog Hierarchy Tree & Work Item Model

- **Multi-Level Hierarchy Tree:**
  `EPIC` -> `USER_STORY` -> `TASK` -> `SUBTASK`
- **Child Aggregation:**
  Recursive computation of child item counts and direct child counts.
- **Collapsible Rows:**
  Expand/collapse chevron controls with child count badges.
- **Attributes Displayed:**
  - Key badge (`DSP-1`, `DSP-101`)
  - Title with inline edit trigger
  - Type badge with semantic colors (Epic = purple, Story = green, Task = blue, Subtask = teal, Bug = red)
  - Status pill (`TO_DO`, `IN_PROGRESS`, `IN_REVIEW`, `DONE`)
  - Priority badge (`CRITICAL`, `HIGH`, `MEDIUM`, `LOW`)
  - Assignee avatar with initials and full display name
  - Story Points and Estimate duration (`formatEstimate`, `formatStoryPoints`)
  - Blocked status flag with tooltip showing blocker rationale.

---

## 3. Dependency Directed Acyclic Graph (DAG) Engine

- **Visual Flow Representation:**
  Predecessor card -> Connector link -> Successor card.
- **Link Attributes:**
  - Link Type: `FINISH_TO_START` (FS), `START_TO_START` (SS), `FINISH_TO_FINISH` (FF).
  - Lag Minutes: custom buffer (e.g. `+60m lag`).
  - Critical Path Flag: highlights dependencies on the critical scheduling chain.
- **Cycle Detection:**
  `detectCycle(predecessorId, successorId, dependencies)` performs breadth-first path exploration to verify that adding an edge does not form a circular loop. Rejects circular dependencies with accessible warning messages.
- **Topological Sorting:**
  `topologicalSort(itemIds, dependencies)` calculates topological evaluation order for downstream CP-SAT solver inputs.

---

## 4. Work Item CRUD Modals & Accessibility

- **CreateWorkItemModal:**
  - Inputs: Title, Description, Type, Priority, Parent Selector, Linked Requirement Selector, Assignee, Story Points, Estimate (hours), Labels.
  - Accessibility: `useFocusTrap` ensures keyboard focus remains within the dialog; `Escape` key closes dialog; focus restores to trigger button.
- **EditWorkItemModal:**
  - Status transition dropdown (`TO_DO` -> `IN_PROGRESS` -> `IN_REVIEW` -> `DONE`).
  - Blocked checkbox with mandatory blocker rationale.
  - Optimistic locking: passes `expectedVersion` to backend.
  - Delete work item action with confirmation dialog.

---

## 5. Verification & Testing

1. **Unit Tests:** `frontend/tests/backlog.test.js` (16 test suites)
   - Multi-level hierarchy nesting (Epic -> Story -> Task -> Subtask).
   - Filter propagation keeping ancestor chains.
   - Collapse/expand state flattening.
   - Cycle detection across direct, indirect, self, diamond, and 10-node chains.
   - Topological sorting and multi-root evaluation.
   - Estimate and story point formatting (fractions, 8h day calculations).
2. **End-to-End Tests:** `frontend/tests/e2e/backlog.spec.js` (18 tests across desktop & mobile)
   - Route rendering at `/p/DSP/backlog` and `/workspaces/devsphere-core/backlog`.
   - Chevron expansion and collapse.
   - Search filtering and reset.
   - Type filter dropdown.
   - Create modal open, focus trap, Escape close, and item creation.
   - Edit modal updates and title change persistence.
   - Dependency DAG view inspection and Add Dependency dialog.
   - Keyboard arrow row traversal.

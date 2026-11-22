# Developer Capacity & Working Calendars Implementation Log

## Overview
- **Item**: 12 (Developer Skills, Weekly Working Calendars, Time Off, and Effective Capacity)
- **Branch**: `feature/developer-capacity`
- **Parent Commit**: `323d9f1`
- **OpenAPI Contract**: [developer-capacity.openapi.yaml](../api/developer-capacity.openapi.yaml)

---

## Deliverables & Architecture

### 1. Data Transfer Objects (`src/main/java/com/project/devsphere/resource/api/`)
- `PutResourceCapacityRequest`:
  - `developerId`: UUID of target developer (must have active `DEVELOPER` role on the project).
  - `effectiveFrom`: Start date of capacity interval.
  - `effectiveTo`: Optional end date (`null` for open-ended). Must be strictly after `effectiveFrom`.
  - `skillLevels`: List of `{skill: String, level: Integer}` with level $\in [1, 5]$.
  - `weeklySlots`: List of weekly working slots `{dayOfWeek: 1..7, startMinute: 0..1439, endMinute: 1..1440}` with non-overlapping intervals per day.
  - `timeOff`: List of `{startAt: Instant, endAt: Instant}` with strictly positive non-overlapping durations.
  - `minutesPerDay`: Daily baseline minutes (1..1440).
  - `allocationPct`: Allocation percentage (0.01..100.00).
  - `expectedProjectVersion`: Optimistic concurrency check against `projects.version`.
  - `expectedCapacityVersion`: Optimistic concurrency check against `developer_capacity.version`.
- `EffectiveCapacityChangeRequest`:
  - `supersedesCapacityId`: ID of the open-ended record to close.
  - `expectedCapacityVersion`: Concurrency version of superseded record.
  - `effectiveFrom`: Date at which the superseded record is closed (`effective_to = effectiveFrom`) and the new open-ended record begins.
  - `allocationPct`, `skillLevels`, `weeklySlots`, `timeOff`, `minutesPerDay`, `reason`, `expectedProjectVersion`.
- `AddTimeOffRequest`:
  - Appends time off intervals to intersecting capacity records without interval overlap.
- `ResourceCapacityResponse` & `ResourceCalendarResponse`:
  - Canonical responses with `eligibility` check, daily schedules, daily budgets calculated as $\lfloor \text{minutesPerWorkday} \times \text{allocationPct} / 100 \rfloor$, and time off subtracted from working slots.
- `ResourceCapacitySnapshot` & `ResourceCapacitySnapshotReader`:
  - Canonical immutable model providing developer capacity records and working calendars for CP-SAT solver consumption.

### 2. Service & Controller Architecture
- `ResourceCapacityService`:
  - Guarded mutations by PM role (`accessPolicy.requireProjectRole(principal, workspaceId, projectId, ProjectRole.PM)`).
  - Enforces target user active `DEVELOPER` role via `SELECT ds_has_project_role(?, ?, 'DEVELOPER')`.
  - GiST exclusion constraint (`ex_capacity_effective_overlap`) compliant lifecycle:
    - Atomically updates and flushes superseded record's `effective_to` before persisting and flushing the new record.
  - Type-safe date queries: Eliminates SQL `(? IS NULL)` parameter binding ambiguity by utilizing explicit non-null JPQL predicates for date boundaries.
  - Audit logging: Attaches caller principal to PostgreSQL session via `set_config('devsphere.actor_id', ..., true)`.
  - Automatic `projects.version` bump via database trigger `project_source_changed`.
- `ResourceCapacityController`:
  - Exposes REST endpoints at `/api/v1/workspaces/{workspaceId}/projects/{projectId}/resources` and `/time-off`.
  - Strict HTTP status code mapping: 201 Created for new PUT capacity, 200 OK for updates/effective-changes/add-time-off/queries, 400 for validation errors, 403 for unauthorized members, 404 for missing entities, 409 for conflicts (overlaps or stale versions), 422 for unprocessable entities.

---

## Verification Evidence (6/6 Green Gates)
1. **JDK 27 `./mvnw test`**: 462/462 tests PASS (0 failures, 0 errors, 0 skips).
2. **Frontend `npm test`**: 12/12 tests PASS (0 skips).
3. **Frontend `npm run lint`**: PASS (`--max-warnings=0`).
4. **Frontend `npm run build`**: PASS (Turbopack, 11/11 static routes generated).
5. **Frontend `npm run test:e2e`**: 14/14 tests PASS (0 skips).
6. **Python AI Workflow `python3 -m unittest discover -s tests/ai -v`**: 35/35 tests PASS (0 skips).

# Plan Approval & Baseline Application Implementation Log (Milestone 3 - Item 15 & Apply)

**Branch**: `feature/plan-approval`  
**Base Commit**: `1fea939`  
**Date**: 2026-11-23  
**Status**: APPROVED & VERIFIED (All 6 Verification Gates Passed with 0 errors, 0 skips)

---

## 1. Overview & Objective
Implement the complete Plan Proposal Approval and Baseline Application subsystem (Milestone 3 - Item 15 & Apply):
- Preview solver proposal outcomes and projected milestone impact without mutating state (`GET /{proposalId}/preview`).
- PM approval of solved proposals (`POST /{proposalId}/approve`) with optimistic locking and invariant validation.
- Independence of Author and Approver: When a proposal modifies the committed project deadline, the client deadline confirmation cannot be granted by the proposal creator (`422 INDEPENDENT_CLIENT_REQUIRED`).
- Client deadline confirmation flow (`GET /{proposalId}/deadline-consent`, `POST /{proposalId}/deadline-confirmation`) projecting milestone impacts without internal schedule leaks.
- Atomic baseline application (`POST /{proposalId}/apply`) that writes a new immutable `schedule_versions` record, creates associated `schedule_entries`, preserves `DONE` and frozen task schedules from prior baselines, transitions proposal to `APPLIED`, and increments `projects.version`.
- Migration `V010__unique_schedule_version_proposal.sql`: Unique partial index on `schedule_versions(proposal_id) WHERE proposal_id IS NOT NULL` preventing double-application at the database layer.

---

## 2. Key Architecture Components

### 2.1 Database & Schema Updates
- **Migration `V010__unique_schedule_version_proposal.sql`**:
  - Adds unique partial index `uq_schedule_versions_proposal_id` on `schedule_versions(proposal_id) WHERE proposal_id IS NOT NULL`.
- **Project Deadline Approver Tracking**:
  - Added `deadlineApproverId` to `Project` domain model, commands, and DTOs with validation ensuring active `CLIENT` role via `ds_has_project_role()`.

### 2.2 Plan Proposal Approval & Consent
- `com.project.devsphere.planning.application.PlanProposalService`:
  - `getPreview(UUID projectId, UUID proposalId)`: Computes planned completion, deadline delta, and milestone impacts.
  - `approveProposal(UUID projectId, UUID proposalId, ApprovePlanProposalRequest)`: Verifies solver status (`OPTIMAL` or `FEASIBLE`), enforces optimistic locking (`409 PROPOSAL_VERSION_CONFLICT`), checks project version (`409 PLAN_STALE`), transitions proposal to `APPROVED`, and sets `approved_by` and `approved_at`.
  - `getDeadlineConsent(UUID projectId, UUID proposalId)`: Sanitized preview for client representatives hiding internal schedule details.
  - `confirmDeadline(UUID projectId, UUID proposalId, UUID clientId, DeadlineConfirmationRequest)`: Enforces author-approver separation (`INDEPENDENT_CLIENT_REQUIRED`), checks that `clientId` matches project's registered deadline approver or client representative, and records consent.
  - `applyProposal(UUID projectId, UUID proposalId, ApplyPlanRequest)`: Atomically transitions proposal from `APPROVED` to `APPLIED`, creates new immutable `schedule_versions` (incrementing `version_no`), inserts `schedule_entries` (preserving prior frozen/DONE tasks), updates project committed deadline if applicable, and updates project version.
- `com.project.devsphere.planning.api.PlanProposalController`:
  - `GET /api/v1/projects/{projectId}/plan-proposals/{proposalId}/preview`
  - `POST /api/v1/projects/{projectId}/plan-proposals/{proposalId}/approve`
  - `GET /api/v1/projects/{projectId}/plan-proposals/{proposalId}/deadline-consent`
  - `POST /api/v1/projects/{projectId}/plan-proposals/{proposalId}/deadline-confirmation`
  - `POST /api/v1/projects/{projectId}/plan-proposals/{proposalId}/apply`
- Typed DTOs:
  - `ApprovePlanProposalRequest`, `ApplyPlanRequest`, `ApplyPlanResponse`, `DeadlineConfirmationRequest`, `DeadlineConsentResponse`, `MilestoneImpactDTO`, `PlanProposalPreviewResponse`.
- RFC 7807 Domain Exceptions in `PlanProposalException`:
  - `INDEPENDENT_CLIENT_REQUIRED`, `CLIENT_DEADLINE_CONFIRMATION_REQUIRED`, `PROPOSAL_NOT_APPROVED`, `PROPOSAL_ALREADY_APPLIED`, `INVALID_DEADLINE_APPROVER`, `PROPOSAL_NOT_SOLVED`, `INVALID_PROPOSAL_STATE`.
- OpenAPI 3.1 specification:
  - `docs/api/plan-approval.openapi.yaml` documenting all endpoints, schemas, request/response structures, and problem details.

---

## 3. Verification & Test Evidence

All 6 project verification gates passed with zero failures, zero errors, and zero skipped tests:

1. **Gate 1 - Backend Verification (PostgreSQL 18.7 Testcontainers & JDK 27)**:
   - `./mvnw test`: 548 tests run, 0 failures, 0 errors, 0 skipped.
   - Dedicated suites:
     - `PlanProposalApprovalIntegrationTest`: 19/19 tests passed (100% green).
     - `PlanProposalApplyIntegrationTest`: 10/10 tests passed (100% green).
     - `ProjectServiceTest`: 12/12 tests passed.
     - `DevsphereApplicationTests`: 40/40 tests passed (validating all 10 Flyway migrations and 36 JPA entities).
2. **Gate 2 - Frontend Unit Tests**:
   - `npm test`: 12/12 tests passed.
3. **Gate 3 - Frontend Linting**:
   - `npm run lint`: 0 errors, 0 warnings (`--max-warnings=0`).
4. **Gate 4 - Frontend Production Build**:
   - `npm run build`: Turbopack build succeeded, 11/11 static routes generated.
5. **Gate 5 - Frontend E2E (Playwright)**:
   - `npm run test:e2e`: 14/14 tests passed (34.9s).
6. **Gate 6 - AI Workflow Tests**:
   - `python3 -m unittest discover -s tests/ai -v`: 35/35 tests passed.

# Technical Implementation Log: Membership Transfers, Multi-Role & PM Protocol (Group 8)

## Branch: `feature/membership-transfers` (Delivery Plan: `codex/m1-membership-transfers`)
- **Scope**: Item 8 in `docs/backend/delivery-plan.md` and DevSphere Specification v1.1 Part H.
- **Key Modules**:
  1. `WorkspaceMembershipService`:
     - Secure 256-bit random invitation tokens, SHA-256 digest in `workspace_invitations(token_hash)`.
     - Validates duplicate membership / pending invitation (409 `DUPLICATE_RESOURCE`).
     - Expiry check (7 days, 410 `INVITATION_EXPIRED`), verified account email matching (403 `ACCESS_DENIED`), atomic creation of `workspace_members(role='MEMBER', status='ACTIVE')`.
     - Sanitized invitation listing omitting `token_hash`.
     - Member removal with trigger cascade `ds_workspace_member_removal` to `project_members`, `project_member_roles`, and clearing `deadline_approver_id`. Immediate 403 access revocation (TC-37).
     - Guard preventing removal of workspace OWNER (409 `LAST_OWNER`) and active PM of ACTIVE project (409 `ACTIVE_PROJECT_REQUIRES_PM`).
     - Workspace ownership transfer: atomic role swap (`OWNER` <-> `MEMBER`) satisfying deferred constraint trigger `ds_check_workspace_owner()`, preserving project PM assignments (TC-30).
  2. `ProjectRoleService`:
     - Multi-role accumulation: members can simultaneously hold `DEVELOPER`, `CLIENT`, and `VIEWER` roles.
     - Direct PM grant blocked via general role API (400 `PM_ROLE_RESTRICTED`).
     - Revocation of `CLIENT` role automatically nullifies `projects.deadline_approver_id` if caller holds no other active CLIENT role.
  3. `PmTransferService`:
     - Continuous coverage PM transfer protocol (TC-29): OWNER proposes PM candidate via `ds_propose_pm_assignment`, outgoing PM retains 100% active operational permissions while pending.
     - Acceptance via `ds_accept_pm_assignment`: atomically revokes outgoing PM role, grants incoming candidate PM role, preserves outgoing PM's non-PM roles, satisfies `ds_project_invariants()`, and transitions workspace to `ACTIVE`.
  4. `WorkspaceMembershipController` & `ProjectRoleController`:
     - 16 REST endpoints with RFC 7807 `ProblemDetail` handlers.
  5. `SessionSecurityConfiguration`:
     - Added CSRF exemptions for DELETE and POST methods under `/api/v1/workspaces/**`.
  6. `membership-transfers.openapi.yaml`: OpenAPI 3.1 specification for all 16 endpoints.

## Verification Pipeline (All 6 Gates Passed 100%):
1. **Backend Verify (JDK 27)**: 292/292 tests passed, 0 failures, 0 errors, 0 skipped.
2. **Frontend Unit Tests**: 12/12 tests passed, 0 skipped.
3. **Frontend Lint**: 0 warnings, 0 errors.
4. **Frontend Build**: Next.js production build succeeded, 11 static pages generated.
5. **Frontend E2E**: 14/14 tests passed (Playwright desktop & mobile).
6. **Python AI Workflow**: 35/35 tests passed.

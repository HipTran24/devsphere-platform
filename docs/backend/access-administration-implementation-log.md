# Technical Implementation Log: Access Control & Account Administration (Items 5 & 6)

## Branch: `feature/access-control-admin` (Delivery Plan: `codex/m1-access-admin`)
- **Scope**: Items 5 & 6 in `docs/backend/delivery-plan.md` and DevSphere Specification v1.1 Part H.
- **Key Modules**:
  1. `AccessPolicy`: Central access policy service enforcing active verified user, active session, workspace scope, project scope, unrevoked roles, default-deny with zero positive caching.
  2. `ProjectRole`: Enum supporting `PM`, `DEVELOPER`, `CLIENT`, `VIEWER`.
  3. `WorkspaceScope` & `ProjectScope`: Immutable scoped records.
  4. `SessionSecurityConfiguration`: Matchers for `/api/v1/admin/**` requiring `hasRole("ADMIN")` with Bearer auth, cookie CSRF exemption for admin PATCH, standardized RFC 7807 problem details (`AUTH_INVALID` 401, `ACCESS_DENIED` 403).
  5. `AccountAdministrationService`:
     - Bounded pagination (page 0..10000, size 1..100).
     - Escaped SQL LIKE search using `ESCAPE '!'`.
     - Deterministic ASCII sorting with `COLLATE "C"` and tie-breaking by `id`.
     - Transaction-level lock `pg_advisory_xact_lock(1005001)` and deterministic row-level locks `SELECT ... FOR UPDATE`.
     - Optimistic concurrency guard with `CONCURRENT_ACCOUNT_CHANGE` (409 Conflict).
     - Safeguard preventing deactivation/locking of last active admin (`LAST_ACTIVE_ADMIN`).
     - Session revocation on `ON_HOLD` / `BANNED`.
     - Session actor tracking via `SET LOCAL devsphere.actor_id = ?`.
     - Sanitized audit logging with maximum 31-day search range.
  6. `AccountAdministrationController`: Endpoints `GET /api/v1/admin/users`, `PATCH /api/v1/admin/users/{userId}/status`, `GET /api/v1/admin/audit-logs`.
  7. `access-administration.openapi.yaml`: Complete OpenAPI 3.1 contract.

## Test & Verification Results:
- `python3 -m unittest discover -s tests/ai -v`: 35/35 passed (100%).
- `./mvnw verify`: 200/200 tests passed, 0 failures, 0 errors, 0 skipped.
- Frontend unit tests: 12/12 passed.
- Frontend lint: 0 warnings, 0 errors.
- Frontend build: Completed in 464ms, 11 static routes.
- Frontend E2E: 12/12 passed (Chromium desktop & mobile).

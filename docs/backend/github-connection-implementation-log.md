# Technical Implementation Log: GitHub App Connection & Activity Sync (Items 16, 17, 18)

## Branch: `feature/github-connection` (Delivery Plan: `codex/m2-github-connection`)
- **Scope**: Items 16, 17, 18 in `docs/backend/delivery-plan.md` and DevSphere Specification v1.1 Part H.
- **Key Modules**:
  1. `GitHubRepositoryService`:
     - PM connects repository with positive installation & repo IDs, default branch, privacy flag (`active = true`).
     - Enforces single active connected repo per project (409 `REPOSITORY_ALREADY_CONNECTED`) and global uniqueness across projects (409 `DUPLICATE_REPOSITORY`).
     - Disconnect sets `active = false`.
  2. `GitHubWebhookService`:
     - Ingress HMAC-SHA256 signature verification (`X-Hub-Signature-256`) using constant-time comparison `MessageDigest.isEqual` against `devsphere.github.webhook-secret`. Missing or invalid signature returns 401 `WEBHOOK_SIGNATURE_INVALID`.
     - Ingress delivery deduplication: checks `X-GitHub-Delivery` against `github_events.delivery_id` (`UNIQUE(delivery_id)`). Replays return 200 OK without inserting duplicate records.
  3. `GitHubActivitySyncService`:
     - Synchronous event processing with regex task key extraction (`\b([A-Z][A-Z0-9]{1,15}-[1-9][0-9]*)\b`) matching project key.
     - Maps `push` events to `BRANCH` and `COMMIT` activities. Commits with task keys link to corresponding `work_items`.
     - Maps `pull_request` events (`opened`, `synchronize`, `closed`) to `PULL_REQUEST` activities linked to `work_items`.
     - **Part H Critical Invariant**: PR merge sets activity state to `MERGED`, but strictly leaves work item status untouched (never auto-completes to `DONE`). Task completion remains strictly governed by PM acceptance.
     - Idempotent upserts on `UNIQUE (repository_id, kind, github_object_id)` to gracefully handle webhook replays.
  4. `GitHubWebhookController` & `GitHubRepositoryController`:
     - Mapped to `/api/v1/github/webhook` (public ingress, HMAC-guarded, CSRF-exempt) and `/api/v1/workspaces/{wId}/projects/{pId}/github/**` (Bearer auth, PM guarded).
  5. `github-connection.openapi.yaml`: OpenAPI 3.1 specification for all GitHub endpoints.

## Verification Pipeline (All 6 Gates Passed 100%):
1. **Backend Verify (JDK 27)**: 384/384 tests passed, 0 failures, 0 errors, 0 skipped.
2. **Frontend Unit Tests**: 12/12 tests passed, 0 skipped.
3. **Frontend Lint**: 0 warnings, 0 errors.
4. **Frontend Build**: Next.js production build succeeded, 11 static pages generated.
5. **Frontend E2E**: 14/14 tests passed (Playwright desktop & mobile).
6. **Python AI Workflow**: 35/35 tests passed.

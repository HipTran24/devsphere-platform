# DevSphere End-to-End Demonstration Guide (Milestone 6)

This guide walks through the end-to-end demonstration of the Week 6 Operational & Production Readiness capabilities.

## Prerequisites
1. Start the backend service:
   ```bash
   ./mvnw spring-boot:run
   ```
2. Start the frontend development server:
   ```bash
   npm --prefix frontend run dev
   ```
3. Open `http://localhost:3000` in the browser.

---

## Demo Script

### Step 1: Frontend States & Keyboard Accessibility
1. Navigate to `/workspaces`.
2. Observe the skeleton `LoadingState` during data fetching.
3. If no workspaces exist, inspect the `EmptyState` component displaying actionable creation prompts.
4. Trigger an offline state (disconnect network in DevTools) and view the `ErrorState` with "Retry" action and error correlation ID.
5. Tab through the interface using the keyboard only:
   - Notice the visible `:focus-visible` outline rings on buttons, navigation links, and dropdown toggles.
   - Activate the "Skip to main content" link using `Tab` -> `Enter`.
   - Open dialogs and verify focus trapping via `useFocusTrap` hook.

### Step 2: Operations Admin & Audit Log
1. Log in with an account holding `ROLE_ADMIN`.
2. Query the Audit Summary endpoint:
   ```bash
   curl -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/v1/admin/audit-logs/summary?from=2026-01-01T00:00:00Z&to=2026-12-31T23:59:59Z"
   ```
3. Export audit logs to CSV:
   ```bash
   curl -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/v1/admin/audit-logs/export?format=csv" -o audit.csv
   ```
4. Open `audit.csv` and verify formula sanitization: values starting with `=`, `@`, `+`, `-` are prefixed with a single quote to prevent spreadsheet injection.

### Step 3: Outbox & Quartz Background Jobs
1. Query background job queue statistics:
   ```bash
   curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/admin/operations/outbox/stats
   ```
2. Trigger an immediate Quartz job execution:
   ```bash
   curl -X POST -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/admin/operations/quartz/triggers/outboxDispatch/trigger
   ```
3. Inspect the response indicating HTTP 202 Accepted and Quartz execution telemetry.

### Step 4: S3 Presigned URL & Storage Provider
1. Request an upload presigned URL for an avatar:
   ```bash
   curl -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
     -d '{"filename": "team-logo.png", "contentType": "image/png"}' \
     http://localhost:8080/api/v1/storage/upload-url
   ```
2. Inspect the generated pre-signed URL verifying AWS SigV4 query parameters.

### Step 5: Metrics, Prometheus & Grafana Dashboards
1. Access `/actuator/health` to view component health indicators (`quartz`, `jobQueue`, `storage`, `db`).
2. Open Grafana at `http://localhost:3000` (or through reverse proxy):
   - **DevSphere API & JVM Dashboard**: Real-time HTTP RPS, latency percentiles, JVM heap utilization.
   - **Jobs & Solver Dashboard**: Outbox dispatch rate, Quartz job executions, CP-SAT duration.
   - **Database & Backup Dashboard**: Connection pool active/idle, time since last successful backup.

### Step 6: Disaster Recovery Drill
Execute a live restore test:
```bash
./infra/backup/restore-verify.sh
```
Observe the verification report verifying 36+ schema tables, Flyway version 10 integrity, and RTO metric under 5 seconds.

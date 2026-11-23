# Milestone 6 Backend Operations Implementation Log

## Overview
This log documents the backend architectural decisions and implementations for Milestone 6 (Production Readiness & Operations).

## Key Implementations

### 1. Quartz Scheduler Integration
- **In-Memory Store Selection**: Configured `spring.quartz.job-store-type=memory` to ensure no additional DDL tables were needed. All transactional state is preserved in the ACID `background_jobs` table.
- **Job Classes Created**:
  - `OutboxDispatcherJob`: Polls and dispatches pending outbox events.
  - `AuditLogCleanupJob`: Rotates audit log entries older than retention policy.
  - `SessionCleanupJob`: Revokes expired session tokens.
  - `RefreshTokenCleanupJob`: Purges expired refresh tokens.
  - `VerificationTokenCleanupJob`: Cleans unverified registration tokens.
  - `PasswordResetTokenCleanupJob`: Cleans expired reset links.

### 2. Audit Log & CSV Export Security
- **Request Tracing**: `RequestTraceFilter` sets MDC correlation IDs on each incoming HTTP request.
- **Formula Injection Defense**: Implemented in `AuditLogExportService`. Any value starting with `=`, `+`, `-`, or `@` is prepended with `'` to render harmless text in spreadsheet applications.

### 3. Spring Boot Actuator & Metrics Security
- Actuator endpoint `/actuator/health` is exposed publicly for load balancer liveness checks.
- Sensitive endpoints like `/actuator/prometheus` require `ROLE_ADMIN` bearer token authorization via Spring Security filter configuration.
- Custom health indicators: `QuartzHealthIndicator`, `JobQueueHealthIndicator`, `StorageHealthIndicator`.

### 4. AWS S3 Presigner & SSRF Prevention
- AWS SDK v2 `S3Presigner` generates signed URLs with SigV4 authentication.
- Key validator `StorageKeyValidator` enforces alphanumeric patterns and prevents directory traversal.
- SSRF metadata protection: Validates that endpoint URLs do not resolve to loopback, link-local (169.254.169.254), or private subnet ranges.

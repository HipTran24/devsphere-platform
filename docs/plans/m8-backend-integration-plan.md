# DevSphere Week 8 (Milestone 8) — Backend Integration & Real-Time Release Plan

**Branch:** `feature/m8-backend-release` (base commit `29d852c` / `feature/m7-fullstack-release`)
**Lead:** `m8-dev-lead` (`gemini-4-neon-xhigh`)
**Execution Model:** Sequential 5-coder chain in a single checkout (`workspace: "inherit"`), preserving `AGENTS.md` Maven/Git isolation.

---

## 1. Critical Invariants (Non-Negotiable Across All 5 Coders)

1. **Strict Backend-Only Scope**:
   - **ZERO** edits under `frontend/**`. Verified via `git diff --name-only feature/m7-fullstack-release..HEAD -- frontend/` returning empty.
2. **Database Schema & JPA Entity Freeze**:
   - `DevsphereApplicationTests.migratesThirtySixTablesAndValidatesEveryJpaEntity()` strictly asserts:
     - Exactly **36** PostgreSQL base tables in `public` (excluding `flyway_schema_history`).
     - Exactly **36** JPA `@Entity` classes in Hibernate's metamodel.
     - Exactly **10** Flyway migrations (`V001..V010`).
   - **NEVER** create a new `@Entity` class or `V011__*.sql`. Reuse existing tables and JSONB columns: `workspaces.ai_connections`, `workspaces.ai_task_routes`, `workspaces.settings`, `ai_jobs`, `github_repositories`, `github_events`, `github_activities`, `ci_runs`, `code_reviews`, `chat_channels`, `chat_channel_members`, `chat_messages`, `notifications`, `alerts`, `attachments`, `reports`, `background_jobs`, `audit_logs`.
3. **PostgreSQL Secret-Free JSONB Guard (`V006`)**:
   - `V006` installs `CHECK (col IS NULL OR NOT ds_json_has_secret(col))` on EVERY `jsonb` column in the schema.
   - `ds_json_has_secret` recursively rejects any object key where `lower(replace(k,'_',''))` is in `('password','passwordhash','apikey','accesstoken','refreshtoken','secret','privatekey','clientsecret')` with a non-null value.
   - Therefore, encrypted AI credentials stored inside `workspaces.ai_connections` **MUST** use a non-blocked field name such as `"encryptedCredential"` (NEVER `"apiKey"`, `"api_key"`, `"secret"`, `"accessToken"`, etc.).
4. **Backward-Compatible Service Constructors**:
   - Preserve every existing public constructor on:
     - `ChatService`, `NotificationService`, `AlertService`, `WorkspaceMembershipService`
     - `AiJobService`
     - `GitHubActivitySyncService`, `GitHubBackfillJobHandler`
     - `AttachmentService`, `ReportService`
     - `OperationsAdminController`
   - Add new dependencies via `@Autowired` full constructor + delegating legacy constructor (or optional `ObjectProvider<...>`). All pre-existing unit tests instantiating services via `new Service(...)` must continue compiling and passing unmodified.
5. **Transaction Hygiene (Zero External I/O Inside DB Transactions)**:
   - **NEVER** perform HTTP calls (OpenAI, vLLM, GitHub REST API, S3 `HEAD`/`PUT`) inside an active `@Transactional` method or `TransactionTemplate` callback.
   - Split orchestration into: **Short read tx** → **External HTTP I/O outside tx** → **Short write tx**.
   - Publish WebSocket events only **after transaction commit** (`TransactionSynchronization.afterCommit()` when `TransactionSynchronizationManager.isSynchronizationActive()` is true; direct dispatch when no tx is active).
6. **Spring Boot 4.1.2 Jackson Compatibility**:
   - Spring Boot 4.1.2 auto-configures `tools.jackson.databind.json.JsonMapper`, NOT `com.fasterxml.jackson.databind.ObjectMapper`.
   - When using `com.fasterxml.jackson.databind.ObjectMapper`, inject `@Autowired(required = false) ObjectMapper` with fallback `new ObjectMapper().findAndRegisterModules()`, or instantiate locally.
7. **Tooling & Security Hygiene**:
   - Run all Maven commands with local JDK 27 and local Maven repository:
     ```bash
     JAVA_HOME=./.tools/jdk-27/Contents/Home ./mvnw -B -Dmaven.repo.local=.tools/m2/repository ...
     ```
   - Never log raw API keys, PEM private keys, bearer tokens, installation tokens, signing secrets, or `.env` values.

---

## 2. Chained Branch Topology

```text
feature/m8-backend-release (plan commit)
  └── feature/m8-ws-realtime        (Coder 1: m8-ws-realtime)
        └── feature/m8-ai-router    (Coder 2: m8-ai-router)
              └── feature/m8-github-app      (Coder 3: m8-github-app)
                    └── feature/m8-storage-export (Coder 4: m8-storage-export)
                          └── feature/m8-seed-e2e      (Coder 5: m8-seed-e2e)
                                └── merge --no-ff -> feature/m8-backend-release
```

---

## 3. Workstream Specifications

### Coder 1: `m8-ws-realtime` (WebSocket + STOMP Real-Time Layer)
- **Branch**: `feature/m8-ws-realtime` from `feature/m8-backend-release`
- **Dependency**: Add `org.springframework.boot:spring-boot-starter-websocket` to `pom.xml`.
- **New Package**: `com.project.devsphere.realtime` (NO `@Entity` classes!)
  - `config/WebSocketBrokerConfig`: `@EnableWebSocketMessageBroker`, STOMP endpoint `/ws` (plain WebSocket + SockJS optional/safe, configurable allowed origin patterns matching `SessionSecurityConfiguration`), simple broker `/topic`, `/queue`, application prefix `/app`, user destination prefix `/user`, transport limits (message size 64KB, send time limit 15s, send buffer 512KB), registers `StompJwtChannelInterceptor` on client inbound channel and decorates `WebSocketHandler` to track live `WebSocketSession` handles in `RealtimeSessionRegistry`.
  - `security/StompJwtChannelInterceptor`:
    - `CONNECT`: Extracts `Authorization: Bearer <token>` ONLY from STOMP native headers. Explicitly rejects query-parameter tokens (if a `token` / `access_token` query param is present during handshake or CONNECT, reject!) and validates token via `SessionService.authenticate(token)`. Sets authenticated STOMP `Principal` whose `getName()` equals `principal.userId().toString()` and stores `SessionService.Principal` + auth session ID in STOMP session attributes. Enforces `devsphere.realtime.max-sessions-per-user` (default `10`).
    - `SUBSCRIBE`: Live DB-backed RBAC check (zero positive caching) using `AccessPolicy` + `JdbcTemplate`:
      1. `/topic/workspaces/{wId}/projects/{pId}/chat/{cId}`: Caller must pass `accessPolicy.requireProjectMember`, be an active row in `chat_channel_members` for `(channel_id=cId, workspace_id=wId, project_id=pId, user_id=principal.userId())`, AND if channel type is internal (`TEAM`, `TASK`, `PROJECT`) reject `CLIENT`-only members.
      2. `/topic/workspaces/{wId}/projects/{pId}/control-tower`: Caller must pass `accessPolicy.requireAnyProjectRole(..., Set.of(PM, DEVELOPER, VIEWER))` (blocks `CLIENT`-only).
      3. `/user/queue/notifications`: Caller must pass `accessPolicy.requireAuthenticatedUser`.
      4. All other destinations: **default-deny** (`AccessDeniedException` / `MessagingException`).
    - `DISCONNECT`: Cleans up subscriptions/session state in `RealtimeSessionRegistry`.
  - `application/RealtimeSessionRegistry`:
    - Thread-safe registry tracking active WebSocket sessions by `wsSessionId`, `userId`, `authSessionId`, and subscribed `workspaceId`s.
    - Enforces `devsphere.realtime.max-sessions-per-user=10` (configurable via `application.properties`).
    - Exposes `disconnectUserFromWorkspace(UUID workspaceId, UUID userId)` closing matching `WebSocketSession`s with `CloseStatus.POLICY_VIOLATION` (`TC-03`), and `disconnectAuthSession(UUID authSessionId)` closing matching sessions on revocation/logout.
  - `application/RealtimeEventPublisher`:
    - Publishes structured JSON event envelopes (`{eventType, workspaceId, projectId, timestamp, payload}`) via `SimpMessagingTemplate`.
    - When `TransactionSynchronizationManager.isSynchronizationActive()` is true, defers dispatch into `TransactionSynchronization.afterCommit()` so rolled-back transactions never leak events.
    - Helper methods: `publishChatMessage(...)`, `publishNotification(...)`, `publishAlert(...)`, `publishControlTowerUpdate(...)`.
- **Service & Security Hooks (Backward-Compatible)**:
  - `SessionSecurityConfiguration`: Permit `/ws` and `/ws/**` handshake upgrade and ignore CSRF on `/ws/**` (authentication is enforced on STOMP `CONNECT`). Also reject WebSocket HTTP upgrade requests carrying `?token=` or `?access_token=` in handshake interceptor if desired (double defense-in-depth).
  - `ChatService`: Optional `ObjectProvider<RealtimeEventPublisher>` — publishes `CHAT_MESSAGE_CREATED` to `/topic/workspaces/{wId}/projects/{pId}/chat/{cId}` after commit.
  - `NotificationService`: Optional `ObjectProvider<RealtimeEventPublisher>` — publishes `NOTIFICATION_CREATED` to user destination `/queue/notifications` (routed to `/user/{userId}/queue/notifications`) after commit.
  - `AlertService`: Optional `ObjectProvider<RealtimeEventPublisher>` — publishes `ALERT_UPSERTED` / `ALERT_UPDATED` to `/topic/workspaces/{wId}/projects/{pId}/control-tower` after commit (look up `workspace_id` from `projects` when needed).
  - `WorkspaceMembershipService.removeMember`: Optional `ObjectProvider<RealtimeSessionRegistry>` — schedules `disconnectUserFromWorkspace(workspaceId, targetUserId)` in `TransactionSynchronization.afterCommit()` so removed members are evicted with `POLICY_VIOLATION` (`TC-03`).
- **Allowed Files**:
  - `pom.xml`, `src/main/resources/application.properties`
  - `src/main/java/com/project/devsphere/realtime/**`
  - `src/main/java/com/project/devsphere/identity/api/SessionSecurityConfiguration.java`
  - `src/main/java/com/project/devsphere/communication/application/ChatService.java`
  - `src/main/java/com/project/devsphere/notification/application/NotificationService.java`
  - `src/main/java/com/project/devsphere/monitoring/application/AlertService.java`
  - `src/main/java/com/project/devsphere/workspace/application/WorkspaceMembershipService.java`
  - `src/test/java/com/project/devsphere/realtime/**`
  - `src/test/java/com/project/devsphere/WebSocketRealtimeIntegrationTest.java`
- **Exclusions**: `frontend/**`, `src/main/resources/db/migration/**`, any new `@Entity`.
- **Acceptance Criteria & Tests**:
  - `StompJwtChannelInterceptorTest` (unit): valid Bearer CONNECT, missing/invalid token rejected, query-param token rejected, SUBSCRIBE RBAC for chat/control-tower/notifications/unknown destinations.
  - `RealtimeSessionRegistryTest` (unit): max-sessions-per-user=10 enforcement, `disconnectUserFromWorkspace` closes with `POLICY_VIOLATION`, `disconnectAuthSession` closes session.
  - `WebSocketRealtimeIntegrationTest` (`@SpringBootTest(RANDOM_PORT)` + Testcontainers + `WebSocketStompClient`): full STOMP CONNECT, SUBSCRIBE RBAC (CLIENT denied internal channel & control-tower), live chat & notification push delivery, and `TC-03` forced disconnect when `WorkspaceMembershipService.removeMember` commits.
  - Pre-existing `ChatServiceTest`, `NotificationServiceTest`, `AlertServiceTest`, `WorkspaceMembershipServiceTest`, `SessionSecurityTest`, `DevsphereApplicationTests` all pass.

---

### Coder 2: `m8-ai-router` (Real OpenAI & vLLM AI Integration + Enhanced Validation Gate)
- **Branch**: `feature/m8-ai-router` from `feature/m8-ws-realtime`
- **Properties (`application.properties`)**:
  - `devsphere.ai.encryption-key=${AI_SECRET_ENCRYPTION_KEY:}` (32-byte Base64 or derived SHA-256 fallback for dev/test when blank, never logged)
  - `devsphere.ai.allow-loopback-self-host=${AI_ALLOW_LOOPBACK_SELF_HOST:false}`
  - `devsphere.ai.connect-timeout-ms=5000`
  - `devsphere.ai.request-timeout-ms=60000`
  - `devsphere.ai.max-response-bytes=2097152`
- **Components in `com.project.devsphere.ai`**:
  - `infrastructure/AiProperties`: `@ConfigurationProperties(prefix = "devsphere.ai")` (safe `toString()` omitting `encryptionKey`).
  - `application/AiEndpointValidator`: SSRF guard mirroring `AwsS3StorageProvider` rigor:
    - Rejects blank/invalid URI, userinfo, query, fragment.
    - Rejects cloud metadata IPs (`169.254.169.254`, `169.254.170.2`, `100.100.100.200`, `fd00:ec2::254`, `::ffff:169.254.169.254`), link-local, multicast, unspecified/any-local (`0.0.0.0`, `::`).
    - For `OPENAI` (cloud): requires `https` scheme; rejects loopback/localhost/private literals.
    - For `VLLM_SELF_HOST`: allows `https` always; allows `http` and loopback (`localhost`/`127.0.0.1`/`::1`) ONLY when `devsphere.ai.allow-loopback-self-host=true`. Metadata/link-local/multicast are ALWAYS rejected.
  - `application/AiSecretCipher`: AES-256-GCM encryption/decryption with random 12-byte IV, encoded as `v1:<base64(iv||ciphertext+tag)>`. Stored in `workspaces.ai_connections` JSONB under key `"encryptedCredential"` (NEVER `"apiKey"` to comply with `V006` `ds_json_has_secret`).
  - `application/AiConnectionConfigService` & `api/WorkspaceAiConfigController`:
    - `GET /api/v1/workspaces/{workspaceId}/ai/config`: Workspace member read (`accessPolicy.requireWorkspaceMember`). Returns redacted connections (`apiKeyConfigured: boolean`, NEVER plaintext or ciphertext), task routes, and `privacyPolicy` (`CLOUD_ALLOWED` | `SELF_HOST_ONLY`, default `CLOUD_ALLOWED`).
    - `PUT /api/v1/workspaces/{workspaceId}/ai/config/connections`: Workspace owner only (`accessPolicy.requireWorkspaceOwner`). Validates each connection (`id`, `provider`: `OPENAI` | `VLLM_SELF_HOST`, `baseUrl` via `AiEndpointValidator`, `model`, `enabled`, optional `apiKey` encrypted via `AiSecretCipher` or preserves existing `encryptedCredential` when omitted/blank on update). Persists to `workspaces.ai_connections` JSONB.
    - `PUT /api/v1/workspaces/{workspaceId}/ai/config/routes`: Workspace owner only. Validates each `JobType` route points to a configured connection ID. Persists to `workspaces.ai_task_routes` JSONB (keeping `"schemaVersion": 1`).
    - `PUT /api/v1/workspaces/{workspaceId}/ai/config/privacy`: Workspace owner only. Validates `privacyPolicy` in `{CLOUD_ALLOWED, SELF_HOST_ONLY}`. Updates `workspaces.settings` JSONB key `"aiPrivacyPolicy"`.
  - `infrastructure/OpenAiCompatibleClient`:
    - Uses `java.net.http.HttpClient` with 5s connect timeout and 60s request timeout (configurable via `AiProperties`).
    - Calls `POST {normalizedBaseUrl}/v1/chat/completions` with JSON body (`model`, `messages`, `response_format: { "type": "json_object" }`, `temperature`).
    - Attaches `Authorization: Bearer <decryptedKey>` only when a credential is configured.
    - Enforces bounded response size cap (default 2MB) to prevent memory exhaustion.
    - Extracts parsed JSON object from `choices[0].message.content`, plus `usage.prompt_tokens` / `usage.completion_tokens`.
    - On network failure, timeout, non-2xx HTTP status, malformed JSON, or size cap breach: throws `AiException` with HTTP **503 Service Unavailable** and error code **`AI_UNAVAILABLE`**.
  - `application/AiTaskRouter`:
    - Resolves `(workspaceId, jobType)` -> active connection from `workspaces.ai_connections`, `workspaces.ai_task_routes`, and `workspaces.settings.aiPrivacyPolicy`.
    - **Strict Privacy Enforcement (`TC-21`, `TC-22`)**: When `aiPrivacyPolicy == SELF_HOST_ONLY`, any `OPENAI` / cloud connection is strictly blocked BEFORE any HTTP call is ever attempted, throwing `AiException(503, "AI_UNAVAILABLE", ...)`. Falls back only to an enabled `VLLM_SELF_HOST` connection if explicitly routed; if routed to cloud or no valid self-host connection exists, throws `503 AI_UNAVAILABLE`.
  - `application/AiValidationGate` (Enhanced, 100% backward-compatible with `AiValidationGateTest`!):
    - Preserves all existing checks and behavior for existing inputs.
    - `REQUIREMENT_PLAN` (`TC-23`): Inspects `items` / `proposal_payload.items` if estimates (`estimateMinutes`, `estimate_minutes`, `storyPoints`, `story_points`) are present and rejects negative or zero (`<= 0`) estimates (`INVALID`); inspects `dependencies` / `item.dependsOn` / `proposal_payload.dependencies` and detects cycles via DFS/Kahn topological sort (`INVALID` with `dependencyCycleDetected: true`).
    - `PR_REVIEW` / `PUSH_REVIEW` (`TC-24`): When `job.inputRefs` contains `diffFiles` (map of `filePath -> maxLine` or list of `{path, maxLine}`), validates every finding's `filePath` is in `diffFiles` and `lineNumber` (if present) is within `[1, maxLine]`. Out-of-diff files or out-of-bounds line numbers return `INVALID` with `invalidFindings`.
    - `REPORT_SUMMARY` (`TC-25`): When `outputJson` contains `metrics` map (e.g., `totalWorkItems`, `completedWorkItems`, `openAlerts`, `activeSprints`), cross-checks supplied numeric values against live PostgreSQL counts for `projectId`. When any metric mismatches DB ground truth, returns `ValidationResult(false, AiJob.ValidationStatus.NEEDS_REVIEW, report)` with `factMismatches` list! (When `metrics` is absent, existing `AiValidationGateTest` stays `VALID`!)
  - `application/AiExecutionService` + `POST /api/v1/workspaces/{workspaceId}/projects/{projectId}/ai/jobs/{jobId}/execute` on `AiJobController`:
    - **Split TX architecture (NO HTTP inside `@Transactional`)**:
      1. Short read tx: `accessPolicy.requireProjectMember`, load `AiJob`, resolve route & decrypted connection via `AiTaskRouter` (blocks cloud under `SELF_HOST_ONLY` with `503 AI_UNAVAILABLE` and records job failure cleanly if needed), transition `status = RUNNING`, build prompt payload from `inputRefs`.
      2. Outside tx: invoke `OpenAiCompatibleClient.chatCompletion(...)`.
      3. Short write tx: persist `outputJson`, `tokensIn`, `tokensOut`, run `AiValidationGate.validate(...)`, persist `validationStatus` (`VALID` / `INVALID` / `NEEDS_REVIEW`), `validationReport`, `status` (`SUCCESS` if `VALID`, else `FAILED`), `errorCode`, `finishedAt`. If provider threw `AI_UNAVAILABLE`, persist `status = FAILED`, `errorCode = "AI_UNAVAILABLE"`, `finishedAt = now` in short write tx and rethrow `AiException(503, "AI_UNAVAILABLE", ...)`.
  - `@ProjectAI` hook in `ChatService`:
    - Optional `ObjectProvider<AiExecutionService>` injected into `ChatService` (preserving legacy constructors!).
    - When `body` contains `@ProjectAI` (case-insensitive) in `sendMessage(...)`: schedules a best-effort post-commit hook (`TransactionSynchronization.afterCommit()` or direct if non-tx) so HTTP never runs inside `ChatService`'s DB transaction and failures NEVER fail or roll back the user's saved message. When AI returns valid output, saves a `ChatMessage` with `senderKind = AI`, `senderId = null`, `aiJobId = job.id`, `replyToId = saved.id` and publishes via `RealtimeEventPublisher` if available.
- **Allowed Files**:
  - `src/main/resources/application.properties`
  - `src/main/java/com/project/devsphere/ai/**`
  - `src/main/java/com/project/devsphere/communication/application/ChatService.java`
  - `src/test/java/com/project/devsphere/ai/**`
  - `src/test/java/com/project/devsphere/AiProviderIntegrationTest.java`
- **Exclusions**: `frontend/**`, `src/main/resources/db/migration/**`, any new `@Entity`.
- **Acceptance Criteria & Tests**:
  - `AiEndpointValidatorTest`, `AiTaskRouterTest`, `AiValidationGateEnhancedTest`
  - `AiProviderIntegrationTest` (embedded `com.sun.net.httpserver.HttpServer` + Testcontainers): covers `TC-21` (cloud routing vs self-host routing), `TC-22` (`SELF_HOST_ONLY` strictly blocks cloud calls with zero outbound HTTP requests & 503 `AI_UNAVAILABLE`), `TC-23` (negative/zero estimates & dependency cycle rejected), `TC-24` (`PR_REVIEW` out-of-diff file/line rejected), `TC-25` (`REPORT_SUMMARY` fabricated metrics marked `NEEDS_REVIEW` + `factMismatches`), encrypted storage without triggering `ds_json_has_secret`, and `@ProjectAI` chat reply best-effort behavior.
  - Existing `AiValidationGateTest`, `AiJobAndReportIntegrationTest` (if any), `ChatServiceTest`, `DevsphereApplicationTests` all pass.

---

### Coder 3: `m8-github-app` (Real GitHub App Auth, REST Client & Expanded Webhook Sync)
- **Branch**: `feature/m8-github-app` from `feature/m8-ai-router`
- **Properties (`application.properties`)**:
  - `devsphere.github.app.enabled=${GITHUB_APP_ENABLED:false}`
  - `devsphere.github.app.app-id=${GITHUB_APP_ID:}`
  - `devsphere.github.app.private-key-pem=${GITHUB_APP_PRIVATE_KEY_PEM:}`
  - `devsphere.github.app.api-base-url=${GITHUB_API_BASE_URL:https://api.github.com}`
  - `devsphere.github.app.allow-loopback-api=${GITHUB_APP_ALLOW_LOOPBACK_API:false}`
  - `devsphere.github.app.connect-timeout-ms=5000`
  - `devsphere.github.app.request-timeout-ms=30000`
- **Components in `com.project.devsphere.gitintegration`**:
  - `infrastructure/GitHubAppProperties`: `@ConfigurationProperties(prefix = "devsphere.github.app")` (safe `toString()` never rendering `privateKeyPem`).
  - `infrastructure/GitHubAppJwtSigner`:
    - Parses PKCS#8 RSA PEM private keys (handling literal `\n` env escapes and standard PEM headers) and signs RS256 JWTs (`alg=RS256`, `typ=JWT`, `iss=appId`, `iat=now-60s`, `exp=now+9m`, max 10m per GitHub App spec) using standard JCA `Signature.getInstance("SHA256withRSA")` (zero new Maven dependencies!).
  - `infrastructure/GitHubApiUnavailableException`: Non-fatal runtime exception (HTTP 503 `GITHUB_API_UNAVAILABLE` via `GitHubException` mapping) thrown on network errors, 5xx, rate-limit exhaustion, or disabled/unconfigured App credentials.
  - `infrastructure/GitHubInstallationTokenService`:
    - Exchanges signed App JWT via `POST {apiBaseUrl}/app/installations/{installationId}/access_tokens`.
    - Caches installation tokens in memory keyed by `installationId` with **5-minute refresh buffer** prior to `expires_at`.
    - Per-installation lock (`ConcurrentHashMap<Long, Object>`) preventing thundering-herd token refresh races.
    - Exposes `invalidateToken(long installationId)` on 401 responses.
  - `infrastructure/GitHubRestClient`:
    - SSRF-validates `apiBaseUrl` (rejects metadata/link-local/multicast/userinfo/query/fragment; requires HTTPS unless `allowLoopbackApi=true` for localhost/127.0.0.1 in tests).
    - Sets `Accept: application/vnd.github+json`, `X-GitHub-Api-Version: 2022-11-28`, `Authorization: Bearer <installationToken>`, `User-Agent: DevSphere-GitHub-App`.
    - Inspects `X-RateLimit-Remaining` and `X-RateLimit-Reset` / `Retry-After` headers; translates 403 rate-limit, 429, 5xx, IO/timeout into non-fatal `GitHubApiUnavailableException`.
    - Fetches: pull requests (`GET /repos/{owner}/{repo}/pulls?state=all&per_page=30`), commits (`GET /repos/{owner}/{repo}/commits?per_page=30`), check runs (`GET /repos/{owner}/{repo}/commits/{ref}/check-runs`), and PR reviews (`GET /repos/{owner}/{repo}/pulls/{prNumber}/reviews`).
  - Expanded `application/GitHubActivitySyncService` (preserving existing constructor!):
    - Keep existing `push` and `pull_request` handlers + Part H invariant (PR merge NEVER marks a work item `DONE`).
    - Add `check_run` & `workflow_run` handlers -> upsert into `ci_runs` (idempotent on `(repository_id, github_run_id, run_attempt)`, validating 40-char lowercase hex `head_sha`, enforcing `V004` constraint `(status = 'COMPLETED') = (conclusion IS NOT NULL AND completed_at IS NOT NULL)`, resolving `work_item_id` via PR number or branch/title).
    - Add `pull_request_review` handler -> resolve reviewer user via `oauth_accounts (provider='GITHUB', lower(provider_login)=lower(?))`:
      - Enforces head SHA matches current PR head SHA (`pr_head_sha`) if present; rejects stale reviews.
      - Enforces author independence: rejects self-approval when `reviewerLogin` equals PR author `actor_login`!
      - Verifies reviewer holds active `DEVELOPER` project role (matching `V006` trigger `ds_review_evidence`) before inserting `code_reviews` with `reviewer_kind='HUMAN'`, `scope='PR'`, mapping GitHub review states (`approved` -> `PASS`, `changes_requested` -> `REQUIRES_CHANGES`, `commented` -> `PENDING` or ignored if not actionable). Also records `HUMAN_REVIEW` activity in `github_activities`.
  - `application/GitHubRemoteSyncService` + Endpoint + Backfill Hook:
    - **Split TX architecture (NO HTTP inside `@Transactional`)**:
      1. Short read tx: verify caller has `PM` role (for API trigger) or system backfill context, load active `GitHubRepository` (`id`, `projectId`, `githubInstallationId`, `fullName`, `defaultBranch`).
      2. Outside tx: call `GitHubRestClient` to fetch recent PRs, commits, check runs, and PR reviews.
      3. Short write tx: upsert activities, CI runs, and eligible human code reviews idempotently, and update `github_repositories.last_synced_at = now()`.
    - Endpoint on `GitHubRepositoryController`: `POST /api/v1/workspaces/{workspaceId}/projects/{projectId}/github/repositories/{repositoryId}/sync` (PM-only via `accessPolicy.requireProjectRole(..., ProjectRole.PM)`). Returns summary DTO (`repositoryId`, `prsSynced`, `commitsSynced`, `ciRunsSynced`, `reviewsSynced`, `syncedAt`).
    - Optional backfill hook in `GitHubBackfillJobHandler` (preserving 3-arg constructor via `@Autowired` + delegating constructor): when `devsphere.github.app.enabled=true` and `GitHubRemoteSyncService` is available, invokes remote sync outside DB transaction after local event replay (non-fatal on `GitHubApiUnavailableException` — logs warning and reports degraded status cleanly).
- **Allowed Files**:
  - `src/main/resources/application.properties`
  - `src/main/java/com/project/devsphere/gitintegration/**`
  - `src/main/java/com/project/devsphere/operations/application/handlers/GitHubBackfillJobHandler.java`
  - `src/test/java/com/project/devsphere/gitintegration/**`
  - `src/test/java/com/project/devsphere/GitHubLiveSyncAndWebhookIntegrationTest.java`
- **Exclusions**: `frontend/**`, `src/main/resources/db/migration/**`, any new `@Entity`.
- **Acceptance Criteria & Tests**:
  - `GitHubAppJwtSignerTest`, `GitHubInstallationTokenServiceTest`, `GitHubRestClientTest` (unit tests with ephemeral 2048-bit RSA keypair & embedded `HttpServer`).
  - `GitHubLiveSyncAndWebhookIntegrationTest` (Testcontainers + embedded `HttpServer` + ephemeral RSA keypair): covers RS256 JWT verification, installation token caching, `POST .../sync` PM-only RBAC, syncing PRs/commits/check_runs/reviews, `check_run`/`workflow_run`/`pull_request_review` webhook processing, self-approval rejection, head SHA mismatch rejection, Part H invariant (PR merge does NOT mark task `DONE`), and non-fatal 503 degradation when GitHub API fails/rate-limits.
  - Existing `GitHubRepositoryServiceTest`, `GitHubWebhookServiceTest`, `GitHubConnectionIntegrationTest`, `CodeQualityIntegrationTest` all pass.

---

### Coder 4: `m8-storage-export` (S3 Live Object Verification & Rich Reporting Exports)
- **Branch**: `feature/m8-storage-export` from `feature/m8-github-app`
- **Properties (`application.properties`)**:
  - `devsphere.storage.s3.verify-on-confirm=${STORAGE_VERIFY_ON_CONFIRM:false}` (added to `S3StorageProperties`; default `false` so local tests without an S3 server pass; enabled in `application-prod.properties` or explicit AWS tests).
  - `devsphere.storage.s3.http-timeout-ms=10000`
- **Components in `com.project.devsphere.storage`**:
  - `application/StorageObjectClient`: interface exposing:
    - `boolean verifyObjectExists(String presignedUrl)` (`HEAD` check returning `true` on 2xx, `false` on 404, throwing `StorageException` 503 on transport error).
    - `void uploadObject(String presignedPutUrl, byte[] content, String contentType)` (`PUT` upload checking 2xx response, throwing `StorageException` 503 on failure).
  - `infrastructure/HttpPresignedObjectClient`: implements `StorageObjectClient` using `java.net.http.HttpClient`, SSRF-validating presigned URLs (blocking metadata/link-local/multicast/userinfo; honoring `S3StorageProperties.isAllowInsecureEndpoint()` for loopback/HTTP test mocks).
  - `application/AttachmentService` (preserving 4-arg constructor via `@Autowired` + delegating constructor + optional `ObjectProvider<StorageObjectClient>` & `ObjectProvider<S3StorageProperties>`):
    - **No HTTP inside DB write transaction!** When `confirmUpload(...)` runs in `aws` mode with `verifyOnConfirm=true`:
      - Refactor transaction boundary using `TransactionTemplate` (or remove class-level `@Transactional` on `confirmUpload` and wrap DB read/write steps separately):
        1. Short read tx: verify membership + load attachment + generate presigned download/head URL.
        2. Outside tx: call `storageObjectClient.verifyObjectExists(presignedUrl)`. If `false`, throw `StorageException(409, "STORAGE_OBJECT_MISSING", ...)` without marking `CLEAN`.
        3. Short write tx: re-load attachment and transition `scanStatus = CLEAN` + optional `targetVisibility`.
    - Add `registerUploadedReportExport(...)`: persists a `REPORT_EXPORT` attachment row (`report_id = reportId`, `kind = REPORT_EXPORT`, `scan_status = CLEAN`, `visibility` matching report visibility `CLIENT_SHARED` if published & `visibleToClient`, else `INTERNAL`, `sha256` computed from bytes, `file_size = bytes.length`) and returns attachment + presigned download URL.
- **Components in `com.project.devsphere.reporting`**:
  - Fix `ReportService.getReports(...)` (line 175):
    - Remove the hardcoded `Map.of("summary", "Client Published Report")` stub!
    - Parse the actual PostgreSQL `content` JSONB column via Jackson `ObjectMapper` (with `@Autowired(required = false)` fallback) into `Map<String, Object>`.
    - Honor `typeFilter` for CLIENT callers as well as internal callers!
  - Enhance `ReportExportGenerator`:
    - `generatePdf(Report report, String projectName)`: Keep valid `%PDF-1.4\n` header, accurate `xref` byte offsets, and `%%EOF\n` trailer. Render real `report.getContent()` fields (`summary`, flattened `metrics`, `highlights`/`sections`/`facts`, sorted deterministically, line-wrapped and truncated cleanly to fit page bounds, ASCII-sanitized + PDF-escaped).
    - `generateXlsx(Report report, String projectName)`: Render metadata rows + real `report.getContent()` summary & metrics key/value rows into `xl/worksheets/sheet1.xml`.
    - **CWE-1236 Formula Neutralization**: Every cell value written to XLSX passes through a strict `neutralizeFormula(String)` helper BEFORE `escapeXml`: if the value (or `value.stripLeading()`) starts with `'='`, `'+'`, `'-'`, `'@'`, `'\t'`, or `'\r'`, prefix with a single quote `'`!
  - Add `ReportService.exportReportAsAttachment(...)` & `POST /api/v1/workspaces/{workspaceId}/projects/{projectId}/reports/{reportId}/export-attachment?format=PDF|XLSX` on `ReportController`:
    - Returns `ReportExportAttachmentResponse(UUID attachmentId, UUID reportId, String format, String filename, String contentType, long fileSize, String sha256, String downloadUrl, long expiresInSeconds)`.
    - **Split TX architecture**:
      1. Short read tx: verify caller access (CLIENT-only blocked from unpublished reports), load `Report` + `projectName`, generate PDF/XLSX bytes, compute SHA-256 hex, determine storage key & visibility (`CLIENT_SHARED` iff `PUBLISHED` and `visibleToClient`, else `INTERNAL`).
      2. Outside tx (when `storageProvider.mode().equals("aws")` and `StorageObjectClient` available): generate presigned `PUT` URL and upload bytes via `storageObjectClient.uploadObject(...)`.
      3. Short write tx: save `REPORT_EXPORT` `Attachment` row via `AttachmentService.registerUploadedReportExport(...)` and return presigned download URL.
- **Allowed Files**:
  - `src/main/resources/application.properties`
  - `src/main/resources/application-prod.properties`
  - `src/main/java/com/project/devsphere/storage/**`
  - `src/main/java/com/project/devsphere/reporting/**`
  - `src/test/java/com/project/devsphere/storage/**`
  - `src/test/java/com/project/devsphere/reporting/**`
  - `src/test/java/com/project/devsphere/ReportS3ExportIntegrationTest.java`
- **Exclusions**: `frontend/**`, `src/main/resources/db/migration/**`, any new `@Entity`.
- **Acceptance Criteria & Tests**:
  - `ReportExportGeneratorContentTest`: PDF renders real summary/metrics with valid `%PDF-1.4`..`%%EOF` and exact xref; XLSX renders real content rows AND neutralizes `=CMD()`, `+1+1`, `-2+3`, `@SUM(A1)`, `  =HYPERLINK(...)`, `\t=1+1`, `\r=1+1` (CWE-1236).
  - `S3ObjectVerificationTest`: `HttpPresignedObjectClient` `HEAD` 200/404/500 & `PUT` 200/500 against embedded `HttpServer`, SSRF rejection of metadata IPs, `AttachmentService.confirmUpload` rejecting missing object in `aws` + `verifyOnConfirm=true` mode.
  - `ReportS3ExportIntegrationTest`: CLIENT `getReports` returns real `content` JSONB and honors `typeFilter`; `POST .../export-attachment?format=PDF|XLSX` uploads bytes to embedded S3 mock in `aws` mode, persists `REPORT_EXPORT` attachment with valid SHA-256, and returns presigned download URL.
  - Existing `ReportServiceTest`, `ReportAndExportIntegrationTest`, `AttachmentServiceTest`, `AwsS3StorageProviderTest`, `S3StorageProviderTest` all pass.

---

### Coder 5: `m8-seed-e2e` (Production-Safe Demo Seed Data & Full Cross-Module Backend E2E Suite)
- **Branch**: `feature/m8-seed-e2e` from `feature/m8-storage-export`
- **Properties**:
  - `application.properties`: `devsphere.seed.demo.enabled=${DEVSPHERE_SEED_DEMO_ENABLED:false}`
  - `application-prod.properties`: `devsphere.seed.demo.enabled=false` (hard-pinned `false` in production!)
- **Components**:
  - `com.project.devsphere.operations.application.DemoSeedDataService`:
    - Uses Argon2id `PasswordEncoder` (matching `SessionService` default: `new Argon2PasswordEncoder(16, 32, 1, 19456, 2)` or injected `ObjectProvider<PasswordEncoder>`).
    - **Idempotent upserts across existing 36 tables** (zero new tables/entities, strictly obeying EVERY `V001..V010` constraint & `V006` trigger!):
      - Users (with valid hex UUIDs, `email_verified_at = now()`, `status = 'ACTIVE'`):
        - System Admin (`admin@devsphere.io`, `ADMIN`)
        - Workspace Owner (`owner@devsphere.io`, `USER`)
        - Project Manager (`pm@devsphere.io`, `USER`)
        - Lead Dev (`dev1@devsphere.io`, `USER`) + Peer Dev (`dev2@devsphere.io`, `USER`) — both with linked `oauth_accounts` (`provider='GITHUB'`) so independent human code reviews satisfy `ds_review_evidence`!
        - Client (`client@devsphere.io`, `USER`)
        - Viewer (`viewer@devsphere.io`, `USER`)
      - Workspace `devsphere-core` (Owner in `workspace_members` as `'OWNER'` inside same tx so deferrable owner constraint commits cleanly; `ai_connections` with safe non-secret fields, `ai_task_routes`, `settings.aiPrivacyPolicy`).
      - Project `DSP` (created as `PLANNING`, populated with backlog items, then transitioned to `ACTIVE`; PM role accepted; Developer/Client/Viewer roles assigned).
      - Requirements (`REQ-1`..`REQ-3`) + immutable `requirement_versions`.
      - Work Items (Epics, Stories, Tasks across `TO_DO`, `IN_PROGRESS`, `IN_REVIEW`, `DONE` — respecting valid state transitions `TO_DO -> IN_PROGRESS -> IN_REVIEW -> DONE` with `accepted_by = pmId`!).
      - Dependencies (`work_item_dependencies`, acyclic DAG).
      - Sprint (`ACTIVE`), Milestone (`PLANNED` / published), `developer_capacity` for both Devs, `plan_proposals` (starting `DRAFT` per `ds_proposal_guard`!).
      - `github_repositories`, `github_activities` (PR authored by `dev1`), `ci_runs` (`COMPLETED`/`SUCCESS`), `code_reviews` (`HUMAN` review by `dev2` with independent GitHub login!).
      - `chat_channels` (`TEAM` internal channel + `CLIENT_PM` channel) + `chat_channel_members` + `chat_messages`.
      - `alerts`, `reports` (DRAFT + PUBLISHED visible to client).
    - Returns structured `DemoSeedResultResponse` (deterministic IDs & counts, idempotent when invoked twice in a row!).
  - `com.project.devsphere.operations.application.DemoSeedRunner`:
    - `ApplicationRunner` annotated with `@ConditionalOnProperty(name = "devsphere.seed.demo.enabled", havingValue = "true")` AND `@Profile("!prod")`.
    - Runs `demoSeedDataService.seedDemoData()` on startup only when enabled outside `prod`.
  - Admin Endpoint in `OperationsAdminController` (preserving legacy 2-arg constructor!):
    - `POST /api/v1/admin/operations/seed-demo`
    - Strictly gated by BOTH `accessPolicy.requireSystemAdmin(principal)` AND `devsphere.seed.demo.enabled=true` (returns **403 Forbidden** `DEMO_SEED_DISABLED` when disabled or active `prod` profile!).
- **Tests**:
  - `DemoSeedDataIntegrationTest`:
    - Verifies default (`devsphere.seed.demo.enabled=false`) blocks `POST /api/v1/admin/operations/seed-demo` with 403 even for System Admin.
    - Verifies enabled mode seeds idempotently (calling twice succeeds with identical counts & no duplicate rows), seeded users can log in via `SessionService.login` with Argon2id password, and all 36-table DB invariants hold.
  - `Week8EndToEndJourneyIntegrationTest` (`@SpringBootTest(RANDOM_PORT)` + Testcontainers + live STOMP WebSocket + embedded HTTP servers for OpenAI/vLLM, GitHub API, and S3):
    - Full cross-module journey covering `TC-01..25`:
      - Seed/Auth/CSRF/Bearer session lifecycle + `TC-03` live WebSocket disconnect on workspace member removal.
      - Live STOMP chat broadcast + CLIENT-only channel/control-tower isolation + live notification push.
      - AI Workspace config (`OPENAI` + `VLLM_SELF_HOST`), `TC-21` routing, `TC-22` `SELF_HOST_ONLY` blocking cloud with 0 outbound cloud calls & 503, `TC-23` plan cycle/negative estimate rejection, `TC-24` PR review file/line bounds check, `TC-25` report summary fact mismatch -> `NEEDS_REVIEW`, plus `@ProjectAI` chat reply.
      - GitHub App RS256 JWT -> installation token -> `POST .../sync` -> webhook `check_run` & `pull_request_review` (independent reviewer passes, self-approval rejected, PR merge does NOT mark task `DONE`).
      - Report publish -> CLIENT `getReports` receives real content -> `POST .../export-attachment?format=XLSX` uploads to S3 mock & verifies CWE-1236 formula neutralization -> Attachment `confirmUpload` with S3 `HEAD` verification.
  - Run targeted tests AND full backend suite:
    ```bash
    JAVA_HOME=./.tools/jdk-27/Contents/Home ./mvnw -B -Dmaven.repo.local=.tools/m2/repository verify
    ```
- **Allowed Files**:
  - `src/main/resources/application.properties`
  - `src/main/resources/application-prod.properties`
  - `src/main/java/com/project/devsphere/operations/**`
  - `src/test/java/com/project/devsphere/DemoSeedDataIntegrationTest.java`
  - `src/test/java/com/project/devsphere/Week8EndToEndJourneyIntegrationTest.java`
- **Exclusions**: `frontend/**`, `src/main/resources/db/migration/**`, any new `@Entity`.

---

## 4. Final Release Gate (`m8-dev-lead`)

1. Merge `feature/m8-seed-e2e` into `feature/m8-backend-release` via `git merge --no-ff`.
2. Verify `git diff --name-only feature/m7-fullstack-release..HEAD -- frontend/` is strictly empty.
3. Verify `DevsphereApplicationTests.migratesThirtySixTablesAndValidatesEveryJpaEntity()` passes (36 tables, 36 `@Entity` classes, 10 migrations).
4. Verify full `./mvnw -B -Dmaven.repo.local=.tools/m2/repository verify` passes with 0 failures, 0 errors, 0 skipped required tests.
5. Verify clean `git status` and leave all 5 coder subagents in `waiting` state for live inspection.

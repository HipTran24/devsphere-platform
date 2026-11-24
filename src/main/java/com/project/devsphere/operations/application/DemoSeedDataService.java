package com.project.devsphere.operations.application;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Idempotent demo data seeder covering the DevSphere 36-table PostgreSQL schema while strictly
 * respecting every {@code V001..V010} constraint and {@code V006} workflow/security trigger.
 */
@Service
public class DemoSeedDataService {

    public static final String DEFAULT_DEMO_PASSPHRASE = "A strong local demo test passphrase";
    public static final String WORKSPACE_SLUG = "devsphere-core";
    public static final String PROJECT_KEY = "DSP";

    public static final String ADMIN_EMAIL = "admin@devsphere.io";
    public static final String OWNER_EMAIL = "owner@devsphere.io";
    public static final String PM_EMAIL = "pm@devsphere.io";
    public static final String DEV1_EMAIL = "dev1@devsphere.io";
    public static final String DEV2_EMAIL = "dev2@devsphere.io";
    public static final String CLIENT_EMAIL = "client@devsphere.io";
    public static final String VIEWER_EMAIL = "viewer@devsphere.io";

    public static final String DEV1_GITHUB_LOGIN = "dev1-gh";
    public static final String DEV2_GITHUB_LOGIN = "dev2-gh";
    public static final long DEMO_GITHUB_INSTALLATION_ID = 55001L;
    public static final long DEMO_GITHUB_REPO_ID = 770001L;
    public static final String DEMO_GITHUB_FULL_NAME = "devsphere-demo/platform";
    public static final String DEMO_PR_HEAD_SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    public static final String DEMO_ALERT_FINGERPRINT = "b".repeat(64);

    // Deterministic valid hex UUIDs
    public static final UUID ADMIN_USER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    public static final UUID OWNER_USER_ID = UUID.fromString("10000000-0000-4000-8000-000000000002");
    public static final UUID PM_USER_ID = UUID.fromString("10000000-0000-4000-8000-000000000003");
    public static final UUID DEV1_USER_ID = UUID.fromString("10000000-0000-4000-8000-000000000004");
    public static final UUID DEV2_USER_ID = UUID.fromString("10000000-0000-4000-8000-000000000005");
    public static final UUID CLIENT_USER_ID = UUID.fromString("10000000-0000-4000-8000-000000000006");
    public static final UUID VIEWER_USER_ID = UUID.fromString("10000000-0000-4000-8000-000000000007");

    public static final UUID DEV1_OAUTH_ID = UUID.fromString("11000000-0000-4000-8000-000000000001");
    public static final UUID DEV2_OAUTH_ID = UUID.fromString("11000000-0000-4000-8000-000000000002");

    public static final UUID WORKSPACE_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    public static final UUID PROJECT_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");

    private static final UUID PM_OWNER_MEMBER_ID = UUID.fromString("31000000-0000-4000-8000-000000000001");
    private static final UUID PM_PM_MEMBER_ID = UUID.fromString("31000000-0000-4000-8000-000000000002");
    private static final UUID PM_DEV1_MEMBER_ID = UUID.fromString("31000000-0000-4000-8000-000000000003");
    private static final UUID PM_DEV2_MEMBER_ID = UUID.fromString("31000000-0000-4000-8000-000000000004");
    private static final UUID PM_CLIENT_MEMBER_ID = UUID.fromString("31000000-0000-4000-8000-000000000005");
    private static final UUID PM_VIEWER_MEMBER_ID = UUID.fromString("31000000-0000-4000-8000-000000000006");

    private static final UUID ROLE_OWNER_VIEWER_ID = UUID.fromString("32000000-0000-4000-8000-000000000001");
    private static final UUID ROLE_PM_ID = UUID.fromString("32000000-0000-4000-8000-000000000002");
    private static final UUID ROLE_DEV1_ID = UUID.fromString("32000000-0000-4000-8000-000000000003");
    private static final UUID ROLE_DEV2_ID = UUID.fromString("32000000-0000-4000-8000-000000000004");
    private static final UUID ROLE_CLIENT_ID = UUID.fromString("32000000-0000-4000-8000-000000000005");
    private static final UUID ROLE_VIEWER_ID = UUID.fromString("32000000-0000-4000-8000-000000000006");

    public static final UUID REQ1_ID = UUID.fromString("40000000-0000-4000-8000-000000000001");
    public static final UUID REQ2_ID = UUID.fromString("40000000-0000-4000-8000-000000000002");
    public static final UUID REQ3_ID = UUID.fromString("40000000-0000-4000-8000-000000000003");
    public static final UUID REQ1_V1_ID = UUID.fromString("41000000-0000-4000-8000-000000000001");
    public static final UUID REQ2_V1_ID = UUID.fromString("41000000-0000-4000-8000-000000000002");
    public static final UUID REQ3_V1_ID = UUID.fromString("41000000-0000-4000-8000-000000000003");

    public static final UUID SPRINT_ID = UUID.fromString("50000000-0000-4000-8000-000000000001");
    public static final UUID MILESTONE_ID = UUID.fromString("51000000-0000-4000-8000-000000000001");

    public static final UUID EPIC_1_ID = UUID.fromString("60000000-0000-4000-8000-000000000001");
    public static final UUID STORY_2_ID = UUID.fromString("60000000-0000-4000-8000-000000000002");
    public static final UUID STORY_3_ID = UUID.fromString("60000000-0000-4000-8000-000000000003");
    public static final UUID TASK_4_DONE_ID = UUID.fromString("60000000-0000-4000-8000-000000000004");
    public static final UUID TASK_5_REVIEW_ID = UUID.fromString("60000000-0000-4000-8000-000000000005");
    public static final UUID TASK_6_PROGRESS_ID = UUID.fromString("60000000-0000-4000-8000-000000000006");
    public static final UUID TASK_7_TODO_ID = UUID.fromString("60000000-0000-4000-8000-000000000007");

    private static final UUID DEP_4_5_ID = UUID.fromString("61000000-0000-4000-8000-000000000001");
    private static final UUID DEP_5_6_ID = UUID.fromString("61000000-0000-4000-8000-000000000002");
    private static final UUID DEP_6_7_ID = UUID.fromString("61000000-0000-4000-8000-000000000003");

    private static final UUID AC_1_ID = UUID.fromString("62000000-0000-4000-8000-000000000001");
    private static final UUID AC_2_ID = UUID.fromString("62000000-0000-4000-8000-000000000002");

    private static final UUID CAP_DEV1_ID = UUID.fromString("70000000-0000-4000-8000-000000000001");
    private static final UUID CAP_DEV2_ID = UUID.fromString("70000000-0000-4000-8000-000000000002");
    public static final UUID PLAN_PROPOSAL_ID = UUID.fromString("71000000-0000-4000-8000-000000000001");

    public static final UUID GITHUB_REPO_ID = UUID.fromString("80000000-0000-4000-8000-000000000001");
    private static final UUID GITHUB_PR_ACTIVITY_ID = UUID.fromString("81000000-0000-4000-8000-000000000001");
    private static final UUID CI_RUN_ID = UUID.fromString("82000000-0000-4000-8000-000000000001");
    private static final UUID CODE_REVIEW_ID = UUID.fromString("83000000-0000-4000-8000-000000000001");

    public static final UUID TEAM_CHANNEL_ID = UUID.fromString("90000000-0000-4000-8000-000000000001");
    public static final UUID CLIENT_PM_CHANNEL_ID = UUID.fromString("90000000-0000-4000-8000-000000000002");
    private static final UUID MSG_TEAM_1_ID = UUID.fromString("91000000-0000-4000-8000-000000000001");
    private static final UUID MSG_TEAM_2_ID = UUID.fromString("91000000-0000-4000-8000-000000000002");
    private static final UUID MSG_CLIENT_1_ID = UUID.fromString("91000000-0000-4000-8000-000000000003");
    private static final UUID MSG_CLIENT_2_ID = UUID.fromString("91000000-0000-4000-8000-000000000004");

    public static final UUID ALERT_ID = UUID.fromString("a0000000-0000-4000-8000-000000000001");
    public static final UUID DRAFT_REPORT_ID = UUID.fromString("b0000000-0000-4000-8000-000000000001");
    public static final UUID PUBLISHED_REPORT_ID = UUID.fromString("b0000000-0000-4000-8000-000000000002");

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final PasswordEncoder passwordEncoder;
    private final String demoPassphrase;

    public DemoSeedDataService(
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate,
            ObjectProvider<PasswordEncoder> passwordEncoderProvider) {
        this(jdbcTemplate, transactionTemplate, passwordEncoderProvider, "");
    }

    @Autowired
    public DemoSeedDataService(
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate,
            ObjectProvider<PasswordEncoder> passwordEncoderProvider,
            @Value("${devsphere.seed.demo.password:}") String configuredDemoPassphrase) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.transactionTemplate = Objects.requireNonNull(transactionTemplate, "transactionTemplate must not be null");
        this.passwordEncoder = passwordEncoderProvider != null
                ? passwordEncoderProvider.getIfAvailable(() -> new Argon2PasswordEncoder(16, 32, 1, 19456, 2))
                : new Argon2PasswordEncoder(16, 32, 1, 19456, 2);
        this.demoPassphrase = (configuredDemoPassphrase != null && !configuredDemoPassphrase.isBlank())
                ? configuredDemoPassphrase
                : DEFAULT_DEMO_PASSPHRASE;
    }

    public String resolvedDemoPassphrase() {
        return demoPassphrase;
    }

    private record DemoUserSpec(UUID id, String key, String email, String displayName, String systemRole) {
    }

    private static final List<DemoUserSpec> DEMO_USERS = List.of(
            new DemoUserSpec(ADMIN_USER_ID, "admin", ADMIN_EMAIL, "System Admin", "ADMIN"),
            new DemoUserSpec(OWNER_USER_ID, "owner", OWNER_EMAIL, "Workspace Owner", "USER"),
            new DemoUserSpec(PM_USER_ID, "pm", PM_EMAIL, "Project Manager", "USER"),
            new DemoUserSpec(DEV1_USER_ID, "dev1", DEV1_EMAIL, "Lead Developer", "USER"),
            new DemoUserSpec(DEV2_USER_ID, "dev2", DEV2_EMAIL, "Peer Developer", "USER"),
            new DemoUserSpec(CLIENT_USER_ID, "client", CLIENT_EMAIL, "Client Stakeholder", "USER"),
            new DemoUserSpec(VIEWER_USER_ID, "viewer", VIEWER_EMAIL, "Project Viewer", "USER")
    );

    /**
     * Idempotently seeds all demo fixtures across existing tables. Safe to invoke repeatedly.
     */
    public DemoSeedResultResponse seedDemoData() {
        // Compute Argon2id password hashes outside the DB transaction only for users not yet present
        Map<UUID, String> missingUserHashes = new HashMap<>();
        for (DemoUserSpec spec : DEMO_USERS) {
            if (!userExists(spec.id(), spec.email())) {
                missingUserHashes.put(spec.id(), passwordEncoder.encode(this.demoPassphrase));
            }
        }

        return Objects.requireNonNull(transactionTemplate.execute(status -> {
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

            seedUsersAndOAuth(now, missingUserHashes);
            seedWorkspaceAndMembers(now);
            seedProjectAndRoles(now);
            seedRequirements(now);
            seedSprintAndMilestone(now);
            seedWorkItemsAndActivateProject(now);
            seedDependenciesAndAcceptanceCriteria(now);
            seedDeveloperCapacities(now);
            seedPlanProposal(now);
            seedGitHubAndCodeQuality(now);
            seedChatChannelsAndMessages(now);
            seedAlert(now);
            seedReports(now);

            Map<String, UUID> userMap = new LinkedHashMap<>();
            for (DemoUserSpec spec : DEMO_USERS) {
                userMap.put(spec.key(), spec.id());
            }

            Map<String, UUID> channelMap = new LinkedHashMap<>();
            channelMap.put("team", TEAM_CHANNEL_ID);
            channelMap.put("clientPm", CLIENT_PM_CHANNEL_ID);

            Map<String, Integer> counts = collectCounts();

            return new DemoSeedResultResponse(
                    true,
                    WORKSPACE_SLUG,
                    WORKSPACE_ID,
                    PROJECT_KEY,
                    PROJECT_ID,
                    userMap,
                    channelMap,
                    GITHUB_REPO_ID,
                    SPRINT_ID,
                    MILESTONE_ID,
                    PLAN_PROPOSAL_ID,
                    counts,
                    Instant.now()
            );
        }));
    }

    private boolean userExists(UUID userId, String email) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM users WHERE id = ? OR email = ?::citext",
                Integer.class,
                userId, email
        );
        return count != null && count > 0;
    }

    private boolean existsById(String table, UUID id) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE id = ?",
                Integer.class,
                id
        );
        return count != null && count > 0;
    }

    private void seedUsersAndOAuth(OffsetDateTime now, Map<UUID, String> missingUserHashes) {
        for (DemoUserSpec spec : DEMO_USERS) {
            if (!userExists(spec.id(), spec.email())) {
                String hash = missingUserHashes.computeIfAbsent(spec.id(), k -> passwordEncoder.encode(this.demoPassphrase));
                jdbcTemplate.update("""
                        INSERT INTO users (
                            id, email, password_hash, display_name, system_role, status,
                            email_verified_at, created_at, updated_at, version
                        ) VALUES (?, ?::citext, ?, ?, ?, 'ACTIVE', ?, ?, ?, 0)
                        ON CONFLICT DO NOTHING
                        """,
                        spec.id(), spec.email(), hash, spec.displayName(), spec.systemRole(), now, now, now
                );
            }
        }

        jdbcTemplate.update("""
                INSERT INTO oauth_accounts (id, user_id, provider, provider_subject, provider_login, linked_at, created_at)
                VALUES (?, ?, 'GITHUB', 'gh-demo-dev1', ?, ?, ?)
                ON CONFLICT DO NOTHING
                """,
                DEV1_OAUTH_ID, DEV1_USER_ID, DEV1_GITHUB_LOGIN, now, now
        );
        jdbcTemplate.update("""
                INSERT INTO oauth_accounts (id, user_id, provider, provider_subject, provider_login, linked_at, created_at)
                VALUES (?, ?, 'GITHUB', 'gh-demo-dev2', ?, ?, ?)
                ON CONFLICT DO NOTHING
                """,
                DEV2_OAUTH_ID, DEV2_USER_ID, DEV2_GITHUB_LOGIN, now, now
        );
    }

    private void seedWorkspaceAndMembers(OffsetDateTime now) {
        if (!existsById("workspaces", WORKSPACE_ID)) {
            String settingsJson = "{\"schemaVersion\":1,\"aiPrivacyPolicy\":\"CLOUD_ALLOWED\"}";
            String aiConnectionsJson = "["
                    + "{\"id\":\"demo-vllm\",\"provider\":\"VLLM_SELF_HOST\",\"baseUrl\":\"https://vllm.devsphere.internal\",\"model\":\"qwen-2.5-coder-32b\",\"enabled\":true},"
                    + "{\"id\":\"demo-openai\",\"provider\":\"OPENAI\",\"baseUrl\":\"https://api.openai.com\",\"model\":\"gpt-4o\",\"enabled\":true}"
                    + "]";
            String aiTaskRoutesJson = "{\"schemaVersion\":1,\"REQUIREMENT_PLAN\":\"demo-vllm\",\"PR_REVIEW\":\"demo-vllm\",\"CHAT_ANSWER\":\"demo-openai\",\"REPORT_SUMMARY\":\"demo-openai\"}";

            jdbcTemplate.update("""
                    INSERT INTO workspaces (
                        id, slug, name, owner_id, description, status,
                        settings, ai_connections, ai_task_routes, created_at, updated_at, version
                    ) VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, 0)
                    ON CONFLICT DO NOTHING
                    """,
                    WORKSPACE_ID, WORKSPACE_SLUG, "DevSphere Core", OWNER_USER_ID,
                    "Primary DevSphere demo engineering workspace",
                    settingsJson, aiConnectionsJson, aiTaskRoutesJson, now, now
            );
        }

        insertWorkspaceMember(OWNER_USER_ID, "OWNER", now);
        insertWorkspaceMember(PM_USER_ID, "MEMBER", now);
        insertWorkspaceMember(DEV1_USER_ID, "MEMBER", now);
        insertWorkspaceMember(DEV2_USER_ID, "MEMBER", now);
        insertWorkspaceMember(CLIENT_USER_ID, "MEMBER", now);
        insertWorkspaceMember(VIEWER_USER_ID, "MEMBER", now);
    }

    private void insertWorkspaceMember(UUID userId, String role, OffsetDateTime now) {
        jdbcTemplate.update("""
                INSERT INTO workspace_members (workspace_id, user_id, role, status, joined_at, created_at, updated_at, version)
                VALUES (?, ?, ?, 'ACTIVE', ?, ?, ?, 0)
                ON CONFLICT (workspace_id, user_id) DO NOTHING
                """,
                WORKSPACE_ID, userId, role, now, now, now
        );
    }

    private void seedProjectAndRoles(OffsetDateTime now) {
        if (!existsById("projects", PROJECT_ID)) {
            // Must start as PLANNING with committed_deadline=NULL per V006 project_activation & deadline_guard
            jdbcTemplate.update("""
                    INSERT INTO projects (
                        id, workspace_id, project_key, name, description, objective, status, timezone,
                        planned_start, committed_deadline, sprint_length_days, created_at, updated_at, version
                    ) VALUES (?, ?, ?, ?, ?, ?, 'PLANNING', 'UTC', ?, NULL, 14, ?, ?, 0)
                    ON CONFLICT DO NOTHING
                    """,
                    PROJECT_ID, WORKSPACE_ID, PROJECT_KEY, "DevSphere Platform",
                    "Integrated agile delivery, real-time collaboration, and AI engineering demo project",
                    "Deliver DevSphere integrated agile & AI engineering platform",
                    Date.valueOf(LocalDate.of(2026, 11, 1)), now, now
            );
        }

        insertProjectMember(PM_OWNER_MEMBER_ID, OWNER_USER_ID, now);
        insertProjectMember(PM_PM_MEMBER_ID, PM_USER_ID, now);
        insertProjectMember(PM_DEV1_MEMBER_ID, DEV1_USER_ID, now);
        insertProjectMember(PM_DEV2_MEMBER_ID, DEV2_USER_ID, now);
        insertProjectMember(PM_CLIENT_MEMBER_ID, CLIENT_USER_ID, now);
        insertProjectMember(PM_VIEWER_MEMBER_ID, VIEWER_USER_ID, now);

        insertProjectRole(ROLE_OWNER_VIEWER_ID, PM_OWNER_MEMBER_ID, "VIEWER", OWNER_USER_ID, null, null, now);
        insertProjectRole(ROLE_PM_ID, PM_PM_MEMBER_ID, "PM", OWNER_USER_ID, PM_USER_ID, now, now);
        insertProjectRole(ROLE_DEV1_ID, PM_DEV1_MEMBER_ID, "DEVELOPER", OWNER_USER_ID, null, null, now);
        insertProjectRole(ROLE_DEV2_ID, PM_DEV2_MEMBER_ID, "DEVELOPER", OWNER_USER_ID, null, null, now);
        insertProjectRole(ROLE_CLIENT_ID, PM_CLIENT_MEMBER_ID, "CLIENT", OWNER_USER_ID, null, null, now);
        insertProjectRole(ROLE_VIEWER_ID, PM_VIEWER_MEMBER_ID, "VIEWER", OWNER_USER_ID, null, null, now);
    }

    private void insertProjectMember(UUID memberId, UUID userId, OffsetDateTime now) {
        jdbcTemplate.update("""
                INSERT INTO project_members (id, project_id, workspace_id, user_id, status, joined_at, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, ?, 0)
                ON CONFLICT (project_id, user_id) DO NOTHING
                """,
                memberId, PROJECT_ID, WORKSPACE_ID, userId, now, now, now
        );
    }

    private void insertProjectRole(
            UUID roleId,
            UUID projectMemberId,
            String role,
            UUID grantedBy,
            UUID acceptedBy,
            OffsetDateTime acceptedAt,
            OffsetDateTime now) {
        if (existsById("project_member_roles", roleId)) {
            return;
        }
        jdbcTemplate.update("""
                INSERT INTO project_member_roles (
                    id, project_id, project_member_id, role, granted_by, granted_at, accepted_by, accepted_at, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """,
                roleId, PROJECT_ID, projectMemberId, role, grantedBy, now, acceptedBy, acceptedAt, now
        );
    }

    private void seedRequirements(OffsetDateTime now) {
        insertRequirementAndVersion(
                REQ1_ID, REQ1_V1_ID, "REQ-1", "Authentication & Real-Time Collaboration",
                "Deliver Argon2id sessions, CSRF protection, and STOMP WebSocket real-time updates with live RBAC.",
                "APPROVED", OWNER_USER_ID, PM_USER_ID, now, now
        );
        insertRequirementAndVersion(
                REQ2_ID, REQ2_V1_ID, "REQ-2", "AI Multi-Provider Routing & Validation Gate",
                "Route AI jobs across OpenAI and self-hosted vLLM respecting workspace privacy policies and strict validation.",
                "APPROVED", PM_USER_ID, PM_USER_ID, now, now
        );
        insertRequirementAndVersion(
                REQ3_ID, REQ3_V1_ID, "REQ-3", "GitHub Quality Gates & Client Reporting",
                "Synchronize GitHub PRs, CI runs, independent human reviews, and S3-backed client report exports.",
                "DRAFT", CLIENT_USER_ID, null, null, now
        );
    }

    private void insertRequirementAndVersion(
            UUID reqId,
            UUID versionId,
            String code,
            String title,
            String description,
            String status,
            UUID requestedBy,
            UUID approvedBy,
            OffsetDateTime approvedAt,
            OffsetDateTime now) {
        if (!existsById("requirements", reqId)) {
            jdbcTemplate.update("""
                    INSERT INTO requirements (
                        id, project_id, code, kind, title, description, status, current_version,
                        requested_by, approved_by, created_at, updated_at, version
                    ) VALUES (?, ?, ?, 'REQUIREMENT', ?, ?, ?, 1, ?, ?, ?, ?, 0)
                    ON CONFLICT DO NOTHING
                    """,
                    reqId, PROJECT_ID, code, title, description, status, requestedBy, approvedBy, now, now
            );
        }
        if (!existsById("requirement_versions", versionId)) {
            jdbcTemplate.update("""
                    INSERT INTO requirement_versions (
                        id, project_id, requirement_id, version_no, description, acceptance_summary, approved_at, created_at
                    ) VALUES (?, ?, ?, 1, ?, ?, ?, ?)
                    ON CONFLICT DO NOTHING
                    """,
                    versionId, PROJECT_ID, reqId, description,
                    "All automated integration tests and security invariants verified.",
                    approvedAt, now
            );
        }
    }

    private void seedSprintAndMilestone(OffsetDateTime now) {
        if (!existsById("sprints", SPRINT_ID)) {
            jdbcTemplate.update("""
                    INSERT INTO sprints (
                        id, project_id, name, goal, start_date, end_date, status, committed_points, created_at, updated_at, version
                    ) VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, 0)
                    ON CONFLICT DO NOTHING
                    """,
                    SPRINT_ID, PROJECT_ID,
                    "Sprint 1 - Core Integration",
                    "Deliver real-time collaboration, AI routing, GitHub App sync, and S3 exports",
                    Date.valueOf(LocalDate.of(2026, 11, 16)),
                    Date.valueOf(LocalDate.of(2026, 11, 30)),
                    new BigDecimal("21.00"),
                    now, now
            );
        }

        if (!existsById("milestones", MILESTONE_ID)) {
            jdbcTemplate.update("""
                    INSERT INTO milestones (
                        id, project_id, code, name, description, due_date, forecast_date,
                        status, acceptance_status, published_to_client, created_at, updated_at, version
                    ) VALUES (?, ?, 'MS-1', ?, ?, ?, ?, 'PLANNED', 'NOT_SUBMITTED', true, ?, ?, 0)
                    ON CONFLICT DO NOTHING
                    """,
                    MILESTONE_ID, PROJECT_ID,
                    "M8 Backend Integration Release",
                    "Production-ready backend milestone with real-time STOMP, AI routing, GitHub App, and S3 reporting.",
                    Date.valueOf(LocalDate.of(2026, 12, 15)),
                    Date.valueOf(LocalDate.of(2026, 12, 14)),
                    now, now
            );
        }
    }

    private void seedWorkItemsAndActivateProject(OffsetDateTime now) {
        if (!existsById("work_items", EPIC_1_ID)) {
            jdbcTemplate.update("""
                    INSERT INTO work_items (
                        id, project_id, item_key, requirement_id, milestone_id, type, work_kind,
                        title, description, status, priority, backlog_rank, created_at, updated_at, version
                    ) VALUES (?, ?, 'DSP-1', ?, ?, 'EPIC', 'CODE', ?, ?, 'IN_PROGRESS', 'HIGH', 100, ?, ?, 0)
                    """,
                    EPIC_1_ID, PROJECT_ID, REQ1_ID, MILESTONE_ID,
                    "Platform Security & Real-Time Foundation",
                    "Core backend architecture spanning authentication, WebSocket delivery, AI orchestration, and quality gates.",
                    now, now
            );
        }

        if (!existsById("work_items", STORY_2_ID)) {
            jdbcTemplate.update("""
                    INSERT INTO work_items (
                        id, project_id, item_key, parent_id, requirement_id, sprint_id, milestone_id,
                        type, work_kind, title, description, status, priority, backlog_rank, story_points,
                        created_at, updated_at, version
                    ) VALUES (?, ?, 'DSP-2', ?, ?, ?, ?, 'USER_STORY', 'CODE', ?, ?, 'IN_PROGRESS', 'HIGH', 200, ?, ?, ?, 0)
                    """,
                    STORY_2_ID, PROJECT_ID, EPIC_1_ID, REQ1_ID, SPRINT_ID, MILESTONE_ID,
                    "STOMP WebSocket Broker & GitHub Quality Gates",
                    "Real-time chat, notifications, and GitHub App review verification.",
                    new BigDecimal("8.00"), now, now
            );
        }

        if (!existsById("work_items", STORY_3_ID)) {
            jdbcTemplate.update("""
                    INSERT INTO work_items (
                        id, project_id, item_key, parent_id, requirement_id, sprint_id, milestone_id,
                        type, work_kind, title, description, status, priority, backlog_rank, story_points,
                        created_at, updated_at, version
                    ) VALUES (?, ?, 'DSP-3', ?, ?, ?, ?, 'USER_STORY', 'CODE', ?, ?, 'IN_PROGRESS', 'HIGH', 300, ?, ?, ?, 0)
                    """,
                    STORY_3_ID, PROJECT_ID, EPIC_1_ID, REQ2_ID, SPRINT_ID, MILESTONE_ID,
                    "AI Multi-Provider Routing & S3 Report Exports",
                    "Privacy-aware OpenAI/vLLM routing, validation gates, and S3-backed reporting.",
                    new BigDecimal("8.00"), now, now
            );
        }

        // DSP-4: DONE (must transition TO_DO -> IN_PROGRESS -> IN_REVIEW -> DONE with accepted_by = PM)
        if (!existsById("work_items", TASK_4_DONE_ID)) {
            jdbcTemplate.update("""
                    INSERT INTO work_items (
                        id, project_id, item_key, parent_id, requirement_id, sprint_id, milestone_id,
                        type, work_kind, title, description, status, priority, assignee_id, required_skills,
                        backlog_rank, story_points, estimate_minutes, remaining_minutes, time_spent_minutes,
                        created_at, updated_at, version
                    ) VALUES (?, ?, 'DSP-4', ?, ?, ?, ?, 'TASK', 'CODE', ?, ?, 'TO_DO', 'HIGH', ?, '[{"skill":"java","level":4}]'::jsonb,
                        400, ?, 480, 480, 0, ?, ?, 0)
                    """,
                    TASK_4_DONE_ID, PROJECT_ID, STORY_2_ID, REQ1_ID, SPRINT_ID, MILESTONE_ID,
                    "Implement STOMP JWT Channel Interceptor",
                    "Enforce Bearer STOMP CONNECT and live RBAC checks on SUBSCRIBE.",
                    DEV1_USER_ID, new BigDecimal("5.00"), now, now
            );
            jdbcTemplate.update(
                    "UPDATE work_items SET status = 'IN_PROGRESS', started_at = ? WHERE id = ?",
                    now.minusHours(4), TASK_4_DONE_ID
            );
            jdbcTemplate.update(
                    "UPDATE work_items SET status = 'IN_REVIEW', claimed_progress = 95 WHERE id = ?",
                    TASK_4_DONE_ID
            );
            jdbcTemplate.update("""
                    UPDATE work_items
                    SET status = 'DONE', completed_at = ?, accepted_by = ?, remaining_minutes = 0,
                        time_spent_minutes = 450, claimed_progress = 100, verified_progress = 100
                    WHERE id = ?
                    """,
                    now.minusHours(1), PM_USER_ID, TASK_4_DONE_ID
            );
        }

        // DSP-5: IN_REVIEW (linked to seeded PR #42)
        if (!existsById("work_items", TASK_5_REVIEW_ID)) {
            jdbcTemplate.update("""
                    INSERT INTO work_items (
                        id, project_id, item_key, parent_id, requirement_id, sprint_id, milestone_id,
                        type, work_kind, title, description, status, priority, assignee_id, required_skills,
                        backlog_rank, story_points, estimate_minutes, remaining_minutes, time_spent_minutes,
                        created_at, updated_at, version
                    ) VALUES (?, ?, 'DSP-5', ?, ?, ?, ?, 'TASK', 'CODE', ?, ?, 'TO_DO', 'HIGH', ?, '[{"skill":"java","level":3}]'::jsonb,
                        500, ?, 360, 360, 0, ?, ?, 0)
                    """,
                    TASK_5_REVIEW_ID, PROJECT_ID, STORY_2_ID, REQ1_ID, SPRINT_ID, MILESTONE_ID,
                    "Add GitHub App Sync & Review Gates",
                    "Implement RS256 GitHub App JWT authentication, REST sync, and independent review verification.",
                    DEV1_USER_ID, new BigDecimal("3.00"), now, now
            );
            jdbcTemplate.update(
                    "UPDATE work_items SET status = 'IN_PROGRESS', started_at = ? WHERE id = ?",
                    now.minusHours(3), TASK_5_REVIEW_ID
            );
            jdbcTemplate.update(
                    "UPDATE work_items SET status = 'IN_REVIEW', remaining_minutes = 60, time_spent_minutes = 300, claimed_progress = 90 WHERE id = ?",
                    TASK_5_REVIEW_ID
            );
        }

        // DSP-6: IN_PROGRESS
        if (!existsById("work_items", TASK_6_PROGRESS_ID)) {
            jdbcTemplate.update("""
                    INSERT INTO work_items (
                        id, project_id, item_key, parent_id, requirement_id, sprint_id, milestone_id,
                        type, work_kind, title, description, status, priority, assignee_id, required_skills,
                        backlog_rank, story_points, estimate_minutes, remaining_minutes, time_spent_minutes,
                        created_at, updated_at, version
                    ) VALUES (?, ?, 'DSP-6', ?, ?, ?, ?, 'TASK', 'CODE', ?, ?, 'TO_DO', 'MEDIUM', ?, '[{"skill":"java","level":4}]'::jsonb,
                        600, ?, 420, 420, 0, ?, ?, 0)
                    """,
                    TASK_6_PROGRESS_ID, PROJECT_ID, STORY_3_ID, REQ2_ID, SPRINT_ID, MILESTONE_ID,
                    "Build OpenAI & vLLM Privacy Router",
                    "Enforce workspace privacy policies and AES-256-GCM encrypted credentials for AI providers.",
                    DEV2_USER_ID, new BigDecimal("5.00"), now, now
            );
            jdbcTemplate.update(
                    "UPDATE work_items SET status = 'IN_PROGRESS', started_at = ?, remaining_minutes = 180, time_spent_minutes = 240, claimed_progress = 55 WHERE id = ?",
                    now.minusHours(2), TASK_6_PROGRESS_ID
            );
        }

        // DSP-7: TO_DO
        if (!existsById("work_items", TASK_7_TODO_ID)) {
            jdbcTemplate.update("""
                    INSERT INTO work_items (
                        id, project_id, item_key, parent_id, requirement_id, sprint_id, milestone_id,
                        type, work_kind, title, description, status, priority, assignee_id, required_skills,
                        backlog_rank, story_points, estimate_minutes, remaining_minutes, time_spent_minutes,
                        created_at, updated_at, version
                    ) VALUES (?, ?, 'DSP-7', ?, ?, ?, ?, 'TASK', 'CODE', ?, ?, 'TO_DO', 'MEDIUM', ?, '[{"skill":"java","level":3}]'::jsonb,
                        700, ?, 240, 240, 0, ?, ?, 0)
                    """,
                    TASK_7_TODO_ID, PROJECT_ID, STORY_3_ID, REQ3_ID, SPRINT_ID, MILESTONE_ID,
                    "Integrate S3 Export & CWE-1236 Sanitizer",
                    "Upload PDF/XLSX report exports to S3 with formula neutralization and HEAD verification.",
                    DEV2_USER_ID, new BigDecimal("3.00"), now, now
            );
        }

        // Transition project from PLANNING -> ACTIVE only after backlog items exist
        jdbcTemplate.update(
                "UPDATE projects SET status = 'ACTIVE' WHERE id = ? AND status = 'PLANNING'",
                PROJECT_ID
        );
    }

    private void seedDependenciesAndAcceptanceCriteria(OffsetDateTime now) {
        insertDependency(DEP_4_5_ID, TASK_4_DONE_ID, TASK_5_REVIEW_ID, now);
        insertDependency(DEP_5_6_ID, TASK_5_REVIEW_ID, TASK_6_PROGRESS_ID, now);
        insertDependency(DEP_6_7_ID, TASK_6_PROGRESS_ID, TASK_7_TODO_ID, now);

        if (!existsById("acceptance_criteria", AC_1_ID)) {
            jdbcTemplate.update("""
                    INSERT INTO acceptance_criteria (
                        id, project_id, work_item_id, position, description, evidence_kind,
                        weight, status, verified_by, verified_at, created_at, updated_at, version
                    ) VALUES (?, ?, ?, 0, ?, 'CI_TEST', 1.000, 'VERIFIED', ?, ?, ?, ?, 0)
                    ON CONFLICT DO NOTHING
                    """,
                    AC_1_ID, PROJECT_ID, TASK_4_DONE_ID,
                    "STOMP CONNECT enforces Bearer JWT and SUBSCRIBE checks live DB project membership.",
                    PM_USER_ID, now.minusHours(1), now, now
            );
        }

        if (!existsById("acceptance_criteria", AC_2_ID)) {
            jdbcTemplate.update("""
                    INSERT INTO acceptance_criteria (
                        id, project_id, work_item_id, position, description, evidence_kind,
                        weight, status, created_at, updated_at, version
                    ) VALUES (?, ?, ?, 0, ?, 'PR', 1.000, 'NOT_EVALUATED', ?, ?, 0)
                    ON CONFLICT DO NOTHING
                    """,
                    AC_2_ID, PROJECT_ID, TASK_5_REVIEW_ID,
                    "Independent peer developer approval and green CI run recorded for PR head SHA.",
                    now, now
            );
        }
    }

    private void insertDependency(UUID depId, UUID predecessorId, UUID successorId, OffsetDateTime now) {
        jdbcTemplate.update("""
                INSERT INTO work_item_dependencies (id, project_id, predecessor_id, successor_id, link_type, lag_minutes, created_at)
                VALUES (?, ?, ?, ?, 'FINISH_TO_START', 0, ?)
                ON CONFLICT DO NOTHING
                """,
                depId, PROJECT_ID, predecessorId, successorId, now
        );
    }

    private void seedDeveloperCapacities(OffsetDateTime now) {
        String weekdaysJson = "["
                + "{\"dayOfWeek\":1,\"startMinute\":540,\"endMinute\":1020},"
                + "{\"dayOfWeek\":2,\"startMinute\":540,\"endMinute\":1020},"
                + "{\"dayOfWeek\":3,\"startMinute\":540,\"endMinute\":1020},"
                + "{\"dayOfWeek\":4,\"startMinute\":540,\"endMinute\":1020},"
                + "{\"dayOfWeek\":5,\"startMinute\":540,\"endMinute\":1020}"
                + "]";

        if (!existsById("developer_capacity", CAP_DEV1_ID)) {
            jdbcTemplate.update("""
                    INSERT INTO developer_capacity (
                        id, project_id, user_id, skills, weekly_slots, time_off,
                        minutes_per_workday, allocation_pct, effective_from, created_at, updated_at, version
                    ) VALUES (?, ?, ?, '[{"skill":"java","level":5},{"skill":"postgresql","level":4}]'::jsonb,
                        ?::jsonb, '[]'::jsonb, 480, 100.00, ?, ?, ?, 0)
                    ON CONFLICT DO NOTHING
                    """,
                    CAP_DEV1_ID, PROJECT_ID, DEV1_USER_ID, weekdaysJson,
                    Date.valueOf(LocalDate.of(2026, 11, 1)), now, now
            );
        }

        if (!existsById("developer_capacity", CAP_DEV2_ID)) {
            jdbcTemplate.update("""
                    INSERT INTO developer_capacity (
                        id, project_id, user_id, skills, weekly_slots, time_off,
                        minutes_per_workday, allocation_pct, effective_from, created_at, updated_at, version
                    ) VALUES (?, ?, ?, '[{"skill":"java","level":4},{"skill":"react","level":4},{"skill":"python","level":3}]'::jsonb,
                        ?::jsonb, '[]'::jsonb, 480, 85.00, ?, ?, ?, 0)
                    ON CONFLICT DO NOTHING
                    """,
                    CAP_DEV2_ID, PROJECT_ID, DEV2_USER_ID, weekdaysJson,
                    Date.valueOf(LocalDate.of(2026, 11, 1)), now, now
            );
        }
    }

    private void seedPlanProposal(OffsetDateTime now) {
        if (existsById("plan_proposals", PLAN_PROPOSAL_ID)) {
            return;
        }
        Long projectVersion = jdbcTemplate.queryForObject(
                "SELECT version FROM projects WHERE id = ?",
                Long.class,
                PROJECT_ID
        );
        long baseVersion = projectVersion != null ? projectVersion : 0L;

        String proposalPayload = "{"
                + "\"schemaVersion\":\"1\","
                + "\"items\":["
                + "{\"tempKey\":\"P-1\",\"title\":\"Harden STOMP broker backpressure\",\"estimateMinutes\":180},"
                + "{\"tempKey\":\"P-2\",\"title\":\"Verify S3 presigned export flow\",\"estimateMinutes\":240}"
                + "],"
                + "\"dependencies\":[{\"predecessorKey\":\"P-1\",\"successorKey\":\"P-2\"}]"
                + "}";

        jdbcTemplate.update("""
                INSERT INTO plan_proposals (
                    id, project_id, kind, base_project_version, requirement_version_id,
                    status, proposal_payload, solver_status, created_by, created_at, updated_at, version
                ) VALUES (?, ?, 'MANUAL_REPLAN', ?, ?, 'DRAFT', ?::jsonb, 'NOT_RUN', ?, ?, ?, 0)
                """,
                PLAN_PROPOSAL_ID, PROJECT_ID, baseVersion, REQ1_V1_ID, proposalPayload, PM_USER_ID, now, now
        );
    }

    private void seedGitHubAndCodeQuality(OffsetDateTime now) {
        if (!existsById("github_repositories", GITHUB_REPO_ID)) {
            jdbcTemplate.update("""
                    INSERT INTO github_repositories (
                        id, workspace_id, project_id, github_installation_id, github_repo_id,
                        full_name, default_branch, private, active, last_synced_at, created_at, updated_at, version
                    ) VALUES (?, ?, ?, ?, ?, ?, 'main', true, true, ?, ?, ?, 0)
                    ON CONFLICT DO NOTHING
                    """,
                    GITHUB_REPO_ID, WORKSPACE_ID, PROJECT_ID,
                    DEMO_GITHUB_INSTALLATION_ID, DEMO_GITHUB_REPO_ID,
                    DEMO_GITHUB_FULL_NAME, now.minusMinutes(5), now, now
            );
        }

        if (!existsById("github_activities", GITHUB_PR_ACTIVITY_ID)) {
            jdbcTemplate.update("""
                    INSERT INTO github_activities (
                        id, project_id, repository_id, work_item_id, kind, github_object_id,
                        pr_number, pr_head_sha, actor_login, state, title, payload, happened_at, synced_at, created_at, updated_at, version
                    ) VALUES (?, ?, ?, ?, 'PULL_REQUEST', 'pr-42', 42, ?, ?, 'OPEN', ?, '{"schemaVersion":1}'::jsonb, ?, ?, ?, ?, 0)
                    ON CONFLICT DO NOTHING
                    """,
                    GITHUB_PR_ACTIVITY_ID, PROJECT_ID, GITHUB_REPO_ID, TASK_5_REVIEW_ID,
                    DEMO_PR_HEAD_SHA, DEV1_GITHUB_LOGIN, "DSP-5 Add GitHub App sync and review gates",
                    now.minusMinutes(30), now.minusMinutes(5), now, now
            );
        }

        if (!existsById("ci_runs", CI_RUN_ID)) {
            jdbcTemplate.update("""
                    INSERT INTO ci_runs (
                        id, project_id, repository_id, work_item_id, github_run_id, run_attempt,
                        head_sha, pr_number, status, conclusion, checks, metrics, started_at, completed_at,
                        created_at, updated_at, version
                    ) VALUES (?, ?, ?, ?, 88001, 1, ?, 42, 'COMPLETED', 'SUCCESS',
                        '[{"name":"maven-verify","conclusion":"SUCCESS"}]'::jsonb,
                        '{"schemaVersion":1,"testsPassed":120}'::jsonb, ?, ?, ?, ?, 0)
                    ON CONFLICT DO NOTHING
                    """,
                    CI_RUN_ID, PROJECT_ID, GITHUB_REPO_ID, TASK_5_REVIEW_ID,
                    DEMO_PR_HEAD_SHA, now.minusMinutes(20), now.minusMinutes(15), now, now
            );
        }

        if (!existsById("code_reviews", CODE_REVIEW_ID)) {
            Long projectVersion = jdbcTemplate.queryForObject(
                    "SELECT version FROM projects WHERE id = ?",
                    Long.class,
                    PROJECT_ID
            );
            long contextVersion = projectVersion != null ? projectVersion : 0L;

            jdbcTemplate.update("""
                    INSERT INTO code_reviews (
                        id, project_id, repository_id, work_item_id, pr_number, head_sha,
                        context_version, scope, reviewer_kind, findings, summary, status,
                        reviewer_id, completed_at, created_at, updated_at, version
                    ) VALUES (?, ?, ?, ?, 42, ?, ?, 'PR', 'HUMAN', '[]'::jsonb, ?, 'PASS', ?, ?, ?, ?, 0)
                    """,
                    CODE_REVIEW_ID, PROJECT_ID, GITHUB_REPO_ID, TASK_5_REVIEW_ID,
                    DEMO_PR_HEAD_SHA, contextVersion,
                    "Independent human peer review passed for PR #42.",
                    DEV2_USER_ID, now.minusMinutes(10), now, now
            );
        }
    }

    private void seedChatChannelsAndMessages(OffsetDateTime now) {
        // TEAM channel (internal: NEVER add CLIENT-only member)
        if (!existsById("chat_channels", TEAM_CHANNEL_ID)) {
            jdbcTemplate.update("""
                    INSERT INTO chat_channels (id, workspace_id, project_id, type, name, created_by, created_at, updated_at, version)
                    VALUES (?, ?, ?, 'TEAM', 'core-engineering', ?, ?, ?, 0)
                    ON CONFLICT DO NOTHING
                    """,
                    TEAM_CHANNEL_ID, WORKSPACE_ID, PROJECT_ID, PM_USER_ID, now, now
            );
        }
        for (UUID memberId : List.of(OWNER_USER_ID, PM_USER_ID, DEV1_USER_ID, DEV2_USER_ID, VIEWER_USER_ID)) {
            jdbcTemplate.update("""
                    INSERT INTO chat_channel_members (workspace_id, project_id, channel_id, user_id, joined_at, created_at, updated_at, version)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 0)
                    ON CONFLICT (channel_id, user_id) DO NOTHING
                    """,
                    WORKSPACE_ID, PROJECT_ID, TEAM_CHANNEL_ID, memberId, now, now, now
            );
        }

        // CLIENT_PM channel (strictly PM + CLIENT)
        if (!existsById("chat_channels", CLIENT_PM_CHANNEL_ID)) {
            jdbcTemplate.update("""
                    INSERT INTO chat_channels (id, workspace_id, project_id, type, name, created_by, created_at, updated_at, version)
                    VALUES (?, ?, ?, 'CLIENT_PM', 'client-stakeholder-sync', ?, ?, ?, 0)
                    ON CONFLICT DO NOTHING
                    """,
                    CLIENT_PM_CHANNEL_ID, WORKSPACE_ID, PROJECT_ID, PM_USER_ID, now, now
            );
        }
        for (UUID memberId : List.of(PM_USER_ID, CLIENT_USER_ID)) {
            jdbcTemplate.update("""
                    INSERT INTO chat_channel_members (workspace_id, project_id, channel_id, user_id, joined_at, created_at, updated_at, version)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 0)
                    ON CONFLICT (channel_id, user_id) DO NOTHING
                    """,
                    WORKSPACE_ID, PROJECT_ID, CLIENT_PM_CHANNEL_ID, memberId, now, now, now
            );
        }

        insertChatMessage(MSG_TEAM_1_ID, TEAM_CHANNEL_ID, PM_USER_ID,
                "Sprint 1 kickoff: STOMP WebSocket and GitHub App integration are underway.", now.minusMinutes(45));
        insertChatMessage(MSG_TEAM_2_ID, TEAM_CHANNEL_ID, DEV1_USER_ID,
                "PR #42 is ready for peer verification; CI checks are green.", now.minusMinutes(25));
        insertChatMessage(MSG_CLIENT_1_ID, CLIENT_PM_CHANNEL_ID, PM_USER_ID,
                "Milestone M8 roadmap report has been published for client review.", now.minusMinutes(20));
        insertChatMessage(MSG_CLIENT_2_ID, CLIENT_PM_CHANNEL_ID, CLIENT_USER_ID,
                "Thanks, we reviewed the roadmap report and look forward to the demo.", now.minusMinutes(10));
    }

    private void insertChatMessage(UUID msgId, UUID channelId, UUID senderId, String body, OffsetDateTime sentAt) {
        if (existsById("chat_messages", msgId)) {
            return;
        }
        jdbcTemplate.update("""
                INSERT INTO chat_messages (
                    id, workspace_id, project_id, channel_id, sender_id, sender_kind,
                    body, sent_at, created_at, updated_at, version
                ) VALUES (?, ?, ?, ?, ?, 'USER', ?, ?, ?, ?, 0)
                ON CONFLICT DO NOTHING
                """,
                msgId, WORKSPACE_ID, PROJECT_ID, channelId, senderId, body, sentAt, sentAt, sentAt
        );
    }

    private void seedAlert(OffsetDateTime now) {
        if (existsById("alerts", ALERT_ID)) {
            return;
        }
        jdbcTemplate.update("""
                INSERT INTO alerts (
                    id, project_id, work_item_id, fingerprint, alert_code, severity, status,
                    title, evidence, impact_snapshot, assigned_to, detected_at, created_at, updated_at, version
                ) VALUES (?, ?, ?, ?, 'CAPACITY_OVERLOAD', 'MEDIUM', 'NEW', ?,
                    '{"schemaVersion":1,"workItemKey":"DSP-6"}'::jsonb,
                    '{"schemaVersion":1,"estimatedSlipDays":1}'::jsonb,
                    ?, ?, ?, ?, 0)
                ON CONFLICT DO NOTHING
                """,
                ALERT_ID, PROJECT_ID, TASK_6_PROGRESS_ID, DEMO_ALERT_FINGERPRINT,
                "Sprint capacity utilization above 85% for DSP-6",
                PM_USER_ID, now.minusMinutes(15), now, now
        );
    }

    private void seedReports(OffsetDateTime now) {
        if (!existsById("reports", DRAFT_REPORT_ID)) {
            String draftContent = "{\"schemaVersion\":1,\"summary\":\"Internal weekly engineering progress draft\","
                    + "\"metrics\":{\"totalWorkItems\":7,\"completedWorkItems\":1,\"openAlerts\":1,\"activeSprints\":1}}";
            jdbcTemplate.update("""
                    INSERT INTO reports (
                        id, project_id, type, period_start, period_end, status, content,
                        visible_to_client, generated_by, approved_by, published_at, created_at, updated_at, version
                    ) VALUES (?, ?, 'WEEKLY', ?, ?, 'DRAFT', ?::jsonb, false, ?, NULL, NULL, ?, ?, 0)
                    ON CONFLICT DO NOTHING
                    """,
                    DRAFT_REPORT_ID, PROJECT_ID,
                    Date.valueOf(LocalDate.of(2026, 11, 16)),
                    Date.valueOf(LocalDate.of(2026, 11, 22)),
                    draftContent, PM_USER_ID, now, now
            );
        }

        if (!existsById("reports", PUBLISHED_REPORT_ID)) {
            String publishedContent = "{\"schemaVersion\":1,\"summary\":\"Milestone M8 backend integration is on track for December delivery\","
                    + "\"metrics\":{\"totalWorkItems\":7,\"completedWorkItems\":1,\"openAlerts\":1,\"activeSprints\":1},"
                    + "\"highlights\":[\"Real-time WebSocket broker verified\",\"Independent human code reviews active\"]}";
            jdbcTemplate.update("""
                    INSERT INTO reports (
                        id, project_id, type, period_start, period_end, status, content,
                        visible_to_client, milestone_id, generated_by, approved_by, published_at, created_at, updated_at, version
                    ) VALUES (?, ?, 'CLIENT_ROADMAP', ?, ?, 'PUBLISHED', ?::jsonb, true, ?, ?, ?, ?, ?, ?, 0)
                    ON CONFLICT DO NOTHING
                    """,
                    PUBLISHED_REPORT_ID, PROJECT_ID,
                    Date.valueOf(LocalDate.of(2026, 11, 16)),
                    Date.valueOf(LocalDate.of(2026, 11, 30)),
                    publishedContent, MILESTONE_ID, PM_USER_ID, PM_USER_ID, now.minusHours(2), now, now
            );
        }
    }

    private Map<String, Integer> collectCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("users", queryCount(
                "SELECT count(*) FROM users WHERE id IN (?, ?, ?, ?, ?, ?, ?)",
                ADMIN_USER_ID, OWNER_USER_ID, PM_USER_ID, DEV1_USER_ID, DEV2_USER_ID, CLIENT_USER_ID, VIEWER_USER_ID
        ));
        counts.put("oauthAccounts", queryCount("SELECT count(*) FROM oauth_accounts WHERE user_id IN (?, ?)", DEV1_USER_ID, DEV2_USER_ID));
        counts.put("workspaceMembers", queryCount("SELECT count(*) FROM workspace_members WHERE workspace_id = ?", WORKSPACE_ID));
        counts.put("projectMembers", queryCount("SELECT count(*) FROM project_members WHERE project_id = ?", PROJECT_ID));
        counts.put("projectRoles", queryCount("SELECT count(*) FROM project_member_roles WHERE project_id = ? AND revoked_at IS NULL", PROJECT_ID));
        counts.put("requirements", queryCount("SELECT count(*) FROM requirements WHERE project_id = ?", PROJECT_ID));
        counts.put("requirementVersions", queryCount("SELECT count(*) FROM requirement_versions WHERE project_id = ?", PROJECT_ID));
        counts.put("workItems", queryCount("SELECT count(*) FROM work_items WHERE project_id = ? AND deleted_at IS NULL", PROJECT_ID));
        counts.put("dependencies", queryCount("SELECT count(*) FROM work_item_dependencies WHERE project_id = ?", PROJECT_ID));
        counts.put("sprints", queryCount("SELECT count(*) FROM sprints WHERE project_id = ?", PROJECT_ID));
        counts.put("milestones", queryCount("SELECT count(*) FROM milestones WHERE project_id = ?", PROJECT_ID));
        counts.put("developerCapacities", queryCount("SELECT count(*) FROM developer_capacity WHERE project_id = ?", PROJECT_ID));
        counts.put("planProposals", queryCount("SELECT count(*) FROM plan_proposals WHERE project_id = ?", PROJECT_ID));
        counts.put("githubRepositories", queryCount("SELECT count(*) FROM github_repositories WHERE project_id = ?", PROJECT_ID));
        counts.put("githubActivities", queryCount("SELECT count(*) FROM github_activities WHERE project_id = ?", PROJECT_ID));
        counts.put("ciRuns", queryCount("SELECT count(*) FROM ci_runs WHERE project_id = ?", PROJECT_ID));
        counts.put("codeReviews", queryCount("SELECT count(*) FROM code_reviews WHERE project_id = ?", PROJECT_ID));
        counts.put("chatChannels", queryCount("SELECT count(*) FROM chat_channels WHERE project_id = ?", PROJECT_ID));
        counts.put("chatMessages", queryCount("SELECT count(*) FROM chat_messages WHERE project_id = ?", PROJECT_ID));
        counts.put("alerts", queryCount("SELECT count(*) FROM alerts WHERE project_id = ?", PROJECT_ID));
        counts.put("reports", queryCount("SELECT count(*) FROM reports WHERE project_id = ?", PROJECT_ID));
        return counts;
    }

    private int queryCount(String sql, Object... args) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return count != null ? count : 0;
    }
}

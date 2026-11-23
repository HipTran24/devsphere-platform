package com.project.devsphere;

import com.project.devsphere.identity.domain.SessionTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "devsphere.database.manage-runtime-privileges=false",
        "devsphere.auth.mail.from=devsphere@example.test",
        "spring.mail.host=localhost",
        "spring.mail.port=2525",
        "devsphere.auth.mail.initial-delay-ms=600000"
})
@AutoConfigureMockMvc
public class AiValidationGateIntegrationTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate tx;
    @Autowired private SessionTokens sessionTokens;
    @MockitoBean private JavaMailSender mailSender;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        DevsphereApplicationTests.database(registry);
        byte[] sessionKey = new byte[32];
        byte[] hmac = new byte[32];
        byte[] encryption = new byte[32];
        sessionKey[0] = 99;
        hmac[0] = 45;
        encryption[0] = 73;
        registry.add("devsphere.auth.session.signing-key", () -> Base64.getEncoder().encodeToString(sessionKey));
        registry.add("devsphere.auth.otp.hmac-key", () -> Base64.getEncoder().encodeToString(hmac));
        registry.add("devsphere.auth.otp.encryption-key", () -> Base64.getEncoder().encodeToString(encryption));
    }

    @BeforeEach
    void cleanDatabase() {
        var tables = jdbc.queryForList(
                "SELECT tablename FROM pg_tables WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'",
                String.class
        );
        jdbc.execute("TRUNCATE TABLE " + String.join(",", tables) + " CASCADE");
    }

    // --- Helpers ---

    private UUID createUser(String email, String displayName, String status, String systemRole) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update(
                "INSERT INTO users (id, email, display_name, password_hash, system_role, status, email_verified_at, created_at, updated_at, version) " +
                        "VALUES (?, ?, ?, 'argon2_dummy_hash', ?, ?, ?, ?, ?, 0)",
                id, email, displayName, systemRole, status, now, now, now
        );
        return id;
    }

    private UUID createWorkspace(UUID ownerId, String slug, String name) {
        return tx.execute(s -> {
            UUID workspaceId = UUID.randomUUID();
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            jdbc.update(
                    "INSERT INTO workspaces (id, slug, name, owner_id, status, created_at, updated_at, version) " +
                            "VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, 0)",
                    workspaceId, slug, name, ownerId, now, now
            );
            jdbc.update(
                    "INSERT INTO workspace_members (workspace_id, user_id, role, status, joined_at, created_at, updated_at, version) " +
                            "VALUES (?, ?, 'OWNER', 'ACTIVE', ?, ?, ?, 0)",
                    workspaceId, ownerId, now, now, now
            );
            return workspaceId;
        });
    }

    private UUID createProject(UUID workspaceId, String projectKey, String name, UUID pmUserId) {
        return tx.execute(s -> {
            UUID projectId = UUID.randomUUID();
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            jdbc.update(
                    "INSERT INTO projects (id, workspace_id, project_key, name, description, objective, status, timezone, planned_start, committed_deadline, sprint_length_days, created_at, updated_at, version) " +
                            "VALUES (?, ?, ?, ?, 'description', 'objective', 'PLANNING', 'UTC', '2026-12-01', '2026-12-31', 14, ?, ?, 0)",
                    projectId, workspaceId, projectKey, name, now, now
            );
            if (pmUserId != null) {
                UUID memberId = UUID.randomUUID();
                jdbc.update(
                        "INSERT INTO project_members (id, project_id, workspace_id, user_id, status, joined_at, created_at, updated_at, version) " +
                                "VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, ?, 0)",
                        memberId, projectId, workspaceId, pmUserId, now, now, now
                );
                jdbc.update(
                        "INSERT INTO project_member_roles (id, project_id, project_member_id, role, granted_by, granted_at, accepted_by, accepted_at) " +
                                "VALUES (?, ?, ?, 'PM', ?, ?, ?, ?)",
                        UUID.randomUUID(), projectId, memberId, pmUserId, now, pmUserId, now
                );
            }
            return projectId;
        });
    }

    private UUID createAiJob(
            UUID workspaceId,
            UUID projectId,
            UUID requestedBy,
            String jobType,
            String status,
            String validationStatus,
            int validationAttempts) {

        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String outputJson = "SUCCESS".equals(status) ? "{\"summary\":\"AI Output Generated\",\"confidence\":0.98}" : null;

        jdbc.update(
                "INSERT INTO ai_jobs (id, workspace_id, project_id, requested_by, job_type, status, model_name, connection_id, route_snapshot, prompt_version, output_schema_version, input_refs, output_json, validation_status, validation_report, validation_attempts, created_at, updated_at, version) " +
                        "VALUES (?, ?, ?, ?, ?, ?, 'gemini-3.8-flash', 'conn-gemini', '{\"provider\":\"google\"}'::jsonb, 'v1', '1', '{\"schemaVersion\":1}'::jsonb, ?::jsonb, ?, '{\"schemaVersion\":1}'::jsonb, ?, ?, ?, 0)",
                id, workspaceId, projectId, requestedBy, jobType, status, outputJson, validationStatus, validationAttempts, now, now
        );
        return id;
    }

    private UUID connectRepositoryDirectly(UUID workspaceId, UUID projectId, Long githubRepoId, String fullName) {
        UUID repoId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update(
                "INSERT INTO github_repositories (id, workspace_id, project_id, github_installation_id, github_repo_id, full_name, default_branch, private, active, created_at, updated_at, version) " +
                        "VALUES (?, ?, ?, 77777, ?, ?, 'main', true, true, ?, ?, 0)",
                repoId, workspaceId, projectId, githubRepoId, fullName, now, now
        );
        return repoId;
    }

    // =========================================================================
    // 1. ai_jobs Table Constraints & Validation Attempts Guard
    // =========================================================================
    @Nested
    @DisplayName("1. AI Jobs Validation Attempts & Constraints")
    class AiJobConstraintTests {

        @Test
        @DisplayName("Validation attempts between 0 and 2 are accepted")
        void testValidationAttemptsBetween0And2Accepted() {
            UUID pmUser = createUser("pm-ai1@example.test", "PM AI1", "ACTIVE", "USER");
            UUID workspaceId = createWorkspace(pmUser, "ws-ai1", "Workspace AI1");
            UUID projectId = createProject(workspaceId, "AI1", "Project AI1", pmUser);

            // Attempt 0
            UUID job0 = createAiJob(workspaceId, projectId, pmUser, "REQUIREMENT_PLAN", "QUEUED", "PENDING", 0);
            assertThat(job0).isNotNull();

            // Attempt 1
            UUID job1 = createAiJob(workspaceId, projectId, pmUser, "REQUIREMENT_PLAN", "RUNNING", "PENDING", 1);
            assertThat(job1).isNotNull();

            // Attempt 2 (Max allowed)
            UUID job2 = createAiJob(workspaceId, projectId, pmUser, "REQUIREMENT_PLAN", "SUCCESS", "VALID", 2);
            assertThat(job2).isNotNull();
        }

        @Test
        @DisplayName("Validation attempts exceeding 2 (> 2) are rejected by check constraint")
        void testValidationAttemptsExceeding2Rejected() {
            UUID pmUser = createUser("pm-ai2@example.test", "PM AI2", "ACTIVE", "USER");
            UUID workspaceId = createWorkspace(pmUser, "ws-ai2", "Workspace AI2");
            UUID projectId = createProject(workspaceId, "AI2", "Project AI2", pmUser);

            // Attempt 3 violates check constraint
            assertThatThrownBy(() -> createAiJob(workspaceId, projectId, pmUser, "REQUIREMENT_PLAN", "SUCCESS", "VALID", 3))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("Negative validation attempts (< 0) are rejected by check constraint")
        void testNegativeValidationAttemptsRejected() {
            UUID pmUser = createUser("pm-ai3@example.test", "PM AI3", "ACTIVE", "USER");
            UUID workspaceId = createWorkspace(pmUser, "ws-ai3", "Workspace AI3");
            UUID projectId = createProject(workspaceId, "AI3", "Project AI3", pmUser);

            assertThatThrownBy(() -> createAiJob(workspaceId, projectId, pmUser, "REQUIREMENT_PLAN", "QUEUED", "PENDING", -1))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("Check constraint: status = 'SUCCESS' requires output_json IS NOT NULL")
        void testStatusSuccessRequiresOutputJson() {
            UUID pmUser = createUser("pm-ai4@example.test", "PM AI4", "ACTIVE", "USER");
            UUID workspaceId = createWorkspace(pmUser, "ws-ai4", "Workspace AI4");
            UUID projectId = createProject(workspaceId, "AI4", "Project AI4", pmUser);

            UUID id = UUID.randomUUID();
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

            // Attempt to insert SUCCESS with null output_json -> violates check constraint
            assertThatThrownBy(() -> jdbc.update(
                    "INSERT INTO ai_jobs (id, workspace_id, project_id, requested_by, job_type, status, model_name, connection_id, route_snapshot, prompt_version, output_schema_version, input_refs, output_json, validation_status, validation_report, validation_attempts, created_at, updated_at, version) " +
                            "VALUES (?, ?, ?, ?, 'REQUIREMENT_PLAN', 'SUCCESS', 'gemini-3.8-flash', 'conn-1', '{}'::jsonb, 'v1', '1', '{\"schemaVersion\":1}'::jsonb, null, 'VALID', '{\"schemaVersion\":1}'::jsonb, 1, ?, ?, 0)",
                    id, workspaceId, projectId, pmUser, now, now
            )).isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    // =========================================================================
    // 2. Downstream Trigger ds_proposal_guard() AI Validation Requirement
    // =========================================================================
    @Nested
    @DisplayName("2. Trigger ds_proposal_guard() Validation Status Requirement")
    class ProposalGuardAiTests {

        @Test
        @DisplayName("AI_REQUIREMENT proposal applied with VALID AI job succeeds")
        void testAiProposalAppliedWithValidAiJobSucceeds() {
            UUID pmUser = createUser("pm-prop1@example.test", "PM Prop1", "ACTIVE", "USER");
            UUID workspaceId = createWorkspace(pmUser, "ws-prop1", "Workspace Prop1");
            UUID projectId = createProject(workspaceId, "PROP1", "Project Prop1", pmUser);

            // Create SUCCESS + VALID AI job
            UUID validAiJob = createAiJob(workspaceId, projectId, pmUser, "REQUIREMENT_PLAN", "SUCCESS", "VALID", 1);

            UUID proposalId = UUID.randomUUID();
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

            // 1. Insert DRAFT proposal with kind = 'AI_REQUIREMENT'
            jdbc.update(
                    "INSERT INTO plan_proposals (id, project_id, kind, status, solver_status, ai_job_id, proposal_payload, base_project_version, changes_committed_deadline, created_by, created_at, updated_at, version) " +
                            "VALUES (?, ?, 'AI_REQUIREMENT', 'DRAFT', 'OPTIMAL', ?, '{\"schemaVersion\":\"1\",\"items\":[{\"tempKey\":\"T1\",\"estimateMinutes\":60}]}'::jsonb, 0, false, ?, ?, ?, 0)",
                    proposalId, projectId, validAiJob, pmUser, now, now
            );

            // 2. Approve proposal by PM
            jdbc.update(
                    "UPDATE plan_proposals SET status = 'APPROVED', approved_by = ?, approved_at = now() WHERE id = ?",
                    pmUser, proposalId
            );

            // 3. Apply proposal -> succeeds because ai_job status = 'SUCCESS' AND validation_status = 'VALID'
            jdbc.update(
                    "UPDATE plan_proposals SET status = 'APPLIED' WHERE id = ?",
                    proposalId
            );

            String status = jdbc.queryForObject("SELECT status FROM plan_proposals WHERE id = ?", String.class, proposalId);
            assertThat(status).isEqualTo("APPLIED");
        }

        @Test
        @DisplayName("AI_REQUIREMENT proposal applied with INVALID or PENDING AI job is rejected with trigger exception")
        void testAiProposalAppliedWithInvalidAiJobRejected() {
            UUID pmUser = createUser("pm-prop2@example.test", "PM Prop2", "ACTIVE", "USER");
            UUID workspaceId = createWorkspace(pmUser, "ws-prop2", "Workspace Prop2");
            UUID projectId = createProject(workspaceId, "PROP2", "Project Prop2", pmUser);

            // Create SUCCESS but INVALID AI job
            UUID invalidAiJob = createAiJob(workspaceId, projectId, pmUser, "REQUIREMENT_PLAN", "SUCCESS", "INVALID", 2);

            UUID proposalId = UUID.randomUUID();
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

            jdbc.update(
                    "INSERT INTO plan_proposals (id, project_id, kind, status, solver_status, ai_job_id, proposal_payload, base_project_version, changes_committed_deadline, created_by, created_at, updated_at, version) " +
                            "VALUES (?, ?, 'AI_REQUIREMENT', 'DRAFT', 'OPTIMAL', ?, '{\"schemaVersion\":\"1\",\"items\":[{\"tempKey\":\"T1\",\"estimateMinutes\":60}]}'::jsonb, 0, false, ?, ?, ?, 0)",
                    proposalId, projectId, invalidAiJob, pmUser, now, now
            );

            jdbc.update(
                    "UPDATE plan_proposals SET status = 'APPROVED', approved_by = ?, approved_at = now() WHERE id = ?",
                    pmUser, proposalId
            );

            // Applying proposal must fail because AI output is INVALID
            assertThatThrownBy(() -> jdbc.update("UPDATE plan_proposals SET status = 'APPLIED' WHERE id = ?", proposalId))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("AI proposal requires validated AI output");
        }
    }

    // =========================================================================
    // 3. Downstream Trigger ds_report_approval() AI Validation Requirement
    // =========================================================================
    @Nested
    @DisplayName("3. Trigger ds_report_approval() Validation Status Requirement")
    class ReportApprovalAiTests {

        @Test
        @DisplayName("Report approval with VALID AI job succeeds")
        void testReportApprovalWithValidAiJobSucceeds() {
            UUID pmUser = createUser("pm-rep1@example.test", "PM Rep1", "ACTIVE", "USER");
            UUID workspaceId = createWorkspace(pmUser, "ws-rep1", "Workspace Rep1");
            UUID projectId = createProject(workspaceId, "REP1", "Project Rep1", pmUser);

            UUID validAiJob = createAiJob(workspaceId, projectId, pmUser, "REPORT_SUMMARY", "SUCCESS", "VALID", 1);

            UUID reportId = UUID.randomUUID();
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            LocalDate start = LocalDate.of(2026, 12, 1);
            LocalDate end = LocalDate.of(2026, 12, 7);

            // Insert DRAFT report referencing valid AI job
            jdbc.update(
                    "INSERT INTO reports (id, project_id, type, period_start, period_end, status, content, ai_job_id, created_at, updated_at, version) " +
                            "VALUES (?, ?, 'WEEKLY', ?, ?, 'DRAFT', '{\"sections\":[\"Progress\",\"Blockers\"]}'::jsonb, ?, ?, ?, 0)",
                    reportId, projectId, start, end, validAiJob, now, now
            );

            // PM approves report -> succeeds
            jdbc.update(
                    "UPDATE reports SET status = 'APPROVED', approved_by = ? WHERE id = ?",
                    pmUser, reportId
            );

            String status = jdbc.queryForObject("SELECT status FROM reports WHERE id = ?", String.class, reportId);
            assertThat(status).isEqualTo("APPROVED");
        }

        @Test
        @DisplayName("Report approval with INVALID or PENDING AI job is rejected with trigger exception")
        void testReportApprovalWithInvalidAiJobRejected() {
            UUID pmUser = createUser("pm-rep2@example.test", "PM Rep2", "ACTIVE", "USER");
            UUID workspaceId = createWorkspace(pmUser, "ws-rep2", "Workspace Rep2");
            UUID projectId = createProject(workspaceId, "REP2", "Project Rep2", pmUser);

            UUID invalidAiJob = createAiJob(workspaceId, projectId, pmUser, "REPORT_SUMMARY", "SUCCESS", "INVALID", 2);

            UUID reportId = UUID.randomUUID();
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            LocalDate start = LocalDate.of(2026, 12, 1);
            LocalDate end = LocalDate.of(2026, 12, 7);

            jdbc.update(
                    "INSERT INTO reports (id, project_id, type, period_start, period_end, status, content, ai_job_id, created_at, updated_at, version) " +
                            "VALUES (?, ?, 'WEEKLY', ?, ?, 'DRAFT', '{\"sections\":[]}'::jsonb, ?, ?, ?, 0)",
                    reportId, projectId, start, end, invalidAiJob, now, now
            );

            // PM attempts to approve report with INVALID AI job -> rejected
            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE reports SET status = 'APPROVED', approved_by = ? WHERE id = ?",
                    pmUser, reportId
            )).isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("AI report requires validated output");
        }
    }

    // =========================================================================
    // 4. Downstream Trigger ds_review_evidence() AI Validation Requirement
    // =========================================================================
    @Nested
    @DisplayName("4. Trigger ds_review_evidence() AI Review Validation Requirement")
    class CodeReviewAiTests {

        @Test
        @DisplayName("AI code review PASS with VALID AI job succeeds")
        void testAiCodeReviewPassWithValidAiJobSucceeds() {
            UUID pmUser = createUser("pm-rev1@example.test", "PM Rev1", "ACTIVE", "USER");
            UUID workspaceId = createWorkspace(pmUser, "ws-rev1", "Workspace Rev1");
            UUID projectId = createProject(workspaceId, "REV1", "Project Rev1", pmUser);
            UUID repoId = connectRepositoryDirectly(workspaceId, projectId, 50001L, "devsphere-org/rev1-repo");

            UUID validAiJob = createAiJob(workspaceId, projectId, pmUser, "PR_REVIEW", "SUCCESS", "VALID", 1);

            UUID reviewId = UUID.randomUUID();
            String headSha = "1111111111111111111111111111111111111111";

            // AI review PASS with VALID ai_job_id succeeds
            jdbc.update(
                    "INSERT INTO code_reviews (id, project_id, repository_id, reviewer_kind, ai_job_id, scope, head_sha, pr_number, status, summary, findings, completed_at, created_at, updated_at, version) " +
                            "VALUES (?, ?, ?, 'AI', ?, 'PR', ?, 1, 'PASS', 'AI LGTM', '[]'::jsonb, now(), now(), now(), 0)",
                    reviewId, projectId, repoId, validAiJob, headSha
            );

            String status = jdbc.queryForObject("SELECT status FROM code_reviews WHERE id = ?", String.class, reviewId);
            assertThat(status).isEqualTo("PASS");
        }

        @Test
        @DisplayName("AI code review PASS with INVALID or PENDING AI job is rejected with trigger exception")
        void testAiCodeReviewPassWithInvalidAiJobRejected() {
            UUID pmUser = createUser("pm-rev2@example.test", "PM Rev2", "ACTIVE", "USER");
            UUID workspaceId = createWorkspace(pmUser, "ws-rev2", "Workspace Rev2");
            UUID projectId = createProject(workspaceId, "REV2", "Project Rev2", pmUser);
            UUID repoId = connectRepositoryDirectly(workspaceId, projectId, 50002L, "devsphere-org/rev2-repo");

            UUID invalidAiJob = createAiJob(workspaceId, projectId, pmUser, "PR_REVIEW", "SUCCESS", "INVALID", 2);

            UUID reviewId = UUID.randomUUID();
            String headSha = "2222222222222222222222222222222222222222";

            // Attempting to record AI review PASS with INVALID AI job -> rejected
            assertThatThrownBy(() -> jdbc.update(
                    "INSERT INTO code_reviews (id, project_id, repository_id, reviewer_kind, ai_job_id, scope, head_sha, pr_number, status, summary, findings, completed_at, created_at, updated_at, version) " +
                            "VALUES (?, ?, ?, 'AI', ?, 'PR', ?, 2, 'PASS', 'AI Faked Approval', '[]'::jsonb, now(), now(), now(), 0)",
                    reviewId, projectId, repoId, invalidAiJob, headSha
            )).isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("AI review PASS requires validated output");
        }
    }
}

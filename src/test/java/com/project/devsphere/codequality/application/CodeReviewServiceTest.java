package com.project.devsphere.codequality.application;

import com.project.devsphere.codequality.domain.CodeReview;
import com.project.devsphere.codequality.infrastructure.persistence.CodeReviewRepository;
import com.project.devsphere.identity.application.AccessPolicy;
import com.project.devsphere.identity.application.SessionService;
import com.project.devsphere.identity.domain.ProjectRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CodeReviewServiceTest {

    @Mock private CodeReviewRepository codeReviewRepository;
    @Mock private AccessPolicy accessPolicy;
    @Mock private JdbcTemplate jdbcTemplate;

    private CodeReviewService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID repositoryId = UUID.randomUUID();
    private final UUID workItemId = UUID.randomUUID();
    private final String validSha = "b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3";
    private SessionService.Principal principal;

    @BeforeEach
    void setUp() {
        service = new CodeReviewService(codeReviewRepository, accessPolicy, jdbcTemplate);
        principal = new SessionService.Principal(userId, sessionId, "Reviewer User", "USER");

        when(jdbcTemplate.query(contains("FROM github_repositories"), any(RowMapper.class), eq(projectId)))
                .thenReturn(List.of(repositoryId));
        when(jdbcTemplate.queryForObject(contains("SELECT version FROM projects"), eq(Long.class), eq(projectId)))
                .thenReturn(3L);
    }

    @Test
    @DisplayName("submitReview succeeds for independent developer on matching PR head SHA")
    void submitReview_success() {
        SubmitReviewCommand command = new SubmitReviewCommand(
                repositoryId,
                "PR",
                12,
                validSha,
                "PASS",
                "Code looks solid, well tested",
                List.of(Map.of("file", "Auth.java", "status", "APPROVED")),
                workItemId
        );

        // Mock PR activity in github_activities with author 'pr-author-bob'
        Map<String, Object> prActivity = Map.of(
                "pr_head_sha", validSha,
                "actor_login", "pr-author-bob",
                "work_item_id", workItemId
        );
        when(jdbcTemplate.queryForList(contains("FROM github_activities"), eq(repositoryId), eq(12)))
                .thenReturn(List.of(prActivity));

        // Mock reviewer's GitHub username in oauth_accounts as 'reviewer-alice'
        when(jdbcTemplate.query(contains("FROM oauth_accounts"), any(RowMapper.class), eq(userId)))
                .thenReturn(List.of("reviewer-alice"));

        when(codeReviewRepository.save(any(CodeReview.class))).thenAnswer(inv -> inv.getArgument(0));

        CodeReview review = service.submitReview(principal, workspaceId, projectId, command);

        verify(accessPolicy).requireAnyProjectRole(principal, workspaceId, projectId, Set.of(ProjectRole.DEVELOPER, ProjectRole.PM));
        assertThat(review).isNotNull();
        assertThat(review.getHeadSha()).isEqualTo(validSha.toLowerCase());
        assertThat(review.getStatus()).isEqualTo(CodeReview.Status.PASS);
        assertThat(review.getReviewerId()).isEqualTo(userId);
        assertThat(review.getReviewerKind()).isEqualTo(CodeReview.ReviewerKind.HUMAN);
        assertThat(review.getContextVersion()).isEqualTo(3L);

        ArgumentCaptor<CodeReview> captor = ArgumentCaptor.forClass(CodeReview.class);
        verify(codeReviewRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(CodeReview.Status.PASS);
    }

    @Test
    @DisplayName("submitReview rejects when headSha does not match current PR head SHA")
    void submitReview_prHeadShaMismatch_throwsConflict() {
        SubmitReviewCommand command = new SubmitReviewCommand(
                repositoryId, "PR", 12, validSha, "PASS", "Good", List.of(), workItemId
        );

        String differentSha = "1111222233334444555566667777888899990000";
        Map<String, Object> prActivity = Map.of(
                "pr_head_sha", differentSha,
                "actor_login", "author-bob"
        );
        when(jdbcTemplate.queryForList(contains("FROM github_activities"), eq(repositoryId), eq(12)))
                .thenReturn(List.of(prActivity));

        assertThatThrownBy(() -> service.submitReview(principal, workspaceId, projectId, command))
                .isInstanceOf(CodeQualityException.class)
                .hasMessageContaining("does not match current PR head SHA")
                .satisfies(ex -> assertThat(((CodeQualityException) ex).getCode()).isEqualTo("PR_HEAD_SHA_MISMATCH"));

        verify(codeReviewRepository, never()).save(any());
    }

    @Test
    @DisplayName("submitReview rejects author self-review (matching GitHub login)")
    void submitReview_authorIndependenceViolation_sameLogin_throws422() {
        SubmitReviewCommand command = new SubmitReviewCommand(
                repositoryId, "PR", 12, validSha, "PASS", "Approved myself", List.of(), workItemId
        );

        Map<String, Object> prActivity = Map.of(
                "pr_head_sha", validSha,
                "actor_login", "dev-charlie"
        );
        when(jdbcTemplate.queryForList(contains("FROM github_activities"), eq(repositoryId), eq(12)))
                .thenReturn(List.of(prActivity));

        // Reviewer has the SAME GitHub login 'dev-charlie'
        when(jdbcTemplate.query(contains("FROM oauth_accounts"), any(RowMapper.class), eq(userId)))
                .thenReturn(List.of("dev-charlie"));

        assertThatThrownBy(() -> service.submitReview(principal, workspaceId, projectId, command))
                .isInstanceOf(CodeQualityException.class)
                .hasMessageContaining("independent GitHub identity")
                .satisfies(ex -> assertThat(((CodeQualityException) ex).getCode()).isEqualTo("REVIEW_AUTHOR_INDEPENDENCE_VIOLATION"));

        verify(codeReviewRepository, never()).save(any());
    }

    @Test
    @DisplayName("submitReview rejects reviewer without a linked GitHub account in oauth_accounts")
    void submitReview_authorIndependenceViolation_noLinkedGithub_throws422() {
        SubmitReviewCommand command = new SubmitReviewCommand(
                repositoryId, "PR", 12, validSha, "PASS", "LGTM", List.of(), workItemId
        );

        Map<String, Object> prActivity = Map.of(
                "pr_head_sha", validSha,
                "actor_login", "author-bob"
        );
        when(jdbcTemplate.queryForList(contains("FROM github_activities"), eq(repositoryId), eq(12)))
                .thenReturn(List.of(prActivity));

        // Reviewer has NO github account in oauth_accounts
        when(jdbcTemplate.query(contains("FROM oauth_accounts"), any(RowMapper.class), eq(userId)))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.submitReview(principal, workspaceId, projectId, command))
                .isInstanceOf(CodeQualityException.class)
                .hasMessageContaining("linked GitHub identity")
                .satisfies(ex -> assertThat(((CodeQualityException) ex).getCode()).isEqualTo("REVIEW_AUTHOR_INDEPENDENCE_VIOLATION"));

        verify(codeReviewRepository, never()).save(any());
    }

    @Test
    @DisplayName("submitReview rejects non-developer/client users")
    void submitReview_notDeveloperOrPm_throwsAccessDenied() {
        SubmitReviewCommand command = new SubmitReviewCommand(
                repositoryId, "PR", 12, validSha, "PASS", "Summary", List.of(), workItemId
        );

        when(accessPolicy.requireAnyProjectRole(principal, workspaceId, projectId, Set.of(ProjectRole.DEVELOPER, ProjectRole.PM)))
                .thenThrow(new AccessDeniedException("Access denied"));

        assertThatThrownBy(() -> service.submitReview(principal, workspaceId, projectId, command))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Access denied");

        verify(codeReviewRepository, never()).save(any());
    }

    @Test
    @DisplayName("getReview throws 404 when code review is not found")
    void getReview_notFound_throws404() {
        UUID reviewId = UUID.randomUUID();
        when(codeReviewRepository.findByIdAndProjectId(reviewId, projectId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getReview(principal, workspaceId, projectId, reviewId))
                .isInstanceOf(CodeQualityException.class)
                .hasMessageContaining("Code review not found")
                .satisfies(ex -> assertThat(((CodeQualityException) ex).getCode()).isEqualTo("CODE_REVIEW_NOT_FOUND"));
    }
}

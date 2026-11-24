package com.project.devsphere.gitintegration.application;

import com.project.devsphere.gitintegration.api.dto.GitHubSyncSummaryResponse;
import com.project.devsphere.gitintegration.domain.GitHubRepository;
import com.project.devsphere.gitintegration.infrastructure.GitHubApiUnavailableException;
import com.project.devsphere.gitintegration.infrastructure.GitHubAppProperties;
import com.project.devsphere.gitintegration.infrastructure.GitHubRestClient;
import com.project.devsphere.gitintegration.infrastructure.persistence.GitHubRepositoryRepository;
import com.project.devsphere.identity.application.AccessPolicy;
import com.project.devsphere.identity.application.SessionService;
import com.project.devsphere.identity.domain.ProjectRole;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Orchestrates live GitHub App REST API synchronization for a connected repository using a strict
 * split-transaction lifecycle:
 * <ol>
 *   <li>Short read transaction ({@code REQUIRES_NEW}): RBAC + load active {@link GitHubRepository} metadata</li>
 *   <li>External HTTP I/O strictly outside any DB transaction via {@link GitHubRestClient}</li>
 *   <li>Short write transaction ({@code REQUIRES_NEW}): idempotent upsert of PRs, commits, CI runs,
 *       human code reviews, and {@code github_repositories.last_synced_at}</li>
 * </ol>
 */
@Service
public class GitHubRemoteSyncService {

    private static final Pattern SHA_PATTERN = Pattern.compile("^[0-9a-f]{40}$");

    private final GitHubRepositoryRepository repositoryRepository;
    private final GitHubActivitySyncService activitySyncService;
    private final GitHubRestClient restClient;
    private final GitHubAppProperties properties;
    private final AccessPolicy accessPolicy;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate requiresNewTx;

    @Autowired
    public GitHubRemoteSyncService(
            GitHubRepositoryRepository repositoryRepository,
            GitHubActivitySyncService activitySyncService,
            GitHubRestClient restClient,
            GitHubAppProperties properties,
            AccessPolicy accessPolicy,
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager) {
        this(
                repositoryRepository,
                activitySyncService,
                restClient,
                properties,
                accessPolicy,
                jdbcTemplate,
                createRequiresNewTemplate(transactionManager)
        );
    }

    public GitHubRemoteSyncService(
            GitHubRepositoryRepository repositoryRepository,
            GitHubActivitySyncService activitySyncService,
            GitHubRestClient restClient,
            GitHubAppProperties properties,
            AccessPolicy accessPolicy,
            JdbcTemplate jdbcTemplate,
            TransactionTemplate requiresNewTx) {
        this.repositoryRepository = Objects.requireNonNull(repositoryRepository, "repositoryRepository must not be null");
        this.activitySyncService = Objects.requireNonNull(activitySyncService, "activitySyncService must not be null");
        this.restClient = Objects.requireNonNull(restClient, "restClient must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy must not be null");
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.requiresNewTx = Objects.requireNonNull(requiresNewTx, "requiresNewTx must not be null");
    }

    private static TransactionTemplate createRequiresNewTemplate(PlatformTransactionManager transactionManager) {
        Objects.requireNonNull(transactionManager, "transactionManager must not be null");
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    /**
     * PM-triggered live sync for a connected repository.
     * Performs ZERO HTTP calls inside any database transaction.
     */
    public GitHubSyncSummaryResponse syncRepository(
            SessionService.Principal principal,
            UUID workspaceId,
            UUID projectId,
            UUID repositoryId) {

        // Phase 1: Short read transaction for PM RBAC + repository metadata
        RepoTarget target = requiresNewTx.execute(status -> {
            setActorId(principal);
            accessPolicy.requireProjectRole(principal, workspaceId, projectId, ProjectRole.PM);
            GitHubRepository repo = repositoryRepository.findByIdAndProjectId(repositoryId, projectId)
                    .orElseThrow(() -> GitHubException.repositoryNotFound("Repository not found with ID: " + repositoryId));
            if (!Boolean.TRUE.equals(repo.getActive())) {
                throw GitHubException.validationFailed("Cannot sync an inactive GitHub repository");
            }
            return toTarget(repo);
        });

        // Phase 2: External GitHub REST API calls strictly outside any DB transaction
        RemoteSnapshot snapshot = fetchRemoteSnapshot(target);

        // Phase 3: Short write transaction to persist snapshot idempotently
        return requiresNewTx.execute(status -> {
            setActorId(principal);
            return persistSnapshot(target, snapshot);
        });
    }

    /**
     * System backfill remote sync for a connected active repository (no interactive principal).
     * Performs ZERO HTTP calls inside any database transaction.
     */
    public GitHubSyncSummaryResponse syncRepositoryForBackfill(UUID projectId, UUID repositoryId) {
        if (!properties.isEnabled()) {
            return null;
        }

        // Phase 1: Short read transaction to load active repository metadata
        RepoTarget target = requiresNewTx.execute(status -> {
            Optional<GitHubRepository> repoOpt = repositoryRepository.findByIdAndProjectId(repositoryId, projectId);
            if (repoOpt.isEmpty() || !Boolean.TRUE.equals(repoOpt.get().getActive())) {
                return null;
            }
            return toTarget(repoOpt.get());
        });

        if (target == null) {
            return null;
        }

        // Phase 2: External GitHub REST API calls strictly outside any DB transaction
        RemoteSnapshot snapshot = fetchRemoteSnapshot(target);

        // Phase 3: Short write transaction to persist snapshot idempotently
        return requiresNewTx.execute(status -> persistSnapshot(target, snapshot));
    }

    private RemoteSnapshot fetchRemoteSnapshot(RepoTarget target) {
        if (!properties.isEnabled()) {
            throw new GitHubApiUnavailableException("GitHub App integration is disabled");
        }

        List<Map<String, Object>> pullRequests = restClient.listPullRequests(
                target.installationId(), target.owner(), target.repo());
        List<Map<String, Object>> commits = restClient.listCommits(
                target.installationId(), target.owner(), target.repo());

        Set<String> checkRunShas = new LinkedHashSet<>();
        Map<String, Integer> shaToPrNumber = new LinkedHashMap<>();
        Map<Integer, List<Map<String, Object>>> reviewsByPr = new LinkedHashMap<>();

        for (Map<String, Object> pr : pullRequests) {
            Integer prNumber = extractPositiveInt(pr.get("number"));
            String headSha = extractPrHeadSha(pr);
            if (headSha != null) {
                checkRunShas.add(headSha);
                if (prNumber != null) {
                    shaToPrNumber.putIfAbsent(headSha, prNumber);
                }
            }
            if (prNumber != null) {
                List<Map<String, Object>> reviews = restClient.listPullRequestReviews(
                        target.installationId(), target.owner(), target.repo(), prNumber);
                reviewsByPr.put(prNumber, reviews);
            }
        }

        if (checkRunShas.isEmpty() && !commits.isEmpty()) {
            String latestCommitSha = normalizeSha(asString(commits.get(0).get("sha")));
            if (latestCommitSha != null) {
                checkRunShas.add(latestCommitSha);
            }
        }

        List<CheckRunBatch> checkRunBatches = new ArrayList<>();
        for (String sha : checkRunShas) {
            List<Map<String, Object>> runs = restClient.listCheckRuns(
                    target.installationId(), target.owner(), target.repo(), sha);
            checkRunBatches.add(new CheckRunBatch(sha, shaToPrNumber.get(sha), runs));
        }

        return new RemoteSnapshot(pullRequests, commits, checkRunBatches, reviewsByPr);
    }

    private GitHubSyncSummaryResponse persistSnapshot(RepoTarget target, RemoteSnapshot snapshot) {
        Instant syncedAt = Instant.now();
        int prsSynced = 0;
        int commitsSynced = 0;
        int ciRunsSynced = 0;
        int reviewsSynced = 0;

        Map<Integer, PrMeta> prMetaByNumber = new LinkedHashMap<>();

        // 1. Upsert pull requests first so PR head SHA & author login are up-to-date
        for (Map<String, Object> pr : snapshot.pullRequests()) {
            if (activitySyncService.syncPullRequestMap(target.projectId(), target.repositoryId(), pr, null, null, syncedAt)) {
                prsSynced++;
                Integer prNumber = extractPositiveInt(pr.get("number"));
                if (prNumber != null) {
                    prMetaByNumber.put(prNumber, new PrMeta(
                            extractPrHeadSha(pr),
                            extractNestedString(pr, "user", "login")
                    ));
                }
            }
        }

        // 2. Upsert commits
        for (Map<String, Object> commit : snapshot.commits()) {
            if (activitySyncService.syncRestCommitMap(target.projectId(), target.repositoryId(), commit, syncedAt)) {
                commitsSynced++;
            }
        }

        // 3. Upsert CI check runs
        for (CheckRunBatch batch : snapshot.checkRunBatches()) {
            for (Map<String, Object> checkRun : batch.checkRuns()) {
                if (activitySyncService.syncCheckRunMap(
                        target.projectId(),
                        target.repositoryId(),
                        checkRun,
                        batch.prNumber(),
                        null,
                        syncedAt)) {
                    ciRunsSynced++;
                }
            }
        }

        // 4. Upsert eligible human PR reviews
        for (Map.Entry<Integer, List<Map<String, Object>>> entry : snapshot.reviewsByPr().entrySet()) {
            Integer prNumber = entry.getKey();
            PrMeta meta = prMetaByNumber.get(prNumber);
            String prHeadSha = meta != null ? meta.headSha() : null;
            String prAuthorLogin = meta != null ? meta.authorLogin() : null;
            for (Map<String, Object> review : entry.getValue()) {
                if (activitySyncService.syncPullRequestReview(
                        target.projectId(),
                        target.repositoryId(),
                        prNumber,
                        prHeadSha,
                        prAuthorLogin,
                        null,
                        review,
                        syncedAt)) {
                    reviewsSynced++;
                }
            }
        }

        // 5. Update last_synced_at on github_repositories
        jdbcTemplate.update(
                "UPDATE github_repositories SET last_synced_at = ?, updated_at = now(), version = version + 1 WHERE id = ? AND project_id = ?",
                Timestamp.from(syncedAt),
                target.repositoryId(),
                target.projectId()
        );

        return new GitHubSyncSummaryResponse(
                target.repositoryId(),
                prsSynced,
                commitsSynced,
                ciRunsSynced,
                reviewsSynced,
                syncedAt
        );
    }

    private static RepoTarget toTarget(GitHubRepository repo) {
        String fullName = repo.getFullName();
        if (fullName == null || !fullName.contains("/")) {
            throw GitHubException.validationFailed("Repository fullName must be in 'owner/repo' format");
        }
        String[] parts = fullName.trim().split("/", 2);
        String owner = parts[0].trim();
        String repoName = parts[1].trim();
        if (owner.isEmpty() || repoName.isEmpty() || repoName.contains("/")) {
            throw GitHubException.validationFailed("Repository fullName must be in 'owner/repo' format");
        }
        return new RepoTarget(
                repo.getId(),
                repo.getProjectId(),
                repo.getGithubInstallationId(),
                owner,
                repoName,
                repo.getDefaultBranch()
        );
    }

    private void setActorId(SessionService.Principal principal) {
        if (principal != null && principal.userId() != null) {
            try {
                jdbcTemplate.queryForObject(
                        "SELECT set_config('devsphere.actor_id', ?, true)",
                        String.class,
                        principal.userId().toString()
                );
            } catch (Exception ignored) {
            }
        }
    }

    private static String extractPrHeadSha(Map<String, Object> pr) {
        return normalizeSha(extractNestedString(pr, "head", "sha"));
    }

    private static String extractNestedString(Map<String, Object> map, String outerKey, String innerKey) {
        Object outer = map.get(outerKey);
        if (outer instanceof Map<?, ?> inner) {
            return asString(inner.get(innerKey));
        }
        return null;
    }

    private static String asString(Object value) {
        return value instanceof String s ? s : null;
    }

    private static Integer extractPositiveInt(Object value) {
        if (value instanceof Number n) {
            int i = n.intValue();
            return i > 0 ? i : null;
        }
        return null;
    }

    private static String normalizeSha(String sha) {
        if (sha == null) {
            return null;
        }
        String trimmed = sha.trim().toLowerCase(Locale.ROOT);
        return SHA_PATTERN.matcher(trimmed).matches() ? trimmed : null;
    }

    private record RepoTarget(
            UUID repositoryId,
            UUID projectId,
            long installationId,
            String owner,
            String repo,
            String defaultBranch
    ) {
    }

    private record CheckRunBatch(
            String headSha,
            Integer prNumber,
            List<Map<String, Object>> checkRuns
    ) {
    }

    private record PrMeta(String headSha, String authorLogin) {
    }

    private record RemoteSnapshot(
            List<Map<String, Object>> pullRequests,
            List<Map<String, Object>> commits,
            List<CheckRunBatch> checkRunBatches,
            Map<Integer, List<Map<String, Object>>> reviewsByPr
    ) {
    }
}

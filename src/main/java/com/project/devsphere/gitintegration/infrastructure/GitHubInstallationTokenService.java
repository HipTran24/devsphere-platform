package com.project.devsphere.gitintegration.infrastructure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Exchanges RS256 GitHub App JWTs for short-lived installation access tokens and caches them
 * in memory per {@code installationId} with a 5-minute refresh buffer before {@code expires_at}.
 *
 * <p>Uses per-installation locks to prevent thundering-herd token refreshes and never logs
 * raw App JWTs or installation access tokens.
 */
@Service
public class GitHubInstallationTokenService {

    private static final Logger log = LoggerFactory.getLogger(GitHubInstallationTokenService.class);
    static final Duration REFRESH_BUFFER = Duration.ofMinutes(5);
    private static final Duration DEFAULT_TOKEN_TTL = Duration.ofHours(1);
    private static final int MAX_TOKEN_RESPONSE_BYTES = 262_144; // 256 KB

    private final GitHubAppProperties properties;
    private final GitHubAppJwtSigner jwtSigner;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Environment environment;
    private final HttpClient httpClient;

    private final ConcurrentHashMap<Long, CachedToken> tokenCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Object> installationLocks = new ConcurrentHashMap<>();

    @Autowired
    public GitHubInstallationTokenService(
            GitHubAppProperties properties,
            GitHubAppJwtSigner jwtSigner,
            @Autowired(required = false) ObjectMapper objectMapper,
            @Autowired(required = false) Environment environment) {
        this(properties, jwtSigner, objectMapper, Clock.systemUTC(), environment);
    }

    public GitHubInstallationTokenService(
            GitHubAppProperties properties,
            GitHubAppJwtSigner jwtSigner,
            ObjectMapper objectMapper) {
        this(properties, jwtSigner, objectMapper, Clock.systemUTC(), null);
    }

    public GitHubInstallationTokenService(
            GitHubAppProperties properties,
            GitHubAppJwtSigner jwtSigner,
            ObjectMapper objectMapper,
            Clock clock) {
        this(properties, jwtSigner, objectMapper, clock, null);
    }

    public GitHubInstallationTokenService(
            GitHubAppProperties properties,
            GitHubAppJwtSigner jwtSigner,
            ObjectMapper objectMapper,
            Clock clock,
            Environment environment) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.jwtSigner = Objects.requireNonNull(jwtSigner, "jwtSigner must not be null");
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper().findAndRegisterModules();
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.environment = environment;
        long connectMs = Math.max(500L, properties.getConnectTimeoutMs());
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectMs))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * Returns a valid installation access token for the given GitHub App installation ID,
     * refreshing when missing or within 5 minutes of {@code expires_at}.
     */
    public String getInstallationToken(long installationId) {
        if (installationId <= 0) {
            throw new GitHubApiUnavailableException("GitHub installationId must be positive");
        }
        if (!properties.isEnabled()) {
            throw new GitHubApiUnavailableException("GitHub App integration is disabled");
        }

        Instant now = clock.instant();
        CachedToken cached = tokenCache.get(installationId);
        if (isUsable(cached, now)) {
            return cached.token();
        }

        Object lock = installationLocks.computeIfAbsent(installationId, k -> new Object());
        synchronized (lock) {
            now = clock.instant();
            cached = tokenCache.get(installationId);
            if (isUsable(cached, now)) {
                return cached.token();
            }

            CachedToken fresh = requestNewInstallationToken(installationId);
            tokenCache.put(installationId, fresh);
            return fresh.token();
        }
    }

    /**
     * Evicts any cached installation token (for example, after a 401 response).
     */
    public void invalidateToken(long installationId) {
        tokenCache.remove(installationId);
    }

    private boolean isUsable(CachedToken cached, Instant now) {
        return cached != null
                && cached.token() != null
                && !cached.token().isBlank()
                && cached.expiresAt() != null
                && now.isBefore(cached.expiresAt().minus(REFRESH_BUFFER));
    }

    private CachedToken requestNewInstallationToken(long installationId) {
        boolean effectiveAllowLoopback = GitHubRestClient.isEffectiveAllowLoopbackApi(properties, environment);
        URI baseUri = GitHubRestClient.validateApiBaseUrl(properties.getApiBaseUrl(), effectiveAllowLoopback);
        String base = baseUri.toString();
        URI tokenUri = URI.create(base + "/app/installations/" + installationId + "/access_tokens");

        String appJwt = jwtSigner.generateAppJwt();
        long requestMs = Math.max(1000L, properties.getRequestTimeoutMs());

        HttpRequest request = HttpRequest.newBuilder(tokenUri)
                .timeout(Duration.ofMillis(requestMs))
                .header("Accept", GitHubRestClient.ACCEPT_HEADER)
                .header("X-GitHub-Api-Version", GitHubRestClient.API_VERSION)
                .header("Authorization", "Bearer " + appJwt)
                .header("User-Agent", GitHubRestClient.USER_AGENT)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new GitHubApiUnavailableException("Interrupted while requesting GitHub installation token", ex);
        } catch (IOException ex) {
            log.warn("GitHub installation token exchange failed for installationId={}: {}", installationId, ex.getMessage());
            throw new GitHubApiUnavailableException("Failed to reach GitHub API for installation token exchange", ex);
        }

        int status = response.statusCode();
        String body = GitHubRestClient.readBoundedBody(response, MAX_TOKEN_RESPONSE_BYTES);
        if (status < 200 || status >= 300) {
            Long retryAfter = GitHubRestClient.extractRetryAfterSeconds(response.headers(), clock.instant());
            log.warn("GitHub installation token exchange returned HTTP {} for installationId={}", status, installationId);
            throw new GitHubApiUnavailableException(
                    "GitHub installation token exchange failed with HTTP " + status,
                    retryAfter
            );
        }

        Map<String, Object> parsed;
        try {
            parsed = objectMapper.readValue(body.getBytes(StandardCharsets.UTF_8), new TypeReference<>() {});
        } catch (Exception ex) {
            throw new GitHubApiUnavailableException("GitHub installation token response was not valid JSON", ex);
        }

        Object tokenObj = parsed.get("token");
        if (!(tokenObj instanceof String token) || token.isBlank()) {
            throw new GitHubApiUnavailableException("GitHub installation token response did not include a token");
        }

        Instant expiresAt = clock.instant().plus(DEFAULT_TOKEN_TTL);
        Object expiresAtObj = parsed.get("expires_at");
        if (expiresAtObj instanceof String expiresStr && !expiresStr.isBlank()) {
            try {
                expiresAt = Instant.parse(expiresStr.trim());
            } catch (Exception ignored) {
                // Keep default TTL if expires_at cannot be parsed.
            }
        }

        return new CachedToken(token, expiresAt);
    }

    private record CachedToken(String token, Instant expiresAt) {
    }
}

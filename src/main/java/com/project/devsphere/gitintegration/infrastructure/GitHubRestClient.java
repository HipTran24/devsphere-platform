package com.project.devsphere.gitintegration.infrastructure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * SSRF-hardened GitHub REST API client authenticated with cached GitHub App installation tokens.
 *
 * <p>Enforces GitHub API headers, inspects rate-limit headers ({@code X-RateLimit-Remaining},
 * {@code X-RateLimit-Reset}, {@code Retry-After}), retries once on HTTP 401 after invalidating
 * the cached installation token, and translates rate-limit, 5xx, and transport failures into
 * non-fatal {@link GitHubApiUnavailableException}.
 */
@Component
public class GitHubRestClient {

    private static final Logger log = LoggerFactory.getLogger(GitHubRestClient.class);

    public static final String ACCEPT_HEADER = "application/vnd.github+json";
    public static final String API_VERSION = "2022-11-28";
    public static final String USER_AGENT = "DevSphere-GitHub-App";

    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024; // 4 MB
    private static final int MAX_DRAIN_BYTES = 64 * 1024; // 64 KB
    private static final Pattern IPV4_LITERAL = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");
    private static final Pattern NON_CANONICAL_NUMERIC_HOST = Pattern.compile(
            "^(?:\\d+|0x[0-9a-f]+|(?:0[0-7]+|0x[0-9a-f]+|\\d+)(?:\\.(?:0[0-7]+|0x[0-9a-f]+|\\d+)){1,3})$"
    );
    private static final Pattern SAFE_SEGMENT = Pattern.compile("^[A-Za-z0-9._-]+$");
    private static final Set<String> BLOCKED_METADATA_HOSTS = Set.of(
            "metadata",
            "metadata.google.internal",
            "metadata.aws.internal",
            "instance-data",
            "instance-data.ec2.internal",
            "169.254.169.254",
            "169.254.170.2",
            "100.100.100.200",
            "fd00:ec2::254",
            "::ffff:169.254.169.254",
            "::ffff:a9fe:a9fe",
            "0:0:0:0:0:ffff:a9fe:a9fe"
    );

    private final GitHubAppProperties properties;
    private final GitHubInstallationTokenService tokenService;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Environment environment;
    private final HttpClient httpClient;

    @Autowired
    public GitHubRestClient(
            GitHubAppProperties properties,
            GitHubInstallationTokenService tokenService,
            @Autowired(required = false) ObjectMapper objectMapper,
            @Autowired(required = false) Environment environment) {
        this(properties, tokenService, objectMapper, Clock.systemUTC(), environment);
    }

    public GitHubRestClient(
            GitHubAppProperties properties,
            GitHubInstallationTokenService tokenService,
            ObjectMapper objectMapper) {
        this(properties, tokenService, objectMapper, Clock.systemUTC(), null);
    }

    public GitHubRestClient(
            GitHubAppProperties properties,
            GitHubInstallationTokenService tokenService,
            ObjectMapper objectMapper,
            Clock clock) {
        this(properties, tokenService, objectMapper, clock, null);
    }

    public GitHubRestClient(
            GitHubAppProperties properties,
            GitHubInstallationTokenService tokenService,
            ObjectMapper objectMapper,
            Clock clock,
            Environment environment) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.tokenService = Objects.requireNonNull(tokenService, "tokenService must not be null");
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper().findAndRegisterModules();
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.environment = environment;
        long connectMs = Math.max(500L, properties.getConnectTimeoutMs());
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectMs))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    static boolean isEffectiveAllowLoopbackApi(GitHubAppProperties properties, Environment environment) {
        return properties != null && properties.isAllowLoopbackApi() && !isProdProfile(environment);
    }

    static boolean isProdProfile(Environment environment) {
        return environment != null && environment.acceptsProfiles(Profiles.of("prod"));
    }

    /**
     * Fetches recent pull requests via {@code GET /repos/{owner}/{repo}/pulls?state=all&per_page=30}.
     */
    public List<Map<String, Object>> listPullRequests(long installationId, String owner, String repo) {
        String path = "/repos/" + encodeSegment(owner, "owner") + "/" + encodeSegment(repo, "repo")
                + "/pulls?state=all&per_page=30";
        return getJsonList(installationId, path);
    }

    /**
     * Fetches recent commits via {@code GET /repos/{owner}/{repo}/commits?per_page=30}.
     */
    public List<Map<String, Object>> listCommits(long installationId, String owner, String repo) {
        String path = "/repos/" + encodeSegment(owner, "owner") + "/" + encodeSegment(repo, "repo")
                + "/commits?per_page=30";
        return getJsonList(installationId, path);
    }

    /**
     * Fetches check runs for a commit ref via {@code GET /repos/{owner}/{repo}/commits/{ref}/check-runs}.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listCheckRuns(long installationId, String owner, String repo, String ref) {
        if (ref == null || ref.isBlank()) {
            throw new GitHubApiUnavailableException("Commit ref must not be blank");
        }
        String cleanRef = ref.trim();
        if (cleanRef.contains("..") || cleanRef.contains("/") || cleanRef.contains("\\")
                || cleanRef.contains("?") || cleanRef.contains("#")) {
            throw new GitHubApiUnavailableException("Invalid commit ref for GitHub check-runs request");
        }
        String path = "/repos/" + encodeSegment(owner, "owner") + "/" + encodeSegment(repo, "repo")
                + "/commits/" + URLEncoder.encode(cleanRef, StandardCharsets.UTF_8) + "/check-runs";
        String body = executeGetWithAuthRetry(installationId, path);
        try {
            Object parsed = objectMapper.readValue(body, Object.class);
            if (parsed instanceof Map<?, ?> map) {
                Object runs = map.get("check_runs");
                if (runs instanceof List<?> list) {
                    return (List<Map<String, Object>>) list;
                }
                return List.of();
            }
            if (parsed instanceof List<?> list) {
                return (List<Map<String, Object>>) list;
            }
            return List.of();
        } catch (Exception ex) {
            throw new GitHubApiUnavailableException("Failed to parse GitHub check-runs response JSON", ex);
        }
    }

    /**
     * Fetches pull request reviews via {@code GET /repos/{owner}/{repo}/pulls/{prNumber}/reviews}.
     */
    public List<Map<String, Object>> listPullRequestReviews(long installationId, String owner, String repo, int prNumber) {
        if (prNumber <= 0) {
            throw new GitHubApiUnavailableException("Pull request number must be positive");
        }
        String path = "/repos/" + encodeSegment(owner, "owner") + "/" + encodeSegment(repo, "repo")
                + "/pulls/" + prNumber + "/reviews";
        return getJsonList(installationId, path);
    }

    @FunctionalInterface
    interface DnsResolver {
        InetAddress[] resolveAll(String host) throws UnknownHostException;
    }

    /**
     * SSRF-validates the configured GitHub API base URL (including DNS resolution checks for hostnames).
     *
     * <ul>
     *   <li>Rejects userinfo, query parameters, fragments, and invalid ports/hosts.</li>
     *   <li>Always rejects cloud metadata IPs/hostnames, link-local, multicast, and unspecified addresses.</li>
     *   <li>Resolves non-IP hostnames via {@link InetAddress#getAllByName(String)} and validates every
     *       resolved address against SSRF rules.</li>
     *   <li>Requires {@code https} unless {@code allowLoopbackApi=true} and the host is loopback
     *       ({@code localhost}, {@code 127.0.0.1}, {@code ::1}).</li>
     * </ul>
     */
    public static URI validateApiBaseUrl(String rawBaseUrl, boolean allowLoopbackApi) {
        return validateApiBaseUrl(rawBaseUrl, allowLoopbackApi, InetAddress::getAllByName);
    }

    static URI validateApiBaseUrl(String rawBaseUrl, boolean allowLoopbackApi, DnsResolver dnsResolver) {
        if (rawBaseUrl == null || rawBaseUrl.isBlank()) {
            throw new GitHubApiUnavailableException("GitHub apiBaseUrl must not be blank");
        }

        URI uri;
        try {
            uri = new URI(rawBaseUrl.strip());
        } catch (URISyntaxException ex) {
            throw new GitHubApiUnavailableException("GitHub apiBaseUrl is not a valid URI");
        }

        if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new GitHubApiUnavailableException("GitHub apiBaseUrl must not contain userinfo, query, or fragment");
        }

        int port = uri.getPort();
        if (port != -1 && (port <= 0 || port > 65535)) {
            throw new GitHubApiUnavailableException("GitHub apiBaseUrl port is out of range");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new GitHubApiUnavailableException("GitHub apiBaseUrl must include a valid host");
        }

        String normalized = normalizeHost(host);
        if (normalized.isEmpty() || normalized.contains("%") || hasWhitespaceOrControl(normalized)) {
            throw new GitHubApiUnavailableException("GitHub apiBaseUrl contains an invalid host");
        }

        if (BLOCKED_METADATA_HOSTS.contains(normalized)) {
            throw new GitHubApiUnavailableException("GitHub apiBaseUrl targets a forbidden metadata host");
        }

        boolean isIpv4Literal = IPV4_LITERAL.matcher(normalized).matches();
        boolean isIpv6Literal = normalized.contains(":");
        if (!isIpv4Literal && !isIpv6Literal && NON_CANONICAL_NUMERIC_HOST.matcher(normalized).matches()) {
            throw new GitHubApiUnavailableException("GitHub apiBaseUrl uses a non-canonical numeric host");
        }
        if (isIpv4Literal && hasLeadingZeroOctet(normalized)) {
            throw new GitHubApiUnavailableException("GitHub apiBaseUrl IPv4 octets must not contain leading zeros");
        }

        boolean loopbackHost = isLoopbackHostname(normalized);
        if (isIpv4Literal || isIpv6Literal) {
            InetAddress address;
            try {
                address = InetAddress.getByName(normalized);
            } catch (UnknownHostException ex) {
                throw new GitHubApiUnavailableException("GitHub apiBaseUrl contains an invalid IP address");
            }
            validateAddress(address, allowLoopbackApi);
            loopbackHost = address.isLoopbackAddress();
        } else {
            if (loopbackHost && !allowLoopbackApi) {
                throw new GitHubApiUnavailableException("GitHub apiBaseUrl loopback host is not permitted");
            }
            try {
                InetAddress[] resolvedAddresses = dnsResolver.resolveAll(normalized);
                if (resolvedAddresses != null) {
                    for (InetAddress resolved : resolvedAddresses) {
                        validateAddress(resolved, allowLoopbackApi);
                    }
                }
            } catch (UnknownHostException ignored) {
                // Defer unreachable/offline DNS failures to HTTP transport -> 503 GITHUB_API_UNAVAILABLE.
            }
        }

        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if ("https".equals(scheme)) {
            return stripTrailingSlash(uri);
        }
        if ("http".equals(scheme) && allowLoopbackApi && loopbackHost) {
            return stripTrailingSlash(uri);
        }
        throw new GitHubApiUnavailableException("GitHub apiBaseUrl must use https");
    }

    private List<Map<String, Object>> getJsonList(long installationId, String pathAndQuery) {
        String body = executeGetWithAuthRetry(installationId, pathAndQuery);
        try {
            return objectMapper.readValue(body, new TypeReference<>() {});
        } catch (Exception ex) {
            throw new GitHubApiUnavailableException("Failed to parse GitHub API JSON array response", ex);
        }
    }

    private String executeGetWithAuthRetry(long installationId, String pathAndQuery) {
        if (!properties.isEnabled()) {
            throw new GitHubApiUnavailableException("GitHub App integration is disabled");
        }
        boolean effectiveAllowLoopback = isEffectiveAllowLoopbackApi(properties, environment);
        URI baseUri = validateApiBaseUrl(properties.getApiBaseUrl(), effectiveAllowLoopback);
        URI targetUri = URI.create(baseUri.toString() + pathAndQuery);

        String token = tokenService.getInstallationToken(installationId);
        HttpResponse<InputStream> response = sendGet(targetUri, token);
        if (response.statusCode() == 401) {
            drainQuietly(response);
            tokenService.invalidateToken(installationId);
            String refreshedToken = tokenService.getInstallationToken(installationId);
            response = sendGet(targetUri, refreshedToken);
        }

        int status = response.statusCode();
        HttpHeaders headers = response.headers();
        String body = readBoundedBody(response, MAX_RESPONSE_BYTES);

        if (status >= 200 && status < 300) {
            checkRateLimitHeaderWarning(headers);
            return body;
        }

        Long retryAfter = extractRetryAfterSeconds(headers, clock.instant());
        if (isRateLimited(status, headers, body)) {
            log.warn("GitHub API rate-limited (HTTP {}, retryAfterSeconds={}) on {}", status, retryAfter, pathAndQuery);
            throw new GitHubApiUnavailableException("GitHub API rate limit exceeded (HTTP " + status + ")", retryAfter);
        }

        if (status >= 500) {
            log.warn("GitHub API server error HTTP {} on {}", status, pathAndQuery);
            throw new GitHubApiUnavailableException("GitHub API returned server error HTTP " + status, retryAfter);
        }

        log.warn("GitHub API request failed with HTTP {} on {}", status, pathAndQuery);
        throw new GitHubApiUnavailableException("GitHub API request failed with HTTP " + status, retryAfter);
    }

    private HttpResponse<InputStream> sendGet(URI targetUri, String installationToken) {
        long requestMs = Math.max(1000L, properties.getRequestTimeoutMs());
        HttpRequest request = HttpRequest.newBuilder(targetUri)
                .timeout(Duration.ofMillis(requestMs))
                .header("Accept", ACCEPT_HEADER)
                .header("X-GitHub-Api-Version", API_VERSION)
                .header("Authorization", "Bearer " + installationToken)
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();

        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new GitHubApiUnavailableException("Interrupted while calling GitHub API", ex);
        } catch (IOException ex) {
            log.warn("GitHub API network/timeout error on {}: {}", targetUri.getPath(), ex.getMessage());
            throw new GitHubApiUnavailableException("GitHub API network or timeout error", ex);
        }
    }

    private void checkRateLimitHeaderWarning(HttpHeaders headers) {
        Optional<String> remaining = headers.firstValue("X-RateLimit-Remaining");
        if (remaining.isPresent()) {
            try {
                if (Long.parseLong(remaining.get().trim()) <= 5L) {
                    log.warn("GitHub API rate limit remaining is low: {}", remaining.get().trim());
                }
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private static boolean isRateLimited(int status, HttpHeaders headers, String body) {
        if (status == 429) {
            return true;
        }
        if (status == 403) {
            Optional<String> remaining = headers.firstValue("X-RateLimit-Remaining");
            if (remaining.isPresent() && "0".equals(remaining.get().trim())) {
                return true;
            }
            if (headers.firstValue("Retry-After").isPresent()) {
                return true;
            }
            if (body != null && body.toLowerCase(Locale.ROOT).contains("rate limit")) {
                return true;
            }
        }
        return false;
    }

    static Long extractRetryAfterSeconds(HttpHeaders headers, Instant now) {
        Optional<String> retryAfter = headers.firstValue("Retry-After");
        if (retryAfter.isPresent()) {
            try {
                long seconds = Long.parseLong(retryAfter.get().trim());
                if (seconds >= 0) {
                    return seconds;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        Optional<String> reset = headers.firstValue("X-RateLimit-Reset");
        if (reset.isPresent()) {
            try {
                long resetEpoch = Long.parseLong(reset.get().trim());
                long delta = resetEpoch - now.getEpochSecond();
                return Math.max(0L, delta);
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    static String readBoundedBody(HttpResponse<InputStream> response, int maxBytes) {
        try (InputStream in = response.body()) {
            if (in == null) {
                return "";
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new GitHubApiUnavailableException("GitHub API response exceeded maximum allowed size");
                }
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new GitHubApiUnavailableException("Failed to read GitHub API response body", ex);
        }
    }

    private static void drainQuietly(HttpResponse<InputStream> response) {
        try (InputStream in = response.body()) {
            if (in == null) {
                return;
            }
            byte[] buffer = new byte[4096];
            int remaining = MAX_DRAIN_BYTES;
            while (remaining > 0) {
                int read = in.read(buffer, 0, Math.min(buffer.length, remaining));
                if (read == -1) {
                    break;
                }
                remaining -= read;
            }
        } catch (IOException ignored) {
        }
    }

    private static String encodeSegment(String segment, String name) {
        if (segment == null || segment.isBlank()) {
            throw new GitHubApiUnavailableException("GitHub repository " + name + " must not be blank");
        }
        String trimmed = segment.trim();
        if (".".equals(trimmed) || "..".equals(trimmed) || !SAFE_SEGMENT.matcher(trimmed).matches()) {
            throw new GitHubApiUnavailableException("Invalid GitHub repository " + name);
        }
        return URLEncoder.encode(trimmed, StandardCharsets.UTF_8);
    }

    private static void validateAddress(InetAddress address, boolean allowLoopbackApi) {
        if (isMetadataAddress(address)) {
            throw new GitHubApiUnavailableException("GitHub apiBaseUrl targets a forbidden cloud metadata address");
        }
        if (address.isLinkLocalAddress() || address.isMulticastAddress() || address.isAnyLocalAddress()) {
            throw new GitHubApiUnavailableException("GitHub apiBaseUrl targets a forbidden link-local, multicast, or unspecified address");
        }
        if (address.isLoopbackAddress()) {
            if (!allowLoopbackApi) {
                throw new GitHubApiUnavailableException("GitHub apiBaseUrl loopback address is not permitted");
            }
            return;
        }
        if (address.isSiteLocalAddress() || isUniqueLocalIpv6(address) || isCarrierGradeNat(address)) {
            throw new GitHubApiUnavailableException("GitHub apiBaseUrl must not target private or internal IP ranges");
        }
    }

    private static String normalizeHost(String host) {
        String normalized = host == null ? "" : host.strip().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static boolean isLoopbackHostname(String normalized) {
        return "localhost".equals(normalized)
                || "localhost.localdomain".equals(normalized)
                || normalized.endsWith(".localhost");
    }

    private static boolean hasWhitespaceOrControl(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasLeadingZeroOctet(String ipv4) {
        String[] parts = ipv4.split("\\.");
        if (parts.length != 4) {
            return true;
        }
        for (String part : parts) {
            if (part.length() > 1 && part.startsWith("0")) {
                return true;
            }
            int octet;
            try {
                octet = Integer.parseInt(part);
            } catch (NumberFormatException ex) {
                return true;
            }
            if (octet < 0 || octet > 255) {
                return true;
            }
        }
        return false;
    }

    private static boolean isMetadataAddress(InetAddress address) {
        String text = address.getHostAddress().toLowerCase(Locale.ROOT);
        int scope = text.indexOf('%');
        if (scope >= 0) {
            text = text.substring(0, scope);
        }
        return "169.254.169.254".equals(text)
                || "169.254.170.2".equals(text)
                || "100.100.100.200".equals(text)
                || "fd00:ec2:0:0:0:0:0:254".equals(text)
                || "0:0:0:0:0:ffff:a9fe:a9fe".equals(text);
    }

    private static boolean isUniqueLocalIpv6(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
    }

    private static boolean isCarrierGradeNat(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 4 && (bytes[0] & 0xFF) == 100 && (bytes[1] & 0xC0) == 64;
    }

    private static URI stripTrailingSlash(URI uri) {
        String path = uri.getRawPath();
        if (path == null || path.isEmpty() || !path.endsWith("/")) {
            return uri;
        }
        String trimmedPath = path;
        while (trimmedPath.endsWith("/")) {
            trimmedPath = trimmedPath.substring(0, trimmedPath.length() - 1);
        }
        try {
            return new URI(
                    uri.getScheme(),
                    uri.getRawUserInfo(),
                    uri.getHost(),
                    uri.getPort(),
                    trimmedPath.isEmpty() ? null : trimmedPath,
                    null,
                    null
            );
        } catch (URISyntaxException ex) {
            return uri;
        }
    }
}

package com.project.devsphere.gitintegration.infrastructure;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitHubRestClientTest {

    private static String pkcs8Pem;

    private HttpServer server;
    private AtomicInteger tokenCalls;
    private AtomicInteger prCalls;
    private AtomicInteger prStatus;
    private AtomicReference<String> prBody;
    private AtomicReference<String> lastAcceptHeader;
    private AtomicReference<String> lastApiVersionHeader;
    private AtomicReference<String> lastAuthHeader;
    private AtomicReference<String> rateLimitRemainingHeader;
    private AtomicReference<String> rateLimitResetHeader;
    private AtomicReference<String> retryAfterHeader;

    @BeforeAll
    static void initKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        String base64 = Base64.getMimeEncoder(64, new byte[]{'\n'})
                .encodeToString(keyPair.getPrivate().getEncoded());
        pkcs8Pem = "-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----";
    }

    @BeforeEach
    void setUp() throws Exception {
        tokenCalls = new AtomicInteger(0);
        prCalls = new AtomicInteger(0);
        prStatus = new AtomicInteger(200);
        prBody = new AtomicReference<>("[{\"number\":42,\"state\":\"open\",\"title\":\"DSP-1 Add sync\"}]");
        lastAcceptHeader = new AtomicReference<>();
        lastApiVersionHeader = new AtomicReference<>();
        lastAuthHeader = new AtomicReference<>();
        rateLimitRemainingHeader = new AtomicReference<>("4999");
        rateLimitResetHeader = new AtomicReference<>(null);
        retryAfterHeader = new AtomicReference<>(null);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/app/installations/", exchange -> {
            int n = tokenCalls.incrementAndGet();
            String body = "{\"token\":\"ghs_tok_" + n + "\",\"expires_at\":\"2099-01-01T00:00:00Z\"}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(201, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });

        server.createContext("/repos/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            lastAcceptHeader.set(exchange.getRequestHeaders().getFirst("Accept"));
            lastApiVersionHeader.set(exchange.getRequestHeaders().getFirst("X-GitHub-Api-Version"));
            lastAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));

            if (rateLimitRemainingHeader.get() != null) {
                exchange.getResponseHeaders().add("X-RateLimit-Remaining", rateLimitRemainingHeader.get());
            }
            if (rateLimitResetHeader.get() != null) {
                exchange.getResponseHeaders().add("X-RateLimit-Reset", rateLimitResetHeader.get());
            }
            if (retryAfterHeader.get() != null) {
                exchange.getResponseHeaders().add("Retry-After", retryAfterHeader.get());
            }

            int status;
            String body;
            if (path.endsWith("/pulls")) {
                int attempt = prCalls.incrementAndGet();
                status = (prStatus.get() == 401 && attempt > 1) ? 200 : prStatus.get();
                body = status == 200 ? prBody.get() : "{\"message\":\"API error\"}";
            } else if (path.endsWith("/commits")) {
                status = 200;
                body = "[{\"sha\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"commit\":{\"message\":\"DSP-1 commit\"}}]";
            } else if (path.endsWith("/check-runs")) {
                status = 200;
                body = "{\"total_count\":1,\"check_runs\":[{\"id\":901,\"name\":\"ci-build\",\"status\":\"completed\",\"conclusion\":\"success\"}]}";
            } else if (path.endsWith("/reviews")) {
                status = 200;
                body = "[{\"id\":701,\"state\":\"APPROVED\",\"user\":{\"login\":\"peer-dev\"}}]";
            } else {
                status = 404;
                body = "{\"message\":\"not found\"}";
            }

            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private GitHubRestClient createClient(Clock clock) {
        GitHubAppProperties props = new GitHubAppProperties();
        props.setEnabled(true);
        props.setAppId("321");
        props.setPrivateKeyPem(pkcs8Pem);
        props.setApiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        props.setAllowLoopbackApi(true);
        GitHubAppJwtSigner signer = new GitHubAppJwtSigner(props, clock);
        GitHubInstallationTokenService tokenService = new GitHubInstallationTokenService(props, signer, null, clock);
        return new GitHubRestClient(props, tokenService, null, clock);
    }

    @Test
    @DisplayName("SSRF validation blocks metadata, link-local, multicast, unspecified, private, userinfo/query/fragment, and non-HTTPS")
    void validateApiBaseUrl_enforcesSsrfGuards() {
        URI github = GitHubRestClient.validateApiBaseUrl("https://api.github.com/", false);
        assertThat(github.toString()).isEqualTo("https://api.github.com");

        List<String> forbiddenAlways = List.of(
                "http://169.254.169.254",
                "https://169.254.169.254",
                "https://169.254.170.2",
                "https://100.100.100.200",
                "https://metadata.google.internal",
                "https://[fd00:ec2::254]",
                "https://169.254.1.10",
                "https://224.0.0.1",
                "https://0.0.0.0",
                "https://[::]",
                "https://10.0.0.1",
                "https://192.168.1.5",
                "https://user:pass@api.github.com",
                "https://api.github.com?foo=bar",
                "https://api.github.com#frag",
                "http://api.github.com"
        );

        for (String url : forbiddenAlways) {
            assertThatThrownBy(() -> GitHubRestClient.validateApiBaseUrl(url, false))
                    .as("Expected SSRF rejection for %s (allowLoopback=false)", url)
                    .isInstanceOf(GitHubApiUnavailableException.class);
            assertThatThrownBy(() -> GitHubRestClient.validateApiBaseUrl(url, true))
                    .as("Expected SSRF rejection for %s (allowLoopback=true)", url)
                    .isInstanceOf(GitHubApiUnavailableException.class);
        }

        // Loopback blocked when allowLoopbackApi=false, allowed when true
        assertThatThrownBy(() -> GitHubRestClient.validateApiBaseUrl("http://127.0.0.1:8080", false))
                .isInstanceOf(GitHubApiUnavailableException.class);
        assertThatThrownBy(() -> GitHubRestClient.validateApiBaseUrl("http://localhost:8080", false))
                .isInstanceOf(GitHubApiUnavailableException.class);

        assertThat(GitHubRestClient.validateApiBaseUrl("http://127.0.0.1:8080/", true).toString())
                .isEqualTo("http://127.0.0.1:8080");
        assertThat(GitHubRestClient.validateApiBaseUrl("http://localhost:8080", true).toString())
                .isEqualTo("http://localhost:8080");
    }

    @Test
    @DisplayName("Fetches PRs, commits, check runs, and PR reviews with required GitHub API headers")
    void fetchEndpoints_sendsRequiredHeadersAndParsesResponses() {
        GitHubRestClient client = createClient(Clock.systemUTC());

        List<Map<String, Object>> prs = client.listPullRequests(100L, "acme", "devsphere");
        assertThat(prs).hasSize(1);
        assertThat(prs.get(0).get("number")).isEqualTo(42);
        assertThat(lastAcceptHeader.get()).isEqualTo("application/vnd.github+json");
        assertThat(lastApiVersionHeader.get()).isEqualTo("2022-11-28");
        assertThat(lastAuthHeader.get()).isEqualTo("Bearer ghs_tok_1");

        List<Map<String, Object>> commits = client.listCommits(100L, "acme", "devsphere");
        assertThat(commits).hasSize(1);
        assertThat(commits.get(0).get("sha")).isEqualTo("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");

        List<Map<String, Object>> checkRuns = client.listCheckRuns(100L, "acme", "devsphere", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        assertThat(checkRuns).hasSize(1);
        assertThat(checkRuns.get(0).get("name")).isEqualTo("ci-build");

        List<Map<String, Object>> reviews = client.listPullRequestReviews(100L, "acme", "devsphere", 42);
        assertThat(reviews).hasSize(1);
        assertThat(reviews.get(0).get("state")).isEqualTo("APPROVED");

        // All 4 requests reused the single cached installation token
        assertThat(tokenCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Invalidates cached token and retries once on HTTP 401")
    void unauthorized_invalidatesAndRetriesOnce() {
        prStatus.set(401);
        GitHubRestClient client = createClient(Clock.systemUTC());

        List<Map<String, Object>> prs = client.listPullRequests(100L, "acme", "devsphere");
        assertThat(prs).hasSize(1);
        assertThat(prCalls.get()).isEqualTo(2);
        assertThat(tokenCalls.get()).isEqualTo(2);
        assertThat(lastAuthHeader.get()).isEqualTo("Bearer ghs_tok_2");
    }

    @Test
    @DisplayName("Translates 403 rate-limit, 429 Retry-After, and 5xx into non-fatal GitHubApiUnavailableException")
    void rateLimitAndServerErrors_throwApiUnavailable() {
        Instant fixedNow = Instant.parse("2026-11-24T12:00:00Z");
        GitHubRestClient client = createClient(Clock.fixed(fixedNow, ZoneOffset.UTC));

        // 403 with X-RateLimit-Remaining: 0 and X-RateLimit-Reset
        prStatus.set(403);
        rateLimitRemainingHeader.set("0");
        rateLimitResetHeader.set(String.valueOf(fixedNow.getEpochSecond() + 120L));
        assertThatThrownBy(() -> client.listPullRequests(100L, "acme", "devsphere"))
                .isInstanceOf(GitHubApiUnavailableException.class)
                .hasMessageContaining("rate limit")
                .satisfies(ex -> assertThat(((GitHubApiUnavailableException) ex).getRetryAfterSeconds()).isEqualTo(120L));

        // 429 with Retry-After
        prStatus.set(429);
        rateLimitRemainingHeader.set(null);
        rateLimitResetHeader.set(null);
        retryAfterHeader.set("45");
        assertThatThrownBy(() -> client.listPullRequests(100L, "acme", "devsphere"))
                .isInstanceOf(GitHubApiUnavailableException.class)
                .satisfies(ex -> assertThat(((GitHubApiUnavailableException) ex).getRetryAfterSeconds()).isEqualTo(45L));

        // 503 server error
        prStatus.set(503);
        retryAfterHeader.set(null);
        assertThatThrownBy(() -> client.listPullRequests(100L, "acme", "devsphere"))
                .isInstanceOf(GitHubApiUnavailableException.class)
                .hasMessageContaining("503");
    }

    @Test
    @DisplayName("Rejects loopback API URL when prod profile is active even if allowLoopbackApi=true")
    void prodProfile_forbidsLoopbackApiEvenWhenConfiguredTrue() {
        GitHubAppProperties props = new GitHubAppProperties();
        props.setEnabled(true);
        props.setAppId("321");
        props.setPrivateKeyPem(pkcs8Pem);
        props.setApiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        props.setAllowLoopbackApi(true);

        MockEnvironment prodEnv = new MockEnvironment();
        prodEnv.setActiveProfiles("prod");
        Clock clock = Clock.systemUTC();
        GitHubAppJwtSigner signer = new GitHubAppJwtSigner(props, clock);
        GitHubInstallationTokenService tokenService =
                new GitHubInstallationTokenService(props, signer, null, clock, prodEnv);
        GitHubRestClient prodClient = new GitHubRestClient(props, tokenService, null, clock, prodEnv);

        assertThatThrownBy(() -> prodClient.listPullRequests(100L, "acme", "devsphere"))
                .isInstanceOf(GitHubApiUnavailableException.class)
                .hasMessageContaining("loopback");
        assertThat(tokenCalls.get()).isEqualTo(0);
        assertThat(prCalls.get()).isEqualTo(0);
    }

    @Test
    @DisplayName("DNS resolution SSRF check blocks hostnames resolving to metadata, link-local, multicast, unspecified, private, or loopback IPs")
    void validateApiBaseUrl_blocksForbiddenDnsResolutions() throws Exception {
        List<String> forbiddenResolvedIps = List.of(
                "169.254.169.254",
                "169.254.170.2",
                "100.100.100.200",
                "fd00:ec2::254",
                "169.254.10.20",
                "224.0.0.1",
                "0.0.0.0",
                "10.0.0.8",
                "192.168.1.50",
                "127.0.0.1"
        );

        for (String ip : forbiddenResolvedIps) {
            InetAddress resolved = InetAddress.getByName(ip);
            assertThatThrownBy(() -> GitHubRestClient.validateApiBaseUrl(
                    "https://rebind.example.test", false, host -> new InetAddress[]{resolved}))
                    .as("Expected DNS SSRF rejection when hostname resolves to %s", ip)
                    .isInstanceOf(GitHubApiUnavailableException.class);
        }

        // Public IP passes cleanly
        InetAddress publicGithubIp = InetAddress.getByName("140.82.114.6");
        URI valid = GitHubRestClient.validateApiBaseUrl(
                "https://api.github.com", false, host -> new InetAddress[]{publicGithubIp});
        assertThat(valid.toString()).isEqualTo("https://api.github.com");
    }
}

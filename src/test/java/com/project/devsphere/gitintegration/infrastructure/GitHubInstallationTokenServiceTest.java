package com.project.devsphere.gitintegration.infrastructure;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitHubInstallationTokenServiceTest {

    private static KeyPair rsaKeyPair;
    private static String pkcs8Pem;

    private HttpServer server;
    private MutableClock mutableClock;
    private AtomicInteger exchangeCalls;
    private AtomicInteger responseStatus;
    private AtomicReference<Instant> tokenExpiresAt;
    private AtomicReference<String> lastAuthHeader;
    private AtomicReference<Long> artificialDelayMs;

    @BeforeAll
    static void initKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        rsaKeyPair = generator.generateKeyPair();
        String base64 = Base64.getMimeEncoder(64, new byte[]{'\n'})
                .encodeToString(rsaKeyPair.getPrivate().getEncoded());
        pkcs8Pem = "-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----";
    }

    @BeforeEach
    void setUp() throws Exception {
        mutableClock = new MutableClock(Instant.parse("2026-11-24T12:00:00Z"));
        exchangeCalls = new AtomicInteger(0);
        responseStatus = new AtomicInteger(201);
        tokenExpiresAt = new AtomicReference<>(mutableClock.instant().plus(Duration.ofHours(1)));
        lastAuthHeader = new AtomicReference<>();
        artificialDelayMs = new AtomicReference<>(0L);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/app/installations/", exchange -> {
            long delay = artificialDelayMs.get();
            if (delay > 0) {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            int callNo = exchangeCalls.incrementAndGet();
            lastAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));

            int status = responseStatus.get();
            String body;
            if (status >= 200 && status < 300) {
                body = "{\"token\":\"ghs_test_token_" + callNo + "\",\"expires_at\":\"" + tokenExpiresAt.get() + "\"}";
            } else {
                body = "{\"message\":\"failed\"}";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private GitHubInstallationTokenService createService(boolean enabled) {
        GitHubAppProperties props = new GitHubAppProperties();
        props.setEnabled(enabled);
        props.setAppId("777001");
        props.setPrivateKeyPem(pkcs8Pem);
        props.setApiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        props.setAllowLoopbackApi(true);
        GitHubAppJwtSigner signer = new GitHubAppJwtSigner(props, mutableClock);
        return new GitHubInstallationTokenService(props, signer, null, mutableClock);
    }

    @Test
    @DisplayName("Exchanges RS256 App JWT for installation token, caches it, and refreshes within 5-minute buffer")
    void tokenExchange_cachesAndRefreshesAtFiveMinuteBuffer() throws Exception {
        GitHubInstallationTokenService service = createService(true);

        String token1 = service.getInstallationToken(5001L);
        assertThat(token1).isEqualTo("ghs_test_token_1");
        assertThat(exchangeCalls.get()).isEqualTo(1);

        // Verify the App JWT sent in Authorization header was legitimately RS256-signed
        String authHeader = lastAuthHeader.get();
        assertThat(authHeader).startsWith("Bearer ");
        String jwt = authHeader.substring("Bearer ".length());
        String[] parts = jwt.split("\\.");
        assertThat(parts).hasSize(3);
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(rsaKeyPair.getPublic());
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(verifier.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();

        // Advance 50 minutes (10 minutes left > 5-minute buffer): must reuse cached token
        mutableClock.advance(Duration.ofMinutes(50));
        assertThat(service.getInstallationToken(5001L)).isEqualTo("ghs_test_token_1");
        assertThat(exchangeCalls.get()).isEqualTo(1);

        // Advance 6 more minutes (4 minutes left < 5-minute buffer): must refresh
        mutableClock.advance(Duration.ofMinutes(6));
        tokenExpiresAt.set(mutableClock.instant().plus(Duration.ofHours(1)));
        String token2 = service.getInstallationToken(5001L);
        assertThat(token2).isEqualTo("ghs_test_token_2");
        assertThat(exchangeCalls.get()).isEqualTo(2);

        // Explicit invalidation forces a new exchange
        service.invalidateToken(5001L);
        tokenExpiresAt.set(mutableClock.instant().plus(Duration.ofHours(1)));
        String token3 = service.getInstallationToken(5001L);
        assertThat(token3).isEqualTo("ghs_test_token_3");
        assertThat(exchangeCalls.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("Per-installation lock prevents thundering-herd token exchanges under concurrency")
    void concurrentRequests_singleExchangePerInstallation() throws Exception {
        artificialDelayMs.set(120L);
        GitHubInstallationTokenService service = createService(true);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            Callable<String> task = () -> {
                ready.countDown();
                start.await();
                return service.getInstallationToken(9001L);
            };
            futures.add(pool.submit(task));
        }

        ready.await();
        start.countDown();

        for (Future<String> future : futures) {
            assertThat(future.get()).isEqualTo("ghs_test_token_1");
        }
        pool.shutdownNow();

        assertThat(exchangeCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Throws GitHubApiUnavailableException when disabled or GitHub returns 5xx")
    void disabledOrServerError_throwsApiUnavailable() {
        GitHubInstallationTokenService disabled = createService(false);
        assertThatThrownBy(() -> disabled.getInstallationToken(5001L))
                .isInstanceOf(GitHubApiUnavailableException.class)
                .hasMessageContaining("disabled");

        responseStatus.set(503);
        GitHubInstallationTokenService enabled = createService(true);
        assertThatThrownBy(() -> enabled.getInstallationToken(5001L))
                .isInstanceOf(GitHubApiUnavailableException.class)
                .hasMessageContaining("503");
    }

    @Test
    @DisplayName("Rejects loopback API URL when prod profile is active even if allowLoopbackApi=true")
    void prodProfile_forbidsLoopbackApiEvenWhenConfiguredTrue() {
        GitHubAppProperties props = new GitHubAppProperties();
        props.setEnabled(true);
        props.setAppId("777001");
        props.setPrivateKeyPem(pkcs8Pem);
        props.setApiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        props.setAllowLoopbackApi(true);

        MockEnvironment prodEnv = new MockEnvironment();
        prodEnv.setActiveProfiles("prod");
        GitHubAppJwtSigner signer = new GitHubAppJwtSigner(props, mutableClock);
        GitHubInstallationTokenService prodService =
                new GitHubInstallationTokenService(props, signer, null, mutableClock, prodEnv);

        assertThatThrownBy(() -> prodService.getInstallationToken(5001L))
                .isInstanceOf(GitHubApiUnavailableException.class)
                .hasMessageContaining("loopback");
        assertThat(exchangeCalls.get()).isEqualTo(0);
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant initial) {
            this.current = initial;
        }

        void advance(Duration delta) {
            this.current = this.current.plus(delta);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}

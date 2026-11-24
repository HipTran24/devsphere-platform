package com.project.devsphere.storage.infrastructure;

import com.project.devsphere.identity.application.AccessPolicy;
import com.project.devsphere.identity.application.SessionService;
import com.project.devsphere.storage.application.AttachmentService;
import com.project.devsphere.storage.application.StorageException;
import com.project.devsphere.storage.application.StorageProvider;
import com.project.devsphere.storage.domain.Attachment;
import com.project.devsphere.storage.infrastructure.persistence.AttachmentRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class S3ObjectVerificationTest {

    private HttpServer server;
    private int port;
    private final Map<String, byte[]> store = new ConcurrentHashMap<>();
    private final AtomicReference<String> lastPutContentType = new AtomicReference<>();
    private final AtomicInteger forcedStatus = new AtomicInteger(0);

    @Mock private AttachmentRepository attachmentRepository;
    @Mock private AccessPolicy accessPolicy;
    @Mock private StorageProvider storageProvider;
    @Mock private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            int override = forcedStatus.get();
            if (override > 0) {
                exchange.sendResponseHeaders(override, -1);
                exchange.close();
                return;
            }
            if ("PUT".equalsIgnoreCase(method)) {
                lastPutContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
                byte[] body = exchange.getRequestBody().readAllBytes();
                store.put(path, body);
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
                return;
            }
            if ("HEAD".equalsIgnoreCase(method)) {
                byte[] existing = store.get(path);
                if (existing != null) {
                    exchange.getResponseHeaders().add("Content-Length", String.valueOf(existing.length));
                    exchange.sendResponseHeaders(200, -1);
                } else {
                    exchange.sendResponseHeaders(404, -1);
                }
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
        store.clear();
        forcedStatus.set(0);
    }

    private static S3StorageProperties props(boolean allowInsecure, boolean verifyOnConfirm) {
        S3StorageProperties p = new S3StorageProperties();
        p.setMode("aws");
        p.setAllowInsecureEndpoint(allowInsecure);
        p.setVerifyOnConfirm(verifyOnConfirm);
        p.setHttpTimeoutMs(3000L);
        return p;
    }

    @Test
    @DisplayName("HttpPresignedObjectClient HEAD returns true on 200, false on 404, throws 503 STORAGE_UNAVAILABLE on 500 or transport error")
    void headVerificationStatuses() {
        HttpPresignedObjectClient client = new HttpPresignedObjectClient(props(true, true));
        String url = "http://127.0.0.1:" + port + "/bucket/key.pdf?X-Amz-Signature=secret123";

        assertThat(client.verifyObjectExists(url)).isFalse();

        store.put("/bucket/key.pdf", "hello".getBytes(StandardCharsets.UTF_8));
        assertThat(client.verifyObjectExists(url)).isTrue();

        forcedStatus.set(500);
        assertThatThrownBy(() -> client.verifyObjectExists(url))
                .isInstanceOf(StorageException.class)
                .satisfies(ex -> {
                    StorageException se = (StorageException) ex;
                    assertThat(se.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(se.getCode()).isEqualTo("STORAGE_UNAVAILABLE");
                    assertThat(se.getMessage()).doesNotContain("secret123");
                });
    }

    @Test
    @DisplayName("HttpPresignedObjectClient PUT uploads bytes on 200 and throws 503 STORAGE_UNAVAILABLE on 500")
    void putUploadStatuses() {
        HttpPresignedObjectClient client = new HttpPresignedObjectClient(props(true, true));
        String url = "http://127.0.0.1:" + port + "/bucket/export.pdf?X-Amz-Signature=sig456";
        byte[] payload = "%PDF-1.4\ntest".getBytes(StandardCharsets.US_ASCII);

        client.uploadObject(url, payload, "application/pdf");
        assertThat(store.get("/bucket/export.pdf")).isEqualTo(payload);
        assertThat(lastPutContentType.get()).isEqualTo("application/pdf");

        forcedStatus.set(500);
        assertThatThrownBy(() -> client.uploadObject(url, payload, "application/pdf"))
                .isInstanceOf(StorageException.class)
                .satisfies(ex -> {
                    StorageException se = (StorageException) ex;
                    assertThat(se.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(se.getCode()).isEqualTo("STORAGE_UNAVAILABLE");
                    assertThat(se.getMessage()).doesNotContain("sig456");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://169.254.169.254/latest/meta-data",
            "https://169.254.169.254/bucket/key",
            "https://[fd00:ec2::254]/bucket/key",
            "https://[::ffff:169.254.169.254]/bucket/key",
            "https://169.254.170.2/v2/credentials",
            "https://metadata.google.internal/computeMetadata/v1/",
            "https://[fe80::1]/bucket/key",
            "https://[ff02::1]/bucket/key",
            "https://0.0.0.0/bucket/key",
            "https://user:pass@s3.us-east-1.amazonaws.com/bucket/key"
    })
    @DisplayName("SSRF validation always blocks metadata, link-local, multicast, unspecified, and userinfo URLs")
    void ssrfAlwaysBlocksDangerousTargets(String url) {
        HttpPresignedObjectClient client = new HttpPresignedObjectClient(props(true, true));
        assertThatThrownBy(() -> client.verifyObjectExists(url))
                .isInstanceOf(StorageException.class);
        assertThatThrownBy(() -> client.uploadObject(url, new byte[]{1}, "application/pdf"))
                .isInstanceOf(StorageException.class);
    }

    @Test
    @DisplayName("SSRF validation blocks http and loopback when allowInsecureEndpoint=false or prod profile active")
    void ssrfBlocksHttpAndLoopbackWhenStrict() {
        HttpPresignedObjectClient strictClient = new HttpPresignedObjectClient(props(false, true));
        assertThatThrownBy(() -> strictClient.verifyObjectExists("http://127.0.0.1:" + port + "/b/k"))
                .isInstanceOf(StorageException.class);
        assertThatThrownBy(() -> strictClient.verifyObjectExists("https://localhost:9000/b/k"))
                .isInstanceOf(StorageException.class);

        MockEnvironment prodEnv = new MockEnvironment();
        prodEnv.setActiveProfiles("prod");
        HttpPresignedObjectClient prodClient = new HttpPresignedObjectClient(props(true, true), prodEnv);
        assertThatThrownBy(() -> prodClient.verifyObjectExists("http://127.0.0.1:" + port + "/b/k"))
                .isInstanceOf(StorageException.class);
        assertThatThrownBy(() -> prodClient.uploadObject("https://localhost:9000/b/k", new byte[]{1}, "application/pdf"))
                .isInstanceOf(StorageException.class);
    }

    @Test
    @DisplayName("AttachmentService.confirmUpload rejects missing S3 object with 409 STORAGE_OBJECT_MISSING and executes HTTP HEAD outside DB transaction")
    void confirmUploadRejectsMissingAndPassesPresentOutsideTx() {
        S3StorageProperties properties = props(true, true);
        HttpPresignedObjectClient client = new HttpPresignedObjectClient(properties);

        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        TransactionTemplate txTemplate = new TransactionTemplate(txManager);

        AttachmentService service = new AttachmentService(
                attachmentRepository, accessPolicy, storageProvider, jdbcTemplate,
                client, properties, txTemplate
        );

        UUID workspaceId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        SessionService.Principal principal = new SessionService.Principal(UUID.randomUUID(), UUID.randomUUID(), "PM", "USER");

        Attachment attachment = new Attachment();
        attachment.setId(attachmentId);
        attachment.setWorkspaceId(workspaceId);
        attachment.setProjectId(projectId);
        attachment.setStorageKey("workspaces/w/projects/p/attachments/a/spec.pdf");
        attachment.setScanStatus(Attachment.ScanStatus.PENDING);
        attachment.setVisibility(Attachment.Visibility.INTERNAL);

        when(storageProvider.mode()).thenReturn("aws");
        when(attachmentRepository.findByIdAndWorkspaceIdAndProjectId(attachmentId, workspaceId, projectId))
                .thenReturn(Optional.of(attachment));
        String presignedHeadUrl = "http://127.0.0.1:" + port + "/devsphere-attachments/" + attachment.getStorageKey() + "?X-Amz-Expires=300";
        when(storageProvider.generatePresignedHeadUrl(attachment.getStorageKey(), 300L))
                .thenReturn(presignedHeadUrl);

        // 1. Missing object -> 409 STORAGE_OBJECT_MISSING, never saved as CLEAN
        assertThatThrownBy(() -> service.confirmUpload(principal, workspaceId, projectId, attachmentId, Attachment.Visibility.CLIENT_SHARED))
                .isInstanceOf(StorageException.class)
                .satisfies(ex -> {
                    StorageException se = (StorageException) ex;
                    assertThat(se.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(se.getCode()).isEqualTo("STORAGE_OBJECT_MISSING");
                });
        verify(storageProvider, times(1)).generatePresignedHeadUrl(attachment.getStorageKey(), 300L);
        verify(storageProvider, never()).generatePresignedDownloadUrl(anyString(), anyLong());
        verify(attachmentRepository, never()).save(any());
        assertThat(attachment.getScanStatus()).isEqualTo(Attachment.ScanStatus.PENDING);
        verify(txManager, times(1)).getTransaction(any());
        verify(txManager, times(1)).commit(any());

        // 2. Put object in S3 mock -> confirm succeeds, split read tx + write tx committed
        store.put("/devsphere-attachments/" + attachment.getStorageKey(), "pdf-bytes".getBytes(StandardCharsets.UTF_8));
        when(attachmentRepository.save(any(Attachment.class))).thenAnswer(i -> i.getArgument(0));

        Attachment confirmed = service.confirmUpload(principal, workspaceId, projectId, attachmentId, Attachment.Visibility.CLIENT_SHARED);
        assertThat(confirmed.getScanStatus()).isEqualTo(Attachment.ScanStatus.CLEAN);
        assertThat(confirmed.getVisibility()).isEqualTo(Attachment.Visibility.CLIENT_SHARED);
        verify(storageProvider, times(2)).generatePresignedHeadUrl(attachment.getStorageKey(), 300L);
        verify(storageProvider, never()).generatePresignedDownloadUrl(anyString(), anyLong());
        verify(txManager, times(3)).getTransaction(any());
        verify(txManager, times(3)).commit(any());
    }
}

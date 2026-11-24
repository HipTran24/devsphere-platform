package com.project.devsphere.storage.infrastructure;

import com.project.devsphere.storage.application.StorageException;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class S3StorageProviderTest {

    private static final Instant NOW = Instant.parse("2026-11-23T12:00:00Z");
    private final S3StorageProvider provider = new S3StorageProvider(
            "https://storage.devsphere.local/", "attachments", "unit-test-signing-secret", Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void uploadUrlKeepsQueryStructureAndSignsWithHmacSha256() {
        String url = provider.generatePresignedUploadUrl("workspaces/w/projects/p/attachments/a/spec file.pdf", "Application/PDF", 300);

        URI uri = URI.create(url);
        assertThat(uri.getScheme() + "://" + uri.getHost()).isEqualTo("https://storage.devsphere.local");
        assertThat(uri.getRawPath()).isEqualTo("/attachments/workspaces%2Fw%2Fprojects%2Fp%2Fattachments%2Fa%2Fspec%20file.pdf");
        Map<String, String> query = query(uri);
        assertThat(query).containsOnlyKeys("action", "contentType", "expires", "sig");
        assertThat(query).containsEntry("action", "upload").containsEntry("contentType", "application%2Fpdf")
                .containsEntry("expires", String.valueOf(NOW.getEpochSecond() + 300));
        assertThat(query.get("sig")).matches("^[0-9a-f]{64}$");
        assertThat(provider.verifySignature("PUT", "workspaces/w/projects/p/attachments/a/spec file.pdf",
                NOW.getEpochSecond() + 300, "application/pdf", query.get("sig"))).isTrue();
        assertThat(provider.verifySignature("PUT", "workspaces/w/projects/p/attachments/a/other.pdf",
                NOW.getEpochSecond() + 300, "application/pdf", query.get("sig"))).isFalse();
        assertThat(provider.verifySignature("PUT", "workspaces/w/projects/p/attachments/a/spec file.pdf",
                NOW.getEpochSecond() + 300, "text/html", query.get("sig"))).isFalse();
    }

    @Test
    void downloadUrlUsesDistinctSignature() {
        String download = provider.generatePresignedDownloadUrl("keys/report.pdf", 60);
        Map<String, String> query = query(URI.create(download));
        assertThat(query).containsOnlyKeys("action", "expires", "sig").containsEntry("action", "download");
        assertThat(provider.verifySignature("GET", "keys/report.pdf", NOW.getEpochSecond() + 60, null, query.get("sig"))).isTrue();
        assertThat(provider.verifySignature("PUT", "keys/report.pdf", NOW.getEpochSecond() + 60, null, query.get("sig"))).isFalse();

        String head = provider.generatePresignedHeadUrl("keys/report.pdf", 60);
        Map<String, String> headQuery = query(URI.create(head));
        assertThat(headQuery).containsOnlyKeys("action", "expires", "sig").containsEntry("action", "head");
        assertThat(provider.verifySignature("HEAD", "keys/report.pdf", NOW.getEpochSecond() + 60, null, headQuery.get("sig"))).isTrue();
        assertThat(provider.verifySignature("GET", "keys/report.pdf", NOW.getEpochSecond() + 60, null, headQuery.get("sig"))).isFalse();
        assertThat(headQuery.get("sig")).isNotEqualTo(query.get("sig"));
    }

    @Test
    void signaturesDependOnTheSecret() {
        S3StorageProvider other = new S3StorageProvider("https://storage.devsphere.local", "attachments", "another-secret",
                Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(query(URI.create(other.generatePresignedDownloadUrl("k/a.pdf", 60))).get("sig"))
                .isNotEqualTo(query(URI.create(provider.generatePresignedDownloadUrl("k/a.pdf", 60))).get("sig"));
    }

    @Test
    void rejectsInvalidKeysAndLifetimes() {
        assertThatThrownBy(() -> provider.generatePresignedDownloadUrl("../secret", 60)).isInstanceOf(StorageException.class);
        assertThatThrownBy(() -> provider.generatePresignedDownloadUrl("k/a.pdf", 301)).isInstanceOf(StorageException.class);
        assertThatThrownBy(() -> provider.generatePresignedUploadUrl("k/a.pdf", "text/html\r\nX: y", 60)).isInstanceOf(StorageException.class);
        assertThat(provider.mode()).isEqualTo("local");
        assertThat(provider.bucket()).isEqualTo("attachments");
    }

    private static Map<String, String> query(URI uri) {
        return Arrays.stream(uri.getRawQuery().split("&"))
                .map(pair -> pair.split("=", 2))
                .collect(Collectors.toMap(pair -> pair[0], pair -> pair[1]));
    }
}

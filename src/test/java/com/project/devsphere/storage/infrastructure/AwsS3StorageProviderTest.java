package com.project.devsphere.storage.infrastructure;

import com.project.devsphere.storage.application.StorageException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AwsS3StorageProviderTest {

    private static final StaticCredentialsProvider CREDENTIALS = StaticCredentialsProvider.create(
            AwsBasicCredentials.create("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"));

    private static S3StorageProperties properties(String endpoint, boolean allowInsecure) {
        S3StorageProperties properties = new S3StorageProperties();
        properties.setMode("aws");
        properties.setBucket("devsphere-attachments");
        properties.setRegion("ap-southeast-1");
        properties.setEndpoint(endpoint);
        properties.setPathStyleAccess(endpoint != null);
        properties.setAllowInsecureEndpoint(allowInsecure);
        return properties;
    }

    private static AwsS3StorageProvider provider(String endpoint, boolean allowInsecure) {
        return new AwsS3StorageProvider(properties(endpoint, allowInsecure), CREDENTIALS);
    }

    @Test
    void uploadUrlIsSigV4PresignedWithLockedContentTypeAndBoundedExpiry() {
        AwsS3StorageProvider provider = provider(null, false);
        try {
            URI url = URI.create(provider.generatePresignedUploadUrl("workspaces/w/projects/p/attachments/a/spec.pdf", "application/pdf", 300));
            Map<String, String> query = query(url);

            assertThat(url.getScheme()).isEqualTo("https");
            assertThat(url.getHost()).isEqualTo("devsphere-attachments.s3.ap-southeast-1.amazonaws.com");
            assertThat(url.getPath()).isEqualTo("/workspaces/w/projects/p/attachments/a/spec.pdf");
            assertThat(query).containsEntry("X-Amz-Algorithm", "AWS4-HMAC-SHA256");
            assertThat(Integer.parseInt(query.get("X-Amz-Expires"))).isPositive().isLessThanOrEqualTo(300);
            assertThat(query.get("X-Amz-Credential")).startsWith("AKIAIOSFODNN7EXAMPLE/").endsWith("/ap-southeast-1/s3/aws4_request");
            assertThat(query.get("X-Amz-SignedHeaders")).contains("content-type").contains("host");
            assertThat(query.get("X-Amz-Signature")).matches("^[0-9a-f]{64}$");
            assertThat(query).doesNotContainKey("x-amz-acl");
            assertThat(url.toString()).doesNotContain("wJalrXUtnFEMI");
        } finally {
            provider.close();
        }
    }

    @Test
    void downloadUrlIsPresignedGetWithinTtl() {
        AwsS3StorageProvider provider = provider(null, false);
        try {
            Map<String, String> query = query(URI.create(provider.generatePresignedDownloadUrl("keys/report.pdf", 60)));
            assertThat(query).containsEntry("X-Amz-Algorithm", "AWS4-HMAC-SHA256").containsEntry("X-Amz-Expires", "60");
            assertThat(query.get("X-Amz-SignedHeaders")).isEqualTo("host");
            assertThat(provider.mode()).isEqualTo("aws");
            assertThat(provider.bucket()).isEqualTo("devsphere-attachments");
        } finally {
            provider.close();
        }
    }

    @Test
    void rejectsLifetimesAboveThreeHundredSecondsAndUnsafeKeys() {
        AwsS3StorageProvider provider = provider(null, false);
        try {
            assertThatThrownBy(() -> provider.generatePresignedUploadUrl("k/a.pdf", "application/pdf", 301)).isInstanceOf(StorageException.class);
            assertThatThrownBy(() -> provider.generatePresignedDownloadUrl("k/a.pdf", 0)).isInstanceOf(StorageException.class);
            assertThatThrownBy(() -> provider.generatePresignedDownloadUrl("../../other-tenant/x", 60)).isInstanceOf(StorageException.class);
            assertThatThrownBy(() -> provider.generatePresignedUploadUrl("k/a.pdf", "text/html; charset=utf-8", 60)).isInstanceOf(StorageException.class);
        } finally {
            provider.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://169.254.169.254", "http://169.254.169.254/latest/meta-data", "https://[fd00:ec2::254]",
            "https://[::ffff:169.254.169.254]", "https://169.254.170.2", "https://metadata.google.internal",
            "https://instance-data", "https://169.254.10.20", "https://[fe80::1]", "https://0.0.0.0"})
    void rejectsMetadataAndLinkLocalEndpointsEvenInTestMode(String endpoint) {
        assertThatThrownBy(() -> AwsS3StorageProvider.validateEndpoint(endpoint, true)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> provider(endpoint, true)).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://s3.example.test", "https://127.0.0.1:9000", "https://localhost:9000", "https://[::1]:9000",
            "https://user:pass@s3.example.test", "https://s3.example.test?x=1", "ftp://s3.example.test", "not a uri"})
    void rejectsInsecureLoopbackOrCredentialBearingEndpointsInProduction(String endpoint) {
        assertThatThrownBy(() -> AwsS3StorageProvider.validateEndpoint(endpoint, false)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void customHttpsEndpointUsesPathStyleAndTestModeAllowsLoopback() {
        AwsS3StorageProvider provider = provider("https://s3.storage.example.com", false);
        try {
            URI url = URI.create(provider.generatePresignedDownloadUrl("keys/a.pdf", 30));
            assertThat(url.getHost()).isEqualTo("s3.storage.example.com");
            assertThat(url.getPath()).isEqualTo("/devsphere-attachments/keys/a.pdf");
        } finally {
            provider.close();
        }
        assertThat(AwsS3StorageProvider.validateEndpoint("http://127.0.0.1:9000", true).getPort()).isEqualTo(9000);
        assertThat(AwsS3StorageProvider.validateEndpoint("http://localhost:9000", true).getHost()).isEqualTo("localhost");
    }

    @Test
    void validatesBucketAndRegion() {
        S3StorageProperties badBucket = properties(null, false);
        badBucket.setBucket("Bad_Bucket");
        assertThatThrownBy(() -> new AwsS3StorageProvider(badBucket, CREDENTIALS)).isInstanceOf(IllegalStateException.class);
        S3StorageProperties badRegion = properties(null, false);
        badRegion.setRegion("mars-1");
        assertThatThrownBy(() -> new AwsS3StorageProvider(badRegion, CREDENTIALS)).isInstanceOf(IllegalStateException.class);
    }

    private static Map<String, String> query(URI uri) {
        return Arrays.stream(uri.getRawQuery().split("&"))
                .map(pair -> pair.split("=", 2))
                .collect(Collectors.toMap(pair -> pair[0], pair -> URLDecoder.decode(pair[1], StandardCharsets.UTF_8)));
    }
}

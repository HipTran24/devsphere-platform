package com.project.devsphere.storage.infrastructure;

import com.project.devsphere.storage.application.StorageProvider;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Production storage adapter issuing AWS Signature V4 presigned URLs with the AWS SDK v2
 * {@link S3Presigner}. Active when {@code devsphere.storage.s3.mode=aws}.
 *
 * <ul>
 *   <li>Objects stay private: no ACL is ever requested, access is only via short-lived URLs.</li>
 *   <li>Upload URLs sign the {@code Content-Type} header, so clients cannot change the media type.</li>
 *   <li>URL lifetime is capped at {@value StorageKeyValidator#MAX_TTL_SECONDS} seconds.</li>
 *   <li>Endpoint overrides must be https and may not point at cloud metadata, link-local, loopback
 *       or wildcard addresses (only {@code allow-insecure-endpoint} test mode relaxes https and
 *       loopback; metadata addresses are always rejected).</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "devsphere.storage.s3.mode", havingValue = "aws")
public class AwsS3StorageProvider implements StorageProvider {

    private static final Pattern BUCKET_NAME = Pattern.compile("^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$");
    private static final Pattern REGION_NAME = Pattern.compile("^[a-z]{2}(-[a-z]+)+-\\d$");
    private static final Pattern IPV4_LITERAL = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");
    private static final Set<String> BLOCKED_HOSTS = Set.of(
            "localhost", "metadata", "metadata.google.internal", "instance-data", "instance-data.ec2.internal",
            "169.254.169.254", "169.254.170.2", "fd00:ec2::254", "100.100.100.200");

    private final S3Presigner presigner;
    private final String bucket;

    @Autowired
    public AwsS3StorageProvider(S3StorageProperties properties) {
        this(properties, DefaultCredentialsProvider.builder().build());
    }

    AwsS3StorageProvider(S3StorageProperties properties, AwsCredentialsProvider credentialsProvider) {
        this.bucket = requireBucket(properties.getBucket());
        Region region = Region.of(requireRegion(properties.getRegion()));
        S3Presigner.Builder builder = S3Presigner.builder()
                .region(region)
                .credentialsProvider(credentialsProvider)
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(properties.isPathStyleAccess())
                        .build());
        if (properties.getEndpoint() != null && !properties.getEndpoint().isBlank()) {
            builder.endpointOverride(validateEndpoint(properties.getEndpoint().strip(), properties.isAllowInsecureEndpoint()));
        }
        this.presigner = builder.build();
    }

    @Override
    public String generatePresignedUploadUrl(String storageKey, String contentType, long expiresInSeconds) {
        String key = StorageKeyValidator.requireValidKey(storageKey);
        long ttl = StorageKeyValidator.requireValidTtl(expiresInSeconds);
        String type = StorageKeyValidator.requireValidContentType(contentType).toLowerCase(Locale.ROOT);
        PutObjectRequest put = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(type)
                .build();
        return presigner.presignPutObject(PutObjectPresignRequest.builder()
                        .signatureDuration(Duration.ofSeconds(ttl))
                        .putObjectRequest(put)
                        .build())
                .url().toString();
    }

    @Override
    public String generatePresignedDownloadUrl(String storageKey, long expiresInSeconds) {
        String key = StorageKeyValidator.requireValidKey(storageKey);
        long ttl = StorageKeyValidator.requireValidTtl(expiresInSeconds);
        GetObjectRequest get = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();
        return presigner.presignGetObject(GetObjectPresignRequest.builder()
                        .signatureDuration(Duration.ofSeconds(ttl))
                        .getObjectRequest(get)
                        .build())
                .url().toString();
    }

    @Override
    public String mode() {
        return "aws";
    }

    @Override
    public String bucket() {
        return bucket;
    }

    @PreDestroy
    public void close() {
        presigner.close();
    }

    static String requireBucket(String bucket) {
        if (bucket == null || !BUCKET_NAME.matcher(bucket).matches() || bucket.contains("..")) {
            throw new IllegalStateException("devsphere.storage.s3.bucket must be a valid S3 bucket name");
        }
        return bucket;
    }

    static String requireRegion(String region) {
        if (region == null || !REGION_NAME.matcher(region).matches()) {
            throw new IllegalStateException("devsphere.storage.s3.region must be an AWS region such as eu-west-1");
        }
        return region;
    }

    /** Validates an endpoint override against SSRF-prone destinations. */
    static URI validateEndpoint(String endpoint, boolean allowInsecure) {
        URI uri;
        try {
            uri = new URI(endpoint);
        } catch (URISyntaxException ex) {
            throw new IllegalStateException("devsphere.storage.s3.endpoint is not a valid URI");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !(allowInsecure && scheme.equals("http"))) {
            throw new IllegalStateException("devsphere.storage.s3.endpoint must use https");
        }
        if (uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalStateException("devsphere.storage.s3.endpoint must not carry credentials, query or fragment");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalStateException("devsphere.storage.s3.endpoint must include a host");
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        if (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (BLOCKED_HOSTS.contains(normalized)) {
            if (allowInsecure && normalized.equals("localhost")) {
                return uri;
            }
            throw new IllegalStateException("devsphere.storage.s3.endpoint points at a forbidden host");
        }
        if (IPV4_LITERAL.matcher(normalized).matches() || normalized.contains(":")) {
            InetAddress address;
            try {
                // Literal addresses only: InetAddress parses them without DNS resolution.
                address = InetAddress.getByName(normalized);
            } catch (UnknownHostException ex) {
                throw new IllegalStateException("devsphere.storage.s3.endpoint has an invalid IP address");
            }
            if (isMetadataAddress(address)) {
                throw new IllegalStateException("devsphere.storage.s3.endpoint points at a cloud metadata address");
            }
            boolean local = address.isLoopbackAddress() || address.isAnyLocalAddress()
                    || address.isLinkLocalAddress() || address.isMulticastAddress();
            if (address.isLinkLocalAddress() || address.isAnyLocalAddress() || address.isMulticastAddress()
                    || (local && !allowInsecure)) {
                throw new IllegalStateException("devsphere.storage.s3.endpoint points at a link-local or loopback address");
            }
        }
        return uri;
    }

    private static boolean isMetadataAddress(InetAddress address) {
        String text = address.getHostAddress().toLowerCase(Locale.ROOT);
        int scope = text.indexOf('%');
        if (scope >= 0) {
            text = text.substring(0, scope);
        }
        return text.equals("169.254.169.254") || text.equals("169.254.170.2") || text.equals("100.100.100.200")
                || text.equals("fd00:ec2:0:0:0:0:0:254") || text.equals("0:0:0:0:0:ffff:a9fe:a9fe");
    }
}

package com.project.devsphere.storage.infrastructure;

import com.project.devsphere.storage.application.StorageProvider;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4FamilyHttpSigner;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;
import software.amazon.awssdk.http.auth.spi.signer.HttpSigner;
import software.amazon.awssdk.http.auth.spi.signer.SignRequest;
import software.amazon.awssdk.http.auth.spi.signer.SignedRequest;
import software.amazon.awssdk.identity.spi.AwsCredentialsIdentity;
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
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Production storage adapter issuing AWS Signature V4 presigned URLs with the AWS SDK v2
 * {@link S3Presigner} and {@link AwsV4HttpSigner}. Active when {@code devsphere.storage.s3.mode=aws}.
 *
 * <ul>
 *   <li>Objects stay private: no ACL is ever requested, access is only via short-lived URLs.</li>
 *   <li>Upload URLs sign the {@code Content-Type} header, so clients cannot change the media type.</li>
 *   <li>URL lifetime is capped at {@value StorageKeyValidator#MAX_TTL_SECONDS} seconds.</li>
 *   <li>Endpoint overrides must be https and may not point at cloud metadata, link-local, loopback
 *       or wildcard addresses (only {@code allow-insecure-endpoint} test mode outside {@code prod}
 *       relaxes https and loopback; metadata addresses are always rejected).</li>
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
    private final AwsCredentialsProvider credentialsProvider;
    private final String bucket;
    private final Region region;
    private final boolean pathStyleAccess;
    private final URI endpointOverride;
    private final Clock clock;

    public AwsS3StorageProvider(S3StorageProperties properties) {
        this(properties, DefaultCredentialsProvider.builder().build(), null, Clock.systemUTC());
    }

    @Autowired
    public AwsS3StorageProvider(
            S3StorageProperties properties,
            @Autowired(required = false) Environment environment) {
        this(properties, DefaultCredentialsProvider.builder().build(), environment, Clock.systemUTC());
    }

    AwsS3StorageProvider(S3StorageProperties properties, AwsCredentialsProvider credentialsProvider) {
        this(properties, credentialsProvider, null, Clock.systemUTC());
    }

    AwsS3StorageProvider(
            S3StorageProperties properties,
            AwsCredentialsProvider credentialsProvider,
            Environment environment) {
        this(properties, credentialsProvider, environment, Clock.systemUTC());
    }

    AwsS3StorageProvider(
            S3StorageProperties properties,
            AwsCredentialsProvider credentialsProvider,
            Environment environment,
            Clock clock) {
        Objects.requireNonNull(properties, "properties must not be null");
        this.credentialsProvider = Objects.requireNonNull(credentialsProvider, "credentialsProvider must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.bucket = requireBucket(properties.getBucket());
        this.region = Region.of(requireRegion(properties.getRegion()));
        this.pathStyleAccess = properties.isPathStyleAccess();
        boolean effectiveAllowInsecure = properties.isAllowInsecureEndpoint() && !isProdProfile(environment);

        S3Presigner.Builder builder = S3Presigner.builder()
                .region(region)
                .credentialsProvider(credentialsProvider)
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(pathStyleAccess)
                        .build());
        if (properties.getEndpoint() != null && !properties.getEndpoint().isBlank()) {
            this.endpointOverride = validateEndpoint(properties.getEndpoint().strip(), effectiveAllowInsecure);
            builder.endpointOverride(this.endpointOverride);
        } else {
            this.endpointOverride = null;
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
    public String generatePresignedHeadUrl(String storageKey, long expiresInSeconds) {
        String key = StorageKeyValidator.requireValidKey(storageKey);
        long ttl = StorageKeyValidator.requireValidTtl(expiresInSeconds);
        URI objectUri = buildObjectUri(key);
        String hostHeader = objectUri.getPort() > 0
                ? objectUri.getHost() + ":" + objectUri.getPort()
                : objectUri.getHost();
        SdkHttpFullRequest requestToSign = SdkHttpFullRequest.builder()
                .method(SdkHttpMethod.HEAD)
                .uri(objectUri)
                .putHeader("Host", hostHeader)
                .build();

        AwsCredentialsIdentity identity = credentialsProvider.resolveCredentials();
        SignedRequest signed = AwsV4HttpSigner.create().sign(SignRequest.builder(identity)
                .request(requestToSign)
                .putProperty(AwsV4FamilyHttpSigner.AUTH_LOCATION, AwsV4FamilyHttpSigner.AuthLocation.QUERY_STRING)
                .putProperty(AwsV4FamilyHttpSigner.SERVICE_SIGNING_NAME, "s3")
                .putProperty(AwsV4HttpSigner.REGION_NAME, region.id())
                .putProperty(AwsV4FamilyHttpSigner.EXPIRATION_DURATION, Duration.ofSeconds(ttl))
                .putProperty(AwsV4FamilyHttpSigner.DOUBLE_URL_ENCODE, false)
                .putProperty(AwsV4FamilyHttpSigner.NORMALIZE_PATH, false)
                .putProperty(AwsV4FamilyHttpSigner.PAYLOAD_SIGNING_ENABLED, false)
                .putProperty(HttpSigner.SIGNING_CLOCK, clock)
                .build());
        return signed.request().getUri().toString();
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

    private URI buildObjectUri(String key) {
        String encodedKey = encodeKeySegments(key);
        String scheme;
        String host;
        String portPart = "";
        String basePath = "";

        if (endpointOverride != null) {
            scheme = endpointOverride.getScheme().toLowerCase(Locale.ROOT);
            String rawHost = endpointOverride.getHost();
            host = pathStyleAccess ? rawHost : bucket + "." + rawHost;
            if (endpointOverride.getPort() > 0) {
                portPart = ":" + endpointOverride.getPort();
            }
            String rawPath = endpointOverride.getRawPath();
            if (rawPath != null && !rawPath.isEmpty() && !"/".equals(rawPath)) {
                basePath = rawPath.endsWith("/") ? rawPath.substring(0, rawPath.length() - 1) : rawPath;
            }
        } else {
            scheme = "https";
            host = pathStyleAccess
                    ? "s3." + region.id() + ".amazonaws.com"
                    : bucket + ".s3." + region.id() + ".amazonaws.com";
        }

        String path = pathStyleAccess
                ? basePath + "/" + bucket + "/" + encodedKey
                : basePath + "/" + encodedKey;
        return URI.create(scheme + "://" + host + portPart + path);
    }

    private static String encodeKeySegments(String key) {
        String[] segments = key.split("/", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(encodePathSegment(segments[i]));
        }
        return sb.toString();
    }

    private static String encodePathSegment(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~");
    }

    private static boolean isProdProfile(Environment environment) {
        return environment != null && environment.acceptsProfiles(Profiles.of("prod"));
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

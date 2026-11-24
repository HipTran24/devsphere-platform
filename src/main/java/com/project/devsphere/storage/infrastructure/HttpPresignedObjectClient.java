package com.project.devsphere.storage.infrastructure;

import com.project.devsphere.storage.application.StorageException;
import com.project.devsphere.storage.application.StorageObjectClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * {@link StorageObjectClient} backed by {@link java.net.http.HttpClient} with SSRF validation on
 * presigned URLs. Never logs or exposes raw presigned query signatures.
 */
@Component
public class HttpPresignedObjectClient implements StorageObjectClient {

    private static final long DEFAULT_TIMEOUT_MS = 10_000L;
    private static final Pattern IPV4_LITERAL = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");
    private static final Pattern NON_CANONICAL_NUMERIC_HOST = Pattern.compile(
            "^(?:\\d+|0x[0-9a-f]+|(?:0[0-7]+|0x[0-9a-f]+|\\d+)(?:\\.(?:0[0-7]+|0x[0-9a-f]+|\\d+)){1,3})$"
    );
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

    private final HttpClient httpClient;
    private final Duration requestTimeout;
    private final boolean allowInsecureEndpoint;

    public HttpPresignedObjectClient(S3StorageProperties properties) {
        this(properties, null, null);
    }

    @Autowired
    public HttpPresignedObjectClient(
            S3StorageProperties properties,
            @Autowired(required = false) Environment environment) {
        this(properties, null, environment);
    }

    HttpPresignedObjectClient(S3StorageProperties properties, HttpClient httpClient) {
        this(properties, httpClient, null);
    }

    HttpPresignedObjectClient(
            S3StorageProperties properties,
            HttpClient httpClient,
            Environment environment) {
        Objects.requireNonNull(properties, "properties must not be null");
        long timeoutMs = properties.getHttpTimeoutMs() > 0 ? properties.getHttpTimeoutMs() : DEFAULT_TIMEOUT_MS;
        this.requestTimeout = Duration.ofMillis(timeoutMs);
        this.allowInsecureEndpoint = properties.isAllowInsecureEndpoint() && !isProdProfile(environment);
        this.httpClient = httpClient != null
                ? httpClient
                : HttpClient.newBuilder()
                        .connectTimeout(this.requestTimeout)
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
    }

    private static boolean isProdProfile(Environment environment) {
        return environment != null && environment.acceptsProfiles(Profiles.of("prod"));
    }

    @Override
    public boolean verifyObjectExists(String presignedUrl) {
        URI uri = validatePresignedUrl(presignedUrl, allowInsecureEndpoint);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .timeout(requestTimeout)
                .build();
        try {
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return true;
            }
            if (status == 404) {
                return false;
            }
            throw StorageException.storageUnavailable(
                    "Storage object verification failed for " + safeLocation(uri) + " with HTTP status " + status);
        } catch (StorageException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw StorageException.storageUnavailable(
                    "Storage object verification interrupted for " + safeLocation(uri));
        } catch (IOException ex) {
            throw StorageException.storageUnavailable(
                    "Storage object verification failed for " + safeLocation(uri) + ": " + ex.getClass().getSimpleName());
        }
    }

    @Override
    public void uploadObject(String presignedPutUrl, byte[] content, String contentType) {
        URI uri = validatePresignedUrl(presignedPutUrl, allowInsecureEndpoint);
        if (content == null) {
            throw StorageException.validationFailed("Upload content must not be null");
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(content))
                .timeout(requestTimeout);
        if (contentType != null && !contentType.isBlank()) {
            builder.header("Content-Type", StorageKeyValidator.requireValidContentType(contentType.strip()));
        }
        try {
            HttpResponse<Void> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw StorageException.storageUnavailable(
                        "Storage object upload failed for " + safeLocation(uri) + " with HTTP status " + status);
            }
        } catch (StorageException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw StorageException.storageUnavailable(
                    "Storage object upload interrupted for " + safeLocation(uri));
        } catch (IOException ex) {
            throw StorageException.storageUnavailable(
                    "Storage object upload failed for " + safeLocation(uri) + ": " + ex.getClass().getSimpleName());
        }
    }

    /** Validates a presigned URL against SSRF targets while preserving signed query parameters. */
    static URI validatePresignedUrl(String rawUrl, boolean allowInsecureEndpoint) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw StorageException.validationFailed("Presigned storage URL must not be blank");
        }
        URI uri;
        try {
            uri = new URI(rawUrl.strip());
        } catch (URISyntaxException ex) {
            throw StorageException.validationFailed("Presigned storage URL is not a valid URI");
        }

        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"https".equals(scheme) && !(allowInsecureEndpoint && "http".equals(scheme))) {
            throw StorageException.validationFailed("Presigned storage URL must use https");
        }
        if (uri.getRawUserInfo() != null || uri.getRawFragment() != null) {
            throw StorageException.validationFailed("Presigned storage URL must not carry userinfo or fragment");
        }
        int port = uri.getPort();
        if (port != -1 && (port <= 0 || port > 65535)) {
            throw StorageException.validationFailed("Presigned storage URL port is out of range");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw StorageException.validationFailed("Presigned storage URL must include a host");
        }

        String normalized = normalizeHost(host);
        if (normalized.isEmpty() || normalized.contains("%") || hasWhitespaceOrControl(normalized)) {
            throw StorageException.validationFailed("Presigned storage URL contains an invalid host");
        }
        if (BLOCKED_METADATA_HOSTS.contains(normalized)) {
            throw StorageException.validationFailed("Presigned storage URL targets a forbidden metadata host");
        }
        if (("localhost".equals(normalized) || normalized.endsWith(".localhost")) && !allowInsecureEndpoint) {
            throw StorageException.validationFailed("Presigned storage URL loopback host is not permitted");
        }

        boolean isIpv4Literal = IPV4_LITERAL.matcher(normalized).matches();
        boolean isIpv6Literal = normalized.contains(":");
        if (!isIpv4Literal && !isIpv6Literal && NON_CANONICAL_NUMERIC_HOST.matcher(normalized).matches()) {
            throw StorageException.validationFailed("Presigned storage URL uses a non-canonical numeric host");
        }
        if (isIpv4Literal && hasLeadingZeroOctet(normalized)) {
            throw StorageException.validationFailed("Presigned storage URL IPv4 octets must not contain leading zeros");
        }

        if (isIpv4Literal || isIpv6Literal) {
            try {
                validateAddress(InetAddress.getByName(normalized), allowInsecureEndpoint);
            } catch (UnknownHostException ex) {
                throw StorageException.validationFailed("Presigned storage URL contains an invalid IP address");
            }
        } else {
            try {
                for (InetAddress resolved : InetAddress.getAllByName(normalized)) {
                    validateAddress(resolved, allowInsecureEndpoint);
                }
            } catch (UnknownHostException ignored) {
                // Defer unreachable host errors to HTTP transport -> 503 STORAGE_UNAVAILABLE.
            }
        }
        return uri;
    }

    private static void validateAddress(InetAddress address, boolean allowInsecureEndpoint) {
        if (isMetadataAddress(address)) {
            throw StorageException.validationFailed("Presigned storage URL targets a forbidden cloud metadata address");
        }
        if (address.isLinkLocalAddress() || address.isMulticastAddress() || address.isAnyLocalAddress()) {
            throw StorageException.validationFailed("Presigned storage URL targets a forbidden link-local, multicast, or unspecified address");
        }
        if (address.isLoopbackAddress() && !allowInsecureEndpoint) {
            throw StorageException.validationFailed("Presigned storage URL loopback address is not permitted");
        }
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

    private static String normalizeHost(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
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
        for (String part : ipv4.split("\\.", -1)) {
            if (part.length() > 1 && part.startsWith("0")) {
                return true;
            }
        }
        return false;
    }

    /** Omits query string so signed URL credentials/signatures are never included in logs or errors. */
    private static String safeLocation(URI uri) {
        String portPart = uri.getPort() > 0 ? ":" + uri.getPort() : "";
        String path = uri.getRawPath() != null ? uri.getRawPath() : "";
        return uri.getScheme() + "://" + uri.getHost() + portPart + path;
    }
}

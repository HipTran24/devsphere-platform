package com.project.devsphere.ai.application;

import com.project.devsphere.ai.domain.AiProviderType;
import com.project.devsphere.ai.infrastructure.AiProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * SSRF guard for AI provider base URLs mirroring {@code AwsS3StorageProvider} validation rigor.
 *
 * <ul>
 *   <li>Rejects userinfo, query parameters, and fragments.</li>
 *   <li>Always rejects cloud metadata hosts/IPs, link-local, multicast, and unspecified addresses.</li>
 *   <li>{@code OPENAI} requires {@code https} and rejects loopback, localhost, and private literals.</li>
 *   <li>{@code VLLM_SELF_HOST} allows {@code https} always and permits plain {@code http} / loopback
 *       only for loopback hosts when {@code devsphere.ai.allow-loopback-self-host=true} outside {@code prod}.</li>
 * </ul>
 */
@Component
public class AiEndpointValidator {

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

    private final boolean allowLoopbackSelfHost;

    public AiEndpointValidator(AiProperties aiProperties) {
        this(aiProperties, null);
    }

    @Autowired
    public AiEndpointValidator(AiProperties aiProperties, Environment environment) {
        this(Objects.requireNonNull(aiProperties, "aiProperties must not be null").isAllowLoopbackSelfHost()
                && !isProdProfile(environment));
    }

    public AiEndpointValidator(boolean allowLoopbackSelfHost) {
        this.allowLoopbackSelfHost = allowLoopbackSelfHost;
    }

    public boolean isLoopbackSelfHostAllowed() {
        return allowLoopbackSelfHost;
    }

    public URI validate(String rawBaseUrl, AiProviderType provider) {
        return validate(rawBaseUrl, provider, allowLoopbackSelfHost);
    }

    public static URI validate(String rawBaseUrl, AiProviderType provider, boolean allowLoopbackSelfHost) {
        if (provider == null) {
            throw AiException.invalidInput("AI provider must not be null");
        }
        if (rawBaseUrl == null || rawBaseUrl.isBlank()) {
            throw AiException.invalidInput("baseUrl must not be blank");
        }

        String trimmed = rawBaseUrl.strip();
        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException ex) {
            throw AiException.invalidInput("baseUrl is not a valid URI");
        }

        if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw AiException.invalidInput("baseUrl must not contain userinfo, query, or fragment");
        }

        int port = uri.getPort();
        if (port != -1 && (port <= 0 || port > 65535)) {
            throw AiException.invalidInput("baseUrl port is out of range");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw AiException.invalidInput("baseUrl must include a valid host");
        }

        String normalized = normalizeHost(host);
        if (normalized.isEmpty() || normalized.contains("%") || hasWhitespaceOrControl(normalized)) {
            throw AiException.invalidInput("baseUrl contains an invalid host");
        }

        if (BLOCKED_METADATA_HOSTS.contains(normalized)) {
            throw AiException.invalidInput("baseUrl targets a forbidden metadata host");
        }

        boolean isIpv4Literal = IPV4_LITERAL.matcher(normalized).matches();
        boolean isIpv6Literal = normalized.contains(":");
        if (!isIpv4Literal && !isIpv6Literal && NON_CANONICAL_NUMERIC_HOST.matcher(normalized).matches()) {
            throw AiException.invalidInput("baseUrl uses a non-canonical numeric host");
        }

        if (isIpv4Literal && hasLeadingZeroOctet(normalized)) {
            throw AiException.invalidInput("baseUrl IPv4 octets must not contain leading zeros");
        }

        boolean loopbackHost = isLoopbackHostname(normalized);
        if (loopbackHost && (provider == AiProviderType.OPENAI || !allowLoopbackSelfHost)) {
            throw AiException.invalidInput("baseUrl loopback host is not permitted for " + provider);
        }

        if (isIpv4Literal || isIpv6Literal) {
            InetAddress address;
            try {
                address = InetAddress.getByName(normalized);
            } catch (UnknownHostException ex) {
                throw AiException.invalidInput("baseUrl contains an invalid IP address");
            }
            validateAddress(address, provider, allowLoopbackSelfHost);
            if (address.isLoopbackAddress()) {
                loopbackHost = true;
            }
        }

        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (provider == AiProviderType.OPENAI) {
            if (!"https".equals(scheme)) {
                throw AiException.invalidInput("OPENAI endpoint must use https");
            }
        } else {
            boolean allowedLoopbackHttp = allowLoopbackSelfHost && loopbackHost && "http".equals(scheme);
            if (!"https".equals(scheme) && !allowedLoopbackHttp) {
                throw AiException.invalidInput("VLLM_SELF_HOST endpoint must use https");
            }
        }

        return stripTrailingSlash(uri);
    }

    /**
     * Validates a parsed or DNS-resolved {@link InetAddress} against SSRF restrictions.
     */
    public static void validateAddress(InetAddress address, AiProviderType provider, boolean allowLoopbackSelfHost) {
        if (address == null) {
            throw AiException.invalidInput("Resolved address must not be null");
        }
        if (isMetadataAddress(address)) {
            throw AiException.invalidInput("baseUrl targets a forbidden cloud metadata address");
        }
        if (address.isLinkLocalAddress() || address.isMulticastAddress() || address.isAnyLocalAddress()) {
            throw AiException.invalidInput("baseUrl targets a forbidden link-local, multicast, or unspecified address");
        }
        if (address.isLoopbackAddress()) {
            if (provider == AiProviderType.OPENAI || !allowLoopbackSelfHost) {
                throw AiException.invalidInput("baseUrl loopback address is not permitted for " + provider);
            }
            return;
        }
        if (provider == AiProviderType.OPENAI) {
            if (address.isSiteLocalAddress() || isUniqueLocalIpv6(address) || isCarrierGradeNat(address)) {
                throw AiException.invalidInput("OPENAI endpoint must not target private or internal IP ranges");
            }
        }
    }

    public static boolean isIpLiteral(String host) {
        String normalized = normalizeHost(host);
        return IPV4_LITERAL.matcher(normalized).matches() || normalized.contains(":");
    }

    static String normalizeHost(String host) {
        String normalized = host == null ? "" : host.strip().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static boolean isProdProfile(Environment environment) {
        return environment != null && environment.acceptsProfiles(Profiles.of("prod"));
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

package com.project.devsphere.realtime.security;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Rejects WebSocket HTTP upgrade requests that carry bearer/access tokens in the
 * query string ({@code ?token=...} or {@code ?access_token=...}) so credentials
 * are never accepted via URLs or leaked into access logs.
 */
@Component
public class QueryParamRejectHandshakeInterceptor implements HandshakeInterceptor {

    public static final String QUERY_TOKEN_REJECTED_ATTR = "devsphere.realtime.queryTokenRejected";
    private static final Set<String> FORBIDDEN_QUERY_PARAMS = Set.of("token", "access_token");

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes) {

        URI uri = request.getURI();
        if (hasForbiddenTokenQueryParam(uri)) {
            if (attributes != null) {
                attributes.put(QUERY_TOKEN_REJECTED_ATTR, Boolean.TRUE);
            }
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        return true;
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Exception exception) {
        // No-op
    }

    static boolean hasForbiddenTokenQueryParam(URI uri) {
        if (uri == null) {
            return false;
        }
        String rawQuery = uri.getRawQuery();
        if (rawQuery == null || rawQuery.isBlank()) {
            return false;
        }
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String rawKey = eq >= 0 ? pair.substring(0, eq) : pair;
            String decodedKey = URLDecoder.decode(rawKey, StandardCharsets.UTF_8).trim().toLowerCase(Locale.ROOT);
            if (FORBIDDEN_QUERY_PARAMS.contains(decodedKey)) {
                return true;
            }
        }
        MultiValueMap<String, String> params = UriComponentsBuilder.fromUri(uri).build().getQueryParams();
        for (String key : params.keySet()) {
            if (key != null && FORBIDDEN_QUERY_PARAMS.contains(key.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}

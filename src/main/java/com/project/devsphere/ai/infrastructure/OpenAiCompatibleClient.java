package com.project.devsphere.ai.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.devsphere.ai.application.AiEndpointValidator;
import com.project.devsphere.ai.application.AiException;
import com.project.devsphere.ai.domain.AiProviderType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * {@code java.net.http.HttpClient}-based client for OpenAI-compatible {@code /v1/chat/completions} endpoints
 * (OpenAI and self-hosted vLLM).
 *
 * <p>Enforces SSRF validation + DNS-resolution address verification, disables HTTP redirects,
 * enforces connect/request timeouts, caps response payload size, and translates transport/protocol
 * failures into non-fatal HTTP 503 {@code AI_UNAVAILABLE}.</p>
 */
@Component
public class OpenAiCompatibleClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleClient.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final AiProperties aiProperties;
    private final AiEndpointValidator endpointValidator;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public OpenAiCompatibleClient(AiProperties aiProperties, AiEndpointValidator endpointValidator) {
        this(aiProperties, endpointValidator, null);
    }

    @Autowired
    public OpenAiCompatibleClient(
            AiProperties aiProperties,
            AiEndpointValidator endpointValidator,
            @Autowired(required = false) ObjectMapper objectMapper) {
        this.aiProperties = Objects.requireNonNull(aiProperties, "aiProperties must not be null");
        this.endpointValidator = Objects.requireNonNull(endpointValidator, "endpointValidator must not be null");
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper().findAndRegisterModules();
        long connectMs = Math.max(100L, aiProperties.getConnectTimeoutMs());
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectMs))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public CompletionResult chatCompletion(CompletionRequest request) {
        if (request == null) {
            throw AiException.invalidInput("CompletionRequest must not be null");
        }
        URI validatedBase = validateAndCheckDns(request.baseUrl(), request.provider());
        URI endpoint = buildCompletionsEndpoint(validatedBase);

        Map<String, Object> bodyMap = new LinkedHashMap<>();
        bodyMap.put("model", request.model());
        bodyMap.put("messages", request.messages().stream()
                .map(m -> Map.of("role", m.role(), "content", m.content()))
                .toList());
        bodyMap.put("response_format", Map.of("type", "json_object"));
        bodyMap.put("temperature", request.temperature() != null ? request.temperature() : 0.2d);

        String jsonBody;
        try {
            jsonBody = objectMapper.writeValueAsString(bodyMap);
        } catch (JsonProcessingException ex) {
            throw AiException.unavailable("Failed to serialize AI request payload");
        }

        long requestTimeoutMs = Math.max(500L, aiProperties.getRequestTimeoutMs());
        HttpRequest.Builder httpRequestBuilder = HttpRequest.newBuilder()
                .uri(endpoint)
                .timeout(Duration.ofMillis(requestTimeoutMs))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));

        if (request.apiKey() != null && !request.apiKey().isBlank()) {
            httpRequestBuilder.header("Authorization", "Bearer " + request.apiKey().strip());
        }

        int maxBytes = Math.max(1024, aiProperties.getMaxResponseBytes());
        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(httpRequestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpTimeoutException ex) {
            log.warn("AI provider request timed out after {}ms for model {}", requestTimeoutMs, request.model());
            throw AiException.unavailable("AI provider request timed out");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw AiException.unavailable("AI provider request interrupted");
        } catch (IOException ex) {
            log.warn("AI provider transport failure for provider {}: {}", request.provider(), ex.getMessage());
            throw AiException.unavailable("AI provider request failed");
        }

        byte[] rawBytes;
        try (InputStream bodyStream = response.body()) {
            OptionalLong declaredLength = response.headers().firstValueAsLong("Content-Length");
            if (declaredLength.isPresent() && declaredLength.getAsLong() > maxBytes) {
                throw AiException.unavailable("AI provider response exceeded max allowed bytes");
            }
            rawBytes = readBounded(bodyStream, maxBytes);
        } catch (IOException ex) {
            throw AiException.unavailable("Failed reading AI provider response stream");
        }

        int statusCode = response.statusCode();
        if (statusCode < 200 || statusCode >= 300) {
            log.warn("AI provider returned non-2xx HTTP status {} for model {}", statusCode, request.model());
            throw AiException.unavailable("AI provider returned HTTP " + statusCode);
        }

        return parseCompletionResponse(rawBytes);
    }

    private URI validateAndCheckDns(String baseUrl, AiProviderType provider) {
        URI validated = endpointValidator.validate(baseUrl, provider);
        String host = validated.getHost();
        if (host != null && !AiEndpointValidator.isIpLiteral(host)) {
            try {
                InetAddress[] resolved = InetAddress.getAllByName(host);
                for (InetAddress address : resolved) {
                    AiEndpointValidator.validateAddress(address, provider, endpointValidator.isLoopbackSelfHostAllowed());
                }
            } catch (UnknownHostException ex) {
                throw AiException.unavailable("AI provider host could not be resolved");
            } catch (AiException ex) {
                throw AiException.unavailable("AI provider resolved to a forbidden network address");
            }
        }
        return validated;
    }

    private static URI buildCompletionsEndpoint(URI validatedBase) {
        String base = validatedBase.toString();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String suffix = base.endsWith("/v1") ? "/chat/completions" : "/v1/chat/completions";
        return URI.create(base + suffix);
    }

    private static byte[] readBounded(InputStream stream, int maxBytes) throws IOException {
        if (stream == null) {
            return new byte[0];
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
        byte[] chunk = new byte[4096];
        int total = 0;
        int read;
        while ((read = stream.read(chunk)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw AiException.unavailable("AI provider response exceeded max allowed bytes");
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    private CompletionResult parseCompletionResponse(byte[] rawBytes) {
        if (rawBytes.length == 0) {
            throw AiException.unavailable("AI provider returned empty response body");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(rawBytes);
        } catch (IOException ex) {
            throw AiException.unavailable("AI provider returned malformed JSON response");
        }

        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw AiException.unavailable("AI provider response missing choices");
        }

        JsonNode contentNode = choices.get(0).path("message").path("content");
        if (!contentNode.isTextual() || contentNode.asText().isBlank()) {
            throw AiException.unavailable("AI provider response missing message content");
        }

        String rawContent = stripMarkdownFences(contentNode.asText().trim());
        Map<String, Object> outputJson;
        try {
            outputJson = objectMapper.readValue(rawContent, MAP_TYPE);
        } catch (JsonProcessingException ex) {
            throw AiException.unavailable("AI provider message content is not a valid JSON object");
        }
        if (outputJson == null || outputJson.isEmpty()) {
            throw AiException.unavailable("AI provider message JSON object is empty");
        }

        JsonNode usage = root.path("usage");
        Integer tokensIn = parseNonNegativeInt(usage.get("prompt_tokens"));
        Integer tokensOut = parseNonNegativeInt(usage.get("completion_tokens"));

        return new CompletionResult(outputJson, tokensIn, tokensOut, rawContent);
    }

    private static String stripMarkdownFences(String content) {
        if (!content.startsWith("```")) {
            return content;
        }
        int firstNewline = content.indexOf('\n');
        int lastFence = content.lastIndexOf("```");
        if (firstNewline > 0 && lastFence > firstNewline) {
            return content.substring(firstNewline + 1, lastFence).trim();
        }
        return content;
    }

    private static Integer parseNonNegativeInt(JsonNode node) {
        if (node == null || !node.isNumber()) {
            return null;
        }
        int value = node.asInt(-1);
        return value >= 0 ? value : null;
    }

    public record ChatPromptMessage(String role, String content) {
    }

    public record CompletionRequest(
            AiProviderType provider,
            String baseUrl,
            String model,
            String apiKey,
            List<ChatPromptMessage> messages,
            Double temperature
    ) {
        @Override
        public String toString() {
            return "CompletionRequest[provider=" + provider
                    + ", baseUrl=" + baseUrl
                    + ", model=" + model
                    + ", apiKeyConfigured=" + (apiKey != null && !apiKey.isBlank())
                    + ", messageCount=" + (messages != null ? messages.size() : 0) + "]";
        }
    }

    public record CompletionResult(
            Map<String, Object> outputJson,
            Integer tokensIn,
            Integer tokensOut,
            String rawContent
    ) {
    }
}

package com.project.devsphere.ai.infrastructure;

import com.project.devsphere.ai.application.AiEndpointValidator;
import com.project.devsphere.ai.application.AiException;
import com.project.devsphere.ai.domain.AiProviderType;
import com.project.devsphere.ai.infrastructure.OpenAiCompatibleClient.ChatPromptMessage;
import com.project.devsphere.ai.infrastructure.OpenAiCompatibleClient.CompletionRequest;
import com.project.devsphere.ai.infrastructure.OpenAiCompatibleClient.CompletionResult;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiCompatibleClientTest {

    private HttpServer server;
    private int port;
    private final AtomicReference<String> lastAuthHeader = new AtomicReference<>();
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();
    private volatile int responseStatus = 200;
    private volatile String responseBody = "{\"choices\":[{\"message\":{\"content\":\"{\\\"ok\\\":true}\"}}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}";

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/v1/chat/completions", exchange -> {
            lastAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private OpenAiCompatibleClient newClient(int maxResponseBytes) {
        AiProperties props = new AiProperties();
        props.setAllowLoopbackSelfHost(true);
        props.setConnectTimeoutMs(2000);
        props.setRequestTimeoutMs(3000);
        props.setMaxResponseBytes(maxResponseBytes);
        return new OpenAiCompatibleClient(props, new AiEndpointValidator(props));
    }

    @Test
    @DisplayName("Sends JSON object response_format, optional Bearer token, and parses usage + content")
    void parsesStructuredCompletionAndOmitsAuthWhenBlank() {
        OpenAiCompatibleClient client = newClient(1024 * 64);

        CompletionResult withAuth = client.chatCompletion(new CompletionRequest(
                AiProviderType.VLLM_SELF_HOST,
                "http://127.0.0.1:" + port,
                "qwen-2.5",
                "secret-token",
                List.of(new ChatPromptMessage("user", "ping")),
                0.2d
        ));
        assertThat(withAuth.outputJson()).containsEntry("ok", true);
        assertThat(withAuth.tokensIn()).isEqualTo(10);
        assertThat(withAuth.tokensOut()).isEqualTo(5);
        assertThat(lastAuthHeader.get()).isEqualTo("Bearer secret-token");
        assertThat(lastRequestBody.get()).contains("\"response_format\":{\"type\":\"json_object\"}");

        lastAuthHeader.set("STALE");
        client.chatCompletion(new CompletionRequest(
                AiProviderType.VLLM_SELF_HOST,
                "http://127.0.0.1:" + port,
                "qwen-2.5",
                null,
                List.of(new ChatPromptMessage("user", "ping")),
                0.2d
        ));
        assertThat(lastAuthHeader.get()).isNull();
    }

    @Test
    @DisplayName("Enforces bounded response cap, non-2xx status, and malformed JSON -> 503 AI_UNAVAILABLE")
    void enforcesSizeCapAndMapsFailuresTo503() {
        OpenAiCompatibleClient smallCapClient = newClient(1024);

        responseBody = "{\"choices\":[{\"message\":{\"content\":\"" + "x".repeat(2048) + "\"}}]}";
        assertThatThrownBy(() -> smallCapClient.chatCompletion(new CompletionRequest(
                AiProviderType.VLLM_SELF_HOST,
                "http://127.0.0.1:" + port,
                "qwen-2.5",
                null,
                List.of(new ChatPromptMessage("user", "big")),
                0.2d
        )))
                .isInstanceOf(AiException.class)
                .satisfies(ex -> {
                    AiException aiEx = (AiException) ex;
                    assertThat(aiEx.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(aiEx.getCode()).isEqualTo("AI_UNAVAILABLE");
                });

        responseStatus = 502;
        responseBody = "{\"error\":\"bad gateway\"}";
        assertThatThrownBy(() -> smallCapClient.chatCompletion(new CompletionRequest(
                AiProviderType.VLLM_SELF_HOST,
                "http://127.0.0.1:" + port,
                "qwen-2.5",
                null,
                List.of(new ChatPromptMessage("user", "err")),
                0.2d
        )))
                .isInstanceOf(AiException.class)
                .satisfies(ex -> assertThat(((AiException) ex).getCode()).isEqualTo("AI_UNAVAILABLE"));

        responseStatus = 200;
        responseBody = "{\"choices\":[{\"message\":{\"content\":\"not-json\"}}]}";
        assertThatThrownBy(() -> smallCapClient.chatCompletion(new CompletionRequest(
                AiProviderType.VLLM_SELF_HOST,
                "http://127.0.0.1:" + port,
                "qwen-2.5",
                null,
                List.of(new ChatPromptMessage("user", "badjson")),
                0.2d
        )))
                .isInstanceOf(AiException.class)
                .satisfies(ex -> assertThat(((AiException) ex).getCode()).isEqualTo("AI_UNAVAILABLE"));
    }
}

package com.project.devsphere.realtime.config;

import com.project.devsphere.realtime.application.RealtimeSessionRegistry;
import com.project.devsphere.realtime.security.QueryParamRejectHandshakeInterceptor;
import com.project.devsphere.realtime.security.StompJwtChannelInterceptor;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketBrokerConfig implements WebSocketMessageBrokerConfigurer {

    private static final int MAX_MESSAGE_SIZE_BYTES = 64 * 1024;
    private static final int SEND_TIME_LIMIT_MS = 15 * 1000;
    private static final int SEND_BUFFER_SIZE_LIMIT_BYTES = 512 * 1024;

    private final StompJwtChannelInterceptor stompJwtChannelInterceptor;
    private final QueryParamRejectHandshakeInterceptor queryParamRejectHandshakeInterceptor;
    private final RealtimeSessionRegistry realtimeSessionRegistry;
    private final List<String> allowedOriginPatterns;

    public WebSocketBrokerConfig(
            StompJwtChannelInterceptor stompJwtChannelInterceptor,
            QueryParamRejectHandshakeInterceptor queryParamRejectHandshakeInterceptor,
            RealtimeSessionRegistry realtimeSessionRegistry,
            @Value("${devsphere.realtime.allowed-origin-patterns:http://localhost:3000,http://127.0.0.1:3000,http://localhost:3200,http://127.0.0.1:3200}")
            List<String> allowedOriginPatterns) {
        this.stompJwtChannelInterceptor = Objects.requireNonNull(
                stompJwtChannelInterceptor, "stompJwtChannelInterceptor must not be null");
        this.queryParamRejectHandshakeInterceptor = Objects.requireNonNull(
                queryParamRejectHandshakeInterceptor, "queryParamRejectHandshakeInterceptor must not be null");
        this.realtimeSessionRegistry = Objects.requireNonNull(
                realtimeSessionRegistry, "realtimeSessionRegistry must not be null");
        this.allowedOriginPatterns = allowedOriginPatterns == null || allowedOriginPatterns.isEmpty()
                ? List.of("http://localhost:3000", "http://127.0.0.1:3000", "http://localhost:3200", "http://127.0.0.1:3200")
                : List.copyOf(allowedOriginPatterns);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns(allowedOriginPatterns.toArray(String[]::new))
                .addInterceptors(queryParamRejectHandshakeInterceptor);
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registry) {
        registry.setMessageSizeLimit(MAX_MESSAGE_SIZE_BYTES);
        registry.setSendTimeLimit(SEND_TIME_LIMIT_MS);
        registry.setSendBufferSizeLimit(SEND_BUFFER_SIZE_LIMIT_BYTES);
        registry.addDecoratorFactory(this::decorateHandler);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(stompJwtChannelInterceptor);
    }

    private WebSocketHandler decorateHandler(WebSocketHandler handler) {
        return new WebSocketHandlerDecorator(handler) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                realtimeSessionRegistry.registerWebSocketSession(session);
                super.afterConnectionEstablished(session);
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
                try {
                    super.afterConnectionClosed(session, closeStatus);
                } finally {
                    realtimeSessionRegistry.unregisterWebSocketSession(session.getId());
                }
            }
        };
    }
}

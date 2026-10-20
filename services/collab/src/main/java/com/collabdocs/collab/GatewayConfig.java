package com.collabdocs.collab;

import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

@Configuration
@EnableWebSocket
public class GatewayConfig implements WebSocketConfigurer {
    private final Gateway gateway;

    public GatewayConfig(Gateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(gateway, "/collab/v1")
                .setHandshakeHandler(new DefaultHandshakeHandler() {
                    @Override
                    protected String selectProtocol(List<String> requested, org.springframework.web.socket.WebSocketHandler handler) {
                        return requested != null && requested.contains("collabdocs.v1") ? "collabdocs.v1" : null;
                    }
                })
                .setAllowedOriginPatterns("*");
    }
}

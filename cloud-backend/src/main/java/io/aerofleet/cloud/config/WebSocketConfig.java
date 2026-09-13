package io.aerofleet.cloud.config;

import io.aerofleet.cloud.api.TelemetryWebSocketHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Raw WebSocket wiring for /ws/telemetry (no SockJS, browser connects
 * directly; the Vite dev server proxies ws:// to us).
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    @Bean
    public TelemetryWebSocketHandler telemetryWebSocketHandler() {
        return new TelemetryWebSocketHandler();
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(telemetryWebSocketHandler(), "/ws/telemetry")
                .setAllowedOrigins("http://localhost:5173");
    }
}

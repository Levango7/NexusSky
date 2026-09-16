package io.aerofleet.cloud.config;

import io.aerofleet.cloud.api.TelemetryWebSocketHandler;
import io.aerofleet.cloud.security.JwtTokenProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Raw WebSocket wiring for /ws/telemetry (no SockJS, browser connects
 * directly; the Vite dev server proxies ws:// to us).
 * <p>
 * 允许的源通过 {@code aerofleet.security.allowed-origins} 配置，默认 {@code http://localhost:5173}。
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final JwtTokenProvider jwtTokenProvider;

    @Value("${aerofleet.security.dev-mode:true}")
    private boolean devMode;

    @Value("${aerofleet.security.allowed-origins:http://localhost:5173}")
    private String allowedOrigins;

    public WebSocketConfig(JwtTokenProvider jwtTokenProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Bean
    public TelemetryWebSocketHandler telemetryWebSocketHandler() {
        return new TelemetryWebSocketHandler(jwtTokenProvider, devMode);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(telemetryWebSocketHandler(), "/ws/telemetry")
                .setAllowedOrigins(allowedOrigins.split(","));
    }
}

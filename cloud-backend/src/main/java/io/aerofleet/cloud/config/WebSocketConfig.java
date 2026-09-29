package io.aerofleet.cloud.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aerofleet.cloud.api.ws.TelemetryWebSocketHandler;
import io.aerofleet.cloud.gateway.DeviceRegistry;
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
 * <p>
 * 连接数限制通过 {@code aerofleet.ws.max-connections-per-ip} 和
 * {@code aerofleet.ws.max-connections-per-tenant} 配置。
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final JwtTokenProvider jwtTokenProvider;
    private final DeviceRegistry deviceRegistry;
    private final ObjectMapper objectMapper;

    @Value("${aerofleet.security.dev-mode:false}")
    private boolean devMode;

    @Value("${aerofleet.security.allowed-origins:http://localhost:5173}")
    private String allowedOrigins;

    @Value("${aerofleet.ws.max-connections-per-ip:10}")
    private int maxConnectionsPerIp;

    @Value("${aerofleet.ws.max-connections-per-tenant:20}")
    private int maxConnectionsPerTenant;

    public WebSocketConfig(JwtTokenProvider jwtTokenProvider, DeviceRegistry deviceRegistry,
                           ObjectMapper objectMapper) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.deviceRegistry = deviceRegistry;
        this.objectMapper = objectMapper;
    }

    @Bean
    public TelemetryWebSocketHandler telemetryWebSocketHandler() {
        return new TelemetryWebSocketHandler(jwtTokenProvider, deviceRegistry, devMode,
                maxConnectionsPerIp, maxConnectionsPerTenant, objectMapper);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(telemetryWebSocketHandler(), "/ws/telemetry")
                .setAllowedOrigins(allowedOrigins.split(","));
    }
}

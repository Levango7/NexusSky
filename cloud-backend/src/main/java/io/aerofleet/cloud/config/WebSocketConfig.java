package io.aerofleet.cloud.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aerofleet.cloud.api.ws.TelemetryWebSocketHandler;
import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.security.JwtTokenProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;

/**
 * Raw WebSocket wiring for /ws/telemetry (no SockJS, browser connects
 * directly; the Vite dev server proxies ws:// to us).
 * <p>
 * 允许的源通过 {@code aerofleet.security.allowed-origins} 配置，默认 {@code http://localhost:5173}。
 * <p>
 * 连接数限制通过 {@code aerofleet.ws.max-connections-per-ip} 和
 * {@code aerofleet.ws.max-connections-per-tenant} 配置。
 * <p>
 * 推送通道对每个连接套 {@code ConcurrentWebSocketSessionDecorator}（由
 * {@code aerofleet.ws.send-time-limit-ms} / {@code aerofleet.ws.buffer-size-limit} 调参），
 * 防慢客户端阻塞占住调度线程。见 GAPS §十五。
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

    /** 单帧推送发送时限（毫秒）：慢客户端不读/TCP 窗口满时，写阻塞超过此值即断开。 */
    @Value("${aerofleet.ws.send-time-limit-ms:5000}")
    private int wsSendTimeLimitMs;

    /** 单连接发送缓冲上限（字节）：客户端消费慢、缓冲堆积超过此值即断开。 */
    @Value("${aerofleet.ws.buffer-size-limit:524288}")
    private int wsBufferSizeLimit;

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
                .setAllowedOrigins(allowedOrigins.split(","))
                .setDecoratorFactory(handler -> new WebSocketHandlerDecorator(handler) {
                    @Override
                    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                        // 慢客户端防护（GAPS §十五）：客户端连上但不读 / 慢读服务端推送的数据时，
                        // 底层 TCP 写会阻塞直到对端 RST，占用调度线程。装饰器设定单帧发送时限与
                        // 缓冲上限，超限即抛 SessionLimitExceededException 并主动断开该会话，
                        // 保护其余调度线程不被单条卡死连接拖垮。超时/上限由 aerofleet.ws.* 配置。
                        super.afterConnectionEstablished(
                                new ConcurrentWebSocketSessionDecorator(
                                        session, wsSendTimeLimitMs, wsBufferSizeLimit));
                    }
                });
    }
}

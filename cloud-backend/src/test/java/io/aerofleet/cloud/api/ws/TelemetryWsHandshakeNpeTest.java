package io.aerofleet.cloud.api.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.security.JwtTokenProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 握手期 NPE 回归：{@code afterConnectionEstablished} 曾把 null 租户塞进
 * {@code session.getAttributes()}（Spring 实现是 ConcurrentHashMap，不接受 null value），
 * 抛出的 NPE 会把整条连接以 1011 关闭——客户端表现为"遥测刚连上又断"。
 * <p>
 * 触发面正是最常见的两类连接：dev 模式（无 token）与"token 有效但用户无租户归属"，
 * 两者 {@code extractTenantId} 都返回 null。租户隔离改造引入、本层E2E 实测撞出。
 */
@DisplayName("遥测 WS 握手：租户/IP 取不到时不得抛 NPE")
class TelemetryWsHandshakeNpeTest {

    /** 造一个属性表与生产一致（ConcurrentHashMap）的 session mock。 */
    private WebSocketSession sessionWithChmAttributes() {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("sess-npe-1");
        when(session.getAttributes()).thenReturn(new ConcurrentHashMap<>());
        when(session.getRemoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 51234));
        return session;
    }

    private TelemetryWebSocketHandler handler(boolean devMode) {
        return new TelemetryWebSocketHandler(
                mock(JwtTokenProvider.class),
                mock(DeviceRegistry.class),
                devMode,
                0,   // 不启用 IP 限流，聚焦 NPE 本身
                0,   // 不启用租户限流
                new ObjectMapper());
    }

    @Test
    @DisplayName("dev 模式（租户解析为 null）：握手不抛异常且连接被登记")
    void devModeWithoutTenantShouldConnect() {
        TelemetryWebSocketHandler handler = handler(true);
        WebSocketSession session = sessionWithChmAttributes();

        assertThatCode(() -> handler.afterConnectionEstablished(session))
                .doesNotThrowAnyException();

        // 连接应真的进了广播表——否则 E2E 里表现为"连上了但一帧收不到"
        assertThat(handler.connectionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("无租户归属的 token：握手不抛异常且连接被登记")
    void authenticatedWithoutTenantShouldConnect() {
        JwtTokenProvider jwt = mock(JwtTokenProvider.class);
        when(jwt.validateToken("tok")).thenReturn(true);   // 握手鉴权通过
        when(jwt.resolveTenantScope("tok")).thenReturn(null); // 但用户无租户归属 → 三态里的全局域
        TelemetryWebSocketHandler handler = new TelemetryWebSocketHandler(
                jwt, mock(DeviceRegistry.class), false, 0, 0, new ObjectMapper());

        WebSocketSession session = sessionWithChmAttributes();
        // token 通过 query 参数传入（生产口径：浏览器握手带不了 Authorization 头）
        org.mockito.Mockito.doReturn(java.net.URI.create("ws://h/ws/telemetry?token=tok"))
                .when(session).getUri();

        assertThatCode(() -> handler.afterConnectionEstablished(session))
                .doesNotThrowAnyException();
        assertThat(handler.connectionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("断连清理在无租户属性时也不抛 NPE")
    void disconnectWithoutTenantAttributeShouldNotThrow() {
        TelemetryWebSocketHandler handler = handler(true);
        WebSocketSession session = sessionWithChmAttributes();
        handler.afterConnectionEstablished(session);

        assertThatCode(() -> handler.afterConnectionClosed(session, CloseStatus.NORMAL))
                .doesNotThrowAnyException();
        assertThat(handler.connectionCount()).isZero();
    }
}
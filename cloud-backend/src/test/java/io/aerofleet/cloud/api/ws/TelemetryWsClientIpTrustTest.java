package io.aerofleet.cloud.api.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.security.JwtTokenProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WS 连接限流的 X-Forwarded-For 取信门测试（认证/JWT 链人工威胁建模 ②-F1）。
 * <p>
 * 与 {@code AuthController.extractClientIp} / {@code AuditInterceptor} 同一开关口径：
 * 默认（trust=false）IP 取 remote address，握手头里的 XFF 不参与分桶——否则每换一个
 * XFF 就是一个新桶，per-IP 上限形同虚设；仅显式信任代理（trust=true）时采用 XFF 首段。
 * <p>
 * 变异钉：把 {@code extractClientIp} 里的 {@code if (trustForwardedFor)} 取反或删掉，
 * 本类两条测试必有一条转红。
 */
@DisplayName("WS 连接限流：XFF 仅在显式信任时参与分桶")
class TelemetryWsClientIpTrustTest {

    private WebSocketSession session(String id, String remoteIp, String forwardedFor) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.getAttributes()).thenReturn(new ConcurrentHashMap<>());
        when(session.getRemoteAddress()).thenReturn(new InetSocketAddress(remoteIp, 40000));
        HttpHeaders headers = new HttpHeaders();
        if (forwardedFor != null) {
            headers.add("X-Forwarded-For", forwardedFor);
        }
        when(session.getHandshakeHeaders()).thenReturn(headers);
        return session;
    }

    private TelemetryWebSocketHandler handler(boolean trustForwardedFor, int perIp) {
        return new TelemetryWebSocketHandler(
                mock(JwtTokenProvider.class),
                mock(DeviceRegistry.class),
                true,               // dev 模式：免 token，聚焦 IP 分桶
                trustForwardedFor,
                perIp,
                0,                  // 不启用租户限流
                new ObjectMapper());
    }

    @Test
    @DisplayName("默认不信任：XFF 换值不换桶，per-IP 上限仍拦同一 remote IP")
    void xffIgnoredByDefault() throws Exception {
        TelemetryWebSocketHandler handler = handler(false, 1);
        try {
            handler.afterConnectionEstablished(session("s1", "10.7.7.9", "203.0.113.1"));
            WebSocketSession second = session("s2", "10.7.7.9", "203.0.113.2");
            handler.afterConnectionEstablished(second);

            assertThat(handler.connectionCount()).isEqualTo(1);
            verify(second).close(CloseStatus.POLICY_VIOLATION);
        } finally {
            handler.stopDispatcher();
        }
    }

    @Test
    @DisplayName("显式信任：XFF 首段分桶（不同 XFF 放行，同 XFF 超限拦截）")
    void xffUsedWhenTrusted() throws Exception {
        TelemetryWebSocketHandler handler = handler(true, 1);
        try {
            handler.afterConnectionEstablished(session("s1", "10.7.7.9", "203.0.113.1"));
            WebSocketSession other = session("s2", "10.7.7.9", "203.0.113.2");
            handler.afterConnectionEstablished(other);
            WebSocketSession same = session("s3", "10.7.7.9", "203.0.113.1");
            handler.afterConnectionEstablished(same);

            assertThat(handler.connectionCount()).isEqualTo(2);
            verify(same).close(CloseStatus.POLICY_VIOLATION);
            verify(other, never()).close(CloseStatus.POLICY_VIOLATION);
        } finally {
            handler.stopDispatcher();
        }
    }
}

package io.aerofleet.cloud.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aerofleet.cloud.security.JwtTokenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Raw WebSocket endpoint /ws/telemetry (no SockJS). Sessions are kept in an
 * in-memory set; the 1Hz pusher broadcasts to all of them.
 * <p>
 * 认证：非开发模式下，handshake 时从 query parameter {@code token} 或
 * {@code Authorization} header 提取 JWT 并验证，无效则拒绝连接。
 * 开发模式（{@code aerofleet.security.dev-mode=true}）跳过认证。
 */
public class TelemetryWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(TelemetryWebSocketHandler.class);

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final JwtTokenProvider jwtTokenProvider;
    private final boolean devMode;

    public TelemetryWebSocketHandler(JwtTokenProvider jwtTokenProvider, boolean devMode) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.devMode = devMode;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        if (!devMode && !validateToken(session)) {
            log.warn("WS handshake 认证失败，拒绝连接: {}", session.getId());
            closeQuietly(session, CloseStatus.POLICY_VIOLATION);
            return;
        }
        sessions.put(session.getId(), session);
        log.info("WS client connected: {} (total {})", session.getId(), sessions.size());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
        log.info("WS client disconnected: {} status={} (total {})",
                session.getId(), status, sessions.size());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // Read-only channel at scaffold stage; ignore client frames.
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable error) {
        sessions.remove(session.getId());
    }

    /** Push one JSON text frame to every live connection. */
    public void broadcast(String json, ObjectMapper mapper) {
        if (sessions.isEmpty()) {
            return;
        }
        TextMessage msg = new TextMessage(json);
        for (WebSocketSession s : sessions.values()) {
            if (!s.isOpen()) {
                sessions.remove(s.getId());
                continue;
            }
            try {
                synchronized (s) {
                    s.sendMessage(msg);
                }
            } catch (IOException e) {
                log.debug("WS send failed to {}: {}", s.getId(), e.getMessage());
                sessions.remove(s.getId());
            }
        }
    }

    public int connectionCount() {
        return sessions.size();
    }

    /**
     * 从 handshake 请求中提取并验证 JWT token。
     * <p>
     * 优先从 query parameter {@code token} 提取，其次从 {@code Authorization} header 提取。
     */
    private boolean validateToken(WebSocketSession session) {
        String token = extractToken(session);
        if (token == null) {
            return false;
        }
        return jwtTokenProvider.validateToken(token);
    }

    private String extractToken(WebSocketSession session) {
        // 1. 从 query parameter 提取
        URI uri = session.getUri();
        if (uri != null && uri.getQuery() != null) {
            String query = uri.getQuery();
            for (String param : query.split("&")) {
                String[] kv = param.split("=", 2);
                if ("token".equals(kv[0]) && kv.length == 2) {
                    return kv[1];
                }
            }
        }
        // 2. 从 Authorization header 提取
        Map<String, Object> headers = session.getAttributes();
        // WebSocket handshake headers 可通过 attributes 获取（需 HandshakeInterceptor）
        // 这里也检查 handshake request 的 headers
        var handshakeHeaders = session.getHandshakeHeaders();
        if (handshakeHeaders != null) {
            String auth = handshakeHeaders.getFirst("Authorization");
            if (auth != null && auth.startsWith("Bearer ")) {
                return auth.substring(7);
            }
        }
        return null;
    }

    private void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (IOException e) {
            log.debug("关闭 WS session 失败: {}", e.getMessage());
        }
    }
}

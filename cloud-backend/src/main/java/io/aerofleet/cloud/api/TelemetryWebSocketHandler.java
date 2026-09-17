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
        // /ws/telemetry 为只读广播通道：服务端 1Hz pusher 向所有连接的 GCS 客户端单向推送遥测 JSON 帧，
        // 客户端不应上行数据。此处不处理客户端帧，仅记录 debug 日志以便排查客户端误发消息的情况。
        // 若未来需支持 GCS 下行命令（如任务下发、返航指令），应在此解析 JSON 帧并路由到对应命令处理器，
        // 并补充相应的认证/鉴权与限流逻辑。
        log.debug("WS /ws/telemetry 收到客户端上行帧（只读通道，已忽略）: session={} payloadLen={}",
                session.getId(), message.getPayloadLength());
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

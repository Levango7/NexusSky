package io.aerofleet.cloud.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aerofleet.cloud.gateway.MavlinkMessageEvent;
import io.aerofleet.cloud.security.JwtTokenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Raw WebSocket endpoint /ws/telemetry (no SockJS). Sessions are kept in an
 * in-memory set; the 1Hz pusher broadcasts to all of them.
 * <p>
 * 认证：非开发模式下，handshake 时从 query parameter {@code token} 或
 * {@code Authorization} header 提取 JWT 并验证，无效则拒绝连接。
 * 开发模式（{@code aerofleet.security.dev-mode=true}）跳过认证。
 * <p>
 * 连接数限制：单 IP 最大 {@code maxConnectionsPerIp} 条连接，
 * 单租户最大 {@code maxConnectionsPerTenant} 条连接，超限拒绝。
 */
public class TelemetryWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(TelemetryWebSocketHandler.class);

    /** Session attribute key for stored client IP. */
    private static final String ATTR_CLIENT_IP = "clientIp";
    /** Session attribute key for stored tenant ID. */
    private static final String ATTR_TENANT_ID = "tenantId";

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final JwtTokenProvider jwtTokenProvider;
    private final boolean devMode;
    private final int maxConnectionsPerIp;
    private final int maxConnectionsPerTenant;
    private final ObjectMapper objectMapper;

    /** IP → current connection count. */
    private final Map<String, Integer> ipConnectionCounts = new ConcurrentHashMap<>();
    /** Tenant ID → current connection count. */
    private final Map<Integer, Integer> tenantConnectionCounts = new ConcurrentHashMap<>();

    /** msgId → WS frame type 映射（从 TelemetryIngestService.forwardToWs 迁移）。 */
    private static final Map<Integer, String> WS_TYPE_MAP = Map.ofEntries(
            Map.entry(io.aerofleet.mavlink.messages.VisionDetectionMsg.ID, "vision-detection"),
            Map.entry(io.aerofleet.mavlink.messages.TaskAssignmentMsg.ID, "task-assignment"),
            Map.entry(io.aerofleet.mavlink.messages.ConflictAlertMsg.ID, "conflict-alert"),
            Map.entry(io.aerofleet.mavlink.messages.TaskStatusMsg.ID, "task-status"),
            Map.entry(io.aerofleet.mavlink.messages.DecisionEventMsg.ID, "decision-event"),
            Map.entry(io.aerofleet.mavlink.messages.AdaptivePathMsg.ID, "adaptive-path"),
            Map.entry(io.aerofleet.mavlink.messages.EdgeTaskStatusMsg.ID, "edge-task-status"),
            Map.entry(io.aerofleet.mavlink.messages.SensorFusionDataMsg.ID, "sensor-fusion"),
            Map.entry(io.aerofleet.mavlink.messages.TwinStateSyncMsg.ID, "twin-state-sync"),
            Map.entry(io.aerofleet.mavlink.messages.PredictionResultMsg.ID, "prediction-result"),
            Map.entry(io.aerofleet.mavlink.messages.AlarmTriggerMsg.ID, "alarm-trigger"),
            Map.entry(io.aerofleet.mavlink.messages.AlarmAckMsg.ID, "alarm-ack"),
            Map.entry(io.aerofleet.mavlink.messages.SurveillanceStatusMsg.ID, "surveillance-status")
    );

    public TelemetryWebSocketHandler(JwtTokenProvider jwtTokenProvider, boolean devMode,
                                     int maxConnectionsPerIp, int maxConnectionsPerTenant,
                                     ObjectMapper objectMapper) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.devMode = devMode;
        this.maxConnectionsPerIp = maxConnectionsPerIp;
        this.maxConnectionsPerTenant = maxConnectionsPerTenant;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        if (!devMode && !validateToken(session)) {
            log.warn("WS handshake 认证失败，拒绝连接: {}", session.getId());
            closeQuietly(session, CloseStatus.POLICY_VIOLATION);
            return;
        }

        // Extract and store client IP for connection-limit checks
        String clientIp = extractClientIp(session);
        session.getAttributes().put(ATTR_CLIENT_IP, clientIp);

        // Check per-IP connection limit
        if (clientIp != null && maxConnectionsPerIp > 0) {
            int ipCount = ipConnectionCounts.merge(clientIp, 1, Integer::sum);
            if (ipCount > maxConnectionsPerIp) {
                log.warn("WS 连接数超限（IP {} 已有 {} 连接，上限 {}），拒绝: {}",
                        clientIp, ipCount - 1, maxConnectionsPerIp, session.getId());
                ipConnectionCounts.merge(clientIp, -1, Integer::sum);
                closeQuietly(session, CloseStatus.POLICY_VIOLATION);
                return;
            }
        }

        // Extract and store tenant ID for connection-limit checks
        Integer tenantId = extractTenantId(session);
        session.getAttributes().put(ATTR_TENANT_ID, tenantId);

        // Check per-tenant connection limit
        if (tenantId != null && maxConnectionsPerTenant > 0) {
            int tenantCount = tenantConnectionCounts.merge(tenantId, 1, Integer::sum);
            if (tenantCount > maxConnectionsPerTenant) {
                log.warn("WS 连接数超限（租户 {} 已有 {} 连接，上限 {}），拒绝: {}",
                        tenantId, tenantCount - 1, maxConnectionsPerTenant, session.getId());
                tenantConnectionCounts.merge(tenantId, -1, Integer::sum);
                closeQuietly(session, CloseStatus.POLICY_VIOLATION);
                return;
            }
        }

        sessions.put(session.getId(), session);
        log.info("WS client connected: {} ip={} tenant={} (total {})",
                session.getId(), clientIp, tenantId, sessions.size());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());

        // Decrement per-IP connection count
        String clientIp = (String) session.getAttributes().get(ATTR_CLIENT_IP);
        if (clientIp != null && maxConnectionsPerIp > 0) {
            ipConnectionCounts.computeIfPresent(clientIp, (k, v) -> v <= 1 ? null : v - 1);
        }

        // Decrement per-tenant connection count
        Integer tenantId = (Integer) session.getAttributes().get(ATTR_TENANT_ID);
        if (tenantId != null && maxConnectionsPerTenant > 0) {
            tenantConnectionCounts.computeIfPresent(tenantId, (k, v) -> v <= 1 ? null : v - 1);
        }

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

        // Decrement per-IP connection count
        String clientIp = (String) session.getAttributes().get(ATTR_CLIENT_IP);
        if (clientIp != null && maxConnectionsPerIp > 0) {
            ipConnectionCounts.computeIfPresent(clientIp, (k, v) -> v <= 1 ? null : v - 1);
        }

        // Decrement per-tenant connection count
        Integer tenantId = (Integer) session.getAttributes().get(ATTR_TENANT_ID);
        if (tenantId != null && maxConnectionsPerTenant > 0) {
            tenantConnectionCounts.computeIfPresent(tenantId, (k, v) -> v <= 1 ? null : v - 1);
        }
    }

    /**
     * Push one JSON text frame to every live connection.
     * <p>
     * Uses {@link WebSocketSession#sendMessage} for synchronous delivery
     * (Spring 6 removed sendMessageAsync from the WebSocketSession interface).
     * The {@code mapper} parameter is retained for API compatibility with
     * existing callers but is not used internally (the json is already serialized).
     */
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
                s.sendMessage(msg);
            } catch (Exception e) {
                log.debug("WS send failed to {}: {}", s.getId(), e.getMessage());
                sessions.remove(s.getId());
            }
        }
    }

    public int connectionCount() {
        return sessions.size();
    }

    /**
     * 监听 MavlinkMessageEvent，将匹配的消息以 JSON 帧广播到所有连接的 /ws/telemetry 客户端。
     * <p>
     * 从 TelemetryIngestService.forwardToWs 迁移。帧格式：
     * {"type":"<type>","sysid":N,"data":<msg>,"timestamp":T}
     * <p>
     * 无 WS 连接时降级为日志输出，不阻塞事件处理。
     */
    @EventListener
    public void onMavlinkMessage(MavlinkMessageEvent event) {
        String type = WS_TYPE_MAP.get(event.getMsgId());
        if (type == null) {
            return; // 不需要 WS 转发的消息类型
        }
        if (sessions.isEmpty()) {
            log.debug("WS forward (no clients): sysid={} type={}", event.getSysid(), type);
            return;
        }
        try {
            Map<String, Object> frame = new LinkedHashMap<>();
            frame.put("type", type);
            frame.put("sysid", event.getSysid());
            frame.put("data", event.getMessage());
            frame.put("timestamp", event.getMsgTimestamp());
            broadcast(objectMapper.writeValueAsString(frame), objectMapper);
        } catch (Exception e) {
            log.warn("WS forward failed: sysid={} type={}: {}", event.getSysid(), type, e.getMessage());
        }
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
        var handshakeHeaders = session.getHandshakeHeaders();
        if (handshakeHeaders != null) {
            String auth = handshakeHeaders.getFirst("Authorization");
            if (auth != null && auth.startsWith("Bearer ")) {
                return auth.substring(7);
            }
        }
        return null;
    }

    /**
     * 从 WebSocket session 中提取客户端 IP 地址。
     * <p>
     * 优先从 X-Forwarded-For header 提取（反向代理场景），
     * 其次从 remote address 提取。
     */
    private String extractClientIp(WebSocketSession session) {
        // 1. Check X-Forwarded-For header (reverse proxy)
        var handshakeHeaders = session.getHandshakeHeaders();
        if (handshakeHeaders != null) {
            String forwarded = handshakeHeaders.getFirst("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.split(",")[0].trim();
            }
        }
        // 2. Fall back to remote address
        InetSocketAddress remoteAddr = session.getRemoteAddress();
        if (remoteAddr != null && remoteAddr.getAddress() != null) {
            return remoteAddr.getAddress().getHostAddress();
        }
        return null;
    }

    /**
     * 从 WebSocket session 的 JWT token 中提取租户 ID。
     * <p>
     * 开发模式下无 token，返回 null（不进行租户限制）。
     */
    private Integer extractTenantId(WebSocketSession session) {
        if (devMode) {
            return null;
        }
        String token = extractToken(session);
        if (token == null) {
            return null;
        }
        return jwtTokenProvider.getTenantId(token);
    }

    private void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (IOException e) {
            log.debug("关闭 WS session 失败: {}", e.getMessage());
        }
    }
}

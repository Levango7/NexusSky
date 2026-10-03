package io.aerofleet.cloud.api.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aerofleet.cloud.gateway.DeviceRegistry;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Raw WebSocket endpoint /ws/telemetry (no SockJS). Sessions are kept in an
 * in-memory set; frames are delivered per tenant — see {@link #broadcastToTenant}
 * — so one tenant's GCS never receives another tenant's telemetry.
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

    /** 分桶哨兵：条目 sysid 不属于机队设备（基站/卫星/地面中继等基础设施）。 */
    private static final Object PUBLIC_INFRA = new Object() {
        @Override
        public String toString() {
            return "PUBLIC_INFRA";
        }
    };

    /** 分桶哨兵：机队里的设备但尚未指派租户——只发给全局会话，不进任何租户界面。 */
    private static final Object UNASSIGNED = new Object() {
        @Override
        public String toString() {
            return "UNASSIGNED";
        }
    };

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final JwtTokenProvider jwtTokenProvider;
    private final DeviceRegistry registry;
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

    public TelemetryWebSocketHandler(JwtTokenProvider jwtTokenProvider, DeviceRegistry registry,
                                     boolean devMode,
                                     int maxConnectionsPerIp, int maxConnectionsPerTenant,
                                     ObjectMapper objectMapper) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.registry = registry;
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
        // ConcurrentHashMap 不接受 null value：写 null 会抛 NPE 并把整条连接
        // 以 1011 打死（客户端表现为"遥测连上了又立刻断"）。取不到就干脆不写，
        // 读取侧本来就按 null 处理（见下方限流分支与断连清理）。
        if (clientIp != null) {
            session.getAttributes().put(ATTR_CLIENT_IP, clientIp);
        }

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
        // 同上：dev 模式与"token 无租户归属"都会得到 null（全局域），
        // 此时不写属性 = 全局会话，dispatch 的可见性判定按 null 语义走。
        if (tenantId != null) {
            session.getAttributes().put(ATTR_TENANT_ID, tenantId);
        }

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
     * 投递给所有连接：仅限<b>无租户归属的基础设施态势</b>（基站/卫星链路/地形/灾害区聚合等）。
     * <p>
     * 任何由设备或任务派生的数据都必须走 {@link #tryBroadcastByDeviceOwner} 或
     * {@link #broadcastToTenant}；调用本方法即是在声明"这帧不属于任何租户"，请写明依据。
     */
    public void broadcastPublicInfra(String json) {
        dispatch(json, null, true);
    }

    /**
     * 按租户投递一帧。
     * <p>
     * 带租户上下文的连接只收本租户的帧；无租户上下文的全局会话（全局管理员与 dev-mode）收全部。
     * {@code ownerTenantId} 为 null 表示设备存在但尚未归属，此时只有全局会话可见——
     * 未归属设备不得落到任何租户连接，口径与 {@code DeviceRegistry} 的可见性判定一致。
     *
     * @param ownerTenantId 帧所属租户 ID，null 表示未归属
     */
    public void broadcastToTenant(Integer ownerTenantId, String json) {
        dispatch(json, ownerTenantId, false);
    }

    /**
     * 定向投递机制（按条目里的 {@code sysid} 归属分区）。等价于
     * {@code tryBroadcastByOwnerKey(frame, "sysid", mapper)}。
     */
    public boolean tryBroadcastByDeviceOwner(Map<String, Object> frame, ObjectMapper mapper) {
        return tryBroadcastByOwnerKey(frame, "sysid", mapper);
    }

    /**
     * 定向投递机制：把一帧按其中条目的设备归属拆开，逐租户各发一份"只含自己条目"的帧。
     * <p>
     * 帧里凡是「元素带 {@code ownerKey} 的列表」都会被分区（例如 mesh 的 nodes/events、
     * hardware 的 radar/rotor、obstacle 的 drones、formation 的 formations 按 leader 设备）；
     * 其余字段（type、时间戳等）原样复制进每一帧。分区依据：
     * <ul>
     *   <li>已知设备且已归属 → 只发给该租户；</li>
     *   <li>已知设备但未归属 → 只发给全局会话（不进任何租户界面）；</li>
     *   <li>ownerKey 缺失或不是已知设备（基站/卫星等基础设施）→ 按公共基础设施发给所有连接。</li>
     * </ul>
     * 帧内找不到任何可分区的条目时返回 false——调用方必须显式决定归属（而不是静默全员广播）。
     *
     * @param frame    待投递的帧（本方法不修改它）
     * @param ownerKey 条目里承载设备 system id 的字段名
     * @param mapper   序列化用的 ObjectMapper
     * @return 完成定向投递返回 true；帧内无归属信息返回 false
     */
    public boolean tryBroadcastByOwnerKey(Map<String, Object> frame, String ownerKey,
                                          ObjectMapper mapper) {
        List<String> listKeys = new ArrayList<>();
        for (Map.Entry<String, Object> entry : frame.entrySet()) {
            if (entry.getValue() instanceof List<?> list && !list.isEmpty()
                    && list.get(0) instanceof Map<?, ?> first && first.containsKey(ownerKey)) {
                listKeys.add(entry.getKey());
            }
        }
        if (listKeys.isEmpty()) {
            return false;
        }

        // 桶：真实租户 ID / UNASSIGNED（机队里的设备但尚未归属）/ PUBLIC_INFRA（不是机队设备）
        Map<Object, Map<String, List<Map<String, Object>>>> byOwner = new LinkedHashMap<>();
        for (String key : listKeys) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) frame.get(key);
            for (Map<String, Object> item : items) {
                Object bucket = ownerBucketFor(item, ownerKey);
                Map<String, List<Map<String, Object>>> scopedLists = byOwner.computeIfAbsent(
                        bucket, k -> {
                            Map<String, List<Map<String, Object>>> fresh = new LinkedHashMap<>();
                            listKeys.forEach(listKey -> fresh.put(listKey, new ArrayList<>()));
                            return fresh;
                        });
                scopedLists.get(key).add(item);
            }
        }

        for (Map.Entry<Object, Map<String, List<Map<String, Object>>>> group : byOwner.entrySet()) {
            Map<String, Object> scopedFrame = new HashMap<>(frame);
            scopedFrame.putAll(group.getValue());
            String json;
            try {
                json = mapper.writeValueAsString(scopedFrame);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                log.warn("Failed to serialize scoped frame: {}", e.getMessage());
                continue;
            }
            Object bucket = group.getKey();
            if (PUBLIC_INFRA.equals(bucket)) {
                broadcastPublicInfra(json);
            } else if (UNASSIGNED.equals(bucket)) {
                broadcastToTenant(null, json);
            } else {
                broadcastToTenant((Integer) bucket, json);
            }
        }
        return true;
    }

    /**
     * 一条目的归属桶：已归属设备→租户 ID；机队内未归属设备→{@link #UNASSIGNED}；
     * 归属键缺失或该 sysid 不是机队设备（基站/卫星/地面中继）→{@link #PUBLIC_INFRA}。
     */
    private Object ownerBucketFor(Map<String, Object> item, String ownerKey) {
        if (!(item.get(ownerKey) instanceof Number sysid)) {
            return PUBLIC_INFRA;
        }
        int id = sysid.intValue();
        if (!registry.isKnownDevice(id)) {
            return PUBLIC_INFRA;
        }
        Integer tenantId = registry.tenantOf(id);
        return tenantId != null ? tenantId : UNASSIGNED;
    }

    /**
     * @param toEverySession true=无归属的基础设施帧，发给所有连接；
     *                       false=按 {@code ownerTenantId} 做租户可见性判定
     */
    private void dispatch(String json, Integer ownerTenantId, boolean toEverySession) {
        if (sessions.isEmpty()) {
            return;
        }
        TextMessage msg = new TextMessage(json);
        for (WebSocketSession s : sessions.values()) {
            if (!s.isOpen()) {
                sessions.remove(s.getId());
                continue;
            }
            Integer sessionTenant = (Integer) s.getAttributes().get(ATTR_TENANT_ID);
            if (!toEverySession && !isVisible(ownerTenantId, sessionTenant)) {
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

    /**
     * 帧可见性判定：全局会话（无租户上下文）看全部；租户会话只看本租户。
     */
    private static boolean isVisible(Integer ownerTenantId, Integer sessionTenantId) {
        if (sessionTenantId == null) {
            return true;
        }
        return ownerTenantId != null && ownerTenantId.equals(sessionTenantId);
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
            broadcastToTenant(registry.tenantOf(event.getSysid()),
                    objectMapper.writeValueAsString(frame));
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
     * 从 WebSocket session 的 JWT token 中解析三态租户域（与 HTTP 入口同一规则）。
     * <p>
     * 开发模式下无 token，返回 null（全局域，不做租户限制）。
     */
    private Integer extractTenantId(WebSocketSession session) {
        if (devMode) {
            return null;
        }
        String token = extractToken(session);
        if (token == null) {
            return null;
        }
        return jwtTokenProvider.resolveTenantScope(token);
    }

    private void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (IOException e) {
            log.debug("关闭 WS session 失败: {}", e.getMessage());
        }
    }
}

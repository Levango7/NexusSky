package io.aerofleet.cloud.rid;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aerofleet.cloud.rid.model.OperatorIdData;
import io.aerofleet.cloud.rid.model.RidSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Remote ID WebSocket 推送处理器。
 * <p>
 * 使用 {@link CopyOnWriteArrayList} 存储所有活跃的 WebSocket 连接，
 * 当 RID 数据更新时将快照序列化为 JSON 推送到所有连接。
 * <p>
 * 操作者信息在序列化前脱敏（保留前 4 位 + *），防止敏感信息泄露。
 * 推送失败时输出 WARN 日志，不影响其他 session。
 *
 * @see RidSnapshot
 */
@Component
public class RidWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(RidWebSocketHandler.class);

    /** WebSocket 连接列表（线程安全）。 */
    private final CopyOnWriteArrayList<WebSocketSession> sessions = new CopyOnWriteArrayList<>();

    /** JSON 序列化器。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 添加 WebSocket 连接。
     *
     * @param session WebSocket 会话
     */
    public void addSession(WebSocketSession session) {
        sessions.add(session);
        log.info("WebSocket 连接已添加: sessionId={}, 当前连接数={}", session.getId(), sessions.size());
    }

    /**
     * 移除 WebSocket 连接。
     *
     * @param session WebSocket 会话
     */
    public void removeSession(WebSocketSession session) {
        sessions.remove(session);
        log.info("WebSocket 连接已移除: sessionId={}, 当前连接数={}", session.getId(), sessions.size());
    }

    /**
     * 推送 RID 更新到所有 WebSocket 连接。
     * <p>
     * 将 RidSnapshot 序列化为 JSON（操作者信息脱敏后），推送到所有活跃 session。
     * 单个 session 推送失败时输出 WARN 日志，不影响其他 session。
     *
     * @param sysid    无人机系统标识
     * @param snapshot RID 快照
     */
    public void pushRidUpdate(int sysid, RidSnapshot snapshot) {
        if (sessions.isEmpty()) {
            return;
        }

        Map<String, Object> jsonMap = toDesensitizedMap(snapshot);
        String json;
        try {
            json = objectMapper.writeValueAsString(jsonMap);
        } catch (Exception e) {
            log.warn("RID 快照 JSON 序列化失败: sysid={}, error={}", sysid, e.getMessage());
            return;
        }

        TextMessage message = new TextMessage(json);
        for (WebSocketSession session : sessions) {
            if (!session.isOpen()) {
                continue;
            }
            try {
                session.sendMessage(message);
            } catch (IOException e) {
                log.warn("WebSocket 推送失败: sessionId={}, sysid={}, error={}",
                        session.getId(), sysid, e.getMessage());
            }
        }
    }

    /**
     * 将 RidSnapshot 转换为脱敏后的 Map（用于 JSON 序列化）。
     * <p>
     * 操作者 ID 脱敏规则：保留前 4 位字符，其余替换为 *。
     *
     * @param snapshot RID 快照
     * @return 脱敏后的 Map
     */
    private Map<String, Object> toDesensitizedMap(RidSnapshot snapshot) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("sysid", snapshot.sysid());
        map.put("ridStatus", snapshot.ridStatus().name());
        map.put("lastReceivedTime", snapshot.lastReceivedTime());

        if (snapshot.basicId() != null) {
            Map<String, Object> basicId = new LinkedHashMap<>();
            basicId.put("idType", snapshot.basicId().idType());
            basicId.put("uaType", snapshot.basicId().uaType());
            basicId.put("uasId", snapshot.basicId().uasId());
            map.put("basicId", basicId);
        }

        if (snapshot.location() != null) {
            Map<String, Object> location = new LinkedHashMap<>();
            location.put("status", snapshot.location().status());
            location.put("direction", snapshot.location().direction());
            location.put("speedHorizontal", snapshot.location().speedHorizontal());
            location.put("speedVertical", snapshot.location().speedVertical());
            location.put("latitude", snapshot.location().latitude());
            location.put("longitude", snapshot.location().longitude());
            location.put("altitudeBarometric", snapshot.location().altitudeBarometric());
            location.put("altitudeGeodetic", snapshot.location().altitudeGeodetic());
            location.put("heightReference", snapshot.location().heightReference());
            location.put("height", snapshot.location().height());
            location.put("horizontalAccuracy", snapshot.location().horizontalAccuracy());
            location.put("verticalAccuracy", snapshot.location().verticalAccuracy());
            location.put("barometerAccuracy", snapshot.location().barometerAccuracy());
            location.put("speedAccuracy", snapshot.location().speedAccuracy());
            location.put("timestamp", snapshot.location().timestamp());
            map.put("location", location);
        }

        if (snapshot.system() != null) {
            Map<String, Object> system = new LinkedHashMap<>();
            system.put("operatorLocationType", snapshot.system().operatorLocationType());
            system.put("operatorLatitude", snapshot.system().operatorLatitude());
            system.put("operatorLongitude", snapshot.system().operatorLongitude());
            system.put("areaCount", snapshot.system().areaCount());
            system.put("areaRadius", snapshot.system().areaRadius());
            system.put("areaCeiling", snapshot.system().areaCeiling());
            system.put("areaFloor", snapshot.system().areaFloor());
            map.put("system", system);
        }

        if (snapshot.selfId() != null) {
            Map<String, Object> selfId = new LinkedHashMap<>();
            selfId.put("descriptionType", snapshot.selfId().descriptionType());
            selfId.put("description", snapshot.selfId().description());
            map.put("selfId", selfId);
        }

        if (snapshot.operatorId() != null) {
            Map<String, Object> operatorId = new LinkedHashMap<>();
            operatorId.put("operatorIdType", snapshot.operatorId().operatorIdType());
            operatorId.put("operatorId", desensitizeOperatorId(snapshot.operatorId().operatorId()));
            map.put("operatorId", operatorId);
        }

        return map;
    }

    /**
     * 操作者 ID 脱敏：保留前 4 位字符，其余替换为 *。
     *
     * @param operatorId 原始操作者 ID
     * @return 脱敏后的操作者 ID
     */
    static String desensitizeOperatorId(String operatorId) {
        if (operatorId == null || operatorId.length() <= 4) {
            return operatorId;
        }
        return operatorId.substring(0, 4) + "*".repeat(operatorId.length() - 4);
    }
}
package io.aerofleet.cloud.rid;

import io.aerofleet.cloud.rid.model.RidSnapshot;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Remote ID REST API（C2-T18）。
 * <p>
 * 端点：
 * <ul>
 *   <li>GET /api/v1/rid/status/{sysid} — 查询单架无人机 RID 状态</li>
 *   <li>GET /api/v1/rid/status — 查询全部无人机 RID 状态列表</li>
 *   <li>POST /api/v1/rid/config — 更新 RID 配置（操作者信息、自描述文本）</li>
 *   <li>POST /api/v1/rid/broadcast/{sysid}/start — 启动 RID 广播</li>
 *   <li>POST /api/v1/rid/broadcast/{sysid}/stop — 停止 RID 广播</li>
 * </ul>
 * <p>
 * 响应中 operatorId 脱敏（保留前 4 位 + *）。
 */
@RestController
@RequestMapping("/api/v1/rid")
@Tag(name = "Remote ID", description = "Remote ID REST API：RID 状态查询、配置更新、广播控制")
public class RidController {

    private static final Logger log = LoggerFactory.getLogger(RidController.class);

    private final RidStateManager stateManager;
    private final RidConfig config;

    /**
     * 构造 RID 控制器。
     *
     * @param stateManager RID 状态管理器
     * @param config       RID 配置
     */
    public RidController(RidStateManager stateManager, RidConfig config) {
        this.stateManager = stateManager;
        this.config = config;
    }

    /**
     * 查询单架无人机 RID 状态（GET /status/{sysid}）。
     * <p>
     * sysid 不存在时返回 404。
     *
     * @param sysid 无人机系统标识
     * @return RID 状态详情（operatorId 脱敏）
     */
    @GetMapping("/status/{sysid}")
    @Operation(summary = "RID 状态详情", description = "查询单架无人机的 Remote ID 状态详情")
    public ResponseEntity<Map<String, Object>> getStatus(@PathVariable int sysid) {
        RidSnapshot snapshot = stateManager.get(sysid);
        if (snapshot == null) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "sysid=" + sysid + " RID 状态记录不存在");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
        }
        return ResponseEntity.ok(toSnapshotMap(snapshot));
    }

    /**
     * 查询全部无人机 RID 状态列表（GET /status）。
     *
     * @return RID 状态列表（operatorId 脱敏）
     */
    @GetMapping("/status")
    @Operation(summary = "RID 状态列表", description = "查询全部无人机的 Remote ID 状态列表")
    public List<Map<String, Object>> getAllStatus() {
        List<RidSnapshot> all = stateManager.getAll();
        List<Map<String, Object>> result = new ArrayList<>(all.size());
        for (RidSnapshot snapshot : all) {
            result.add(toSnapshotMap(snapshot));
        }
        return result;
    }

    /**
     * 更新 RID 配置（POST /config）。
     * <p>
     * 可更新操作者 ID、操作者纬度/经度、自描述文本。
     *
     * @param request 配置更新请求
     * @return 更新后的配置信息（operatorId 脱敏）
     */
    @PostMapping("/config")
    @Operation(summary = "更新 RID 配置", description = "更新操作者信息、自描述文本等 RID 配置")
    public Map<String, Object> updateConfig(@RequestBody Map<String, Object> request) {
        if (request.containsKey("operatorId")) {
            config.setOperatorId((String) request.get("operatorId"));
        }
        if (request.containsKey("operatorLat")) {
            config.setOperatorLat(((Number) request.get("operatorLat")).doubleValue());
        }
        if (request.containsKey("operatorLon")) {
            config.setOperatorLon(((Number) request.get("operatorLon")).doubleValue());
        }
        if (request.containsKey("defaultSelfId")) {
            config.setDefaultSelfId((String) request.get("defaultSelfId"));
        }

        log.info("RID 配置更新: operatorId={}, operatorLat={}, operatorLon={}, defaultSelfId={}",
                RidWebSocketHandler.desensitizeOperatorId(config.getOperatorId()),
                config.getOperatorLat(), config.getOperatorLon(), config.getDefaultSelfId());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("operatorId", RidWebSocketHandler.desensitizeOperatorId(config.getOperatorId()));
        response.put("operatorLat", config.getOperatorLat());
        response.put("operatorLon", config.getOperatorLon());
        response.put("defaultSelfId", config.getDefaultSelfId());
        return response;
    }

    /**
     * 启动 RID 广播（POST /broadcast/{sysid}/start）。
     * <p>
     * 将指定无人机的 RID 状态设为 BROADCASTING。
     *
     * @param sysid 无人机系统标识
     * @return 操作结果
     */
    @PostMapping("/broadcast/{sysid}/start")
    @Operation(summary = "启动 RID 广播", description = "启动指定无人机的 Remote ID 广播")
    public Map<String, Object> startBroadcast(@PathVariable int sysid) {
        RidSnapshot snapshot = stateManager.get(sysid);
        if (snapshot == null) {
            // 初始化为 BROADCASTING 状态
            stateManager.updateBasicId(sysid, null);
            snapshot = stateManager.get(sysid);
        }

        log.info("RID 广播启动: sysid={}", sysid);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("sysid", sysid);
        response.put("action", "start");
        response.put("ridStatus", snapshot != null ? snapshot.ridStatus().name() : "BROADCASTING");
        return response;
    }

    /**
     * 停止 RID 广播（POST /broadcast/{sysid}/stop）。
     * <p>
     * 将指定无人机的 RID 状态设为 NOT_BROADCASTING。
     * 不允许从 BROADCASTING_ERROR 直接回退到 NOT_BROADCASTING。
     *
     * @param sysid 无人机系统标识
     * @return 操作结果
     */
    @PostMapping("/broadcast/{sysid}/stop")
    @Operation(summary = "停止 RID 广播", description = "停止指定无人机的 Remote ID 广播")
    public Map<String, Object> stopBroadcast(@PathVariable int sysid) {
        RidSnapshot snapshot = stateManager.get(sysid);

        if (snapshot != null && snapshot.ridStatus() == io.aerofleet.cloud.rid.model.RidComplianceState.BROADCASTING_ERROR) {
            log.warn("RID 广播停止被拒绝（BROADCASTING_ERROR 不允许回退到 NOT_BROADCASTING）: sysid={}", sysid);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("sysid", sysid);
            response.put("action", "stop");
            response.put("success", false);
            response.put("error", "BROADCASTING_ERROR 状态不允许直接回退到 NOT_BROADCASTING");
            return response;
        }

        // 移除快照，状态回到 NOT_BROADCASTING（不存在即未广播）
        stateManager.remove(sysid);
        log.info("RID 广播停止: sysid={}", sysid);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("sysid", sysid);
        response.put("action", "stop");
        response.put("success", true);
        response.put("ridStatus", "NOT_BROADCASTING");
        return response;
    }

    // --- 私有辅助方法 ---

    /**
     * 将 RidSnapshot 转换为 Map 格式输出（operatorId 脱敏）。
     *
     * @param snapshot RID 快照
     * @return Map 格式的状态详情
     */
    private Map<String, Object> toSnapshotMap(RidSnapshot snapshot) {
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
            location.put("latitude", snapshot.location().latitude());
            location.put("longitude", snapshot.location().longitude());
            location.put("altitudeBarometric", snapshot.location().altitudeBarometric());
            location.put("altitudeGeodetic", snapshot.location().altitudeGeodetic());
            location.put("direction", snapshot.location().direction());
            location.put("speedHorizontal", snapshot.location().speedHorizontal());
            location.put("speedVertical", snapshot.location().speedVertical());
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
            operatorId.put("operatorId",
                    RidWebSocketHandler.desensitizeOperatorId(snapshot.operatorId().operatorId()));
            map.put("operatorId", operatorId);
        }

        return map;
    }
}
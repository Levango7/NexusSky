package io.aerofleet.cloud.commadapt;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.aerofleet.cloud.api.ApiExceptionHandler.BadRequestException;
import static io.aerofleet.cloud.api.ApiExceptionHandler.NotFoundException;

/**
 * 多模态通信自适应态势可视化 REST API（P2-1）。
 * <p>
 * 端点前缀 {@code /api/comm-adapt}，覆盖：
 * <ul>
 *   <li>{@code GET /quality/{sysid}} — 获取单机通信质量</li>
 *   <li>{@code GET /quality/fleet} — 获取机队通信质量总览</li>
 *   <li>{@code GET /recommendations} — 获取链路切换建议</li>
 *   <li>{@code POST /switch/{sysid}} — 手动切换链路</li>
 *   <li>{@code GET /failover/history/{sysid}} — 获取故障切换历史</li>
 *   <li>{@code GET /topology} — 获取通信拓扑</li>
 *   <li>{@code GET /config} — 获取自适应配置</li>
 *   <li>{@code PUT /config} — 更新配置</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/comm-adapt")
@Tag(name = "CommAdapt", description = "多模态通信自适应 REST API：通信质量监控、链路切换建议、故障切换管理、拓扑可视化")
public class CommSituationController {

    private static final Logger log = LoggerFactory.getLogger(CommSituationController.class);

    private final LinkQualityMonitor monitor;
    private final AdaptiveRouter router;
    private final FailoverManager failoverManager;
    private final CommAdaptConfig config;
    private final DeviceRegistry registry;

    public CommSituationController(LinkQualityMonitor monitor,
                                   AdaptiveRouter router,
                                   FailoverManager failoverManager,
                                   CommAdaptConfig config,
                                   DeviceRegistry registry) {
        this.monitor = monitor;
        this.router = router;
        this.failoverManager = failoverManager;
        this.config = config;
        this.registry = registry;
    }

    /**
     * 获取单机通信质量评分。
     *
     * @param sysid 无人机 systemId
     * @return 综合通信质量评分
     */
    @Operation(summary = "获取单机通信质量", description = "返回指定无人机的综合通信质量评分，包含总体分数、等级、最优链路与各链路详细数据。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "通信质量评分"),
            @ApiResponse(responseCode = "404", description = "无人机未注册或无质量数据")
    })
    @GetMapping("/quality/{sysid}")
    public ResponseEntity<Map<String, Object>> getQuality(@PathVariable("sysid") int sysid) {
        requireRegistered(sysid);
        CommQualityScore score = monitor.getOverallQuality(sysid);
        if (score == null) {
            throw new NotFoundException("no quality data for sysid " + sysid);
        }
        return ResponseEntity.ok(scoreToMap(sysid, score));
    }

    /**
     * 获取机队通信质量总览。
     */
    @Operation(summary = "获取机队通信质量总览", description = "返回所有已采集质量数据的无人机的综合通信质量评分列表。")
    @GetMapping("/quality/fleet")
    public ResponseEntity<Map<String, Object>> getFleetQuality() {
        List<CommQualityScore> fleet = monitor.getFleetQuality();
        List<Map<String, Object>> items = new ArrayList<>(fleet.size());
        for (CommQualityScore score : fleet) {
            Map<LinkQuality.LinkType, LinkQuality> details = score.getDetails();
            int sysid = details != null && !details.isEmpty()
                    ? details.values().iterator().next().getSysid() : 0;
            items.add(scoreToMap(sysid, score));
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("items", items);
        resp.put("total", items.size());
        return ResponseEntity.ok(resp);
    }

    /**
     * 获取链路切换建议。
     */
    @Operation(summary = "获取链路切换建议", description = "返回所有需要切换链路的无人机的切换决策列表。")
    @GetMapping("/recommendations")
    public ResponseEntity<Map<String, Object>> getRecommendations() {
        Map<Integer, SwitchDecision> recommendations = router.getRecommendations();
        List<Map<String, Object>> items = new ArrayList<>(recommendations.size());
        for (Map.Entry<Integer, SwitchDecision> entry : recommendations.entrySet()) {
            items.add(decisionToMap(entry.getValue()));
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("items", items);
        resp.put("total", items.size());
        return ResponseEntity.ok(resp);
    }

    /**
     * 手动切换链路。
     * <p>
     * body: {@code {"targetLink": "SATELLITE"}}
     *
     * @param sysid 无人机 systemId
     * @param body  请求体，包含 targetLink
     * @return 切换结果
     */
    @Operation(summary = "手动切换链路", description = "body: {targetLink: MESH|SATELLITE|CELLULAR}")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "切换结果"),
            @ApiResponse(responseCode = "400", description = "请求体格式错误"),
            @ApiResponse(responseCode = "404", description = "无人机未注册")
    })
    @PostMapping("/switch/{sysid}")
    public ResponseEntity<Map<String, Object>> switchLink(
            @PathVariable("sysid") int sysid,
            @RequestBody Map<String, Object> body) {
        requireRegistered(sysid);
        String targetLinkStr = body.get("targetLink") == null
                ? null : String.valueOf(body.get("targetLink"));
        if (targetLinkStr == null) {
            // P1-fix: 使用 BadRequestException 替代 IllegalArgumentException，返回 HTTP 400
            throw new BadRequestException("targetLink is required");
        }
        LinkQuality.LinkType targetLink;
        try {
            targetLink = LinkQuality.LinkType.valueOf(targetLinkStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            // P1-fix: 使用 BadRequestException 替代 IllegalArgumentException，返回 HTTP 400
            throw new BadRequestException("invalid targetLink: " + targetLinkStr);
        }

        FailoverResult result = failoverManager.executeFailover(sysid, targetLink);
        log.info("Manual switch for sysid={}: {} → {} status={}",
                sysid, result.getFromLink(), result.getToLink(), result.getStatus());

        return ResponseEntity.ok(failoverResultToMap(result));
    }

    /**
     * 获取故障切换历史。
     *
     * @param sysid 无人机 systemId
     * @return 故障切换记录列表
     */
    @Operation(summary = "获取故障切换历史", description = "返回指定无人机的故障切换历史记录。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "故障切换历史列表"),
            @ApiResponse(responseCode = "404", description = "无人机未注册")
    })
    @GetMapping("/failover/history/{sysid}")
    public ResponseEntity<Map<String, Object>> getFailoverHistory(
            @PathVariable("sysid") int sysid) {
        requireRegistered(sysid);
        List<FailoverRecord> history = failoverManager.getFailoverHistory(sysid);
        List<Map<String, Object>> items = new ArrayList<>(history.size());
        for (FailoverRecord r : history) {
            items.add(failoverRecordToMap(r));
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("items", items);
        resp.put("total", items.size());
        return ResponseEntity.ok(resp);
    }

    /**
     * 获取通信拓扑（三种链路的连接状态）。
     */
    @Operation(summary = "获取通信拓扑", description = "返回三种通信链路（mesh/卫星/基站）的连接状态与覆盖范围。")
    @GetMapping("/topology")
    public ResponseEntity<Map<String, Object>> getTopology() {
        Map<String, Object> topology = new LinkedHashMap<>();

        // MESH 拓扑
        Map<String, Object> mesh = new LinkedHashMap<>();
        mesh.put("linkType", "MESH");
        mesh.put("status", "ACTIVE");
        mesh.put("description", "Mesh 自组网，低延迟近距通信");
        mesh.put("priority", 1);
        topology.put("MESH", mesh);

        // SATELLITE 拓扑
        Map<String, Object> satellite = new LinkedHashMap<>();
        satellite.put("linkType", "SATELLITE");
        satellite.put("status", "ACTIVE");
        satellite.put("description", "卫星链路，广域覆盖高延迟通信");
        satellite.put("priority", 2);
        topology.put("SATELLITE", satellite);

        // CELLULAR 拓扑
        Map<String, Object> cellular = new LinkedHashMap<>();
        cellular.put("linkType", "CELLULAR");
        cellular.put("status", "ACTIVE");
        cellular.put("description", "基站蜂窝网络，中等延迟中等覆盖");
        cellular.put("priority", 3);
        topology.put("CELLULAR", cellular);

        // 故障无人机列表
        List<Integer> failedDrones = failoverManager.getFailedDrones();
        topology.put("failedDrones", failedDrones);
        topology.put("failedCount", failedDrones.size());

        return ResponseEntity.ok(topology);
    }

    /**
     * 获取自适应配置。
     */
    @Operation(summary = "获取自适应配置", description = "返回当前通信自适应模块的运行时配置。")
    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getConfig() {
        return ResponseEntity.ok(configToMap(config));
    }

    /**
     * 更新自适应配置。
     * <p>
     * body: {@code {"switchThreshold": 60, "failoverThreshold": 40,
     * "detectionIntervalMs": 5000, "autoSwitchEnabled": true, "minStableTimeMs": 10000}}
     */
    @Operation(summary = "更新自适应配置", description = "字段级合并，未提供的字段保留原值")
    @PutMapping("/config")
    public ResponseEntity<Map<String, Object>> updateConfig(@RequestBody Map<String, Object> body) {
        // P1-fix: 使用原子更新方法，避免 volatile 字段组合写非原子问题
        Integer switchThreshold = body.containsKey("switchThreshold")
                ? toInt(body.get("switchThreshold")) : null;
        Integer failoverThreshold = body.containsKey("failoverThreshold")
                ? toInt(body.get("failoverThreshold")) : null;
        Long detectionIntervalMs = body.containsKey("detectionIntervalMs")
                ? toLong(body.get("detectionIntervalMs")) : null;
        Boolean autoSwitchEnabled = body.containsKey("autoSwitchEnabled")
                ? toBool(body.get("autoSwitchEnabled")) : null;
        Long minStableTimeMs = body.containsKey("minStableTimeMs")
                ? toLong(body.get("minStableTimeMs")) : null;

        config.updateConfig(switchThreshold, failoverThreshold,
                detectionIntervalMs, autoSwitchEnabled, minStableTimeMs);

        log.info("Config updated: {}", config);
        return ResponseEntity.ok(configToMap(config));
    }

    // =====================================================================
    // 序列化辅助
    // =====================================================================

    private static Map<String, Object> scoreToMap(int sysid, CommQualityScore score) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sysid", sysid);
        m.put("overallScore", score.getOverallScore());
        m.put("grade", score.getGrade().name());
        m.put("bestLinkType", score.getBestLinkType().name());
        // 各链路详细数据
        List<Map<String, Object>> linkDetails = new ArrayList<>();
        Map<LinkQuality.LinkType, LinkQuality> details = score.getDetails();
        if (details != null) {
            for (Map.Entry<LinkQuality.LinkType, LinkQuality> entry : details.entrySet()) {
                linkDetails.add(linkQualityToMap(entry.getValue()));
            }
        }
        m.put("details", linkDetails);
        return m;
    }

    private static Map<String, Object> linkQualityToMap(LinkQuality lq) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("linkType", lq.getLinkType().name());
        m.put("latencyMs", lq.getLatencyMs());
        m.put("bandwidthKbps", lq.getBandwidthKbps());
        m.put("rssiDbm", lq.getRssiDbm());
        m.put("packetLossPct", lq.getPacketLossPct());
        m.put("jitterMs", lq.getJitterMs());
        m.put("timestamp", lq.getTimestamp());
        m.put("sysid", lq.getSysid());
        m.put("score", LinkQualityMonitor.calculateLinkScore(lq));
        return m;
    }

    private static Map<String, Object> decisionToMap(SwitchDecision d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sysid", d.getSysid());
        m.put("currentLink", d.getCurrentLink().name());
        m.put("recommendedLink", d.getRecommendedLink().name());
        m.put("currentScore", d.getCurrentScore());
        m.put("recommendedScore", d.getRecommendedScore());
        m.put("reason", d.getReason());
        m.put("urgency", d.getUrgency().name());
        return m;
    }

    private static Map<String, Object> failoverResultToMap(FailoverResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sysid", r.getSysid());
        m.put("fromLink", r.getFromLink().name());
        m.put("toLink", r.getToLink().name());
        m.put("status", r.getStatus().name());
        m.put("timestamp", r.getTimestamp());
        m.put("message", r.getMessage());
        return m;
    }

    private static Map<String, Object> failoverRecordToMap(FailoverRecord r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("sysid", r.getSysid());
        m.put("fromLink", r.getFromLink().name());
        m.put("toLink", r.getToLink().name());
        m.put("triggerTime", r.getTriggerTime());
        m.put("completeTime", r.getCompleteTime());
        m.put("status", r.getStatus().name());
        m.put("reason", r.getReason());
        return m;
    }

    private static Map<String, Object> configToMap(CommAdaptConfig c) {
        CommAdaptConfig.ConfigSnapshot snap = c.snapshot();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("switchThreshold", snap.switchThreshold());
        m.put("failoverThreshold", snap.failoverThreshold());
        m.put("detectionIntervalMs", snap.detectionIntervalMs());
        m.put("autoSwitchEnabled", snap.autoSwitchEnabled());
        m.put("minStableTimeMs", snap.minStableTimeMs());
        return m;
    }

    private static int toInt(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        if (o instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException e) {
                throw new BadRequestException("invalid integer value: " + s);
            }
        }
        return 0;
    }

    private static long toLong(Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        if (o instanceof String s) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException e) {
                throw new BadRequestException("invalid long value: " + s);
            }
        }
        return 0L;
    }

    private static boolean toBool(Object o) {
        if (o instanceof Boolean b) {
            return b;
        }
        if (o instanceof String s) {
            return Boolean.parseBoolean(s);
        }
        return false;
    }

    private void requireRegistered(int sysid) {
        if (registry.get(sysid) == null) {
            throw new NotFoundException("unknown drone sysid " + sysid);
        }
    }
}
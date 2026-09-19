package io.aerofleet.cloud.alarm;

import io.aerofleet.cloud.api.EmergencyOrchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 报警→应急编排桥接（M10 报警联动编排，FR-31）。
 * <p>
 * 将 {@link AlarmEvent} + {@link AlarmLinkageRule} 转换为无人机应急任务请求，
 * 调用 {@link EmergencyOrchService#start(int, int, int, int, List)} 启动编排计划，
 * 并生成侦察任务模板：起飞→飞往报警位置→盘旋侦察→实时回传→返航。
 * <p>
 * 坐标转换：事件经纬度（WGS84 度）→ EmergencyOrchService 1E7 度（int）。
 * 场景映射：FIRE→2(火灾)，其余→3(自定义)。
 *
 * @see AlarmLinkageEngine
 * @see EmergencyOrchService
 */
@Component
public class AlarmToOrchBridge {

    private static final Logger log = LoggerFactory.getLogger(AlarmToOrchBridge.class);

    /** EmergencyOrchService 使用 1E7 度定点整数表示经纬度。 */
    private static final double LATLON_SCALE = 1e7;

    private final EmergencyOrchService emergencyOrchService;

    public AlarmToOrchBridge(EmergencyOrchService emergencyOrchService) {
        this.emergencyOrchService = emergencyOrchService;
    }

    /**
     * 将报警事件 + 联动规则转换为无人机应急任务。
     * <p>
     * 流程：
     * <ol>
     *   <li>根据事件类型映射应急场景（FIRE→火灾，其余→自定义）</li>
     *   <li>将事件经纬度转换为 1E7 度定点整数</li>
     *   <li>根据规则 droneCount 生成无人机 ID 列表</li>
     *   <li>调用 {@link EmergencyOrchService#start} 启动编排计划</li>
     *   <li>生成侦察任务模板 JSON（起飞→飞往报警点→盘旋侦察→实时回传→返航）</li>
     * </ol>
     *
     * @param event 报警事件
     * @param rule  联动规则
     * @return 桥接结果（含 planId 与任务模板），actionType 非 DEPLOY_DRONE 时 planId=-1
     */
    public BridgeResult bridge(AlarmEvent event, AlarmLinkageRule rule) {
        log.info("bridging alarm to orch: eventId={} ruleId={} action={}",
                event.getId(), rule.getId(), rule.getActionType());

        // 非 DEPLOY_DRONE 动作不触发无人机编排
        if (rule.getActionType() != AlarmLinkageRule.ActionType.DEPLOY_DRONE) {
            log.info("skip drone deploy: action={} (non-deploy)", rule.getActionType());
            return new BridgeResult(-1L, buildTaskTemplate(event, rule), "SKIP");
        }

        int scenarioType = mapScenarioType(event.getEventType());
        int centerLat = toE7(clamp(event.getLat(), -90.0, 90.0));
        int centerLon = toE7(clamp(event.getLon(), -180.0, 180.0));
        int radius = (int) Math.max(1, rule.getTargetRadiusM());
        List<Integer> droneIds = generateDroneId(rule.getDroneCount());

        long planId = emergencyOrchService.start(
                scenarioType, centerLat, centerLon, radius, droneIds);

        Map<String, Object> template = buildTaskTemplate(event, rule);
        log.info("alarm bridged to orch: eventId={} planId={} scenario={} drones={}",
                event.getId(), planId, scenarioName(scenarioType),
                droneIds.size());

        return new BridgeResult(planId, template, "DEPLOYED");
    }

    /**
     * 生成侦察任务模板（起飞→飞往报警点→盘旋侦察→实时回传→返航）。
     * <p>
     * 模板字段与 MAVLink Mission Item 对齐，可透传给无人机飞控。
     */
    public Map<String, Object> buildTaskTemplate(AlarmEvent event, AlarmLinkageRule rule) {
        Map<String, Object> template = new LinkedHashMap<>();
        template.put("name", "alarm-scout-" + event.getId());
        template.put("sourceEventId", event.getId());
        template.put("sourceDevice", event.getSourceDeviceId());
        template.put("eventType", event.getEventType().name());
        template.put("severity", event.getSeverity().name());

        // 目标位置：规则指定优先，否则用事件位置
        double targetLat = Double.isNaN(rule.getTargetLat()) ? event.getLat() : rule.getTargetLat();
        double targetLon = Double.isNaN(rule.getTargetLon()) ? event.getLon() : rule.getTargetLon();
        template.put("targetLat", targetLat);
        template.put("targetLon", targetLon);
        template.put("targetRadiusM", rule.getTargetRadiusM());
        template.put("altitudeM", rule.getAltitudeM());
        template.put("droneCount", rule.getDroneCount());

        // 任务阶段序列
        List<Map<String, Object>> phases = new ArrayList<>();
        phases.add(phase("TAKEOFF", "起飞至侦察高度", rule.getAltitudeM()));
        phases.add(phase("WAYPOINT", "飞往报警位置", targetLat, targetLon, rule.getAltitudeM()));
        phases.add(phase("LOITER", "盘旋侦察", rule.getTargetRadiusM(), rule.getAltitudeM()));
        phases.add(phase("STREAM", "实时视频回传", 0));
        phases.add(phase("RTL", "返航降落", 0));
        template.put("phases", phases);

        // 透传规则自定义模板（若存在）
        if (rule.getTaskTemplate() != null && !rule.getTaskTemplate().isEmpty()) {
            template.put("customTemplate", rule.getTaskTemplate());
        }
        return template;
    }

    /** 事件类型 → EmergencyOrchService 场景类型映射。 */
    private static int mapScenarioType(AlarmEvent.EventType eventType) {
        return switch (eventType) {
            case FIRE -> 2;       // 火灾
            default -> 3;         // 自定义
        };
    }

    /** 场景类型 → 名称（与 EmergencyOrchService.scenarioName 对齐，避免跨包访问 package-private 方法）。 */
    private static String scenarioName(int type) {
        return switch (type) {
            case 0 -> "地震";
            case 1 -> "泥石流";
            case 2 -> "火灾";
            case 3 -> "自定义";
            default -> "UNKNOWN";
        };
    }

    /**
     * 经纬度（度）→ 1E7 度定点整数。
     * <p>
     * 调用方应先通过 {@link #clamp(double, double, double)} 将经纬度钳位到合法范围，
     * 避免非法值（如 lat=300）经 ×1E7 后溢出 Integer.MAX_VALUE 变为负数。
     */
    private static int toE7(double deg) {
        long scaled = Math.round(deg * LATLON_SCALE);
        // 防御性溢出保护：若调用方未钳位，此处截断到 int 可表示范围
        if (scaled > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        if (scaled < Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        return (int) scaled;
    }

    /** 将值钳位到 [min, max] 范围。 */
    private static double clamp(double value, double min, double max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    /** 生成 droneCount 个无人机 ID（1..droneCount）。 */
    private static List<Integer> generateDroneId(int count) {
        List<Integer> ids = new ArrayList<>();
        for (int i = 1; i <= Math.max(1, count); i++) {
            ids.add(i);
        }
        return ids;
    }

    private static Map<String, Object> phase(String type, String description, double alt) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", type);
        p.put("description", description);
        p.put("altitudeM", alt);
        return p;
    }

    private static Map<String, Object> phase(String type, String description,
                                             double lat, double lon, double alt) {
        Map<String, Object> p = phase(type, description, alt);
        p.put("lat", lat);
        p.put("lon", lon);
        return p;
    }

    private static Map<String, Object> phase(String type, String description,
                                             double radius, double alt) {
        Map<String, Object> p = phase(type, description, alt);
        p.put("radiusM", radius);
        return p;
    }

    /** 桥接结果。 */
    public static class BridgeResult {
        /** 编排计划 ID（-1 表示未启动编排）。 */
        private final long planId;
        /** 侦察任务模板。 */
        private final Map<String, Object> taskTemplate;
        /** 状态：DEPLOYED/SKIP。 */
        private final String status;

        public BridgeResult(long planId, Map<String, Object> taskTemplate, String status) {
            this.planId = planId;
            this.taskTemplate = taskTemplate;
            this.status = status;
        }

        public long getPlanId() {
            return planId;
        }

        public Map<String, Object> getTaskTemplate() {
            return taskTemplate;
        }

        public String getStatus() {
            return status;
        }
    }
}
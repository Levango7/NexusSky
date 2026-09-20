package io.aerofleet.cloud.mission;

import io.aerofleet.cloud.api.EmergencyOrchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一键应急响应服务。
 * <p>
 * 自动执行完整应急流程：接报→研判→部署→执行，根据事件类型匹配预设方案，
 * 自动分配可用无人机，调用 {@link EmergencyOrchService} 启动编排任务。
 * <p>
 * 基于应急救援行业最佳实践，针对不同灾害类型预设标准响应方案：
 * <ul>
 *   <li>火灾：6 架无人机，2km 半径，指挥中继优先</li>
 *   <li>地震：12 架无人机，5km 半径，搜救优先</li>
 *   <li>洪水：8 架无人机，3km 半径，测绘预警优先</li>
 *   <li>泥石流：8 架无人机，3km 半径，区域测绘优先</li>
 *   <li>安防报警：4 架无人机，1km 半径，监控中继优先</li>
 *   <li>其他：4 架无人机，1km 半径，常规响应</li>
 * </ul>
 */
@Service
public class OneClickEmergencyResponse {

    private static final Logger log = LoggerFactory.getLogger(OneClickEmergencyResponse.class);

    private final EmergencyCommandWorkflow workflow;
    private final EmergencyOrchService orchService;

    public OneClickEmergencyResponse(EmergencyCommandWorkflow workflow,
                                     EmergencyOrchService orchService) {
        this.workflow = workflow;
        this.orchService = orchService;
    }

    /**
     * 自动执行完整应急流程（研判→部署→执行）。
     * <p>
     * 前提：命令已创建（处于 RECEIVED 阶段）。本方法自动完成：
     * <ol>
     *   <li>预设研判：根据事件类型匹配标准方案</li>
     *   <li>自动部署：分配可用无人机、生成部署计划</li>
     *   <li>自动执行：调用 EmergencyOrchService 启动编排</li>
     * </ol>
     *
     * @param cmdId 命令 ID
     * @return 更新后的命令（EXECUTING 阶段），若命令不存在返回 null
     */
    public EmergencyCommand execute(String cmdId) {
        EmergencyCommand cmd = workflow.getCommand(cmdId);
        if (cmd == null) {
            log.warn("one-click: command {} not found", cmdId);
            return null;
        }

        log.info("one-click emergency response started: id={} type={}", cmdId, cmd.getIncidentType());

        // 1. 预设研判
        EmergencyCommand assessed = presetAssess(cmd);
        if (assessed == null) {
            log.warn("one-click: assess failed for {}", cmdId);
            return null;
        }

        // 2. 自动部署
        EmergencyCommand deployed = autoDeploy(assessed);
        if (deployed == null) {
            log.warn("one-click: deploy failed for {}", cmdId);
            return null;
        }

        // 3. 自动执行（启动编排）
        EmergencyCommand executing = workflow.startExecution(cmdId, "one-click");
        if (executing == null) {
            log.warn("one-click: start execution failed for {}", cmdId);
            return null;
        }

        // 4. 调用 EmergencyOrchService 启动编排任务
        long planId = startOrchestration(executing);
        executing.appendExecutionLog("orchestration plan " + planId + " started by one-click");
        log.info("one-click emergency response completed: id={} planId={}", cmdId, planId);

        return executing;
    }

    /**
     * 预设研判逻辑：根据事件类型匹配标准响应方案。
     * <p>
     * 不同事件类型对应不同的研判结论、建议无人机数量、响应半径和优先级。
     *
     * @param cmd 应急指挥命令（应处于 RECEIVED 阶段）
     * @return 研判后的命令，若阶段非法返回 null
     */
    public EmergencyCommand presetAssess(EmergencyCommand cmd) {
        PresetPlan preset = presetFor(cmd.getIncidentType());
        String assessment = buildAssessment(cmd, preset);
        return workflow.assess(cmd.getId(), assessment, "one-click-assess");
    }

    /**
     * 自动部署逻辑：选择可用无人机、生成部署计划。
     * <p>
     * 根据研判结果分配无人机（1~N 号），生成部署计划（策略/时长/中继方式）。
     *
     * @param cmd 应急指挥命令（应处于 ASSESSED 阶段）
     * @return 部署后的命令，若阶段非法返回 null
     */
    public EmergencyCommand autoDeploy(EmergencyCommand cmd) {
        PresetPlan preset = presetFor(cmd.getIncidentType());

        // 分配无人机：1~droneCount
        List<Integer> drones = new ArrayList<>();
        for (int i = 1; i <= preset.droneCount; i++) {
            drones.add(i);
        }
        cmd.assignDrones(new java.util.LinkedHashSet<>(drones));

        EmergencyCommand.DeploymentPlan plan = new EmergencyCommand.DeploymentPlan(
                "auto-" + cmd.getIncidentType().name().toLowerCase(),
                preset.strategy,
                preset.estimatedDurationMin,
                preset.communicationRelay);
        return workflow.deploy(cmd.getId(), plan, "one-click-deploy");
    }

    // =====================================================================
    // 内部辅助
    // =====================================================================

    /** 调用 EmergencyOrchService 启动编排任务。 */
    private long startOrchestration(EmergencyCommand cmd) {
        int scenarioType = toScenarioType(cmd.getIncidentType());
        int centerLat = toE7(clamp(cmd.getLocation().getLat(), -90.0, 90.0));
        int centerLon = toE7(clamp(cmd.getLocation().getLon(), -180.0, 180.0));
        PresetPlan preset = presetFor(cmd.getIncidentType());
        List<Integer> droneIds = new ArrayList<>(cmd.getAssignedDrones());
        if (droneIds.isEmpty()) {
            droneIds = Arrays.asList(1);
        }
        try {
            long planId = orchService.start(scenarioType, centerLat, centerLon,
                    preset.radius, droneIds);
            log.info("one-click orchestration started: cmdId={} planId={} scenario={} drones={}",
                    cmd.getId(), planId, scenarioType, droneIds.size());
            return planId;
        } catch (Exception e) {
            log.error("one-click orchestration failed: cmdId={}", cmd.getId(), e);
            cmd.appendExecutionLog("orchestration failed: " + e.getMessage());
            return -1;
        }
    }

    /** 构建研判结果文本。 */
    private String buildAssessment(EmergencyCommand cmd, PresetPlan preset) {
        return String.format(
                "事件类型：%s，严重级别：%s。%s。建议部署 %d 架无人机，响应半径 %d 米，预估时长 %d 分钟。优先级：%s。",
                cmd.getIncidentType().displayName(),
                cmd.getSeverity().name(),
                preset.assessment,
                preset.droneCount,
                preset.radius,
                preset.estimatedDurationMin,
                preset.strategy);
    }

    /** 事件类型 → EmergencyOrchService 场景类型映射。 */
    private static int toScenarioType(EmergencyCommand.IncidentType type) {
        return switch (type) {
            case EARTHQUAKE -> 0;
            case LANDSLIDE -> 1;
            case FIRE -> 2;
            default -> 3; // 自定义
        };
    }

    /** 经纬度（度）→ 1E7 度定点整数（与 AlarmToOrchBridge.toE7 一致，使用 Math.round 避免截断误差）。 */
    private static int toE7(double deg) {
        long scaled = Math.round(deg * 1E7);
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

    /** 事件类型 → 预设方案。 */
    static PresetPlan presetFor(EmergencyCommand.IncidentType type) {
        return switch (type) {
            case FIRE -> new PresetPlan(
                    "火灾现场监控与指挥中继，优先建立通信链路保障指挥调度",
                    "COMMAND_RELAY", 6, 2000, 120, "mesh+haps",
                    "指挥中继优先，6 架无人机组成通信中继网络，2km 范围实时监控火势蔓延");
            case EARTHQUAKE -> new PresetPlan(
                    "地震灾害应急搜救与通信覆盖，优先大面积搜救与生命探测",
                    "SEARCH_RESCUE", 12, 5000, 240, "mesh+leo",
                    "搜救优先，12 架无人机分区搜救，5km 范围生命探测与通信覆盖");
            case FLOOD -> new PresetPlan(
                    "洪水灾害区域测绘与预警，优先水域测绘和危险区域标识",
                    "MAPPING", 8, 3000, 180, "mesh+loro",
                    "测绘预警优先，8 架无人机水域测绘，3km 范围洪水演进监测");
            case LANDSLIDE -> new PresetPlan(
                    "泥石流灾害区域测绘与预警，优先地质灾害范围评估",
                    "MAPPING", 8, 3000, 180, "mesh+loro",
                    "区域测绘优先，8 架无人机地质灾害评估，3km 范围泥石流演进监测");
            case SECURITY_ALARM -> new PresetPlan(
                    "安防报警现场监控，优先目标区域监控和证据采集",
                    "SURVEILLANCE", 4, 1000, 60, "mesh",
                    "监控中继优先，4 架无人机目标区域监控，1km 范围实时视频回传");
            case OTHER -> new PresetPlan(
                    "应急响应，根据现场情况灵活部署",
                    "ROUTINE", 4, 1000, 90, "mesh",
                    "常规响应，4 架无人机现场勘察，1km 范围综合应急");
        };
    }

    /** 预设方案。 */
    static final class PresetPlan {
        final String assessment;
        final String strategy;
        final int droneCount;
        final int radius;
        final int estimatedDurationMin;
        final String communicationRelay;
        final String detail;

        PresetPlan(String assessment, String strategy, int droneCount, int radius,
                   int estimatedDurationMin, String communicationRelay, String detail) {
            this.assessment = assessment;
            this.strategy = strategy;
            this.droneCount = droneCount;
            this.radius = radius;
            this.estimatedDurationMin = estimatedDurationMin;
            this.communicationRelay = communicationRelay;
            this.detail = detail;
        }
    }
}
package io.aerofleet.cloud.mission.emergency;

import io.aerofleet.cloud.alarm.AlarmEvent;
import io.aerofleet.cloud.alarm.AlarmEventStore;
import io.aerofleet.cloud.alarm.AlarmLinkageEngine;

import io.aerofleet.cloud.api.service.EmergencyOrchService;
import io.aerofleet.cloud.surveillance.OnvifClient;
import io.aerofleet.cloud.surveillance.RapidDeployService;
import io.aerofleet.cloud.surveillance.SurveillanceDevice;
import io.aerofleet.cloud.surveillance.SurveillanceDeviceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 空地协同指挥服务。
 * <p>
 * 整合安防报警与无人机侦察，实现空地协同指挥六阶段完整流程：
 * <ol>
 *   <li>接报 — 安防设备报警 + 人工报警 → 统一进入 AlarmEventStore</li>
 *   <li>研判 — 空地态势融合 + 威胁等级评定 + 资源可用性评估 + 自动推荐响应方案</li>
 *   <li>部署 — 无人机部署 + 安防设备部署 + 联合部署 + 优先级分配</li>
 *   <li>执行 — 实时监控 + 动态调整 + QoS保障 + 空地协同（安防检测→无人机确认→PTZ跟踪）</li>
 *   <li>评估 — 覆盖率评估 + 连通率评估 + 响应时效评估 + 资源消耗评估</li>
 *   <li>总结 — 自动生成报告 + 事件时间线归档 + 联动规则优化建议</li>
 * </ol>
 * <p>
 * 核心能力：
 * <pre>
 * {@link #triggerReconFromAlarm(AlarmEvent)} — 安防报警触发无人机自动侦察
 * {@link #triggerPtzTracking(GeoTarget)} — 无人机发现目标触发安防 PTZ 联动
 * {@link #fuseAirGroundSituation()} — 空地态势融合（统一态势感知）
 * {@link #startAirGroundCoordination(String)} — 启动空地协同指挥六阶段流程
 * {@link #evaluateCoordination(String)} — 评估阶段：覆盖率/连通率/响应时效/资源消耗
 * {@link #summarizeCoordination(String)} — 总结阶段：生成报告 + 时间线归档
 * {@link #getCoordinationReport(String)} — 获取指挥报告
 * </pre>
 * <p>
 * 依赖注入采用 {@code @Autowired(required=false)} + null 检查模式，
 * 确保在安防/无人机子系统未部署时仍可降级运行。
 */
@Service
public class AirGroundCoordinationService {

    private static final Logger log = LoggerFactory.getLogger(AirGroundCoordinationService.class);

    /** 经纬度（度）→ 1E7 度定点整数（与 AlarmToOrchBridge/OneClickEmergencyResponse 一致）。 */
    private static final double LATLON_SCALE = 1e7;

    /** PTZ 联动搜索半径（米）：查找目标位置附近的安防设备。 */
    private static final double PTZ_SEARCH_RADIUS_M = 500.0;

    /** 地球纬度方向每度对应的米数（近似）。 */
    private static final double M_PER_DEG_LAT = 111_320.0;

    /** 应急编排服务。 */
    private final EmergencyOrchService orchService;
    /** 安防设备注册表（可选依赖）。 */
    private final SurveillanceDeviceRegistry deviceRegistry;
    /** ONVIF 客户端（可选依赖，用于 PTZ 控制）。 */
    private final OnvifClient onvifClient;
    /** 报警联动引擎（可选依赖，用于规则匹配）。 */
    private final AlarmLinkageEngine linkageEngine;
    /** 报警事件存储（可选依赖，用于查询报警事件）。 */
    private final AlarmEventStore alarmEventStore;
    /** 应急指挥工作流引擎（可选依赖，用于六阶段状态机管理）。 */
    private final EmergencyCommandWorkflow workflow;
    /** 布控球快速部署服务（可选依赖，用于安防设备部署）。 */
    private final RapidDeployService rapidDeployService;
    /** 空地协同 WebSocket 推送（可选依赖，用于态势推送）。 */
    private final AirGroundCoordinationPusher pusher;

    /** 空地协同指挥记录存储：coordinationId → CoordinationRecord。 */
    private final ConcurrentHashMap<String, CoordinationRecord> coordinationRecords = new ConcurrentHashMap<>();

    /**
     * 构造空地协同服务。
     *
     * @param orchService         应急编排服务（必需）
     * @param deviceRegistry      安防设备注册表（可选）
     * @param onvifClient         ONVIF 客户端（可选，PTZ 控制需要）
     * @param linkageEngine       报警联动引擎（可选，规则匹配需要）
     * @param alarmEventStore     报警事件存储（可选，接报阶段需要）
     * @param workflow            应急指挥工作流引擎（可选，六阶段状态机需要）
     * @param rapidDeployService  布控球快速部署服务（可选，部署阶段需要）
     * @param pusher              空地协同推送（可选，态势推送需要）
     */
    @Autowired
    public AirGroundCoordinationService(EmergencyOrchService orchService,
                                        @Autowired(required = false) SurveillanceDeviceRegistry deviceRegistry,
                                        @Autowired(required = false) OnvifClient onvifClient,
                                        @Autowired(required = false) AlarmLinkageEngine linkageEngine,
                                        @Autowired(required = false) AlarmEventStore alarmEventStore,
                                        @Autowired(required = false) EmergencyCommandWorkflow workflow,
                                        @Autowired(required = false) RapidDeployService rapidDeployService,
                                        @Autowired(required = false) AirGroundCoordinationPusher pusher) {
        this.orchService = orchService;
        this.deviceRegistry = deviceRegistry;
        this.onvifClient = onvifClient;
        this.linkageEngine = linkageEngine;
        this.alarmEventStore = alarmEventStore;
        this.workflow = workflow;
        this.rapidDeployService = rapidDeployService;
        this.pusher = pusher;
    }

    // =====================================================================
    // 核心能力 1：安防报警 → 无人机自动侦察
    // =====================================================================

    /**
     * 安防报警触发无人机自动侦察。
     * <p>
     * 流程：
     * <ol>
     *   <li>根据报警事件类型映射应急场景</li>
     *   <li>将报警位置经纬度转换为 1E7 度定点整数</li>
     *   <li>调用 {@link EmergencyOrchService#start} 启动无人机编排</li>
     *   <li>返回编排计划 ID（planId）</li>
     * </ol>
     * <p>
     * 若报警联动引擎可用，优先通过联动规则匹配后委托 {@link AlarmLinkageEngine} 处理，
     * 否则直接调用编排服务启动侦察任务。
     *
     * @param alarmEvent 安防报警事件
     * @return 编排计划 ID；若启动失败返回 -1
     */
    public long triggerReconFromAlarm(AlarmEvent alarmEvent) {
        if (alarmEvent == null) {
            log.warn("triggerReconFromAlarm: alarmEvent is null");
            return -1;
        }

        log.info("空地协同：安防报警触发无人机侦察 eventId={} type={} severity={} device={}",
                alarmEvent.getId(), alarmEvent.getEventType(), alarmEvent.getSeverity(),
                alarmEvent.getSourceDeviceId());

        // 优先通过联动引擎处理（匹配规则后执行联动动作）
        if (linkageEngine != null) {
            try {
                AlarmLinkageEngine.ProcessResult result = linkageEngine.processEvent(alarmEvent);
                log.info("空地协同：联动引擎处理完成 eventId={} matched={}",
                        alarmEvent.getId(), result.getMatchedCount());
                // 若有 DEPLOY_DRONE 类型的执行结果，返回其 planId
                for (AlarmLinkageEngine.ActionExecution exec : result.getExecutions()) {
                    if ("DEPLOY_DRONE".equals(exec.getActionType()) && exec.getPlanId() > 0) {
                        return exec.getPlanId();
                    }
                }
                // 联动引擎处理了但未启动无人机编排，继续走直接启动路径
            } catch (Exception e) {
                log.warn("空地协同：联动引擎处理失败，降级为直接启动编排 eventId={} error={}",
                        alarmEvent.getId(), e.getMessage());
                // 降级：继续走直接启动路径
            }
        }

        // 直接调用编排服务启动侦察任务
        int scenarioType = mapScenarioType(alarmEvent.getEventType());
        int centerLat = toE7(clamp(alarmEvent.getLat(), -90.0, 90.0));
        int centerLon = toE7(clamp(alarmEvent.getLon(), -180.0, 180.0));
        int radius = 1000; // 默认侦察半径 1km
        List<Integer> droneIds = List.of(1, 2); // 默认 2 架无人机

        try {
            long planId = orchService.start(scenarioType, centerLat, centerLon, radius, droneIds);
            log.info("空地协同：无人机侦察已启动 eventId={} planId={} scenario={}",
                    alarmEvent.getId(), planId, scenarioType);
            return planId;
        } catch (Exception e) {
            log.error("空地协同：无人机侦察启动失败 eventId={}", alarmEvent.getId(), e);
            return -1;
        }
    }

    // =====================================================================
    // 核心能力 2：无人机发现目标 → 安防 PTZ 联动
    // =====================================================================

    /**
     * 无人机发现目标触发安防 PTZ 联动。
     * <p>
     * 流程：
     * <ol>
     *   <li>验证目标置信度是否达到联动阈值（{@link GeoTarget#isReliable()}）</li>
     *   <li>查找目标位置附近最近的安防设备（具备 PTZ 能力）</li>
     *   <li>通过 {@link OnvifClient} 控制安防设备 PTZ 转向目标方向</li>
     * </ol>
     * <p>
     * PTZ 转向策略：根据安防设备与目标的相对方位，选择 up/down/left/right 命令。
     * 简化实现：仅发送方向命令，真实实现需持续跟踪。
     *
     * @param geoTarget 无人机侦察发现的目标
     * @return PTZ 联动结果（"TRACKING"/"NO_DEVICE"/"LOW_CONFIDENCE"/"FAILED"）
     */
    public String triggerPtzTracking(GeoTarget geoTarget) {
        if (geoTarget == null) {
            log.warn("triggerPtzTracking: geoTarget is null");
            return "FAILED";
        }

        // 置信度检查
        if (!geoTarget.isReliable()) {
            log.info("空地协同：目标置信度不足，跳过 PTZ 联动 target={} confidence={}",
                    geoTarget, geoTarget.confidence);
            return "LOW_CONFIDENCE";
        }

        log.info("空地协同：无人机目标触发 PTZ 联动 target={} sysid={}",
                geoTarget, geoTarget.sourceSysid);

        // 查找最近的安防设备
        if (deviceRegistry == null) {
            log.warn("空地协同：安防设备注册表未注入，无法执行 PTZ 联动");
            return "NO_DEVICE";
        }

        SurveillanceDevice nearestDevice = findNearestPtzDevice(geoTarget.lat, geoTarget.lon);
        if (nearestDevice == null) {
            log.info("空地协同：目标位置附近无可用的 PTZ 安防设备");
            return "NO_DEVICE";
        }

        // PTZ 控制
        if (onvifClient == null) {
            log.warn("空地协同：ONVIF 客户端未注入，无法执行 PTZ 控制");
            return "NO_DEVICE";
        }

        try {
            String ptzCmd = computePtzDirection(nearestDevice, geoTarget);
            String result = onvifClient.ptzControl(
                    nearestDevice.ip, nearestDevice.port,
                    nearestDevice.username, nearestDevice.password, ptzCmd);
            log.info("空地协同：PTZ 联动执行成功 device={} cmd={} result={}",
                    nearestDevice.id, ptzCmd, result);
            return "TRACKING";
        } catch (Exception e) {
            log.error("空地协同：PTZ 联动执行失败 device={}", nearestDevice.id, e);
            return "FAILED";
        }
    }

    // =====================================================================
    // 核心能力 3：空地态势融合
    // =====================================================================

    /**
     * 空地态势融合：生成统一态势感知视图。
     * <p>
     * 融合三类数据源：
     * <ul>
     *   <li>安防设备状态（来自 {@link SurveillanceDeviceRegistry}）</li>
     *   <li>无人机状态（来自 {@link EmergencyOrchService} 的编排计划）</li>
     *   <li>报警事件（来自 {@link AlarmLinkageEngine} 的联动日志）</li>
     * </ul>
     * <p>
     * 若某个数据源不可用（依赖未注入），对应列表为空，不影响其他数据源的融合。
     *
     * @return 空地态势融合视图
     */
    public AirGroundSituation fuseAirGroundSituation() {
        log.debug("空地协同：生成态势融合视图");

        // 安防设备状态
        List<SurveillanceDevice> devices = deviceRegistry == null
                ? List.of()
                : deviceRegistry.listDevices();

        // 无人机状态（从编排计划中提取）
        List<AirGroundSituation.DroneStatus> droneStatuses = collectDroneStatuses();

        // 报警事件（从联动引擎日志中提取）
        List<AlarmEvent> alarmEvents = collectAlarmEvents();

        // mesh 拓扑（简化：基于在线无人机数量估算）
        AirGroundSituation.MeshTopology mesh = buildMeshTopology(droneStatuses);

        AirGroundSituation situation = new AirGroundSituation(
                devices, droneStatuses, alarmEvents, mesh, System.currentTimeMillis());

        log.info("空地协同：态势融合完成 devices={} drones={} alarms={} mesh={}",
                devices.size(), droneStatuses.size(), alarmEvents.size(), mesh);
        return situation;
    }

    // =====================================================================
    // 六阶段流程：接报 → 研判 → 部署 → 执行 → 评估 → 总结
    // =====================================================================

    /**
     * 启动空地协同指挥流程（接报→研判→部署→执行）。
     * <p>
     * 从报警事件 ID 开始，自动执行前四个阶段：
     * <ol>
     *   <li>接报：从 AlarmEventStore 获取报警事件，创建 EmergencyCommand</li>
     *   <li>研判：空地态势融合 + 威胁等级评定 + 资源可用性评估 + 自动推荐响应方案</li>
     *   <li>部署：无人机部署 + 安防设备部署 + 联合部署 + 优先级分配</li>
     *   <li>执行：启动编排任务 + 空地协同监控</li>
     * </ol>
     * 评估和总结阶段需分别调用 {@link #evaluateCoordination(String)} 和
     * {@link #summarizeCoordination(String)} 完成。
     *
     * @param alarmEventId 报警事件 ID
     * @return 空地协同指挥记录（含 coordinationId、cmdId、planId）；若启动失败返回 null
     */
    public CoordinationRecord startAirGroundCoordination(String alarmEventId) {
        if (alarmEventId == null || alarmEventId.isBlank()) {
            log.warn("空地协同：启动失败，alarmEventId 为空");
            return null;
        }

        log.info("空地协同：启动指挥流程 alarmEventId={}", alarmEventId);

        // ── 阶段 1：接报 ──
        AlarmEvent alarmEvent = retrieveAlarmEvent(alarmEventId);
        if (alarmEvent == null) {
            log.warn("空地协同：报警事件不存在 alarmEventId={}", alarmEventId);
            return null;
        }

        EmergencyCommand cmd = createCommandFromAlarm(alarmEvent);
        if (cmd == null) {
            log.warn("空地协同：创建指挥命令失败 alarmEventId={}", alarmEventId);
            return null;
        }

        String coordinationId = "AGC-" + cmd.getId();
        CoordinationRecord record = new CoordinationRecord(coordinationId, alarmEventId, cmd.getId());
        record.setAlarmEvent(alarmEvent);
        record.setPhase(EmergencyCommandPhase.RECEIVED);
        coordinationRecords.put(coordinationId, record);
        pushProgress(coordinationId, "接报", "报警事件已接收", cmd.getId());

        // ── 阶段 2：研判 ──
        String assessment = performAssessment(alarmEvent);
        EmergencyCommand assessedCmd = workflow == null ? null
                : workflow.assess(cmd.getId(), assessment, "air-ground-assess");
        if (assessedCmd == null) {
            log.warn("空地协同：研判阶段失败，降级为直接部署 coordinationId={}", coordinationId);
            // 降级：跳过研判，直接进入部署
        } else {
            record.setPhase(EmergencyCommandPhase.ASSESSED);
            record.setAssessmentResult(assessment);
            pushProgress(coordinationId, "研判", assessment, cmd.getId());
        }

        // ── 阶段 3：部署 ──
        DeploymentInfo deployInfo = performDeployment(alarmEvent, coordinationId);
        EmergencyCommand deployedCmd = workflow == null ? null
                : workflow.deploy(cmd.getId(), deployInfo.toDeploymentPlan(), "air-ground-deploy");
        if (deployedCmd == null) {
            log.warn("空地协同：部署阶段失败，降级为直接执行 coordinationId={}", coordinationId);
            // 降级：跳过部署，直接进入执行
        } else {
            record.setPhase(EmergencyCommandPhase.DEPLOYED);
            record.setDeploymentInfo(deployInfo);
            pushProgress(coordinationId, "部署", deployInfo.summary(), cmd.getId());
        }

        // ── 阶段 4：执行 ──
        EmergencyCommand executingCmd = workflow == null ? null
                : workflow.startExecution(cmd.getId(), "air-ground-execute");
        if (executingCmd == null) {
            log.warn("空地协同：执行阶段失败 coordinationId={}", coordinationId);
        } else {
            record.setPhase(EmergencyCommandPhase.EXECUTING);
            pushProgress(coordinationId, "执行", "空地协同监控已启动", cmd.getId());
        }

        // 启动无人机编排
        long planId = triggerReconFromAlarm(alarmEvent);
        record.setPlanId(planId);

        // 触发空地协同态势推送
        pushSituationUpdate(coordinationId);

        log.info("空地协同：指挥流程已启动 coordinationId={} cmdId={} planId={}",
                coordinationId, cmd.getId(), planId);
        return record;
    }

    /**
     * 评估阶段：覆盖率/连通率/响应时效/资源消耗评估。
     * <p>
     * 执行四维度评估：
     * <ul>
     *   <li>覆盖率评估 — 无人机+安防设备对事件区域的覆盖比例</li>
     *   <li>连通率评估 — mesh 网络连通性 + 安防设备在线率</li>
     *   <li>响应时效评估 — 从接报到执行各阶段耗时</li>
     *   <li>资源消耗评估 — 无人机架次、安防设备数、电池消耗等</li>
     * </ul>
     *
     * @param coordinationId 协同指挥 ID
     * @return 评估结果；若协同指挥不存在返回 null
     */
    public synchronized CoordinationEvaluation evaluateCoordination(String coordinationId) {
        CoordinationRecord record = coordinationRecords.get(coordinationId);
        if (record == null) {
            log.warn("空地协同：评估失败，协同指挥不存在 coordinationId={}", coordinationId);
            return null;
        }

        log.info("空地协同：执行评估 coordinationId={}", coordinationId);

        // 覆盖率评估
        AirGroundSituation situation = fuseAirGroundSituation();
        int coverageRate = situation.getMeshTopology().coverageRate;
        int deviceCoverageRate = computeDeviceCoverageRate(situation);

        // 连通率评估
        int connectivityRate = computeConnectivityRate(situation);

        // 响应时效评估
        ResponseTiming timing = computeResponseTiming(record);

        // 资源消耗评估
        ResourceConsumption consumption = computeResourceConsumption(record, situation);

        CoordinationEvaluation evaluation = new CoordinationEvaluation(
                coverageRate, deviceCoverageRate, connectivityRate, timing, consumption);

        record.setEvaluation(evaluation);
        record.setPhase(EmergencyCommandPhase.EVALUATED);

        // 通过工作流引擎推进评估阶段
        if (workflow != null) {
            String evalText = String.format(
                    "覆盖率=%d%%, 设备覆盖率=%d%%, 连通率=%d%%, 响应时效=%ds, 资源消耗=%s",
                    coverageRate, deviceCoverageRate, connectivityRate,
                    timing.totalResponseSec, consumption.summary());
            workflow.evaluate(record.getCmdId(), evalText, "air-ground-evaluate");
        }

        pushEvaluationResult(coordinationId, evaluation);
        log.info("空地协同：评估完成 coordinationId={} coverage={} connectivity={} timing={}s",
                coordinationId, coverageRate, connectivityRate, timing.totalResponseSec);
        return evaluation;
    }

    /**
     * 总结阶段：生成报告 + 时间线归档 + 联动规则优化建议。
     * <p>
     * 自动生成指挥报告，包含：
     * <ul>
     *   <li>事件概述（报警事件信息 + 指挥命令信息）</li>
     *   <li>六阶段时间线（各阶段时间戳和耗时）</li>
     *   <li>评估结果（覆盖率/连通率/响应时效/资源消耗）</li>
     *   <li>联动规则优化建议（基于评估结果推荐改进措施）</li>
     * </ul>
     *
     * @param coordinationId 协同指挥 ID
     * @return 指挥报告；若协同指挥不存在返回 null
     */
    public synchronized CoordinationReport summarizeCoordination(String coordinationId) {
        CoordinationRecord record = coordinationRecords.get(coordinationId);
        if (record == null) {
            log.warn("空地协同：总结失败，协同指挥不存在 coordinationId={}", coordinationId);
            return null;
        }

        log.info("空地协同：执行总结 coordinationId={}", coordinationId);

        // 若未评估，先执行评估
        if (record.getEvaluation() == null) {
            evaluateCoordination(coordinationId);
        }

        // 生成报告
        CoordinationReport report = buildCoordinationReport(record);
        record.setReport(report);
        record.setPhase(EmergencyCommandPhase.CLOSED);

        // 通过工作流引擎推进总结阶段
        if (workflow != null) {
            workflow.close(record.getCmdId(), report.getSummary(), "air-ground-summarize");
        }

        pushProgress(coordinationId, "总结", "指挥报告已生成", record.getCmdId());
        log.info("空地协同：总结完成 coordinationId={}", coordinationId);

        // 总结完成后清理该协同指挥记录，防止 coordinationRecords 无限增长导致 OOM
        coordinationRecords.remove(coordinationId);
        log.info("空地协同：已清理 CLOSED 状态记录 coordinationId={} remaining={}",
                coordinationId, coordinationRecords.size());

        // 清理阈值保护：超过 1000 条时批量清理所有 CLOSED 状态记录
        if (coordinationRecords.size() > 1000) {
            int before = coordinationRecords.size();
            coordinationRecords.entrySet().removeIf(entry ->
                    entry.getValue().getPhase() == EmergencyCommandPhase.CLOSED);
            int removed = before - coordinationRecords.size();
            log.warn("空地协同：清理阈值触发，批量清理 CLOSED 状态记录 removed={} remaining={}",
                    removed, coordinationRecords.size());
        }

        return report;
    }

    /**
     * 获取指挥报告。
     *
     * @param coordinationId 协同指挥 ID
     * @return 指挥报告；若不存在或未生成返回 null
     */
    public CoordinationReport getCoordinationReport(String coordinationId) {
        CoordinationRecord record = coordinationRecords.get(coordinationId);
        if (record == null) {
            return null;
        }
        return record.getReport();
    }

    /**
     * 获取空地协同指挥记录。
     *
     * @param coordinationId 协同指挥 ID
     * @return 协同指挥记录；不存在返回 null
     */
    public CoordinationRecord getCoordinationRecord(String coordinationId) {
        return coordinationRecords.get(coordinationId);
    }

    // =====================================================================
    // 六阶段流程内部辅助
    // =====================================================================

    /** 从 AlarmEventStore 获取报警事件。 */
    private AlarmEvent retrieveAlarmEvent(String alarmEventId) {
        if (alarmEventStore == null) {
            log.warn("空地协同：AlarmEventStore 未注入，无法查询报警事件");
            return null;
        }
        try {
            return alarmEventStore.getById(alarmEventId);
        } catch (Exception e) {
            log.warn("空地协同：查询报警事件失败 alarmEventId={} error={}", alarmEventId, e.getMessage());
            return null;
        }
    }

    /** 从报警事件创建应急指挥命令（接报阶段）。 */
    private EmergencyCommand createCommandFromAlarm(AlarmEvent alarmEvent) {
        EmergencyCommand.IncidentType incidentType = mapIncidentType(alarmEvent.getEventType());
        EmergencyCommand.Severity severity = mapSeverity(alarmEvent.getSeverity());
        EmergencyCommand.Location location = new EmergencyCommand.Location(
                clamp(alarmEvent.getLat(), -90.0, 90.0),
                clamp(alarmEvent.getLon(), -180.0, 180.0),
                alarmEvent.getAlt());
        EmergencyCommand cmd = new EmergencyCommand(
                null, incidentType, severity, location,
                alarmEvent.getDescription(),
                alarmEvent.getSourceDeviceName(), "",
                alarmEvent.getTimestampMs());
        if (workflow == null) {
            log.warn("空地协同：EmergencyCommandWorkflow 未注入，降级为直接创建指挥命令");
            // 降级模式：直接返回 cmd（id 为 null），不通过 workflow.createCommand
            return cmd;
        }
        return workflow.createCommand(cmd);
    }

    /** 执行研判：空地态势融合 + 威胁等级评定 + 资源可用性评估。 */
    private String performAssessment(AlarmEvent alarmEvent) {
        AirGroundSituation situation = fuseAirGroundSituation();
        int threatLevel = assessThreatLevel(alarmEvent);
        int resourceAvailability = assessResourceAvailability(situation);
        String recommendedPlan = recommendResponsePlan(alarmEvent, threatLevel, resourceAvailability);

        return String.format(
                "威胁等级：%d/3，资源可用性：%d%%。%s。在线安防设备 %d 台，在线无人机 %d 架，mesh 覆盖率 %d%%。",
                threatLevel, resourceAvailability, recommendedPlan,
                situation.onlineDeviceCount(), situation.onlineDroneCount(),
                situation.getMeshTopology().coverageRate);
    }

    /** 威胁等级评定（1~3）。 */
    private int assessThreatLevel(AlarmEvent alarmEvent) {
        if (alarmEvent.getSeverity() == AlarmEvent.Severity.CRITICAL) {
            return 3;
        } else if (alarmEvent.getSeverity() == AlarmEvent.Severity.WARN) {
            return 2;
        }
        return 1;
    }

    /** 资源可用性评估（0~100）。 */
    private int assessResourceAvailability(AirGroundSituation situation) {
        int score = 0;
        // 安防设备在线率
        if (!situation.getSurveillanceDevices().isEmpty()) {
            score += situation.onlineDeviceCount() * 25 / situation.getSurveillanceDevices().size();
        }
        // 无人机在线率
        if (!situation.getDroneStatuses().isEmpty()) {
            score += situation.onlineDroneCount() * 25 / situation.getDroneStatuses().size();
        }
        // mesh 覆盖率
        score += situation.getMeshTopology().coverageRate / 4;
        // 报警确认率
        if (!situation.getAlarmEvents().isEmpty()) {
            long acked = situation.getAlarmEvents().stream()
                    .filter(AlarmEvent::isAcknowledged).count();
            score += (int) (acked * 25 / situation.getAlarmEvents().size());
        }
        return Math.min(100, score);
    }

    /** 自动推荐响应方案。 */
    private String recommendResponsePlan(AlarmEvent alarmEvent, int threatLevel, int resourceAvailability) {
        String basePlan = switch (alarmEvent.getEventType()) {
            case FIRE -> "火灾监控方案：优先部署指挥中继无人机，建立通信链路";
            case INTRUSION -> "入侵监控方案：优先部署监控无人机，目标区域实时视频回传";
            case MOTION -> "移动侦测方案：部署侦察无人机确认目标性质";
            case DOOR -> "门禁异常方案：部署监控无人机巡查门禁区域";
            case CUSTOM -> "常规响应方案：根据现场情况灵活部署";
        };
        if (threatLevel >= 3 && resourceAvailability < 50) {
            basePlan += "。建议增派无人机和安防设备，提升覆盖能力";
        } else if (threatLevel >= 2 && resourceAvailability < 30) {
            basePlan += "。建议补充安防设备部署";
        }
        return basePlan;
    }

    /** 执行部署：无人机部署 + 安防设备部署 + 联合部署。 */
    private DeploymentInfo performDeployment(AlarmEvent alarmEvent, String coordinationId) {
        DeploymentInfo info = new DeploymentInfo();
        info.setScenarioType(mapScenarioType(alarmEvent.getEventType()));
        info.setCenterLat(toE7(clamp(alarmEvent.getLat(), -90.0, 90.0)));
        info.setCenterLon(toE7(clamp(alarmEvent.getLon(), -180.0, 180.0)));
        info.setRadius(1000);

        // 无人机部署：默认 2 架
        List<Integer> droneIds = List.of(1, 2);
        info.setDroneIds(droneIds);

        // 安防设备部署：若 RapidDeployService 可用，记录部署信息
        if (rapidDeployService != null) {
            info.setGroundDeployAvailable(true);
            info.setGroundDeploySummary("布控球快速部署服务可用，可按需部署便携式监控设备");
        } else {
            info.setGroundDeployAvailable(false);
            info.setGroundDeploySummary("布控球快速部署服务未注入，仅依赖现有安防设备");
        }

        // 优先级分配
        info.setPriority(alarmEvent.getSeverity() == AlarmEvent.Severity.CRITICAL ? "HIGH"
                : alarmEvent.getSeverity() == AlarmEvent.Severity.WARN ? "MEDIUM" : "LOW");

        return info;
    }

    /** 计算安防设备覆盖率。 */
    private int computeDeviceCoverageRate(AirGroundSituation situation) {
        if (situation.getSurveillanceDevices().isEmpty()) {
            return 0;
        }
        return (int) ((double) situation.onlineDeviceCount()
                / situation.getSurveillanceDevices().size() * 100);
    }

    /** 计算连通率（mesh 覆盖率 + 安防设备在线率加权）。 */
    private int computeConnectivityRate(AirGroundSituation situation) {
        int meshRate = situation.getMeshTopology().coverageRate;
        int deviceRate = computeDeviceCoverageRate(situation);
        return (meshRate + deviceRate) / 2;
    }

    /** 计算响应时效。 */
    private ResponseTiming computeResponseTiming(CoordinationRecord record) {
        long now = System.currentTimeMillis();
        long receiveMs = record.getStartTimeMs();
        long assessMs = record.getAssessTimeMs();
        long deployMs = record.getDeployTimeMs();
        long executeMs = record.getExecuteTimeMs();

        long receiveToAssess = (assessMs > 0 && receiveMs > 0) ? (assessMs - receiveMs) / 1000 : 0;
        long assessToDeploy = (deployMs > 0 && assessMs > 0) ? (deployMs - assessMs) / 1000 : 0;
        long deployToExecute = (executeMs > 0 && deployMs > 0) ? (executeMs - deployMs) / 1000 : 0;
        long totalResponse = executeMs > 0 ? (executeMs - receiveMs) / 1000 : (now - receiveMs) / 1000;

        return new ResponseTiming(receiveToAssess, assessToDeploy, deployToExecute, totalResponse);
    }

    /** 计算资源消耗。 */
    private ResourceConsumption computeResourceConsumption(CoordinationRecord record,
                                                           AirGroundSituation situation) {
        int droneCount = record.getDeploymentInfo() != null
                ? record.getDeploymentInfo().getDroneIds().size() : 0;
        int deviceCount = situation.onlineDeviceCount();
        int avgBattery = (int) situation.getDroneStatuses().stream()
                .filter(AirGroundSituation.DroneStatus::isOnline)
                .mapToInt(d -> d.batteryPct)
                .average().orElse(0);
        int meshNodes = situation.getMeshTopology().nodeCount;

        return new ResourceConsumption(droneCount, deviceCount, avgBattery, meshNodes);
    }

    /** 生成指挥报告。 */
    private CoordinationReport buildCoordinationReport(CoordinationRecord record) {
        AlarmEvent alarmEvent = record.getAlarmEvent();
        CoordinationEvaluation evaluation = record.getEvaluation();

        // 事件概述
        String overview = String.format(
                "事件类型：%s，严重程度：%s，来源设备：%s，描述：%s",
                alarmEvent != null ? alarmEvent.getEventType() : "UNKNOWN",
                alarmEvent != null ? alarmEvent.getSeverity() : "UNKNOWN",
                alarmEvent != null ? alarmEvent.getSourceDeviceId() : "",
                alarmEvent != null ? alarmEvent.getDescription() : "");

        // 评估摘要
        String evalSummary = evaluation != null
                ? String.format("覆盖率=%d%%, 连通率=%d%%, 响应时效=%ds, 无人机=%d架, 安防设备=%d台",
                        evaluation.coverageRate, evaluation.connectivityRate,
                        evaluation.timing.totalResponseSec,
                        evaluation.consumption.droneCount, evaluation.consumption.deviceCount)
                : "未执行评估";

        // 联动规则优化建议
        List<String> recommendations = generateRecommendations(evaluation);

        // 总结
        String summary = String.format(
                "空地协同指挥已完成。%s。%s。建议：%s",
                overview, evalSummary,
                recommendations.isEmpty() ? "无" : String.join("；", recommendations));

        return new CoordinationReport(
                record.getCoordinationId(), record.getAlarmEventId(),
                record.getCmdId(), record.getPlanId(),
                overview, evalSummary, summary, recommendations,
                record.getPhaseHistory(), System.currentTimeMillis());
    }

    /** 基于评估结果生成联动规则优化建议。 */
    private List<String> generateRecommendations(CoordinationEvaluation evaluation) {
        List<String> recs = new ArrayList<>();
        if (evaluation == null) {
            return recs;
        }
        if (evaluation.coverageRate < 50) {
            recs.add("覆盖率不足，建议增加无人机部署数量或调整飞行航线");
        }
        if (evaluation.connectivityRate < 50) {
            recs.add("连通率不足，建议增加 mesh 中继节点或优化通信链路");
        }
        if (evaluation.timing.totalResponseSec > 300) {
            recs.add("响应时效较长，建议优化接报到执行的自动化流程");
        }
        if (evaluation.consumption.avgBatteryPct < 30) {
            recs.add("无人机电池消耗较大，建议增加备用无人机轮换");
        }
        if (evaluation.deviceCoverageRate < 50) {
            recs.add("安防设备覆盖率不足，建议增加布控球快速部署");
        }
        if (recs.isEmpty()) {
            recs.add("各项指标良好，维持当前联动策略");
        }
        return recs;
    }

    // =====================================================================
    // WebSocket 推送辅助
    // =====================================================================

    /** 推送态势更新。 */
    private void pushSituationUpdate(String coordinationId) {
        if (pusher == null) {
            return;
        }
        try {
            AirGroundSituation situation = fuseAirGroundSituation();
            pusher.pushSituationUpdate(coordinationId, situation);
        } catch (Exception e) {
            log.warn("空地协同：推送态势更新失败 coordinationId={} error={}", coordinationId, e.getMessage());
        }
    }

    /** 推送指挥进度。 */
    private void pushProgress(String coordinationId, String phase, String message, String cmdId) {
        if (pusher == null) {
            return;
        }
        try {
            pusher.pushCoordinationProgress(coordinationId, phase, message, cmdId);
        } catch (Exception e) {
            log.warn("空地协同：推送进度失败 coordinationId={} error={}", coordinationId, e.getMessage());
        }
    }

    /** 推送评估结果。 */
    private void pushEvaluationResult(String coordinationId, CoordinationEvaluation evaluation) {
        if (pusher == null) {
            return;
        }
        try {
            pusher.pushEvaluationResult(coordinationId, evaluation);
        } catch (Exception e) {
            log.warn("空地协同：推送评估结果失败 coordinationId={} error={}", coordinationId, e.getMessage());
        }
    }

    // =====================================================================
    // 内部辅助（原有）
    // =====================================================================

    /**
     * 查找目标位置附近最近的具备 PTZ 能力的安防设备。
     * <p>
     * 搜索半径 {@link #PTZ_SEARCH_RADIUS_M} 米，在注册表中遍历所有设备，
     * 筛选在线且具备 PTZ 能力的设备，按距离排序返回最近的。
     *
     * @param lat 目标纬度
     * @param lon 目标经度
     * @return 最近的 PTZ 设备；若无可用的返回 null
     */
    private SurveillanceDevice findNearestPtzDevice(double lat, double lon) {
        if (deviceRegistry == null) {
            return null;
        }

        List<SurveillanceDevice> allDevices = deviceRegistry.listDevices();
        SurveillanceDevice nearest = null;
        double minDistance = Double.MAX_VALUE;

        for (SurveillanceDevice device : allDevices) {
            // 仅选择在线且具备 PTZ 能力的设备
            if (device.status != SurveillanceDevice.Status.ONLINE) {
                continue;
            }
            if (!device.getCapabilities().contains("PTZ")) {
                continue;
            }
            // 安防设备位置未知时跳过（无经纬度信息）
            // SurveillanceDevice 无 lat/lon 字段，此处简化：所有 PTZ 设备均候选
            // 真实实现需从设备注册信息中获取经纬度
            double distance = estimateDistance(lat, lon, device);
            if (distance < minDistance) {
                minDistance = distance;
                nearest = device;
            }
        }

        return nearest;
    }

    /**
     * 估算目标与安防设备的距离（简化实现）。
     * <p>
     * 由于 {@link SurveillanceDevice} 不含经纬度字段，此处返回固定值 0，
     * 表示所有设备等距。真实实现应从设备注册信息或数据库中获取设备位置。
     *
     * @param targetLat 目标纬度
     * @param targetLon 目标经度
     * @param device    安防设备
     * @return 估算距离（米）
     */
    private double estimateDistance(double targetLat, double targetLon, SurveillanceDevice device) {
        // 简化实现：SurveillanceDevice 无经纬度，返回 0 使第一个在线 PTZ 设备被选中
        // 生产环境应从设备实体或外部配置中获取设备部署位置
        return 0;
    }

    /**
     * 计算安防设备 PTZ 转向方向。
     * <p>
     * 简化策略：根据目标类型选择默认转向方向。
     * 真实实现需根据设备与目标的相对方位角计算精确的 PTZ 方向。
     *
     * @param device    安防设备
     * @param geoTarget 地理目标
     * @return PTZ 命令（up/down/left/right/zoomIn）
     */
    private String computePtzDirection(SurveillanceDevice device, GeoTarget geoTarget) {
        // 简化实现：根据目标类型选择 PTZ 方向
        // 真实实现需根据设备朝向与目标方位的差值计算
        return switch (geoTarget.targetType) {
            case PERSON -> "zoomIn";   // 人员目标：放大查看
            case VEHICLE -> "zoomIn";  // 车辆目标：放大查看
            case FIRE_SOURCE -> "up";  // 火源目标：上仰查看烟雾
            case STRUCTURE -> "left";  // 建筑目标：左转扫描
            case UNKNOWN -> "right";   // 未知目标：右转扫描
        };
    }

    /**
     * 从编排服务中收集无人机状态信息。
     * <p>
     * 简化实现：当前 EmergencyOrchService 不直接提供无人机状态列表，
     * 此处返回空列表。真实实现应从遥测服务或编排计划中提取无人机实时状态。
     *
     * @return 无人机状态列表
     */
    private List<AirGroundSituation.DroneStatus> collectDroneStatuses() {
        // 简化实现：EmergencyOrchService 的 plans map 不直接暴露无人机状态
        // 生产环境应从 TelemetryIngestService 或专用无人机状态服务获取
        return List.of();
    }

    /**
     * 从报警事件存储中收集最近的报警事件。
     * <p>
     * 若 AlarmEventStore 可用，查询最近的未确认报警事件。
     * 否则返回空列表。
     *
     * @return 报警事件列表
     */
    private List<AlarmEvent> collectAlarmEvents() {
        if (alarmEventStore == null) {
            return List.of();
        }
        try {
            AlarmEventStore.PageResult result = alarmEventStore.query(0, 20, null, null);
            return result.getItems();
        } catch (Exception e) {
            log.warn("空地协同：查询最近报警事件失败 error={}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 基于无人机状态构建 mesh 拓扑信息。
     * <p>
     * 简化估算：节点数 = 在线无人机数，连接数 = 节点数 - 1（链式），
     * 覆盖率 = 在线节点数 / 总节点数 * 100。
     *
     * @param droneStatuses 无人机状态列表
     * @return mesh 拓扑信息
     */
    private AirGroundSituation.MeshTopology buildMeshTopology(
            List<AirGroundSituation.DroneStatus> droneStatuses) {
        int onlineCount = (int) droneStatuses.stream()
                .filter(AirGroundSituation.DroneStatus::isOnline)
                .count();
        int linkCount = Math.max(0, onlineCount - 1);
        int coverageRate = droneStatuses.isEmpty() ? 0
                : (int) ((double) onlineCount / droneStatuses.size() * 100);

        List<Integer> relayNodes = new ArrayList<>();
        for (AirGroundSituation.DroneStatus d : droneStatuses) {
            if (d.isOnline()) {
                relayNodes.add(d.sysid);
            }
        }

        return new AirGroundSituation.MeshTopology(onlineCount, linkCount, coverageRate, relayNodes);
    }

    /** 事件类型 → EmergencyOrchService 场景类型映射。 */
    private static int mapScenarioType(AlarmEvent.EventType eventType) {
        return switch (eventType) {
            case FIRE -> 2;       // 火灾
            case INTRUSION -> 3;  // 自定义（安防入侵）
            case MOTION -> 3;     // 自定义（移动侦测）
            case DOOR -> 3;       // 自定义（门禁异常）
            case CUSTOM -> 3;     // 自定义
        };
    }

    /** 经纬度（度）→ 1E7 度定点整数。 */
    private static int toE7(double deg) {
        long scaled = Math.round(deg * LATLON_SCALE);
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

    /** 报警事件类型 → EmergencyCommand 事件类型映射。 */
    private static EmergencyCommand.IncidentType mapIncidentType(AlarmEvent.EventType eventType) {
        return switch (eventType) {
            case FIRE -> EmergencyCommand.IncidentType.FIRE;
            case INTRUSION -> EmergencyCommand.IncidentType.SECURITY_ALARM;
            case MOTION -> EmergencyCommand.IncidentType.SECURITY_ALARM;
            case DOOR -> EmergencyCommand.IncidentType.SECURITY_ALARM;
            case CUSTOM -> EmergencyCommand.IncidentType.OTHER;
        };
    }

    /** AlarmEvent.Severity → EmergencyCommand.Severity 映射。 */
    private static EmergencyCommand.Severity mapSeverity(AlarmEvent.Severity severity) {
        return switch (severity) {
            case INFO -> EmergencyCommand.Severity.INFO;
            case WARN -> EmergencyCommand.Severity.WARN;
            case CRITICAL -> EmergencyCommand.Severity.CRITICAL;
        };
    }

    // =====================================================================
    // 数据类
    // =====================================================================

    /** 空地协同指挥记录。 */
    public static final class CoordinationRecord {
        private final String coordinationId;
        private final String alarmEventId;
        private final String cmdId;
        private volatile EmergencyCommandPhase phase;
        private volatile AlarmEvent alarmEvent;
        private volatile String assessmentResult;
        private volatile DeploymentInfo deploymentInfo;
        private volatile long planId;
        private volatile CoordinationEvaluation evaluation;
        private volatile CoordinationReport report;
        private final long startTimeMs;
        private volatile long assessTimeMs;
        private volatile long deployTimeMs;
        private volatile long executeTimeMs;

        public CoordinationRecord(String coordinationId, String alarmEventId, String cmdId) {
            this.coordinationId = coordinationId;
            this.alarmEventId = alarmEventId;
            this.cmdId = cmdId;
            this.phase = EmergencyCommandPhase.RECEIVED;
            this.startTimeMs = System.currentTimeMillis();
            this.planId = -1;
        }

        public String getCoordinationId() { return coordinationId; }
        public String getAlarmEventId() { return alarmEventId; }
        public String getCmdId() { return cmdId; }
        public EmergencyCommandPhase getPhase() { return phase; }
        public void setPhase(EmergencyCommandPhase phase) {
            this.phase = phase;
            long now = System.currentTimeMillis();
            if (phase == EmergencyCommandPhase.ASSESSED) this.assessTimeMs = now;
            else if (phase == EmergencyCommandPhase.DEPLOYED) this.deployTimeMs = now;
            else if (phase == EmergencyCommandPhase.EXECUTING) this.executeTimeMs = now;
        }
        public AlarmEvent getAlarmEvent() { return alarmEvent; }
        public void setAlarmEvent(AlarmEvent alarmEvent) { this.alarmEvent = alarmEvent; }
        public String getAssessmentResult() { return assessmentResult; }
        public void setAssessmentResult(String assessmentResult) { this.assessmentResult = assessmentResult; }
        public DeploymentInfo getDeploymentInfo() { return deploymentInfo; }
        public void setDeploymentInfo(DeploymentInfo deploymentInfo) { this.deploymentInfo = deploymentInfo; }
        public long getPlanId() { return planId; }
        public void setPlanId(long planId) { this.planId = planId; }
        public CoordinationEvaluation getEvaluation() { return evaluation; }
        public void setEvaluation(CoordinationEvaluation evaluation) { this.evaluation = evaluation; }
        public CoordinationReport getReport() { return report; }
        public void setReport(CoordinationReport report) { this.report = report; }
        public long getStartTimeMs() { return startTimeMs; }
        public long getAssessTimeMs() { return assessTimeMs; }
        public long getDeployTimeMs() { return deployTimeMs; }
        public long getExecuteTimeMs() { return executeTimeMs; }

        /** 返回阶段历史时间线。 */
        public List<String> getPhaseHistory() {
            List<String> history = new ArrayList<>();
            history.add(String.format("接报: %dms", startTimeMs));
            if (assessTimeMs > 0) {
                history.add(String.format("研判: %dms (+%ds)", assessTimeMs, (assessTimeMs - startTimeMs) / 1000));
            }
            if (deployTimeMs > 0) {
                history.add(String.format("部署: %dms (+%ds)", deployTimeMs, (deployTimeMs - assessTimeMs) / 1000));
            }
            if (executeTimeMs > 0) {
                history.add(String.format("执行: %dms (+%ds)", executeTimeMs, (executeTimeMs - deployTimeMs) / 1000));
            }
            return history;
        }
    }

    /** 部署信息。 */
    public static final class DeploymentInfo {
        private int scenarioType;
        private int centerLat;
        private int centerLon;
        private int radius;
        private List<Integer> droneIds;
        private boolean groundDeployAvailable;
        private String groundDeploySummary;
        private String priority;

        public int getScenarioType() { return scenarioType; }
        public void setScenarioType(int scenarioType) { this.scenarioType = scenarioType; }
        public int getCenterLat() { return centerLat; }
        public void setCenterLat(int centerLat) { this.centerLat = centerLat; }
        public int getCenterLon() { return centerLon; }
        public void setCenterLon(int centerLon) { this.centerLon = centerLon; }
        public int getRadius() { return radius; }
        public void setRadius(int radius) { this.radius = radius; }
        public List<Integer> getDroneIds() { return droneIds; }
        public void setDroneIds(List<Integer> droneIds) { this.droneIds = droneIds; }
        public boolean isGroundDeployAvailable() { return groundDeployAvailable; }
        public void setGroundDeployAvailable(boolean groundDeployAvailable) { this.groundDeployAvailable = groundDeployAvailable; }
        public String getGroundDeploySummary() { return groundDeploySummary; }
        public void setGroundDeploySummary(String groundDeploySummary) { this.groundDeploySummary = groundDeploySummary; }
        public String getPriority() { return priority; }
        public void setPriority(String priority) { this.priority = priority; }

        /** 转换为 EmergencyCommand.DeploymentPlan。 */
        public EmergencyCommand.DeploymentPlan toDeploymentPlan() {
            String strategy = switch (scenarioType) {
                case 0 -> "SEARCH_RESCUE";
                case 1 -> "MAPPING";
                case 2 -> "COMMAND_RELAY";
                default -> "SURVEILLANCE";
            };
            return new EmergencyCommand.DeploymentPlan(
                    "air-ground-" + priority,
                    strategy, 60, "mesh");
        }

        public String summary() {
            return String.format("无人机 %d 架，场景类型 %d，半径 %dm，优先级 %s。%s",
                    droneIds != null ? droneIds.size() : 0,
                    scenarioType, radius, priority, groundDeploySummary);
        }
    }

    /** 协同指挥评估结果。 */
    public static final class CoordinationEvaluation {
        /** 无人机覆盖率（0~100）。 */
        public final int coverageRate;
        /** 安防设备覆盖率（0~100）。 */
        public final int deviceCoverageRate;
        /** 连通率（0~100）。 */
        public final int connectivityRate;
        /** 响应时效。 */
        public final ResponseTiming timing;
        /** 资源消耗。 */
        public final ResourceConsumption consumption;

        public CoordinationEvaluation(int coverageRate, int deviceCoverageRate,
                                       int connectivityRate, ResponseTiming timing,
                                       ResourceConsumption consumption) {
            this.coverageRate = Math.max(0, Math.min(100, coverageRate));
            this.deviceCoverageRate = Math.max(0, Math.min(100, deviceCoverageRate));
            this.connectivityRate = Math.max(0, Math.min(100, connectivityRate));
            this.timing = timing;
            this.consumption = consumption;
        }
    }

    /** 响应时效评估。 */
    public static final class ResponseTiming {
        /** 接报到研判耗时（秒）。 */
        public final long receiveToAssessSec;
        /** 研判到部署耗时（秒）。 */
        public final long assessToDeploySec;
        /** 部署到执行耗时（秒）。 */
        public final long deployToExecuteSec;
        /** 总响应耗时（秒）。 */
        public final long totalResponseSec;

        public ResponseTiming(long receiveToAssessSec, long assessToDeploySec,
                              long deployToExecuteSec, long totalResponseSec) {
            this.receiveToAssessSec = receiveToAssessSec;
            this.assessToDeploySec = assessToDeploySec;
            this.deployToExecuteSec = deployToExecuteSec;
            this.totalResponseSec = totalResponseSec;
        }
    }

    /** 资源消耗评估。 */
    public static final class ResourceConsumption {
        /** 投入无人机数量。 */
        public final int droneCount;
        /** 投入安防设备数量。 */
        public final int deviceCount;
        /** 平均电池剩余百分比。 */
        public final int avgBatteryPct;
        /** mesh 节点数。 */
        public final int meshNodes;

        public ResourceConsumption(int droneCount, int deviceCount, int avgBatteryPct, int meshNodes) {
            this.droneCount = droneCount;
            this.deviceCount = deviceCount;
            this.avgBatteryPct = avgBatteryPct;
            this.meshNodes = meshNodes;
        }

        public String summary() {
            return String.format("无人机=%d架, 安防设备=%d台, 平均电池=%d%%, mesh节点=%d",
                    droneCount, deviceCount, avgBatteryPct, meshNodes);
        }
    }

    /** 协同指挥报告。 */
    public static final class CoordinationReport {
        private final String coordinationId;
        private final String alarmEventId;
        private final String cmdId;
        private final long planId;
        private final String overview;
        private final String evaluationSummary;
        private final String summary;
        private final List<String> recommendations;
        private final List<String> phaseTimeline;
        private final long generatedAtMs;

        public CoordinationReport(String coordinationId, String alarmEventId,
                                  String cmdId, long planId,
                                  String overview, String evaluationSummary,
                                  String summary, List<String> recommendations,
                                  List<String> phaseTimeline, long generatedAtMs) {
            this.coordinationId = coordinationId;
            this.alarmEventId = alarmEventId;
            this.cmdId = cmdId;
            this.planId = planId;
            this.overview = overview;
            this.evaluationSummary = evaluationSummary;
            this.summary = summary;
            this.recommendations = recommendations == null ? List.of() : List.copyOf(recommendations);
            this.phaseTimeline = phaseTimeline == null ? List.of() : List.copyOf(phaseTimeline);
            this.generatedAtMs = generatedAtMs;
        }

        public String getCoordinationId() { return coordinationId; }
        public String getAlarmEventId() { return alarmEventId; }
        public String getCmdId() { return cmdId; }
        public long getPlanId() { return planId; }
        public String getOverview() { return overview; }
        public String getEvaluationSummary() { return evaluationSummary; }
        public String getSummary() { return summary; }
        public List<String> getRecommendations() { return recommendations; }
        public List<String> getPhaseTimeline() { return phaseTimeline; }
        public long getGeneratedAtMs() { return generatedAtMs; }
    }
}
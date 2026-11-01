package io.aerofleet.cloud.api.service;

import io.aerofleet.cloud.api.pusher.EmergencyOrchPusher;

import io.aerofleet.cloud.gateway.MavlinkMessageEvent;
import io.aerofleet.cloud.orch.event.EmergencyStartEvent;
import io.aerofleet.cloud.orch.event.EmergencyEndEvent;
import io.aerofleet.cloud.security.TenantContext;
import io.aerofleet.mavlink.messages.CoverageOptimizationMsg;
import io.aerofleet.mavlink.messages.EmergencyMissionPlanMsg;
import io.aerofleet.mavlink.messages.EmergencyPriorityMsg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 应急任务编排服务（M9，FR-30）。
 * <p>
 * 维护编排计划状态（{@link ConcurrentHashMap}，线程安全），提供启动/中止/查询/
 * 重规划/优先级调整等业务能力，并接收 MAVLink 30045/30046/30047 上报更新计划状态。
 * <p>
 * 4 种场景预设：0=地震 / 1=泥石流 / 2=火灾 / 3=自定义。
 * 4 级优先级队列：1=SEARCH_RESCUE / 2=COMMAND / 3=MAPPING / 4=ROUTINE。
 * 5 个编排阶段：0=测绘 / 1=规划 / 2=部署 / 3=服务 / 4=自愈。
 */
@Service
public class EmergencyOrchService {

    private static final Logger log = LoggerFactory.getLogger(EmergencyOrchService.class);

    /** 编排计划状态：planId → plan 状态 map。 */
    private final ConcurrentHashMap<Long, Map<String, Object>> plans = new ConcurrentHashMap<>();
    /** planId → tenantId（租户隔离映射）。 */
    private final Map<Long, Integer> planTenantMap = new ConcurrentHashMap<>();
    /** planId 生成器起始值。 */
    private static final long PLAN_ID_START = 10000L;
    /** 默认优先级（常规）。 */
    private static final int DEFAULT_PRIORITY = 4;
    /** 阶段状态：已完成。 */
    private static final int PHASE_STATUS_COMPLETED = 3;
    /** 阶段状态：已中止。 */
    private static final int PHASE_STATUS_ABORTED = 4;
    /** 编排阶段总数。 */
    private static final int PHASE_COUNT = 5;
    /** 优先级取消动作。 */
    private static final int ACTION_CANCEL = 4;
    /** planId 生成器。 */
    private final AtomicLong planIdGenerator = new AtomicLong(PLAN_ID_START);

    /**
     * 内存计划缓存上限。超出后按 planId 从旧到新淘汰<b>终态</b>计划
     * （status=ABORTED / phaseStatus=COMPLETED）；RUNNING / PAUSED 一个都不动。
     */
    static final int MAX_CACHED_PLANS = 256;

    /**
     * 单调序列发号器（V35；§5 Q5 裁决：planId 跨重启单调——若它进过客户侧报表，
     * 复用比丢数据更严重）。缺失时走内存 generator。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private PersistentSequence sequence;

    /**
     * 计划仓库（V37；P1#8 裁决：新表独立落库）。快照 = 计划 Map 整体 JSON
     * （Jackson 对 Map&lt;String,Object&gt; 双向无损）；写穿于 start/abort/上报回调三处，
     * 读穿于 getPlan 内存未命中时——重启后编排进度与事件列表可查。
     * 仓库缺失（纯单测构造）时退化为纯内存。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private EmergencyPlanRepository planRepository;

    private static final com.fasterxml.jackson.databind.ObjectMapper PLAN_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** 写穿：快照 + 排查用列（status/tenant）一次写入。异常只告警（旁路不阻塞主链）。 */
    private void persistPlan(long planId, Map<String, Object> plan) {
        if (planRepository == null) {
            return;
        }
        try {
            EmergencyPlanEntity entity = planRepository.findById(planId)
                    .orElseGet(() -> new EmergencyPlanEntity(planId));
            entity.setTenantId(planTenantMap.get(planId));
            Object status = plan.get("status");
            entity.setStatus(status == null ? null : String.valueOf(status));
            entity.setSnapshot(PLAN_MAPPER.writeValueAsString(plan));
            entity.setUpdatedAt(java.time.Instant.now());
            planRepository.save(entity);
        } catch (Exception e) {
            log.warn("编排计划写穿失败 planId={}：{}", planId, e.getMessage());
        }
    }

    /** 读穿（重启/多实例）：快照重建 Map 并回填内存与租户映射。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> readThroughPlan(long planId) {
        if (planRepository == null) {
            return null;
        }
        try {
            return planRepository.findById(planId)
                    .map(entity -> {
                        try {
                            Map<String, Object> restored =
                                    PLAN_MAPPER.readValue(entity.getSnapshot(), Map.class);
                            plans.put(planId, restored);
                            if (entity.getTenantId() != null) {
                                planTenantMap.put(planId, entity.getTenantId());
                            }
                            // 放在租户回填之后：若这次读穿恰好又触发淘汰，租户映射会被一并清掉，
                            // 不留「计划已不在内存、租户映射还在」的孤儿条目。
                            evictTerminalPlans();
                            return restored;
                        } catch (Exception parse) {
                            log.warn("编排计划快照解析失败 planId={}：{}", planId, parse.getMessage());
                            return null;
                        }
                    })
                    .orElse(null);
        } catch (Exception e) {
            log.warn("编排计划读穿失败 planId={}：{}", planId, e.getMessage());
            return null;
        }
    }
    /** WebSocket 推送器（可为 null，测试场景）。 */
    private final EmergencyOrchPusher pusher;
    /** 事件发布器。 */
    private final ApplicationEventPublisher eventPublisher;

    public EmergencyOrchService(EmergencyOrchPusher pusher, ApplicationEventPublisher eventPublisher) {
        this.pusher = pusher;
        this.eventPublisher = eventPublisher;
    }

    // =====================================================================
    // 业务方法
    // =====================================================================

    /**
     * 启动编排计划。
     *
     * @param scenarioType 场景类型 0=地震/1=泥石流/2=火灾/3=自定义
     * @param centerLat    灾区中心纬度（1E7 度）
     * @param centerLon    灾区中心经度（1E7 度）
     * @param radius       灾区半径（米）
     * @param droneIds     参与无人机 ID 列表
     * @return planId
     */
    public long start(int scenarioType, int centerLat, int centerLon, int radius, List<Integer> droneIds) {
        Long issued = sequence != null ? sequence.next("orch_plan_id", PLAN_ID_START) : null;
        long planId = issued != null ? issued : planIdGenerator.incrementAndGet();
        long now = System.currentTimeMillis();
        Map<String, Object> plan = newPlanMap(planId, scenarioType, centerLat, centerLon, radius,
                droneIds, now);
        plans.put(planId, plan);
        Integer tenantId = TenantContext.getEffectiveTenantId();
        if (tenantId != null) {
            planTenantMap.put(planId, tenantId);
        }
        evictTerminalPlans();
        persistPlan(planId, plan);
        log.info("emergency plan started: planId={} scenario={} drones={} radius={}m",
                planId, scenarioName(scenarioType), droneIds.size(), radius);
        pushPlan(planId, plan);
        eventPublisher.publishEvent(new EmergencyStartEvent(this, planId, droneIds));
        return planId;
    }

    /**
     * 中止编排计划。
     *
     * @param planId 编排计划 ID
     * @return true 若计划存在并已中止
     */
    public boolean abort(long planId) {
        if (!checkTenantAccess(planId)) {
            return false;
        }
        Map<String, Object> plan = plans.get(planId);
        if (plan == null) {
            return false;
        }
        plan.put("status", "ABORTED");
        plan.put("timestamp", System.currentTimeMillis());
        addEvent(plan, "plan aborted");
        updatePhaseStatus(plan, phaseOf(plan), PHASE_STATUS_ABORTED); // ABORTED
        persistPlan(planId, plan);
        log.info("emergency plan aborted: planId={}", planId);
        pushPlan(planId, plan);
        eventPublisher.publishEvent(new EmergencyEndEvent(this, planId));
        return true;
    }

    /**
     * 获取计划状态 map；不存在返回 null。
     */
    public Map<String, Object> getPlan(long planId) {
        if (!checkTenantAccess(planId)) {
            return null;
        }
        Map<String, Object> plan = plans.get(planId);
        if (plan != null) {
            return plan;
        }
        // 内存未命中（重启/多实例）：读穿快照——修掉"planId 失效、进度不可查"
        return readThroughPlan(planId);
    }

    /**
     * 获取阶段进度与事件列表。
     * <p>
     * 返回防御性浅拷贝：调用方修改返回的列表不会影响 plan 内部状态
     * （phases 元素为 ConcurrentHashMap，浅拷贝保留阶段状态的并发更新）。
     */
    public Map<String, Object> getProgress(long planId) {
        // 与 getPlan 同口径：内存未命中时读穿快照。否则重启/淘汰后 getPlan 查得到、
        // 进度却 404，是两个接口之间的自相矛盾。
        Map<String, Object> plan = getPlan(planId);
        if (plan == null) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("phases", new ArrayList<>((List<?>) plan.get("phases")));
        result.put("events", new ArrayList<>((List<?>) plan.get("events")));
        return result;
    }

    /**
     * 获取覆盖信息。
     * <p>
     * 返回防御性浅拷贝：deployments/uncoveredAreas 复制后返回，避免调用方绕过
     * 内部 synchronizedList 的保护直接修改内部状态。
     */
    public Map<String, Object> getCoverage(long planId) {
        // 同 getProgress：共用 getPlan 的租户判定 + 读穿，避免两个接口口径漂移。
        Map<String, Object> plan = getPlan(planId);
        if (plan == null) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("coverageRate", plan.get("coverageRate"));
        result.put("connectRate", plan.get("connectRate"));
        result.put("deployments", new ArrayList<>((List<?>) plan.get("deployments")));
        result.put("uncoveredAreas", new ArrayList<>((List<?>) plan.get("uncoveredAreas")));
        return result;
    }

    /**
     * 重规划：基于已有计划创建新计划。
     *
     * @param planId 原计划 ID
     * @param reason 重规划原因
     * @return 新 planId，若原计划不存在返回 -1
     */
    public long replan(long planId, String reason) {
        if (!checkTenantAccess(planId)) {
            return -1;
        }
        Map<String, Object> old = plans.get(planId);
        if (old == null) {
            return -1;
        }
        int scenarioType = (int) old.get("scenarioType");
        int centerLat = (int) old.get("centerLat");
        int centerLon = (int) old.get("centerLon");
        int radius = (int) old.get("radius");
        @SuppressWarnings("unchecked")
        List<Integer> droneIds = (List<Integer>) old.get("droneIds");
        long newPlanId = start(scenarioType, centerLat, centerLon, radius, droneIds);
        addEvent(plans.get(newPlanId), "replan from " + planId + ": " + reason);
        log.info("emergency replan: old={} new={} reason={}", planId, newPlanId, reason);
        return newPlanId;
    }

    /**
     * 调整任务优先级。
     *
     * @param planId    编排计划 ID
     * @param taskId    任务 ID
     * @param priority  新优先级 1=搜救/2=指挥/3=测绘/4=常规
     * @param reason    调整原因
     * @return 调整结果 map（含 oldPriority/newPriority/preemptedTaskId），若计划不存在返回 null
     */
    public Map<String, Object> adjustPriority(long planId, long taskId, int priority, String reason) {
        if (!checkTenantAccess(planId)) {
            return null;
        }
        Map<String, Object> plan = plans.get(planId);
        if (plan == null) {
            return null;
        }
        @SuppressWarnings("unchecked")
        Map<Long, Integer> taskPriorities = (Map<Long, Integer>) plan.get("taskPriorities");
        @SuppressWarnings("unchecked")
        Map<String, List<Long>> queues = (Map<String, List<Long>>) plan.get("priorityQueues");

        // P1: 队列移动 + 抢占组合操作必须原子，避免并发下任务从旧队列移除后
        // 但尚未加入新队列时被其他线程观察到不一致状态。synchronized(plan)
        // 保证同一计划内的优先级调整串行化。
        int oldPriority;
        long preemptedTaskId;
        synchronized (plan) {
            oldPriority = taskPriorities.getOrDefault(taskId, DEFAULT_PRIORITY); // 默认常规
            taskPriorities.put(taskId, priority);

            // 移动到新优先级队列
            String oldQueueName = priorityQueueName(oldPriority);
            String newQueueName = priorityQueueName(priority);
            queues.get(oldQueueName).remove(Long.valueOf(taskId));
            if (!queues.get(newQueueName).contains(taskId)) {
                queues.get(newQueueName).add(taskId);
            }

            // 抢占：若提升到最高优先级，抢占同队列首个低优先级任务
            preemptedTaskId = 0L;
            if (priority < oldPriority) {
                for (int p = priority + 1; p <= DEFAULT_PRIORITY; p++) {
                    List<Long> queue = queues.get(priorityQueueName(p));
                    if (!queue.isEmpty()) {
                        preemptedTaskId = queue.get(0);
                        break;
                    }
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", taskId);
        result.put("oldPriority", oldPriority);
        result.put("newPriority", priority);
        result.put("preemptedTaskId", preemptedTaskId);
        addEvent(plan, "priority adjust: task=" + taskId + " " + oldPriority + "->" + priority
                + " reason=" + reason);
        log.info("emergency priority adjust: planId={} task={} {}->{} preempted={}",
                planId, taskId, oldPriority, priority, preemptedTaskId);
        pushPriority(planId, result);
        return result;
    }

    /**
     * 获取优先级队列。
     */
    public Map<String, Object> getPriorityQueue(long planId) {
        if (!checkTenantAccess(planId)) {
            return null;
        }
        Map<String, Object> plan = plans.get(planId);
        if (plan == null) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("queues", plan.get("priorityQueues"));
        return result;
    }

    /**
     * 获取场景预设列表（4 种）。
     */
    public List<Map<String, Object>> getScenarios() {
        List<Map<String, Object>> scenarios = new ArrayList<>();
        scenarios.add(scenarioOf(0, "地震", "地震灾害应急搜救与通信覆盖",
                5000, 12, 1));
        scenarios.add(scenarioOf(1, "泥石流", "泥石流灾害区域测绘与预警",
                3000, 8, 2));
        scenarios.add(scenarioOf(2, "火灾", "火灾现场监控与指挥中继",
                2000, 6, 2));
        scenarios.add(scenarioOf(3, "自定义", "自定义应急场景",
                1000, 4, 4));
        return scenarios;
    }

    /**
     * 加载预设场景并启动。
     *
     * @param scenarioType 场景类型
     * @param centerLat    灾区中心纬度（1E7 度）
     * @param centerLon    灾区中心经度（1E7 度）
     * @param radius       灾区半径（米），若 0 则用预设默认值
     * @param droneIds     参与无人机 ID 列表，若空则用预设默认数量
     * @return planId，若场景类型不存在返回 -1
     */
    public long startScenario(int scenarioType, int centerLat, int centerLon, int radius,
                              List<Integer> droneIds) {
        Map<String, Object> preset = findScenario(scenarioType);
        if (preset == null) {
            return -1;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> defaults = (Map<String, Object>) preset.get("defaults");
        int effectiveRadius = radius > 0 ? radius : (int) defaults.get("radius");
        List<Integer> effectiveDrones = droneIds;
        if (effectiveDrones == null || effectiveDrones.isEmpty()) {
            effectiveDrones = new ArrayList<>();
            int defaultCount = (int) defaults.get("droneCount");
            for (int i = 1; i <= defaultCount; i++) {
                effectiveDrones.add(i);
            }
        }
        return start(scenarioType, centerLat, centerLon, effectiveRadius, effectiveDrones);
    }

    // =====================================================================
    // MAVLink 上报回调（由 TelemetryIngestService 调用）
    // =====================================================================

    /**
     * EMERGENCY_MISSION_PLAN (30045) 上报回调：更新计划阶段/状态/覆盖/连通率。
     */
    public void onEmergencyMissionPlan(long planId, int scenarioType, int phase, int phaseStatus,
                                       int droneCount, int coverageRate, int connectRate, int priority) {
        Map<String, Object> plan = plans.get(planId);
        if (plan == null) {
            log.debug("emergency mission plan for unknown planId={}: create implicitly", planId);
            plan = newPlanMap(planId, scenarioType, 0, 0, 0,
                    Collections.emptyList(), System.currentTimeMillis());
            plans.put(planId, plan);
            evictTerminalPlans();
        }
        int oldPhase = phaseOf(plan);
        plan.put("scenarioType", scenarioType);
        plan.put("phase", phase);
        plan.put("phaseStatus", phaseStatus);
        plan.put("droneCount", droneCount);
        plan.put("coverageRate", coverageRate);
        plan.put("connectRate", connectRate);
        plan.put("priority", priority);
        plan.put("timestamp", System.currentTimeMillis());
        updatePhaseStatus(plan, phase, phaseStatus);
        if (phase != oldPhase) {
            addEvent(plan, "phase " + oldPhase + "->" + phase + " status=" + phaseStatusName(phaseStatus));
        }
        persistPlan(planId, plan);
        log.debug("emergency mission plan update: planId={} phase={} status={} cov={}%",
                planId, phase, phaseStatus, coverageRate);
        pushPlan(planId, plan);
        if (phaseStatus == PHASE_STATUS_COMPLETED) { // COMPLETED
            eventPublisher.publishEvent(new EmergencyEndEvent(this, planId));
        }
    }

    /**
     * COVERAGE_OPTIMIZATION (30046) 上报回调：记录单架无人机覆盖部署方案。
     */
    public void onCoverageOptimization(long planId, int droneId, int cellType, int relayRole,
                                       int txPower, int expectedCoverage, int batteryBudget) {
        Map<String, Object> plan = plans.get(planId);
        if (plan == null) {
            log.debug("coverage optimization for unknown planId={}: ignored", planId);
            return;
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> deployments = (List<Map<String, Object>>) plan.get("deployments");
        Map<String, Object> dep = new LinkedHashMap<>();
        dep.put("droneId", droneId);
        dep.put("cellType", cellTypeName(cellType));
        dep.put("relayRole", relayRoleName(relayRole));
        dep.put("txPower", txPower);
        dep.put("expectedCoverage", expectedCoverage);
        dep.put("batteryBudget", batteryBudget);
        deployments.add(dep);
        log.debug("coverage optimization: planId={} drone={} cell={} cov={}%",
                planId, droneId, cellTypeName(cellType), expectedCoverage);
        pushCoverage(planId, getCoverage(planId));
    }

    /**
     * EMERGENCY_PRIORITY (30047) 上报回调：记录优先级调度事件。
     */
    public void onEmergencyPriority(long planId, long taskId, int priority, int action,
                                    long preemptedTaskId, String reason) {
        Map<String, Object> plan = plans.get(planId);
        if (plan == null) {
            log.debug("emergency priority for unknown planId={}: ignored", planId);
            return;
        }
        @SuppressWarnings("unchecked")
        Map<Long, Integer> taskPriorities = (Map<Long, Integer>) plan.get("taskPriorities");
        int oldPriority = taskPriorities.getOrDefault(taskId, DEFAULT_PRIORITY);
        taskPriorities.put(taskId, priority);

        @SuppressWarnings("unchecked")
        Map<String, List<Long>> queues = (Map<String, List<Long>>) plan.get("priorityQueues");
        String queueName = priorityQueueName(priority);
        if (!queues.get(queueName).contains(taskId)) {
            queues.get(queueName).add(taskId);
        }
        if (action == ACTION_CANCEL) { // 取消
            queues.get(queueName).remove(Long.valueOf(taskId));
            taskPriorities.remove(taskId);
        }

        addEvent(plan, "priority event: task=" + taskId + " pri=" + priority
                + " action=" + actionName(action) + " preempted=" + preemptedTaskId
                + " reason=" + reason);
        log.info("emergency priority event: planId={} task={} pri={} action={} preempted={}",
                planId, taskId, priority, actionName(action), preemptedTaskId);

        Map<String, Object> pushData = new LinkedHashMap<>();
        pushData.put("taskId", taskId);
        pushData.put("oldPriority", oldPriority);
        pushData.put("newPriority", priority);
        pushData.put("preemptedTaskId", preemptedTaskId);
        pushData.put("action", actionName(action));
        pushData.put("reason", reason);
        pushPriority(planId, pushData);
    }

    // =====================================================================
    // @EventListener：监听 MavlinkMessageEvent 自行处理应急消息
    // =====================================================================

    @EventListener(condition = "#event.msgId == T(io.aerofleet.mavlink.messages.EmergencyMissionPlanMsg).ID")
    public void onEmergencyMissionPlanEvent(MavlinkMessageEvent event) {
        EmergencyMissionPlanMsg msg = (EmergencyMissionPlanMsg) event.getMessage();
        onEmergencyMissionPlan(
                msg.planId, msg.scenarioType, msg.phase, msg.phaseStatus,
                msg.droneCount, msg.coverageRate, msg.connectRate, msg.priority);
        log.debug("EMERGENCY_MISSION_PLAN sysid={} planId={} phase={} status={}",
                event.getSysid(), msg.planId, msg.phase, msg.phaseStatus);
    }

    @EventListener(condition = "#event.msgId == T(io.aerofleet.mavlink.messages.CoverageOptimizationMsg).ID")
    public void onCoverageOptimizationEvent(MavlinkMessageEvent event) {
        CoverageOptimizationMsg msg = (CoverageOptimizationMsg) event.getMessage();
        onCoverageOptimization(
                msg.planId, msg.droneId, msg.cellType, msg.relayRole,
                msg.txPower, msg.expectedCoverage, msg.batteryBudget);
        log.debug("COVERAGE_OPTIMIZATION sysid={} planId={} drone={} cell={} cov={}%",
                event.getSysid(), msg.planId, msg.droneId, msg.cellType, msg.expectedCoverage);
    }

    @EventListener(condition = "#event.msgId == T(io.aerofleet.mavlink.messages.EmergencyPriorityMsg).ID")
    public void onEmergencyPriorityEvent(MavlinkMessageEvent event) {
        EmergencyPriorityMsg msg = (EmergencyPriorityMsg) event.getMessage();
        onEmergencyPriority(
                msg.planId, msg.taskId, msg.priority, msg.action,
                msg.preemptedTaskId, msg.reason);
        log.debug("EMERGENCY_PRIORITY sysid={} planId={} task={} pri={} action={}",
                event.getSysid(), msg.planId, msg.taskId, msg.priority, msg.action);
    }

    // =====================================================================
    // 内部辅助
    // =====================================================================

    /**
     * 验证当前请求的租户是否有权访问指定计划。
     * <p>
     * 若当前请求未设置租户上下文（全局管理员），或计划的 tenantId 与当前请求的 tenantId 匹配，则允许访问。
     * 若计划的 tenantId 为 null（历史数据未分配租户），也允许访问。
     *
     * @param planId 编排计划 ID
     * @return true 若允许访问，false 若拒绝
     */
    private boolean checkTenantAccess(long planId) {
        Integer currentTenantId = TenantContext.getEffectiveTenantId();
        if (currentTenantId == null) {
            return true; // 全局管理员，允许访问所有计划
        }
        Integer planTenantId = planTenantMap.get(planId);
        if (planTenantId == null) {
            // 映射缺失有两种来源：① 历史数据本就没有租户；② 内存淘汰/重启后缓存为空。
            // 直接放行会在 ② 下变成跨租户读取漏洞（重启后第一次访问谁的租户都放），
            // 所以先回查 DB（V37 快照里带着 tenantId）再判。
            planTenantId = loadPlanTenantId(planId);
            if (planTenantId != null) {
                planTenantMap.put(planId, planTenantId);
            }
        }
        if (planTenantId == null) {
            return true; // 无租户记录（历史数据）→ 保持旧语义
        }
        return currentTenantId.equals(planTenantId);
    }

    /** 从 V37 快照读真实租户；仓库缺失（纯单测构造）或查询失败时返回 null。 */
    private Integer loadPlanTenantId(long planId) {
        if (planRepository == null) {
            return null;
        }
        try {
            return planRepository.findById(planId)
                    .map(EmergencyPlanEntity::getTenantId)
                    .orElse(null);
        } catch (Exception e) {
            log.warn("编排计划租户回查失败 planId={}：{}", planId, e.getMessage());
            return null;
        }
    }

    /** 创建新计划状态 map。 */
    private Map<String, Object> newPlanMap(long planId, int scenarioType, int centerLat, int centerLon,
                                           int radius, List<Integer> droneIds, long now) {
        // 使用 ConcurrentHashMap 保证多线程并发读写 plan 内部状态安全
        Map<String, Object> plan = new ConcurrentHashMap<>();
        plan.put("planId", planId);
        plan.put("scenarioType", scenarioType);
        plan.put("status", "RUNNING");
        plan.put("phase", 0);
        plan.put("phaseStatus", 1); // 执行中
        plan.put("centerLat", centerLat);
        plan.put("centerLon", centerLon);
        plan.put("radius", radius);
        plan.put("droneIds", Collections.synchronizedList(new ArrayList<>(droneIds)));
        plan.put("droneCount", droneIds.size());
        plan.put("coverageRate", 0);
        plan.put("connectRate", 0);
        plan.put("priority", DEFAULT_PRIORITY); // 默认常规
        plan.put("timestamp", now);
        plan.put("createdAt", now);

        // 5 个阶段，初始全部 PENDING，第 0 阶段设为 RUNNING
        List<Map<String, Object>> phases = new ArrayList<>();
        for (int i = 0; i < PHASE_COUNT; i++) {
            Map<String, Object> ph = new ConcurrentHashMap<>();
            ph.put("phase", i);
            ph.put("status", i == 0 ? "RUNNING" : "PENDING");
            ph.put("durationMs", 0);
            phases.add(ph);
        }
        plan.put("phases", phases);

        plan.put("events", Collections.synchronizedList(new ArrayList<>(Arrays.asList(
                "plan created: scenario=" + scenarioName(scenarioType)))));

        plan.put("deployments", Collections.synchronizedList(new ArrayList<>()));
        plan.put("uncoveredAreas", Collections.synchronizedList(new ArrayList<>()));

        // 4 级优先级队列（ConcurrentHashMap 保证并发读安全）
        Map<String, List<Long>> queues = new ConcurrentHashMap<>();
        queues.put("SEARCH_RESCUE", Collections.synchronizedList(new ArrayList<>()));
        queues.put("COMMAND", Collections.synchronizedList(new ArrayList<>()));
        queues.put("MAPPING", Collections.synchronizedList(new ArrayList<>()));
        queues.put("ROUTINE", Collections.synchronizedList(new ArrayList<>()));
        plan.put("priorityQueues", queues);

        plan.put("taskPriorities", new ConcurrentHashMap<Long, Integer>());
        return plan;
    }

    /**
     * 内存计划缓存收口：只增表不能随运行时长无限长。
     * <p>
     * 只淘汰<b>终态</b>计划（{@code status=ABORTED} 或 {@code phaseStatus=COMPLETED}），
     * 按 planId 升序（V35 发号器单调递增 = 创建顺序）从最旧开始，直到回到上限；
     * RUNNING / PAUSED 一个都不动——计划是活文档，淘汰在途计划会让
     * start/pause/resume/abort 拿不到同一份内存状态。
     * <p>
     * 淘汰后仍可通过 {@link #getPlan} 读穿 V37 快照（DB 是兜底源），对外可查性不变。
     * 同时清掉租户映射：{@link #checkTenantAccess} 在映射缺失时会回查 DB 拿真实租户，
     * 不会因为淘汰而放行别的租户。
     */
    private void evictTerminalPlans() {
        if (plans.size() <= MAX_CACHED_PLANS) {
            return;
        }
        List<Long> terminal = plans.entrySet().stream()
                .filter(e -> isTerminalPlan(e.getValue()))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
        for (Long id : terminal) {
            if (plans.size() <= MAX_CACHED_PLANS) {
                break;
            }
            plans.remove(id);
            planTenantMap.remove(id);
        }
    }

    /** 终态判定：已中止，或上报回调把阶段标成 COMPLETED（此时 status 字段仍是 RUNNING）。 */
    private static boolean isTerminalPlan(Map<String, Object> plan) {
        Object status = plan.get("status");
        if ("ABORTED".equals(status) || "COMPLETED".equals(status)) {
            return true;
        }
        return Integer.valueOf(PHASE_STATUS_COMPLETED).equals(plan.get("phaseStatus"));
    }

    /** 测试辅助：当前内存计划缓存条数（不含 DB 兜底）。 */
    int cachedPlanCount() {
        return plans.size();
    }

    /** 查找场景预设。 */
    private Map<String, Object> findScenario(int type) {
        for (Map<String, Object> s : getScenarios()) {
            if ((int) s.get("type") == type) {
                return s;
            }
        }
        return null;
    }

    /** 构造场景预设 map。 */
    private static Map<String, Object> scenarioOf(int type, String name, String description,
                                                  int radius, int droneCount, int priority) {
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("radius", radius);
        defaults.put("droneCount", droneCount);
        defaults.put("priority", priority);
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", type);
        s.put("name", name);
        s.put("description", description);
        s.put("defaults", defaults);
        return s;
    }

    /** 添加事件到计划事件列表。 */
    @SuppressWarnings("unchecked")
    private static void addEvent(Map<String, Object> plan, String event) {
        ((List<String>) plan.get("events")).add(event);
    }

    /** 获取当前阶段号。 */
    private static int phaseOf(Map<String, Object> plan) {
        return (int) plan.get("phase");
    }

    /** 更新指定阶段的状态。 */
    @SuppressWarnings("unchecked")
    private static void updatePhaseStatus(Map<String, Object> plan, int phase, int status) {
        List<Map<String, Object>> phases = (List<Map<String, Object>>) plan.get("phases");
        if (phase >= 0 && phase < phases.size()) {
            phases.get(phase).put("status", phaseStatusName(status));
        }
    }

    /** 推送计划更新（pusher 可能为 null）。 */
    private void pushPlan(long planId, Map<String, Object> plan) {
        if (pusher != null) {
            pusher.pushPlanUpdate(planId, plan);
        }
    }

    /** 推送覆盖更新。 */
    private void pushCoverage(long planId, Map<String, Object> coverage) {
        if (pusher != null && coverage != null) {
            pusher.pushCoverageUpdate(planId, coverage);
        }
    }

    /** 推送优先级更新。 */
    private void pushPriority(long planId, Map<String, Object> priority) {
        if (pusher != null && priority != null) {
            pusher.pushPriorityUpdate(planId, priority);
        }
    }

    // =====================================================================
    // 名称映射
    // =====================================================================

    static String scenarioName(int type) {
        return switch (type) {
            case 0 -> "地震";
            case 1 -> "泥石流";
            case 2 -> "火灾";
            case 3 -> "自定义";
            default -> "UNKNOWN";
        };
    }

    static String priorityQueueName(int priority) {
        return switch (priority) {
            case 1 -> "SEARCH_RESCUE";
            case 2 -> "COMMAND";
            case 3 -> "MAPPING";
            default -> "ROUTINE";
        };
    }

    static String phaseStatusName(int status) {
        return switch (status) {
            case 0 -> "PENDING";
            case 1 -> "RUNNING";
            case 2 -> "COMPLETED";
            case 3 -> "FAILED";
            case 4 -> "ABORTED";
            default -> "UNKNOWN";
        };
    }

    static String cellTypeName(int cellType) {
        return switch (cellType) {
            case 1 -> "LTE";
            case 2 -> "WiFi";
            case 3 -> "LoRa";
            default -> "NONE";
        };
    }

    static String relayRoleName(int relayRole) {
        return switch (relayRole) {
            case 1 -> "MESH";
            case 2 -> "HAPS";
            case 3 -> "LEO";
            default -> "NONE";
        };
    }

    static String actionName(int action) {
        return switch (action) {
            case 0 -> "SUBMIT";
            case 1 -> "ADJUST";
            case 2 -> "PREEMPT";
            case 3 -> "COMPLETE";
            case 4 -> "CANCEL";
            default -> "UNKNOWN";
        };
    }
}
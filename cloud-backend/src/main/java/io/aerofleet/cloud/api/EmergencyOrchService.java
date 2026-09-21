package io.aerofleet.cloud.api;

import io.aerofleet.cloud.orch.event.EmergencyStartEvent;
import io.aerofleet.cloud.orch.event.EmergencyEndEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
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
 * 重规划/优先级调整等业务能力，并接收 MAVLink 465/466/467 上报更新计划状态。
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
    /** planId 生成器。 */
    private final AtomicLong planIdGenerator = new AtomicLong(10000);
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
        long planId = planIdGenerator.incrementAndGet();
        long now = System.currentTimeMillis();
        Map<String, Object> plan = newPlanMap(planId, scenarioType, centerLat, centerLon, radius,
                droneIds, now);
        plans.put(planId, plan);
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
        Map<String, Object> plan = plans.get(planId);
        if (plan == null) {
            return false;
        }
        plan.put("status", "ABORTED");
        plan.put("timestamp", System.currentTimeMillis());
        addEvent(plan, "plan aborted");
        updatePhaseStatus(plan, phaseOf(plan), 4); // 4=ABORTED
        log.info("emergency plan aborted: planId={}", planId);
        pushPlan(planId, plan);
        eventPublisher.publishEvent(new EmergencyEndEvent(this, planId));
        return true;
    }

    /**
     * 获取计划状态 map；不存在返回 null。
     */
    public Map<String, Object> getPlan(long planId) {
        return plans.get(planId);
    }

    /**
     * 获取阶段进度与事件列表。
     * <p>
     * 返回防御性浅拷贝：调用方修改返回的列表不会影响 plan 内部状态
     * （phases 元素为 ConcurrentHashMap，浅拷贝保留阶段状态的并发更新）。
     */
    public Map<String, Object> getProgress(long planId) {
        Map<String, Object> plan = plans.get(planId);
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
        Map<String, Object> plan = plans.get(planId);
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
            oldPriority = taskPriorities.getOrDefault(taskId, 4); // 默认常规
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
                for (int p = priority + 1; p <= 4; p++) {
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
     * EMERGENCY_MISSION_PLAN (465) 上报回调：更新计划阶段/状态/覆盖/连通率。
     */
    public void onEmergencyMissionPlan(long planId, int scenarioType, int phase, int phaseStatus,
                                       int droneCount, int coverageRate, int connectRate, int priority) {
        Map<String, Object> plan = plans.get(planId);
        if (plan == null) {
            log.debug("emergency mission plan for unknown planId={}: create implicitly", planId);
            plan = newPlanMap(planId, scenarioType, 0, 0, 0,
                    Collections.emptyList(), System.currentTimeMillis());
            plans.put(planId, plan);
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
        log.debug("emergency mission plan update: planId={} phase={} status={} cov={}%",
                planId, phase, phaseStatus, coverageRate);
        pushPlan(planId, plan);
        if (phaseStatus == 3) { // COMPLETED
            eventPublisher.publishEvent(new EmergencyEndEvent(this, planId));
        }
    }

    /**
     * COVERAGE_OPTIMIZATION (466) 上报回调：记录单架无人机覆盖部署方案。
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
     * EMERGENCY_PRIORITY (467) 上报回调：记录优先级调度事件。
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
        int oldPriority = taskPriorities.getOrDefault(taskId, 4);
        taskPriorities.put(taskId, priority);

        @SuppressWarnings("unchecked")
        Map<String, List<Long>> queues = (Map<String, List<Long>>) plan.get("priorityQueues");
        String queueName = priorityQueueName(priority);
        if (!queues.get(queueName).contains(taskId)) {
            queues.get(queueName).add(taskId);
        }
        if (action == 4) { // 取消
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
    // 内部辅助
    // =====================================================================

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
        plan.put("priority", 4); // 默认常规
        plan.put("timestamp", now);
        plan.put("createdAt", now);

        // 5 个阶段，初始全部 PENDING，第 0 阶段设为 RUNNING
        List<Map<String, Object>> phases = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
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
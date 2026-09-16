package io.aerofleet.sim.orch;

import io.aerofleet.sim.SimLog;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 应急任务编排引擎（M9 应急任务编排，T2 编排引擎核心）。
 * <p>
 * 核心职责：
 * <ul>
 *   <li>管理多个并发 OrchestrationPlan（ConcurrentHashMap + AtomicLong 生成 planId）</li>
 *   <li>驱动 5 阶段状态机：DISASTER_MAPPING → COVERAGE_PLANNING → NETWORK_DEPLOYMENT
 *       → CONTINUOUS_SERVICE → SELF_HEALING</li>
 *   <li>通过 4 个集成接口（Mesh/CellTower/SatRelay/Terrain）与各子系统协作，
 *       接口可为 null（表示该模块未启用），调用前 null 检查</li>
 *   <li>响应无人机丢失、低电量、地形变化等事件</li>
 *   <li>由 tick(nowMs) 周期驱动持续服务阶段</li>
 * </ul>
 * 线程安全：plans 用 ConcurrentHashMap，planId 用 AtomicLong，plan 内部自身线程安全。
 */
public class OrchestrationEngine {

    private final ConcurrentHashMap<Long, OrchestrationPlan> plans;
    private final OrchestrationConfig config;
    private final AtomicLong planIdGenerator;

    // 集成接口（可为 null，表示该模块未启用）
    private final MeshIntegration meshIntegration;
    private final CellTowerIntegration cellIntegration;
    private final SatRelayIntegration satIntegration;
    private final TerrainIntegration terrainIntegration;

    /**
     * 构造器。
     *
     * @param config             编排配置
     * @param meshIntegration    mesh 集成接口（可 null）
     * @param cellIntegration    基站集成接口（可 null）
     * @param satIntegration     卫星中继集成接口（可 null）
     * @param terrainIntegration 地形集成接口（可 null）
     */
    public OrchestrationEngine(OrchestrationConfig config,
                               MeshIntegration meshIntegration,
                               CellTowerIntegration cellIntegration,
                               SatRelayIntegration satIntegration,
                               TerrainIntegration terrainIntegration) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        this.config = config;
        this.plans = new ConcurrentHashMap<Long, OrchestrationPlan>();
        this.planIdGenerator = new AtomicLong(1);
        this.meshIntegration = meshIntegration;
        this.cellIntegration = cellIntegration;
        this.satIntegration = satIntegration;
        this.terrainIntegration = terrainIntegration;
    }

    /**
     * 启动一次应急任务编排。
     *
     * @param scenarioType 场景类型（0~3）
     * @param centerLat    灾区中心纬度（degE7）
     * @param centerLon    灾区中心经度（degE7）
     * @param radius       灾区半径（m）
     * @param droneIds     参与无人机 ID 列表
     * @return planId（全局唯一）
     */
    public long startOrchestration(int scenarioType, int centerLat, int centerLon,
                                   int radius, List<Integer> droneIds) {
        if (!config.enabled) {
            throw new IllegalStateException("OrchestrationEngine is disabled (config.enabled=false)");
        }
        if (plans.size() >= config.maxConcurrentPlans) {
            throw new IllegalStateException("Max concurrent plans reached: " + config.maxConcurrentPlans);
        }
        if (droneIds == null || droneIds.size() > config.maxDrones) {
            throw new IllegalArgumentException("droneIds count must be in [1," + config.maxDrones + "]");
        }

        long planId = planIdGenerator.getAndIncrement();
        OrchestrationPlan plan = new OrchestrationPlan(planId, scenarioType,
                centerLat, centerLon, radius, droneIds);
        plans.put(planId, plan);

        logEvent(plan, "PlanCreated scenario=" + scenarioType + " drones=" + droneIds.size());
        SimLog.info("Orchestration plan " + planId + " created (scenario=" + scenarioType + ")");

        // 从阶段 0 开始执行
        executePhase(plan, 0);
        return planId;
    }

    /**
     * 中止编排计划：当前阶段 → ABORTED。
     *
     * @param planId 计划 ID
     */
    public void abortOrchestration(long planId) {
        OrchestrationPlan plan = plans.get(planId);
        if (plan == null) {
            SimLog.warn("abortOrchestration: plan " + planId + " not found");
            return;
        }
        OrchestrationState current = plan.getCurrentPhaseState();
        current.abort();
        logEvent(plan, "PlanAborted phase=" + current.getPhase().label);
        SimLog.info("Orchestration plan " + planId + " aborted at phase " + current.getPhase().label);
    }

    /**
     * 查询计划。
     *
     * @param planId 计划 ID
     * @return 计划对象，不存在返回 null
     */
    public OrchestrationPlan getPlan(long planId) {
        return plans.get(planId);
    }

    /**
     * 无人机丢失事件：从 mesh 移成移除节点，记录日志。
     *
     * @param droneId 无人机 ID
     */
    public void onDroneLost(int droneId) {
        if (meshIntegration != null) {
            meshIntegration.removeNode(droneId);
        }
        if (cellIntegration != null) {
            cellIntegration.removeCellTower(droneId);
        }
        for (OrchestrationPlan plan : plans.values()) {
            if (plan.getDroneIds().contains(droneId)) {
                logEvent(plan, "DroneLost droneId=" + droneId);
                plan.setCurrentPriority(2);  // 提升优先级
            }
        }
        SimLog.warn("Drone lost: " + droneId);
    }

    /**
     * 低电量事件。
     *
     * @param droneId 无人机 ID
     * @param battery 当前电量（%）
     */
    public void onLowBattery(int droneId, int battery) {
        for (OrchestrationPlan plan : plans.values()) {
            if (plan.getDroneIds().contains(droneId)) {
                if (battery <= config.criticalBatteryThreshold) {
                    logEvent(plan, "CriticalBattery droneId=" + droneId + " battery=" + battery);
                    plan.setCurrentPriority(2);
                } else if (battery <= config.lowBatteryThreshold) {
                    logEvent(plan, "LowBattery droneId=" + droneId + " battery=" + battery);
                }
            }
        }
        if (battery <= config.criticalBatteryThreshold) {
            SimLog.warn("Critical battery: droneId=" + droneId + " battery=" + battery);
        }
    }

    /**
     * 地形变化事件：触发灾区重测绘（简化为记录日志）。
     *
     * @param gridX 网格 X
     * @param gridY 网格 Y
     */
    public void onTerrainChanged(int gridX, int gridY) {
        for (OrchestrationPlan plan : plans.values()) {
            logEvent(plan, "TerrainChanged grid=(" + gridX + "," + gridY + ")");
        }
        SimLog.info("Terrain changed at grid (" + gridX + "," + gridY + ")");
    }

    /**
     * 执行指定阶段：启动状态、调用对应 doXxx、完成或失败。
     *
     * @param plan       计划
     * @param phaseIndex 阶段序号
     */
    private void executePhase(OrchestrationPlan plan, int phaseIndex) {
        OrchestrationState state = plan.getPhaseState(phaseIndex);
        try {
            state.start();
            logEvent(plan, "PhaseStart " + state.getPhase().label);

            switch (phaseIndex) {
                case 0:
                    doDisasterMapping(plan);
                    break;
                case 1:
                    doCoveragePlanning(plan);
                    break;
                case 2:
                    doNetworkDeployment(plan);
                    break;
                case 3:
                    doContinuousService(plan);
                    break;
                case 4:
                    doSelfHealing(plan);
                    break;
                default:
                    throw new IllegalStateException("Unknown phase index: " + phaseIndex);
            }

            // CONTINUOUS_SERVICE 不立即 complete，由 tick 驱动
            if (phaseIndex != 3) {
                state.complete();
                logEvent(plan, "PhaseComplete " + state.getPhase().label);
                // 自动推进到下一阶段（CONTINUOUS_SERVICE 除外，由 tick 推进）
                if (plan.advancePhase()) {
                    executePhase(plan, plan.getCurrentPhaseIndex());
                }
            }
        } catch (Exception e) {
            failPhase(plan, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    /**
     * 阶段 0：灾区测绘。调用 terrainIntegration 获取地形信息。
     * terrainIntegration 为 null 时跳过（空实现）。
     */
    private void doDisasterMapping(OrchestrationPlan plan) {
        if (terrainIntegration == null) {
            logEvent(plan, "DisasterMapping skipped (terrainIntegration=null)");
            return;
        }
        double centerLat = plan.getDisasterCenterLat() / 1e7;
        double centerLon = plan.getDisasterCenterLon() / 1e7;
        int terrainType = terrainIntegration.getTerrainType(centerLat, centerLon);
        boolean flightAllowed = terrainIntegration.isFlightAllowed(centerLat, centerLon, 100.0);
        logEvent(plan, "DisasterMapping terrainType=" + terrainType + " flightAllowed=" + flightAllowed);
    }

    /**
     * 阶段 1：覆盖规划。简化实现：生成基础部署方案占位。
     * 不调用 CoverageOptimizer（由 T5 集成时连接）。
     */
    private void doCoveragePlanning(OrchestrationPlan plan) {
        // 占位部署方案：用 String 表示，T3/T5 会替换为 DeploymentPlan
        Object placeholderPlan = "CoveragePlan(planId=" + plan.getPlanId()
                + ", drones=" + plan.getDroneIds().size()
                + ", radius=" + plan.getDisasterRadius() + ")";
        plan.setDeploymentPlan(placeholderPlan);
        plan.setCoverageRate(0);
        logEvent(plan, "CoveragePlanning done (placeholder)");
    }

    /**
     * 阶段 2：组网部署。调用 mesh/cell/sat 集成接口部署。
     * 各接口为 null 时跳过对应子步骤。
     */
    private void doNetworkDeployment(OrchestrationPlan plan) {
        double centerLat = plan.getDisasterCenterLat() / 1e7;
        double centerLon = plan.getDisasterCenterLon() / 1e7;
        List<Integer> drones = plan.getDroneIds();

        // mesh 部署
        if (meshIntegration != null) {
            for (int id : drones) {
                meshIntegration.addNode(id, centerLat, centerLon);
            }
            logEvent(plan, "NetworkDeployment mesh nodes=" + drones.size());
        }

        // 基站部署（每 5 架选一架做基站，简化）
        if (cellIntegration != null) {
            int towerCount = 0;
            for (int i = 0; i < drones.size(); i += 5) {
                cellIntegration.createCellTower(drones.get(i), 0, centerLat, centerLon, 23);
                towerCount++;
            }
            logEvent(plan, "NetworkDeployment cellTowers=" + towerCount);
        }

        // 卫星中继（如果星链可用）
        if (satIntegration != null && satIntegration.isSatLinkAvailable()) {
            if (!drones.isEmpty()) {
                satIntegration.assignRelayRole(drones.get(0), 1);
                satIntegration.enableHapsRelay(drones.get(0));
            }
            logEvent(plan, "NetworkDeployment satRelay enabled");
        }

        plan.setConnectRate(100);
    }

    /**
     * 阶段 3：持续服务。空实现，由 tick 驱动监控。
     */
    private void doContinuousService(OrchestrationPlan plan) {
        logEvent(plan, "ContinuousService entered (tick-driven)");
    }

    /**
     * 阶段 4：自愈重构。简化实现：记录日志。
     */
    private void doSelfHealing(OrchestrationPlan plan) {
        logEvent(plan, "SelfHealing done (simplified)");
    }

    /**
     * 阶段转换：推进到新阶段并执行。
     *
     * @param plan     计划
     * @param newIndex 新阶段序号
     */
    private void transitionPhase(OrchestrationPlan plan, int newIndex) {
        logEvent(plan, "PhaseTransition " + plan.getCurrentPhase().label + " -> "
                + OrchestrationPhase.fromCode(newIndex).label);
        executePhase(plan, newIndex);
    }

    /**
     * 阶段失败处理：记录失败原因，可重试则重试，否则中止。
     *
     * @param plan   计划
     * @param reason 失败原因
     */
    private void failPhase(OrchestrationPlan plan, String reason) {
        OrchestrationState state = plan.getCurrentPhaseState();
        if (state.getStatus() == OrchestrationState.Status.RUNNING) {
            state.fail(reason);
        }
        logEvent(plan, "PhaseFail " + state.getPhase().label + " reason=" + reason
                + " retry=" + state.getRetryCount() + "/" + OrchestrationState.MAX_RETRY);

        if (state.canRetry() && state.getRetryCount() < config.maxRetryPerPhase) {
            try {
                state.retry();
                logEvent(plan, "PhaseRetry " + state.getPhase().label);
                executePhase(plan, plan.getCurrentPhaseIndex());
            } catch (IllegalStateException e) {
                logEvent(plan, "PhaseRetryExhausted " + state.getPhase().label);
                state.abort();
            }
        } else {
            state.abort();
            logEvent(plan, "PhaseAborted " + state.getPhase().label + " (retries exhausted)");
        }
    }

    /** 记录事件日志（带 planId 前缀）。 */
    private void logEvent(OrchestrationPlan plan, String event) {
        plan.logEvent(event);
    }

    /**
     * 周期 tick：由 VirtualDrone 调用驱动持续服务阶段。
     * 检查各 plan 当前阶段是否超时，超时则失败处理。
     *
     * @param nowMs 当前时间戳（ms）
     */
    public void tick(long nowMs) {
        for (OrchestrationPlan plan : plans.values()) {
            OrchestrationState current = plan.getCurrentPhaseState();
            if (current.getStatus() != OrchestrationState.Status.RUNNING) {
                continue;
            }
            // 超时检查
            long timeout = current.getPhase().timeoutMs;
            if (timeout != Long.MAX_VALUE && current.getStartTimeMs() > 0) {
                if (nowMs - current.getStartTimeMs() > timeout) {
                    failPhase(plan, "Timeout " + current.getPhase().label);
                }
            }
            // CONTINUOUS_SERVICE 阶段：可在此添加持续监控逻辑
        }
    }

    // ---- getters for testing/diagnostics ----

    public OrchestrationConfig getConfig() {
        return config;
    }

    public int getPlanCount() {
        return plans.size();
    }

    public MeshIntegration getMeshIntegration() {
        return meshIntegration;
    }

    public CellTowerIntegration getCellIntegration() {
        return cellIntegration;
    }

    public SatRelayIntegration getSatIntegration() {
        return satIntegration;
    }

    public TerrainIntegration getTerrainIntegration() {
        return terrainIntegration;
    }
}
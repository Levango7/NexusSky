package io.aerofleet.sim.orch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 应急任务编排计划（M9 应急任务编排，T2 编排引擎核心）。
 * <p>
 * 线程安全：phaseStates 用 CopyOnWriteArrayList，eventLog 用 ConcurrentLinkedQueue，
 * 可变指标用 volatile，planId/scenarioType 等不可变字段用 final。
 * <p>
 * 一个计划对应一次应急任务编排，包含 5 个阶段状态、参与无人机列表、
 * 实时指标（覆盖率、连通率、当前优先级）与事件日志。
 * deploymentPlan 字段类型为 Object，避免对 T3 DeploymentPlan 的编译依赖。
 */
public class OrchestrationPlan {

    /** 场景类型：地震。 */
    public static final int SCENARIO_EARTHQUAKE = 0;
    /** 场景类型：泥石流。 */
    public static final int SCENARIO_MUDSLIDE = 1;
    /** 场景类型：火灾。 */
    public static final int SCENARIO_FIRE = 2;
    /** 场景类型：自定义。 */
    public static final int SCENARIO_CUSTOM = 3;

    private final long planId;
    private final int scenarioType;
    private final int disasterCenterLat;  // degE7
    private final int disasterCenterLon;  // degE7
    private final int disasterRadius;     // m
    private final List<Integer> droneIds;
    private final List<OrchestrationState> phaseStates;  // 5 个阶段
    private final long createTimeMs;
    private volatile int currentPhaseIndex;
    private volatile int coverageRate;       // 0~100
    private volatile int connectRate;        // 0~100
    private volatile int currentPriority;    // 0=低 1=中 2=高
    private final ConcurrentLinkedQueue<String> eventLog;
    // T3 会创建 DeploymentPlan 类；此处用 Object 占位避免编译依赖
    private volatile Object deploymentPlan;

    /**
     * 构造器。
     *
     * @param planId            计划 ID
     * @param scenarioType      场景类型（0~3）
     * @param disasterCenterLat 灾区中心纬度（degE7）
     * @param disasterCenterLon 灾区中心经度（degE7）
     * @param disasterRadius    灾区半径（m）
     * @param droneIds          参与无人机 ID 列表
     */
    public OrchestrationPlan(long planId, int scenarioType,
                             int disasterCenterLat, int disasterCenterLon,
                             int disasterRadius, List<Integer> droneIds) {
        if (scenarioType < 0 || scenarioType > 3) {
            throw new IllegalArgumentException("scenarioType must be in [0,3], got " + scenarioType);
        }
        if (disasterRadius <= 0) {
            throw new IllegalArgumentException("disasterRadius must be > 0, got " + disasterRadius);
        }
        if (droneIds == null || droneIds.isEmpty()) {
            throw new IllegalArgumentException("droneIds must not be null or empty");
        }
        this.planId = planId;
        this.scenarioType = scenarioType;
        this.disasterCenterLat = disasterCenterLat;
        this.disasterCenterLon = disasterCenterLon;
        this.disasterRadius = disasterRadius;
        this.droneIds = Collections.unmodifiableList(new ArrayList<Integer>(droneIds));
        this.phaseStates = new CopyOnWriteArrayList<OrchestrationState>();
        for (OrchestrationPhase ph : OrchestrationPhase.values()) {
            this.phaseStates.add(new OrchestrationState(ph));
        }
        this.createTimeMs = System.currentTimeMillis();
        this.currentPhaseIndex = 0;
        this.coverageRate = 0;
        this.connectRate = 0;
        this.currentPriority = 2;  // 默认高优先级
        this.eventLog = new ConcurrentLinkedQueue<String>();
        this.deploymentPlan = null;
    }

    /** 记录事件日志（线程安全）。 */
    public void logEvent(String event) {
        if (event == null) {
            return;
        }
        eventLog.add(System.currentTimeMillis() + "|" + event);
    }

    /** 获取阶段状态（index 0~4）。 */
    public OrchestrationState getPhaseState(int index) {
        if (index < 0 || index >= phaseStates.size()) {
            throw new IllegalArgumentException("phase index out of range: " + index);
        }
        return phaseStates.get(index);
    }

    /** 获取当前阶段状态。 */
    public OrchestrationState getCurrentPhaseState() {
        return phaseStates.get(currentPhaseIndex);
    }

    /** 获取当前阶段枚举。 */
    public OrchestrationPhase getCurrentPhase() {
        return phaseStates.get(currentPhaseIndex).getPhase();
    }

    /** 推进到下一阶段；已是最后阶段则返回 false。 */
    public boolean advancePhase() {
        if (currentPhaseIndex >= phaseStates.size() - 1) {
            return false;
        }
        currentPhaseIndex++;
        return true;
    }

    /** 是否已完成所有阶段。 */
    public boolean isFinished() {
        return phaseStates.get(phaseStates.size() - 1).getStatus() == OrchestrationState.Status.COMPLETED
                || phaseStates.get(phaseStates.size() - 1).getStatus() == OrchestrationState.Status.ABORTED;
    }

    // ---- setter for volatile metrics ----

    public void setCoverageRate(int rate) {
        if (rate < 0 || rate > 100) {
            throw new IllegalArgumentException("coverageRate must be in [0,100], got " + rate);
        }
        this.coverageRate = rate;
    }

    public void setConnectRate(int rate) {
        if (rate < 0 || rate > 100) {
            throw new IllegalArgumentException("connectRate must be in [0,100], got " + rate);
        }
        this.connectRate = rate;
    }

    public void setCurrentPriority(int priority) {
        if (priority < 0 || priority > 2) {
            throw new IllegalArgumentException("priority must be in [0,2], got " + priority);
        }
        this.currentPriority = priority;
    }

    public void setDeploymentPlan(Object deploymentPlan) {
        this.deploymentPlan = deploymentPlan;
    }

    // ---- getters ----

    public long getPlanId() {
        return planId;
    }

    public int getScenarioType() {
        return scenarioType;
    }

    public int getDisasterCenterLat() {
        return disasterCenterLat;
    }

    public int getDisasterCenterLon() {
        return disasterCenterLon;
    }

    public int getDisasterRadius() {
        return disasterRadius;
    }

    public List<Integer> getDroneIds() {
        return droneIds;
    }

    public List<OrchestrationState> getPhaseStates() {
        return Collections.unmodifiableList(phaseStates);
    }

    public long getCreateTimeMs() {
        return createTimeMs;
    }

    public int getCurrentPhaseIndex() {
        return currentPhaseIndex;
    }

    public int getCoverageRate() {
        return coverageRate;
    }

    public int getConnectRate() {
        return connectRate;
    }

    public int getCurrentPriority() {
        return currentPriority;
    }

    /** 获取事件日志快照（线程安全）。 */
    public List<String> getEventLog() {
        List<String> snapshot = new ArrayList<String>();
        for (String e : eventLog) {
            snapshot.add(e);
        }
        return Collections.unmodifiableList(snapshot);
    }

    public Object getDeploymentPlan() {
        return deploymentPlan;
    }

    @Override
    public String toString() {
        return "OrchestrationPlan{id=" + planId
                + ", scenario=" + scenarioType
                + ", center=(" + disasterCenterLat + "," + disasterCenterLon + ")"
                + ", radius=" + disasterRadius + "m"
                + ", drones=" + droneIds.size()
                + ", phase=" + currentPhaseIndex + "(" + getCurrentPhase().label + ")"
                + ", coverage=" + coverageRate + "%"
                + ", connect=" + connectRate + "%"
                + ", priority=" + currentPriority + "}";
    }
}
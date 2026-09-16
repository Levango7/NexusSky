package io.aerofleet.sim.orch;

/**
 * 应急任务编排配置（M9 应急任务编排，T2 编排引擎核心）。
 * <p>
 * 不可变配置对象，参照 MeshRouterConfig 风格。所有字段 final，构造器做参数验证。
 * <p>
 * 默认值（design.md M9 配置项取值策略）：
 * <pre>
 * enabled                     = false
 * maxConcurrentPlans          = 10
 * maxDrones                   = 50
 * heartbeatTimeoutMs          = 30000 ms
 * lowBatteryThreshold         = 30 %
 * criticalBatteryThreshold    = 15 %
 * maxRetryPerPhase            = 3
 * coverageOptStepM            = 500 m
 * localOptRangeM              = 100 m
 * localOptIterations          = 3
 * meshOneHopRangeM            = 2000 m
 * coverageDeclineReplanThreshold = 10.0 %
 * </pre>
 */
public final class OrchestrationConfig {

    /** 是否启用编排引擎。 */
    public final boolean enabled;
    /** 最大并发编排计划数。 */
    public final int maxConcurrentPlans;
    /** 单计划最大无人机数。 */
    public final int maxDrones;
    /** 心跳超时阈值（ms）。 */
    public final long heartbeatTimeoutMs;
    /** 低电量阈值（%）。 */
    public final int lowBatteryThreshold;
    /** 危急电量阈值（%）。 */
    public final int criticalBatteryThreshold;
    /** 单阶段最大重试次数。 */
    public final int maxRetryPerPhase;
    /** 覆盖优化步长（m）。 */
    public final double coverageOptStepM;
    /** 局部优化范围（m）。 */
    public final double localOptRangeM;
    /** 局部优化迭代次数。 */
    public final int localOptIterations;
    /** mesh 单跳通信范围（m）。 */
    public final double meshOneHopRangeM;
    /** 覆盖率下降触发重规划阈值（%）。 */
    public final double coverageDeclineReplanThreshold;

    public OrchestrationConfig(boolean enabled, int maxConcurrentPlans, int maxDrones,
                               long heartbeatTimeoutMs, int lowBatteryThreshold,
                               int criticalBatteryThreshold, int maxRetryPerPhase,
                               double coverageOptStepM, double localOptRangeM,
                               int localOptIterations, double meshOneHopRangeM,
                               double coverageDeclineReplanThreshold) {
        if (maxConcurrentPlans <= 0) {
            throw new IllegalArgumentException("maxConcurrentPlans must be > 0, got " + maxConcurrentPlans);
        }
        if (maxDrones <= 0) {
            throw new IllegalArgumentException("maxDrones must be > 0, got " + maxDrones);
        }
        if (heartbeatTimeoutMs <= 0) {
            throw new IllegalArgumentException("heartbeatTimeoutMs must be > 0, got " + heartbeatTimeoutMs);
        }
        if (lowBatteryThreshold < 0 || lowBatteryThreshold > 100) {
            throw new IllegalArgumentException("lowBatteryThreshold must be in [0,100], got " + lowBatteryThreshold);
        }
        if (criticalBatteryThreshold < 0 || criticalBatteryThreshold > 100) {
            throw new IllegalArgumentException("criticalBatteryThreshold must be in [0,100], got " + criticalBatteryThreshold);
        }
        if (criticalBatteryThreshold > lowBatteryThreshold) {
            throw new IllegalArgumentException("criticalBatteryThreshold (" + criticalBatteryThreshold
                    + ") must be <= lowBatteryThreshold (" + lowBatteryThreshold + ")");
        }
        if (maxRetryPerPhase <= 0) {
            throw new IllegalArgumentException("maxRetryPerPhase must be > 0, got " + maxRetryPerPhase);
        }
        if (coverageOptStepM <= 0 || Double.isNaN(coverageOptStepM)) {
            throw new IllegalArgumentException("coverageOptStepM must be > 0, got " + coverageOptStepM);
        }
        if (localOptRangeM <= 0 || Double.isNaN(localOptRangeM)) {
            throw new IllegalArgumentException("localOptRangeM must be > 0, got " + localOptRangeM);
        }
        if (localOptIterations <= 0) {
            throw new IllegalArgumentException("localOptIterations must be > 0, got " + localOptIterations);
        }
        if (meshOneHopRangeM <= 0 || Double.isNaN(meshOneHopRangeM)) {
            throw new IllegalArgumentException("meshOneHopRangeM must be > 0, got " + meshOneHopRangeM);
        }
        if (coverageDeclineReplanThreshold < 0 || Double.isNaN(coverageDeclineReplanThreshold)) {
            throw new IllegalArgumentException("coverageDeclineReplanThreshold must be >= 0, got " + coverageDeclineReplanThreshold);
        }
        this.enabled = enabled;
        this.maxConcurrentPlans = maxConcurrentPlans;
        this.maxDrones = maxDrones;
        this.heartbeatTimeoutMs = heartbeatTimeoutMs;
        this.lowBatteryThreshold = lowBatteryThreshold;
        this.criticalBatteryThreshold = criticalBatteryThreshold;
        this.maxRetryPerPhase = maxRetryPerPhase;
        this.coverageOptStepM = coverageOptStepM;
        this.localOptRangeM = localOptRangeM;
        this.localOptIterations = localOptIterations;
        this.meshOneHopRangeM = meshOneHopRangeM;
        this.coverageDeclineReplanThreshold = coverageDeclineReplanThreshold;
    }

    /** 默认配置。 */
    public static OrchestrationConfig defaults() {
        return new OrchestrationConfig(
                false, 10, 50, 30000L,
                30, 15, 3,
                500.0, 100.0, 3,
                2000.0, 10.0);
    }

    @Override
    public String toString() {
        return "OrchestrationConfig{enabled=" + enabled
                + ", maxConcurrentPlans=" + maxConcurrentPlans
                + ", maxDrones=" + maxDrones
                + ", heartbeatTimeout=" + heartbeatTimeoutMs + "ms"
                + ", lowBattery=" + lowBatteryThreshold + "%"
                + ", criticalBattery=" + criticalBatteryThreshold + "%"
                + ", maxRetryPerPhase=" + maxRetryPerPhase
                + ", coverageOptStep=" + coverageOptStepM + "m"
                + ", localOptRange=" + localOptRangeM + "m"
                + ", localOptIterations=" + localOptIterations
                + ", meshOneHopRange=" + meshOneHopRangeM + "m"
                + ", coverageDeclineReplanThreshold=" + coverageDeclineReplanThreshold + "%}";
    }
}

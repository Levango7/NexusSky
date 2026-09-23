package io.aerofleet.sim.orch;

import io.aerofleet.sim.BudgetMode;

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

    // ==================== BudgetConstraints ====================

    /**
     * 丐版预算约束（M9 编排引擎丐版约束感知）。
     * <p>
     * 根据 {@link BudgetMode} 的硬件能力差异，定义覆盖优化参数约束。
     * 不可变值对象，所有字段 final。
     * <p>
     * 约束映射表（参考 docs/new-features-plan.md 2.3.3 节）：
     * <pre>
     * | 优化参数           | Full Mode    | Emergency-Standard | Emergency-Toy |
     * |-------------------|--------------|--------------------|---------------|
     * | 最大覆盖半径       | 5km(LTE)     | 2km(LoRa)          | 200m(WiFi)    |
     * | 最大节点数         | 100+         | 20                 | 5-8           |
     * | 最大续航           | 30min        | 8min               | 5min          |
     * | 避障距离           | 100m(雷达)   | 4m(ToF)            | 2m(超声波)    |
     * | 搜救能力           | 热成像640×480| 热源8×8            | LED+蜂鸣器    |
     * | 编排复杂度         | 全量         | 简化(无卫星层级)    | 最简(仅WiFi)  |
     * </pre>
     */
    public static final class BudgetConstraints {

        /** 搜救能力枚举。 */
        public enum SearchRescueCapability {
            /** 高分辨率热成像 640×480（Full Mode）。 */
            THERMAL_HIGH_RES,
            /** 低分辨率热源检测 8×8（Emergency-Standard, AMG8833）。 */
            THERMAL_LOW_RES,
            /** LED 信号灯 + 蜂鸣器声光报警（Emergency-Toy）。 */
            LED_BUZZER
        }

        /** 编排复杂度枚举。 */
        public enum OrchestrationComplexity {
            /** 全量编排：贪心部署 + 局部优化 + 连通性修复 + HAPS 中继。 */
            FULL,
            /** 简化编排：无卫星层级，LoRa Mesh 单跳中继。 */
            SIMPLIFIED,
            /** 最简编排：仅 WiFi 单跳，无中继层级。 */
            MINIMAL
        }

        /** 最大覆盖半径（km）。 */
        public final double maxCoverageRadiusKm;
        /** 最大节点数。 */
        public final int maxNodes;
        /** 最大续航（分钟）。 */
        public final int maxEnduranceMin;
        /** 避障距离（m）。 */
        public final double obstacleAvoidanceDistanceM;
        /** 搜救能力。 */
        public final SearchRescueCapability searchRescueCapability;
        /** 编排复杂度。 */
        public final OrchestrationComplexity orchestrationComplexity;

        /**
         * 构造预算约束。
         *
         * @param maxCoverageRadiusKm       最大覆盖半径（km），必须 &gt; 0
         * @param maxNodes                  最大节点数，必须 &gt; 0
         * @param maxEnduranceMin           最大续航（分钟），必须 &gt; 0
         * @param obstacleAvoidanceDistanceM 避障距离（m），必须 &gt; 0
         * @param searchRescueCapability    搜救能力，不能为 null
         * @param orchestrationComplexity   编排复杂度，不能为 null
         */
        public BudgetConstraints(double maxCoverageRadiusKm, int maxNodes,
                                  int maxEnduranceMin,
                                  double obstacleAvoidanceDistanceM,
                                  SearchRescueCapability searchRescueCapability,
                                  OrchestrationComplexity orchestrationComplexity) {
            if (maxCoverageRadiusKm <= 0 || Double.isNaN(maxCoverageRadiusKm)) {
                throw new IllegalArgumentException(
                        "maxCoverageRadiusKm must be > 0, got " + maxCoverageRadiusKm);
            }
            if (maxNodes <= 0) {
                throw new IllegalArgumentException("maxNodes must be > 0, got " + maxNodes);
            }
            if (maxEnduranceMin <= 0) {
                throw new IllegalArgumentException(
                        "maxEnduranceMin must be > 0, got " + maxEnduranceMin);
            }
            if (obstacleAvoidanceDistanceM <= 0 || Double.isNaN(obstacleAvoidanceDistanceM)) {
                throw new IllegalArgumentException(
                        "obstacleAvoidanceDistanceM must be > 0, got " + obstacleAvoidanceDistanceM);
            }
            if (searchRescueCapability == null) {
                throw new IllegalArgumentException("searchRescueCapability must not be null");
            }
            if (orchestrationComplexity == null) {
                throw new IllegalArgumentException("orchestrationComplexity must not be null");
            }
            this.maxCoverageRadiusKm = maxCoverageRadiusKm;
            this.maxNodes = maxNodes;
            this.maxEnduranceMin = maxEnduranceMin;
            this.obstacleAvoidanceDistanceM = obstacleAvoidanceDistanceM;
            this.searchRescueCapability = searchRescueCapability;
            this.orchestrationComplexity = orchestrationComplexity;
        }

        /**
         * Full Mode 约束（无限制，全量编排）。
         *
         * @return Full Mode 预算约束
         */
        public static BudgetConstraints fullMode() {
            return new BudgetConstraints(
                    5.0, 100, 30,
                    100.0,
                    SearchRescueCapability.THERMAL_HIGH_RES,
                    OrchestrationComplexity.FULL);
        }

        /**
         * Emergency-Standard 约束（千元级，LoRa Mesh）。
         *
         * @return Emergency-Standard 预算约束
         */
        public static BudgetConstraints emergencyStandard() {
            return new BudgetConstraints(
                    2.0, 20, 8,
                    4.0,
                    SearchRescueCapability.THERMAL_LOW_RES,
                    OrchestrationComplexity.SIMPLIFIED);
        }

        /**
         * Emergency-Toy 约束（百元级，WiFi ESP-NOW）。
         *
         * @return Emergency-Toy 预算约束
         */
        public static BudgetConstraints emergencyToy() {
            return new BudgetConstraints(
                    0.2, 8, 5,
                    2.0,
                    SearchRescueCapability.LED_BUZZER,
                    OrchestrationComplexity.MINIMAL);
        }

        /**
         * 从 BudgetMode 创建对应的预算约束。
         *
         * @param budgetMode 预算模式，null 视为 Full Mode
         * @return 对应的预算约束
         */
        public static BudgetConstraints fromBudgetMode(BudgetMode budgetMode) {
            if (budgetMode == null) {
                return fullMode();
            }
            return switch (budgetMode) {
                case EMERGENCY_TOY -> emergencyToy();
                case EMERGENCY_STANDARD -> emergencyStandard();
                default -> fullMode();
            };
        }

        /**
         * 从 BudgetMode 的 CLI 字符串值创建对应的预算约束。
         *
         * @param budgetModeCli 预算模式 CLI 值（如 "emergency-toy"），null 或无效值视为 Full Mode
         * @return 对应的预算约束
         */
        public static BudgetConstraints fromBudgetMode(String budgetModeCli) {
            BudgetMode mode = BudgetMode.fromCliValue(budgetModeCli);
            return fromBudgetMode(mode);
        }

        @Override
        public String toString() {
            return "BudgetConstraints{maxCoverageRadius=" + maxCoverageRadiusKm + "km"
                    + ", maxNodes=" + maxNodes
                    + ", maxEndurance=" + maxEnduranceMin + "min"
                    + ", obstacleAvoidance=" + obstacleAvoidanceDistanceM + "m"
                    + ", searchRescue=" + searchRescueCapability
                    + ", complexity=" + orchestrationComplexity + "}";
        }
    }
}

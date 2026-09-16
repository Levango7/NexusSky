package io.aerofleet.sim.orch;

/**
 * 应急任务编排阶段枚举（M9 应急任务编排，T2 编排引擎核心）。
 * <p>
 * 定义 5 个阶段，按 code 顺序执行：
 * <pre>
 * 0. DISASTER_MAPPING    灾区测绘   超时 10 分钟
 * 1. COVERAGE_PLANNING   覆盖规划   超时 5  分钟
 * 2. NETWORK_DEPLOYMENT  组网部署   超时 15 分钟
 * 3. CONTINUOUS_SERVICE  持续服务   无超时（Long.MAX_VALUE）
 * 4. SELF_HEALING        自愈重构   超时 2  分钟
 * </pre>
 * 每个阶段携带 code（序号）、label（中文标签）、timeoutMs（超时阈值）。
 */
public enum OrchestrationPhase {

    /** 阶段 0：灾区测绘，超时 10 分钟。 */
    DISASTER_MAPPING(0, "DisasterMapping", 10 * 60 * 1000L),
    /** 阶段 1：覆盖规划，超时 5 分钟。 */
    COVERAGE_PLANNING(1, "CoveragePlanning", 5 * 60 * 1000L),
    /** 阶段 2：组网部署，超时 15 分钟。 */
    NETWORK_DEPLOYMENT(2, "NetworkDeployment", 15 * 60 * 1000L),
    /** 阶段 3：持续服务，无超时。 */
    CONTINUOUS_SERVICE(3, "ContinuousService", Long.MAX_VALUE),
    /** 阶段 4：自愈重构，超时 2 分钟。 */
    SELF_HEALING(4, "SelfHealing", 2 * 60 * 1000L);

    /** 阶段序号（0~4）。 */
    public final int code;
    /** 阶段标签（ASCII，用于日志与事件）。 */
    public final String label;
    /** 阶段超时阈值（ms），CONTINUOUS_SERVICE 为 Long.MAX_VALUE 表示无超时。 */
    public final long timeoutMs;

    OrchestrationPhase(int code, String label, long timeoutMs) {
        this.code = code;
        this.label = label;
        this.timeoutMs = timeoutMs;
    }

    /**
     * 按 code 反查阶段枚举。
     *
     * @param code 阶段序号（0~4）
     * @return 对应枚举
     * @throws IllegalArgumentException code 越界
     */
    public static OrchestrationPhase fromCode(int code) {
        for (OrchestrationPhase p : values()) {
            if (p.code == code) {
                return p;
            }
        }
        throw new IllegalArgumentException("Unknown phase code: " + code);
    }
}
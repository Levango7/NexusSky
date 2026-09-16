package io.aerofleet.sim.orch;

/**
 * 动态重构结果（M9 应急任务编排，T4 动态重构）。
 * <p>
 * 描述一次动态重构决策的类型、耗时与说明。所有字段 final 不可变。
 * <p>
 * 重构类型：
 * <pre>
 * MESH_SELF_HEAL   mesh 自愈（覆盖下降 &lt; 10%，仅补链）
 * PARTIAL_REPLAN   局部重规划（轮换/地形变化，仅重算受影响子区域）
 * FULL_REPLAN      全量重规划（覆盖下降 ≥ 10%，重新分配全部无人机）
 * NO_ACTION        不动作（由调用方处理或无需重构）
 * </pre>
 */
public final class ReconfigResult {

    /** 重构类型。 */
    public enum Type {
        /** mesh 自愈：覆盖下降 &lt; 10%，仅补链不重规划。 */
        MESH_SELF_HEAL,
        /** 局部重规划：轮换/地形变化，仅重算受影响子区域。 */
        PARTIAL_REPLAN,
        /** 全量重规划：覆盖下降 ≥ 10%，重新分配全部无人机。 */
        FULL_REPLAN,
        /** 不动作：由调用方处理或无需重构。 */
        NO_ACTION
    }

    /** 重构类型。 */
    public final Type type;
    /** 重构耗时（ms）。 */
    public final long durationMs;
    /** 重构说明。 */
    public final String description;

    /**
     * 构造重构结果。
     *
     * @param type        重构类型
     * @param durationMs  重构耗时（ms）
     * @param description 重构说明
     */
    public ReconfigResult(Type type, long durationMs, String description) {
        this.type = type;
        this.durationMs = durationMs;
        this.description = description;
    }

    @Override
    public String toString() {
        return "ReconfigResult{type=" + type + ", durationMs=" + durationMs
                + ", description=\"" + description + "\"}";
    }
}
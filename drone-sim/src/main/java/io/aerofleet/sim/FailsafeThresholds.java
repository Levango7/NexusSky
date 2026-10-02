package io.aerofleet.sim;

/**
 * 飞控安全阈值的**唯一真相源**。
 *
 * <p><b>为什么单独抽一个类</b>：同一个「低电量返航」阈值在本仓曾同时存在三个值——
 * {@code FailsafeController} 用 22（PX4 {@code BAT_CRIT_THR} 默认，唯一真正生效的那个）、
 * {@code ReturnToHomeStrategy} 用 25、{@code DecisionEngine} 的权重放大阈值用 20。
 * 两套实现各写各的，于是「引擎认为该返航、飞控认为还没到临界」这种分裂可以长期
 * 无人察觉。把它们收敛到一处，改阈值只需改这里，且有测试钉住。
 *
 * <p><b>注意生效路径只有一条</b>：只有 {@link FailsafeController} 会<b>执行</b>
 * 应急动作（被 {@code VirtualDrone.tickOnce()} 调用）。{@code io.aerofleet.sim.ai}
 * 包的 {@code DecisionEngine} 现已通过 {@code AutonomyAdvisor} 接入飞行路径，
 * 但只是 <b>advisory</b>（1Hz 评估、STATUSTEXT 建议文本，不执行动作），
 * 其余策略/规划器仍只被 ai 包内引用，详见 {@code AiAutonomyWiringTest}
 * 与 README「已知边界」。ai 包引用本类是为了与执行链路保持同一阈值口径。
 */
public final class FailsafeThresholds {

    /**
     * 电量临界值（百分比，含）：低于此值触发返航。
     *
     * <p>取 PX4 的 {@code BAT_CRIT_THR} 默认值。PX4 同时还有一个更早触发的
     * {@code BAT_LOW_THR}（警告），本仓目前只实现临界这一档。
     */
    public static final int BATTERY_CRIT_PCT = 22;

    /**
     * 链路丢失判定时间（毫秒）：距上次收到地面站包超过此时长即视为数传丢失。
     *
     * <p>15s 大于 cloud-backend 的 10s 离线超时，使飞机比 GCS 稍晚反应——
     * 与 PX4 的 {@code NAV_DLLC_ACT} 行为一致。
     */
    public static final long LINK_LOSS_AFTER_MS = 15_000L;

    private FailsafeThresholds() {
    }
}

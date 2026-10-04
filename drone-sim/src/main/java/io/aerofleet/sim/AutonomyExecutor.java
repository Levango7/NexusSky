package io.aerofleet.sim;

import io.aerofleet.sim.ai.DecisionResult;
import io.aerofleet.sim.ai.FusedDecision;

/**
 * M11 执行级接线：把 {@link FusedDecision} 映射为飞控动作（2026-10）。
 *
 * <p><b>与既有链路的仲裁（不可绕过）</b>：
 * <ol>
 *   <li><b>FailsafeController 永远优先</b>——任一 failsafe 触发沿激活期间
 *       （电量临界/链路丢失/GPS 丢失），本类不执行任何动作并复位避障限速；
 *       真正生效的应急执行链路仍然是 {@code FailsafeController}；</li>
 *   <li><b>默认关闭</b>——需 {@code --autonomy-exec} 显式开启
 *       （{@code SimConfig.autonomyExecEnabled}），A/B 对比可关；</li>
 *   <li><b>只在可执行态执行</b>——仅 ARMED/MISSION；STANDBY/RTL/HOLD/CRASHED/
 *       MANUAL 不接管（RTL/HOLD 已是应急或任务终态，人工模式不抢杆）；</li>
 *   <li><b>变化沿驱动</b>——由 {@link AutonomyAdvisor} 的 decisionListener 仅在
 *       主决策变化沿（含清除沿）回调，本类内部不做去抖。</li>
 * </ol>
 *
 * <p><b>动作映射</b>：
 * <ul>
 *   <li>RTL / EMERGENCY_LAND → {@code engageRtl()}：走与 failsafe 同一条 RTL
 *       程序（爬升安全高度→回家→自动降落）。仿真没有独立的「原地紧急降落」
 *       原语，EMERGENCY_LAND 复用 RTL 的自动降落终局，语义为「立即启动自动
 *       降落程序」；</li>
 *   <li>AVOID → 避障限速：全局速度因子压到 {@link #AVOID_SPEED_FACTOR}
 *       （{@code DronePhysics.setSpeedFactor}，作用于巡航/导航速度）；决策
 *       清除沿后恢复 1.0；</li>
 *   <li>ADAPT_PATH → 自适应航线（2026-10-04 执行级接线，此前公告级不执行）：
 *       {@code adaptPath} 由 {@code VirtualDrone} 实现——对剩余任务航段跑
 *       {@code AdaptivePathStrategy.adaptPath}（Dubins 平滑 + 风修正 + 能耗
 *       调速），尖角航段插入平滑点后改写任务尾部并经 ADAPTIVE_PATH(30052)
 *       公告真实新航点（该消息的第一个生产者）；限速按当前段能耗最优速度
 *       下调（物理层因子上限 1.0，顺风提速不可表达，见实现注记）；</li>
 *   <li>NONE（清除沿）/ 未知类型 → 复位限速。</li>
 * </ul>
 *
 * <p>本类不持有飞行状态，全部飞控原语经 {@link FlightControl} 由
 * {@code VirtualDrone} 提供；见 {@code AutonomyExecutorTest}（记录型假实现的
 * 纯单元测试）。
 */
public final class AutonomyExecutor {

    /** AVOID 决策期间的全局速度因子（0.5 = 巡航减半）。 */
    public static final double AVOID_SPEED_FACTOR = 0.5;

    /** 飞控原语接口：VirtualDrone 实现，测试用记录型假实现。 */
    public interface FlightControl {
        /** 执行级开关（--autonomy-exec）。 */
        boolean autonomyExecEnabled();

        /** 当前是否处于可执行态（ARMED / MISSION）。 */
        boolean inExecutableState();

        /** 任一 failsafe 触发沿是否激活（电量临界/链路丢失/GPS 丢失）。 */
        boolean failsafeActive();

        /** 执行 RTL 程序（与 failsafe 同一条：爬升→回家→降落）。 */
        void engageRtl(String aiReason);

        /** 执行自适应航线：对剩余任务航段跑策略并按结果改写任务尾部/调速。 */
        void adaptPath(String aiReason);

        /** 设置避障限速因子（1.0 = 恢复）。 */
        void setAvoidSpeedFactor(double factor);
    }

    private final FlightControl control;

    public AutonomyExecutor(FlightControl control) {
        this.control = control;
    }

    /**
     * 主决策变化沿回调（含清除沿；fused 可能为 null 或无决策）。
     * 任何分支都不外抛异常——执行级失败不得影响 tickOnce 链路。
     */
    public void onDecision(FusedDecision fused) {
        try {
            apply(fused);
        } catch (RuntimeException e) {
            SimLog.warn("autonomy executor failed: " + e.getMessage());
        }
    }

    private void apply(FusedDecision fused) {
        if (!control.autonomyExecEnabled()) {
            return;
        }
        // 仲裁 1：failsafe 永远优先，不抢杆；同时复位避障限速
        if (control.failsafeActive()) {
            control.setAvoidSpeedFactor(1.0);
            return;
        }
        String type = (fused != null && fused.hasDecision())
                ? fused.primary.decisionType : "NONE";
        switch (type) {
            case "RTL", "EMERGENCY_LAND" -> {
                control.setAvoidSpeedFactor(1.0);
                if (control.inExecutableState()) {
                    DecisionResult primary = fused.primary;
                    control.engageRtl(primary.decisionType + ": " + primary.reason);
                }
            }
            case "AVOID" -> control.setAvoidSpeedFactor(
                    control.inExecutableState() ? AVOID_SPEED_FACTOR : 1.0);
            // ADAPT_PATH：执行级接线（2026-10-04），实现与边界见类 Javadoc
            case "ADAPT_PATH" -> {
                control.setAvoidSpeedFactor(1.0);
                if (control.inExecutableState()) {
                    DecisionResult primary = fused.primary;
                    control.adaptPath(primary.reason);
                }
            }
            // NONE / 未知类型：复位
            default -> control.setAvoidSpeedFactor(1.0);
        }
    }
}

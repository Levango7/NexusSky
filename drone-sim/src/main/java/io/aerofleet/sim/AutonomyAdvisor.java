package io.aerofleet.sim;

import io.aerofleet.mavlink.enums.MavEnums;
import io.aerofleet.sim.ai.DecisionContext;
import io.aerofleet.sim.ai.DecisionEngine;
import io.aerofleet.sim.ai.DecisionResult;
import io.aerofleet.sim.ai.FusedDecision;

import java.util.function.BiConsumer;

/**
 * M11 自主决策引擎的机载接线（1Hz）：advisory 播报 + 变化沿回调。
 *
 * <p><b>职责边界（不要「顺手增强」）</b>：本类把 {@link DecisionEngine} 接进
 * {@code VirtualDrone.tickOnce()} 的飞行路径。本类自身<b>只建议、不执行</b>——
 * 主决策类型发生<b>变化沿</b>时，经 STATUSTEXT 下发一条建议文本，不触碰飞行
 * 状态、不接管控制。同一变化沿还会（若注册）经 {@code decisionListener} 回调
 * 最新 {@link FusedDecision}，供消费方做两件事：下发 DECISION_EVENT(30051)
 * 与执行级门控（{@link AutonomyExecutor}：默认关闭、failsafe 永远优先、仅
 * ARMED/MISSION 可执行——仲裁规则见该类 Javadoc）。真正生效的应急执行链路
 * 仍然是 {@code FailsafeController}（链路丢失/电量临界/GPS 丢失 → RTL/HOLD），
 * 阈值唯一真相源是 {@link FailsafeThresholds}。
 *
 * <p><b>节流与去抖</b>：评估周期 {@link #PERIOD_MS}（1Hz，tickOnce 是 20ms，
 * 由本类自行分频）；同一主决策类型持续期间只播报一次（变化沿触发），
 * 恢复无建议时播报一条 INFO 澄清。首次评估无决策时不播报（避免开机刷屏）。
 * listener 与播报共用同一变化沿语义（清除沿也会回调，fused 无决策）。
 *
 * <p><b>missionUrgency 固定为 0</b>：仿真侧没有「任务紧急度」的自然来源
 * （无任务截止时间/优先级语义），固定 0 表示不放大自适应航线权重。
 * 将来若引入任务紧急度，应在快照里带过来，而不是在本类里猜。
 *
 * <p>见 {@code AiAutonomyWiringTest}：DecisionEngine「已接线」是钉死的断言；
 * 其余策略/规划器仍只被 ai 包内引用。
 */
public final class AutonomyAdvisor {

    /** 评估周期（ms）：advisory 1Hz 足够，且与 tickOnce 的 20ms 解耦。 */
    public static final long PERIOD_MS = 1000;

    private final DecisionEngine engine = new DecisionEngine();
    private final BiConsumer<Integer, String> statusSink;
    /** 主决策变化沿回调（可空）：供 DECISION_EVENT 下发与执行级消费。 */
    private final BiConsumer<FusedDecision, Snapshot> decisionListener;

    /** 上次评估时刻（ms）；0 表示尚未评估过（首次 tick 立即评估）。 */
    private long lastTickMs = 0;
    /** 上次播报的主决策类型；"NONE" 起步，避免首个无决策周期播报澄清消息。 */
    private String lastAnnouncedType = "NONE";
    /** 评估次数（测试观察节流用）。 */
    private int evaluationCount = 0;

    public AutonomyAdvisor(BiConsumer<Integer, String> statusSink) {
        this(statusSink, null);
    }

    public AutonomyAdvisor(BiConsumer<Integer, String> statusSink,
                           BiConsumer<FusedDecision, Snapshot> decisionListener) {
        this.statusSink = statusSink;
        this.decisionListener = decisionListener;
    }

    /**
     * 态势快照：由 {@code VirtualDrone} 每 tick 组装（电量含场景故障覆盖、
     * 链路静默与 {@link FailsafeThresholds#LINK_LOSS_AFTER_MS} 同口径、
     * GPS、位置、障碍报告与合成风）。
     */
    public record Snapshot(int batteryPct, boolean linkHealthy, boolean gpsHealthy,
                           double altM, double distanceToHomeM,
                           boolean obstacleDetected, double obstacleDistanceM,
                           double windSpeed) {
    }

    /**
     * 周期评估：节流 → 组装 {@link DecisionContext} →
     * {@link DecisionEngine#evaluateFused} → 主决策类型变化沿播报 + listener 回调。
     * 任何情况下不抛异常、不执行动作。
     */
    public void tick(long nowMs, Snapshot s) {
        if (nowMs - lastTickMs < PERIOD_MS) {
            return;
        }
        lastTickMs = nowMs;
        evaluationCount++;
        DecisionContext ctx = new DecisionContext(s.batteryPct(), s.linkHealthy(),
                s.gpsHealthy(), s.altM(), s.distanceToHomeM(), s.obstacleDetected(),
                s.obstacleDistanceM(), s.windSpeed(), 0.0);
        FusedDecision fused = engine.evaluateFused(ctx);
        String type = fused.hasDecision() ? fused.primary.decisionType : "NONE";
        if (type.equals(lastAnnouncedType)) {
            return;
        }
        if (fused.hasDecision()) {
            DecisionResult primary = fused.primary;
            statusSink.accept(severityOf(primary.decisionType),
                    "AI advisory: " + primary.decisionType + " - " + primary.reason);
        } else {
            statusSink.accept(MavEnums.MAV_SEVERITY_INFO,
                    "AI advisory cleared - no action recommended");
        }
        lastAnnouncedType = type;
        if (decisionListener != null) {
            decisionListener.accept(fused, s);
        }
    }

    /** 最近一次播报的主决策类型（"NONE" = 无建议）；供测试与排障。 */
    public String lastPrimaryDecision() {
        return lastAnnouncedType;
    }

    /** 累计评估次数；供测试验证节流（1Hz 内的重复 tick 不计入）。 */
    int evaluationCount() {
        return evaluationCount;
    }

    /**
     * 决策类型 → STATUSTEXT 严重级别。
     * EMERGENCY_LAND=CRITICAL；RTL/AVOID=WARNING（建议返航/避障但未失控）；
     * ADAPT_PATH=NOTICE（航线优化建议）；未知类型兜底 INFO。
     */
    static int severityOf(String decisionType) {
        switch (decisionType) {
            case "EMERGENCY_LAND":
                return MavEnums.MAV_SEVERITY_CRITICAL;
            case "RTL":
            case "AVOID":
                return MavEnums.MAV_SEVERITY_WARNING;
            case "ADAPT_PATH":
                return MavEnums.MAV_SEVERITY_NOTICE;
            default:
                return MavEnums.MAV_SEVERITY_INFO;
        }
    }
}

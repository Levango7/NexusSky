package io.aerofleet.sim;

import io.aerofleet.mavlink.enums.MavEnums;
import io.aerofleet.sim.ai.FusedDecision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AutonomyAdvisor} 的行为契约测试：1Hz 节流、变化沿播报、去抖、
 * 恢复澄清、严重级别映射。
 *
 * <p>输入取值依据（阈值都有出处）：
 * <ul>
 *   <li>RTL 触发：battery &lt; {@code FailsafeThresholds.BATTERY_CRIT_PCT}(22)
 *       —— ReturnToHomeStrategy 与 FailsafeController 同源；</li>
 *   <li>EMERGENCY_LAND 触发：!gpsHealthy 且电量/链路正常（ReturnToHomeStrategy）；</li>
 *   <li>AdaptivePathStrategy 在 battery&lt;40 也会给出 ADAPT_PATH 候选（conf 0.5），
 *       但 battery=18 时 RTL（conf 0.9）仍是 primary——测试只断言 primary；</li>
 *   <li>健康基线：battery=100、无障碍、零风 → 三个策略全部返回 null → NONE。</li>
 * </ul>
 */
class AutonomyAdvisorTest {

    /** 录制 statusSink 调用，便于断言播报次数与内容。 */
    private static final class RecordingSink implements BiConsumer<Integer, String> {
        final List<Integer> severities = new ArrayList<>();
        final List<String> texts = new ArrayList<>();

        @Override
        public void accept(Integer severity, String text) {
            severities.add(severity);
            texts.add(text);
        }
    }

    // ------------------------------------------------------------------
    // 快照工厂
    // ------------------------------------------------------------------

    /** 健康基线：电量 100、链路/GPS 正常、无障碍、零风、高度 50m、距家 200m。 */
    private static AutonomyAdvisor.Snapshot healthy() {
        return new AutonomyAdvisor.Snapshot(100, true, true,
                50.0, 200.0, false, Double.MAX_VALUE, 0.0);
    }

    /** 指定电量与 GPS 状态，其余同健康基线。 */
    private static AutonomyAdvisor.Snapshot snapshot(int batteryPct, boolean gpsHealthy) {
        return new AutonomyAdvisor.Snapshot(batteryPct, true, gpsHealthy,
                50.0, 200.0, false, Double.MAX_VALUE, 0.0);
    }

    // ------------------------------------------------------------------
    // 行为契约
    // ------------------------------------------------------------------

    @Test
    @DisplayName("健康状态不播报，且首评无决策不触发开机澄清刷屏")
    void healthyStateNoAnnouncement() {
        RecordingSink sink = new RecordingSink();
        AutonomyAdvisor advisor = new AutonomyAdvisor(sink);
        advisor.tick(1000, healthy());
        advisor.tick(2000, healthy());
        assertTrue(sink.texts.isEmpty(), "健康状态下不应有任何 STATUSTEXT 播报");
        assertEquals("NONE", advisor.lastPrimaryDecision());
        assertEquals(2, advisor.evaluationCount(), "1Hz 节流下两次相隔 1s 的 tick 都应评估");
    }

    @Test
    @DisplayName("低电量触发一次 RTL 建议后去抖：同一决策持续期间不重复播报")
    void lowBatteryAnnouncesRtlOnceAndDedupes() {
        RecordingSink sink = new RecordingSink();
        AutonomyAdvisor advisor = new AutonomyAdvisor(sink);
        advisor.tick(1000, snapshot(18, true));
        assertEquals(1, sink.texts.size(), "低电量变化沿应恰好播报一次");
        assertEquals(MavEnums.MAV_SEVERITY_WARNING, sink.severities.get(0).intValue(),
                "RTL 建议应为 WARNING 级别");
        assertTrue(sink.texts.get(0).contains("RTL"), "播报文本应含决策类型 RTL: "
                + sink.texts.get(0));
        // 同一态势持续：不再播报
        advisor.tick(2000, snapshot(18, true));
        assertEquals(1, sink.texts.size(), "决策类型未变化时应去抖，不重复播报");
        assertEquals("RTL", advisor.lastPrimaryDecision());
    }

    @Test
    @DisplayName("低电量恢复后播报一次 INFO 澄清，随后继续去抖")
    void recoveryAnnouncesClearOnce() {
        RecordingSink sink = new RecordingSink();
        AutonomyAdvisor advisor = new AutonomyAdvisor(sink);
        advisor.tick(1000, snapshot(18, true));
        advisor.tick(2000, healthy());
        assertEquals(2, sink.texts.size(), "RTL→NONE 变化沿应追加一条澄清播报");
        assertEquals(MavEnums.MAV_SEVERITY_INFO, sink.severities.get(1).intValue(),
                "澄清播报应为 INFO 级别");
        assertTrue(sink.texts.get(1).contains("cleared"), "澄清文本应含 cleared: "
                + sink.texts.get(1));
        advisor.tick(3000, healthy());
        assertEquals(2, sink.texts.size(), "恢复后无新决策，不再播报");
        assertEquals("NONE", advisor.lastPrimaryDecision());
    }

    @Test
    @DisplayName("GPS 降级触发 EMERGENCY_LAND 建议，级别为 CRITICAL")
    void gpsLossAnnouncesEmergencyLandCritical() {
        RecordingSink sink = new RecordingSink();
        AutonomyAdvisor advisor = new AutonomyAdvisor(sink);
        advisor.tick(1000, snapshot(100, false));
        assertEquals(1, sink.texts.size());
        assertEquals(MavEnums.MAV_SEVERITY_CRITICAL, sink.severities.get(0).intValue(),
                "EMERGENCY_LAND 建议应为 CRITICAL 级别");
        assertTrue(sink.texts.get(0).contains("EMERGENCY_LAND"),
                "播报文本应含决策类型 EMERGENCY_LAND: " + sink.texts.get(0));
        assertEquals("EMERGENCY_LAND", advisor.lastPrimaryDecision());
    }

    @Test
    @DisplayName("1Hz 节流：周期内的重复 tick 被跳过，不计入评估")
    void throttleSkipsSubSecondTicks() {
        RecordingSink sink = new RecordingSink();
        AutonomyAdvisor advisor = new AutonomyAdvisor(sink);
        advisor.tick(1000, snapshot(18, true));
        assertEquals(1, advisor.evaluationCount());
        advisor.tick(1500, snapshot(18, true));   // 周期内：跳过
        assertEquals(1, advisor.evaluationCount(), "500ms 后的 tick 应被节流跳过");
        advisor.tick(2001, snapshot(18, true));   // 满 1s：评估（但决策未变，仍不播报）
        assertEquals(2, advisor.evaluationCount());
        assertEquals(1, sink.texts.size(), "评估发生但决策类型未变，不应新增播报");
    }

    @Test
    @DisplayName("决策类型 → STATUSTEXT 严重级别映射")
    void severityMapping() {
        assertEquals(MavEnums.MAV_SEVERITY_CRITICAL, AutonomyAdvisor.severityOf("EMERGENCY_LAND"));
        assertEquals(MavEnums.MAV_SEVERITY_WARNING, AutonomyAdvisor.severityOf("RTL"));
        assertEquals(MavEnums.MAV_SEVERITY_WARNING, AutonomyAdvisor.severityOf("AVOID"));
        assertEquals(MavEnums.MAV_SEVERITY_NOTICE, AutonomyAdvisor.severityOf("ADAPT_PATH"));
        assertEquals(MavEnums.MAV_SEVERITY_INFO, AutonomyAdvisor.severityOf("SOMETHING_NEW"));
    }

    // ------------------------------------------------------------------
    // 决策变化沿 listener（DECISION_EVENT 下发与执行级的消费入口）
    // ------------------------------------------------------------------

    /** 录制 decisionListener 回调，断言变化沿语义。 */
    private static final class RecordingListener
            implements BiConsumer<FusedDecision, AutonomyAdvisor.Snapshot> {
        final List<String> types = new ArrayList<>();
        final List<Boolean> hasDecision = new ArrayList<>();

        @Override
        public void accept(FusedDecision fused, AutonomyAdvisor.Snapshot snap) {
            types.add(fused.hasDecision() ? fused.primary.decisionType : "NONE");
            hasDecision.add(fused.hasDecision());
        }
    }

    @Test
    @DisplayName("decisionListener 与播报共用变化沿：决策沿/清除沿各回调一次，持续期间去抖")
    void decisionListenerFiresOnChangeEdgesOnly() {
        RecordingSink sink = new RecordingSink();
        RecordingListener listener = new RecordingListener();
        AutonomyAdvisor advisor = new AutonomyAdvisor(sink, listener);
        advisor.tick(1000, healthy());          // 首评无决策：不播报、不回调
        assertTrue(listener.types.isEmpty(), "首评无决策不应回调（与播报口径一致）");
        advisor.tick(2000, snapshot(18, true)); // NONE→RTL 决策沿
        assertEquals(List.of("RTL"), listener.types);
        advisor.tick(3000, snapshot(18, true)); // 同决策持续：去抖
        assertEquals(1, listener.types.size(), "同一决策持续期间不应重复回调");
        advisor.tick(4000, healthy());          // RTL→NONE 清除沿
        assertEquals(2, listener.types.size());
        assertEquals("NONE", listener.types.get(1));
        assertEquals(Boolean.FALSE, listener.hasDecision.get(1), "清除沿回调的 fused 应无决策");
    }

    @Test
    @DisplayName("未注册 listener 时行为与旧构造完全一致（向后兼容）")
    void nullListenerKeepsLegacyBehavior() {
        RecordingSink sink = new RecordingSink();
        AutonomyAdvisor advisor = new AutonomyAdvisor(sink);
        advisor.tick(1000, snapshot(18, true));
        advisor.tick(2000, snapshot(18, true));
        advisor.tick(3000, healthy());
        assertEquals(2, sink.texts.size(), "单参构造的播报序列应与改造前一致");
        assertEquals("NONE", advisor.lastPrimaryDecision());
    }
}

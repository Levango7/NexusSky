package io.aerofleet.regulatorsim;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ActivationStateMachine} 单元测试——C1 高保真模拟的语义钉住。
 *
 * <p>这些语义是真实 UOM 联调时 90% 问题的来源（状态/幂等/时序），
 * 单测先钉住，e2e 再走 HTTP 面（scripts/e2e-regulator.ps1）。
 */
@DisplayName("ActivationStateMachine 激活状态机（C1 模拟语义）")
class ActivationStateMachineTest {

    private ActivationStateMachine machine;
    private final AtomicInteger actSeq = new AtomicInteger(0);
    private final AtomicInteger canSeq = new AtomicInteger(0);

    @BeforeEach
    void setUp() {
        machine = new ActivationStateMachine();
    }

    /** 确定性 ID 生成器：断言可直接比字符串。 */
    private ActivationStateMachine.IdGenerator ids() {
        return new ActivationStateMachine.IdGenerator() {
            @Override
            public String nextActivation(String serialNo, long nowMs) {
                return "ACT-" + actSeq.incrementAndGet();
            }

            @Override
            public String nextCancellation(String serialNo, long nowMs) {
                return "CAN-" + canSeq.incrementAndGet();
            }
        };
    }

    @Test
    @DisplayName("未实名注册的 serialNo 激活被拒（NOT_FOUND）")
    void activateWithoutRegistrationRejected() {
        var out = machine.activate("NOPE", null, 1000L, ids());
        assertThat(out.verdict()).isEqualTo(ActivationStateMachine.Verdict.NOT_FOUND);
        assertThat(machine.statusOf("NOPE").state()).isEqualTo(ActivationStateMachine.State.NONE);
    }

    @Test
    @DisplayName("实名状态非 VERIFIED（UNVERIFIED）激活被拒（NOT_VERIFIED）")
    void activateUnverifiedRejected() {
        var out = machine.activate("TEST-002", "UNVERIFIED", 1000L, ids());
        assertThat(out.verdict()).isEqualTo(ActivationStateMachine.Verdict.NOT_VERIFIED);
    }

    @Test
    @DisplayName("已实名 → 激活发放 ID；重复激活幂等返回同一 ID 且不新建")
    void activateIsIdempotent() {
        var first = machine.activate("TEST-001", "VERIFIED", 1000L, ids());
        assertThat(first.verdict()).isEqualTo(ActivationStateMachine.Verdict.GRANTED);
        assertThat(first.id()).isEqualTo("ACT-1");

        var second = machine.activate("TEST-001", "VERIFIED", 2000L, ids());
        assertThat(second.verdict()).isEqualTo(ActivationStateMachine.Verdict.IDEMPOTENT);
        assertThat(second.id()).isEqualTo("ACT-1"); // 同一 ID，序号不递增
        assertThat(actSeq.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("未激活就上报遥测被拒（NOT_ACTIVATED）")
    void telemetryWithoutActivationRejected() {
        var out = machine.recordTelemetry("TEST-001", 1000L);
        assertThat(out.verdict()).isEqualTo(ActivationStateMachine.TelemVerdict.NOT_ACTIVATED);
        assertThat(out.reason()).contains("未激活");
    }

    @Test
    @DisplayName("已激活 → 遥测接收并累计次数/最后时间")
    void telemetryAfterActivationAccepted() {
        machine.activate("TEST-001", "VERIFIED", 1000L, ids());
        assertThat(machine.recordTelemetry("TEST-001", 1500L).verdict())
                .isEqualTo(ActivationStateMachine.TelemVerdict.ACCEPTED);
        assertThat(machine.recordTelemetry("TEST-001", 1600L).verdict())
                .isEqualTo(ActivationStateMachine.TelemVerdict.ACCEPTED);
        var st = machine.statusOf("TEST-001");
        assertThat(st.telemetryCount()).isEqualTo(2);
        assertThat(st.lastTelemetryMs()).isEqualTo(1600L);
    }

    @Test
    @DisplayName("注销后激活与遥测一律拒绝；注销自身幂等")
    void cancelLocksDownMachine() {
        machine.activate("TEST-001", "VERIFIED", 1000L, ids());
        var cancel = machine.cancel("TEST-001", 2000L, ids());
        assertThat(cancel.verdict()).isEqualTo(ActivationStateMachine.Verdict.GRANTED);
        assertThat(cancel.id()).isEqualTo("CAN-1");

        // 已注销 → 再激活拒绝
        var reActivate = machine.activate("TEST-001", "VERIFIED", 3000L, ids());
        assertThat(reActivate.verdict()).isEqualTo(ActivationStateMachine.Verdict.CANCELLED);
        // 已注销 → 遥测拒绝
        assertThat(machine.recordTelemetry("TEST-001", 3000L).verdict())
                .isEqualTo(ActivationStateMachine.TelemVerdict.CANCELLED);
        // 注销幂等
        var cancelAgain = machine.cancel("TEST-001", 4000L, ids());
        assertThat(cancelAgain.verdict()).isEqualTo(ActivationStateMachine.Verdict.IDEMPOTENT);
        assertThat(cancelAgain.id()).isEqualTo("CAN-1");
    }

    @Test
    @DisplayName("reset 清空状态（e2e 用例间隔离）")
    void resetClearsEverything() {
        machine.activate("TEST-001", "VERIFIED", 1000L, ids());
        machine.reset();
        assertThat(machine.statusOf("TEST-001").state()).isEqualTo(ActivationStateMachine.State.NONE);
        // reset 后同一 serialNo 可重新完整走通激活
        assertThat(machine.activate("TEST-001", "VERIFIED", 1500L, ids())
                .verdict()).isEqualTo(ActivationStateMachine.Verdict.GRANTED);
    }

    @Test
    @DisplayName("不同 serialNo 的状态互不影响（多机隔离）")
    void serialsAreIndependent() {
        machine.activate("TEST-001", "VERIFIED", 1000L, ids());
        machine.cancel("TEST-003", 1000L, ids());
        assertThat(machine.statusOf("TEST-001").state()).isEqualTo(ActivationStateMachine.State.ACTIVATED);
        assertThat(machine.statusOf("TEST-003").state()).isEqualTo(ActivationStateMachine.State.CANCELLED);
        assertThat(machine.statusOf("TEST-002").state()).isEqualTo(ActivationStateMachine.State.NONE);
    }

    // ---- null/空白 serialNo：不允许炸穿 handler（ConcurrentHashMap 拒 null key）----

    @Test
    @DisplayName("null serialNo 激活被拒（NOT_FOUND），不抛 NPE")
    void activateWithNullSerialRejected() {
        var out = machine.activate(null, "VERIFIED", 1000L, ids());
        assertThat(out.verdict()).isEqualTo(ActivationStateMachine.Verdict.NOT_FOUND);
        assertThat(out.reason()).contains("产品序列号");
    }

    @Test
    @DisplayName("空白串 serialNo 归一为空，激活同样被拒")
    void activateWithBlankSerialRejected() {
        assertThat(machine.activate("   ", "VERIFIED", 1000L, ids()).verdict())
                .isEqualTo(ActivationStateMachine.Verdict.NOT_FOUND);
    }

    @Test
    @DisplayName("null serialNo 遥测被拒（NOT_IDENTIFIED），不抛 NPE")
    void telemetryWithNullSerialRejected() {
        var out = machine.recordTelemetry(null, 1000L);
        assertThat(out.verdict()).isEqualTo(ActivationStateMachine.TelemVerdict.NOT_IDENTIFIED);
    }

    @Test
    @DisplayName("null serialNo 注销被拒（NOT_FOUND），不抛 NPE")
    void cancelWithNullSerialRejected() {
        assertThat(machine.cancel(null, 1000L, ids()).verdict())
                .isEqualTo(ActivationStateMachine.Verdict.NOT_FOUND);
    }

    @Test
    @DisplayName("statusOf(null) 返回 NONE 快照，不抛")
    void statusOfNullIsNone() {
        assertThat(machine.statusOf(null).state()).isEqualTo(ActivationStateMachine.State.NONE);
    }

    @Test
    @DisplayName("首尾空白串按同一设备归一（\" TEST-001 \" ≡ \"TEST-001\"）")
    void serialNoTrimmed() {
        assertThat(machine.activate("  TEST-001  ", "VERIFIED", 1000L, ids()).verdict())
                .isEqualTo(ActivationStateMachine.Verdict.GRANTED);
        // 归一后是同一台设备：再激活应当幂等而不是发第二个 ID
        assertThat(machine.activate("TEST-001", "VERIFIED", 1100L, ids()).verdict())
                .isEqualTo(ActivationStateMachine.Verdict.IDEMPOTENT);
        assertThat(machine.recordTelemetry(" TEST-001 ", 1200L).verdict())
                .isEqualTo(ActivationStateMachine.TelemVerdict.ACCEPTED);
    }

    @Test
    @DisplayName("预置数据查询对 null/空白返回 null，不抛 NPE")
    void presetLookupNullSafe() {
        var presets = new PresetDataStore();
        assertThat(presets.find(null)).isNull();
        assertThat(presets.find("  ")).isNull();
        assertThat(presets.find("TEST-001").status).isEqualTo("VERIFIED");
    }
}

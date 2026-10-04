package io.aerofleet.sim;

import io.aerofleet.sim.ai.DecisionResult;
import io.aerofleet.sim.ai.FusedDecision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AutonomyExecutor} 的仲裁与动作映射契约测试。
 *
 * <p>全部使用记录型 {@link AutonomyExecutor.FlightControl} 假实现，不触碰
 * VirtualDrone/DronePhysics（速度因子对运动学的影响见
 * {@code DronePhysicsTest.speedFactorCapsCruiseSpeed}；接线状态守卫见
 * {@code AiAutonomyWiringTest}）。
 *
 * <p>四条仲裁规则逐条钉死：默认关闭、failsafe 永远优先、仅 ARMED/MISSION
 * 可执行、变化沿驱动（去抖在 AutonomyAdvisor，本类不做）。
 */
class AutonomyExecutorTest {

    /** 记录型飞控假实现：断言 RTL/adaptive 调用序列与限速因子序列。 */
    private static final class RecordingControl implements AutonomyExecutor.FlightControl {
        boolean enabled = true;
        boolean executable = true;
        boolean failsafe = false;
        boolean throwOnEngage = false;
        final List<String> rtlCalls = new ArrayList<>();
        final List<Double> speedCalls = new ArrayList<>();
        final List<String> adaptCalls = new ArrayList<>();

        @Override
        public boolean autonomyExecEnabled() {
            return enabled;
        }

        @Override
        public boolean inExecutableState() {
            return executable;
        }

        @Override
        public boolean failsafeActive() {
            return failsafe;
        }

        @Override
        public void engageRtl(String aiReason) {
            if (throwOnEngage) {
                throw new IllegalStateException("boom");
            }
            rtlCalls.add(aiReason);
        }

        @Override
        public void adaptPath(String aiReason) {
            adaptCalls.add(aiReason);
        }

        @Override
        public void setAvoidSpeedFactor(double factor) {
            speedCalls.add(factor);
        }
    }

    private static FusedDecision fused(String type, String reason) {
        DecisionResult primary = new DecisionResult(type, reason, 10.0, 0.9);
        return new FusedDecision(primary, new ArrayList<>(List.of(primary)),
                1.0, 1.0, 1.0, "test", null);
    }

    private static FusedDecision cleared() {
        return new FusedDecision(null, new ArrayList<>(), 1.0, 1.0, 1.0, "test", null);
    }

    @Test
    @DisplayName("开关关闭（默认）：任何决策都不执行")
    void disabledExecutesNothing() {
        RecordingControl control = new RecordingControl();
        control.enabled = false;
        AutonomyExecutor executor = new AutonomyExecutor(control);
        executor.onDecision(fused("RTL", "low battery"));
        executor.onDecision(fused("AVOID", "obstacle ahead, climb"));
        assertTrue(control.rtlCalls.isEmpty(), "--autonomy-exec 未开启时不得执行 RTL");
        assertTrue(control.speedCalls.isEmpty(), "--autonomy-exec 未开启时不得改速度因子");
    }

    @Test
    @DisplayName("failsafe 激活期间永不抢杆，且复位避障限速")
    void failsafeAlwaysWins() {
        RecordingControl control = new RecordingControl();
        control.failsafe = true;
        AutonomyExecutor executor = new AutonomyExecutor(control);
        executor.onDecision(fused("RTL", "low battery"));
        executor.onDecision(fused("AVOID", "obstacle ahead, climb"));
        assertTrue(control.rtlCalls.isEmpty(), "failsafe 激活时执行级不得抢杆");
        assertEquals(List.of(1.0, 1.0), control.speedCalls, "failsafe 期间应复位限速 1.0");
    }

    @Test
    @DisplayName("RTL 决策在可执行态触发一次 RTL 程序，并先复位限速")
    void rtlDecisionEngagesRtl() {
        RecordingControl control = new RecordingControl();
        AutonomyExecutor executor = new AutonomyExecutor(control);
        executor.onDecision(fused("RTL", "low battery"));
        assertEquals(1, control.rtlCalls.size(), "RTL 变化沿应恰好执行一次");
        assertEquals("RTL: low battery", control.rtlCalls.get(0));
        assertEquals(List.of(1.0), control.speedCalls, "RTL 前应复位限速");
    }

    @Test
    @DisplayName("EMERGENCY_LAND 复用 RTL 程序（仿真无独立原地降落原语）")
    void emergencyLandMapsToRtl() {
        RecordingControl control = new RecordingControl();
        AutonomyExecutor executor = new AutonomyExecutor(control);
        executor.onDecision(fused("EMERGENCY_LAND", "GPS degraded"));
        assertEquals(1, control.rtlCalls.size());
        assertEquals("EMERGENCY_LAND: GPS degraded", control.rtlCalls.get(0));
    }

    @Test
    @DisplayName("AVOID 压限速到 0.5 且不触发 RTL；清除沿恢复 1.0")
    void avoidCapsSpeedAndClearRestores() {
        RecordingControl control = new RecordingControl();
        AutonomyExecutor executor = new AutonomyExecutor(control);
        executor.onDecision(fused("AVOID", "obstacle ahead, climb"));
        assertEquals(List.of(AutonomyExecutor.AVOID_SPEED_FACTOR), control.speedCalls);
        assertTrue(control.rtlCalls.isEmpty(), "AVOID 不应触发 RTL");
        executor.onDecision(cleared());
        assertEquals(2, control.speedCalls.size());
        assertEquals(1.0, control.speedCalls.get(1), 1e-9, "清除沿应恢复限速 1.0");
    }

    @Test
    @DisplayName("AVOID 在不可执行态（STANDBY/RTL/HOLD/CRASHED/MANUAL）不压限速")
    void avoidInNonExecutableStateDoesNotCap() {
        RecordingControl control = new RecordingControl();
        control.executable = false;
        AutonomyExecutor executor = new AutonomyExecutor(control);
        executor.onDecision(fused("AVOID", "obstacle ahead, climb"));
        assertEquals(List.of(1.0), control.speedCalls);
    }

    @Test
    @DisplayName("ADAPT_PATH 执行级（2026-10-04 接线）：可执行态触发一次 adaptPath，先复位限速且不 RTL")
    void adaptPathExecutesInExecutableState() {
        RecordingControl control = new RecordingControl();
        AutonomyExecutor executor = new AutonomyExecutor(control);
        executor.onDecision(fused("ADAPT_PATH", "strong wind"));
        assertEquals(List.of("strong wind"), control.adaptCalls,
                "ADAPT_PATH 变化沿应恰好触发一次自适应执行");
        assertTrue(control.rtlCalls.isEmpty(), "ADAPT_PATH 不得触发 RTL");
        assertEquals(List.of(1.0), control.speedCalls, "适配前先复位限速（调速由执行实现自理）");
    }

    @Test
    @DisplayName("ADAPT_PATH 在不可执行态不执行：只复位限速")
    void adaptPathInNonExecutableStateIsIgnored() {
        RecordingControl control = new RecordingControl();
        control.executable = false;
        AutonomyExecutor executor = new AutonomyExecutor(control);
        executor.onDecision(fused("ADAPT_PATH", "strong wind"));
        assertTrue(control.adaptCalls.isEmpty(), "不可执行态不得改写任务");
        assertEquals(List.of(1.0), control.speedCalls);
    }

    @Test
    @DisplayName("RTL 在不可执行态（STANDBY/RTL/HOLD/CRASHED/MANUAL）不执行")
    void rtlInNonExecutableStateIsIgnored() {
        RecordingControl control = new RecordingControl();
        control.executable = false;
        AutonomyExecutor executor = new AutonomyExecutor(control);
        executor.onDecision(fused("RTL", "low battery"));
        assertTrue(control.rtlCalls.isEmpty(), "不可执行态不得接管");
    }

    @Test
    @DisplayName("执行级异常不外抛（不得影响 tickOnce 链路）")
    void executorSwallowsControlExceptions() {
        RecordingControl control = new RecordingControl();
        control.throwOnEngage = true;
        AutonomyExecutor executor = new AutonomyExecutor(control);
        assertDoesNotThrow(() -> executor.onDecision(fused("RTL", "low battery")));
    }

    @Test
    @DisplayName("null/无决策 fused 等价于清除沿：只复位限速")
    void nullFusedResetsSpeedFactor() {
        RecordingControl control = new RecordingControl();
        AutonomyExecutor executor = new AutonomyExecutor(control);
        executor.onDecision(null);
        executor.onDecision(cleared());
        assertEquals(List.of(1.0, 1.0), control.speedCalls);
        assertTrue(control.rtlCalls.isEmpty());
    }
}

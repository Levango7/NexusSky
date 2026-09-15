package io.aerofleet.sim;

import io.aerofleet.mavlink.enums.LightPattern;
import io.aerofleet.mavlink.messages.LedControlMsg;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LedState 单测（FR-10/12，tasks T12）：
 * 默认关灯、LedControlMsg→LedState 映射、开/关灯激活、相位推进、状态上报字段一致。
 *
 * <p>LedState 为不可变值对象，{@code applyLedControl} 复刻 {@link VirtualDrone#handleLedControl}
 * 的映射逻辑（FR-12），{@code tickAdvancesPhase} 通过 {@link LedState#currentBrightnessAt}
 * 验证相位随时间推进，{@code toStatusMessageReflectsState} 复刻 {@link VirtualDrone#sendLedStatus}
 * 的 STATUSTEXT 格式验证字段反映。
 */
class LedStateTest {

    /** 复刻 VirtualDrone.handleLedControl 的 LedControlMsg → LedState 映射（FR-12）。 */
    private static LedState applyLedControl(LedControlMsg led) {
        return new LedState(led.on, led.pattern, led.brightness, led.freq,
                led.phaseStartUs, led.colorR, led.colorG, led.colorB);
    }

    @Test
    void defaultStateIsOff() {
        LedState s = LedState.off();
        assertFalse(s.on, "default state should be off (on=false)");
        assertEquals(LightPattern.STEADY.ordinal(), s.pattern,
                "default pattern should be STEADY (0)");
        assertEquals(0, s.brightness);
        assertEquals(0, s.freq);
        assertEquals(0L, s.phaseStartUs);
        assertEquals(0, s.colorR);
        assertEquals(0, s.colorG);
        assertEquals(0, s.colorB);
    }

    @Test
    void applyLedControlUpdatesPattern() {
        LedControlMsg led = new LedControlMsg(1, 1, 255, 0, 0,
                LightPattern.BLINK.ordinal(), 100, 5, true, false, 0L, 0, 0);
        LedState s = applyLedControl(led);
        assertEquals(LightPattern.BLINK.ordinal(), s.pattern,
                "pattern should update to BLINK after apply");
    }

    @Test
    void applyLedControlSetsActive() {
        // 非 OFF 模式（on=true）→ active=true
        LedControlMsg led = new LedControlMsg(1, 1, 255, 0, 0,
                LightPattern.BREATHE.ordinal(), 80, 2, true, false, 1000L, 0, 0);
        LedState s = applyLedControl(led);
        assertTrue(s.on, "on=true command should activate led");
    }

    @Test
    void applyOffPatternDeactivates() {
        // OFF 由 on=false 表示（LightPattern 无 OFF 枚举，DFX 4.5）
        LedControlMsg led = new LedControlMsg(1, 1, 0, 0, 0,
                LightPattern.STEADY.ordinal(), 0, 0, false, false, 0L, 0, 0);
        LedState s = applyLedControl(led);
        assertFalse(s.on, "on=false command should deactivate led");
        assertEquals(0, s.currentBrightnessAt(0L),
                "deactivated led should produce 0 brightness");
    }

    @Test
    void tickAdvancesPhase() {
        // BREATHE 模式：正弦渐变，相位随时间推进 → 亮度变化
        LedState s = new LedState(true, LightPattern.BREATHE.ordinal(), 100, 1,
                0L, 255, 0, 0);
        // t=0s:  phase=0,      sin(0)=0   → 100*(0.5+0.5*0)   = 50
        // t=0.25s: phase=π/2,  sin(π/2)=1 → 100*(0.5+0.5*1)   = 100
        double b0 = s.currentBrightnessAt(0L);
        double bQuarter = s.currentBrightnessAt(250_000L);
        assertEquals(50, b0, 1e-9, "BREATHE at t=0 should be 50");
        assertEquals(100, bQuarter, 1e-6, "BREATHE at t=0.25s should be ~100");
        assertNotEquals(b0, bQuarter, "phase advance should change brightness");
    }

    @Test
    void toStatusMessageReflectsState() {
        LedState s = new LedState(true, LightPattern.CHASE.ordinal(), 75, 4,
                1000L, 255, 0, 0);
        // 复刻 VirtualDrone.sendLedStatus 的 STATUSTEXT 格式："LED:on:%d:%d:%d:%d:%d"
        String status = String.format("LED:on:%d:%d:%d:%d:%d",
                s.pattern, s.colorR, s.colorG, s.colorB, s.brightness);
        assertEquals("LED:on:3:255:0:0:75", status,
                "status should encode pattern/rgb/brightness from LedState");
        // 未开灯不上报（DFX 4.5）
        LedState off = LedState.off();
        assertFalse(off.on, "off state should not report");
    }
}
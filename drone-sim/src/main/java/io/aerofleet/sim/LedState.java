package io.aerofleet.sim;

import io.aerofleet.mavlink.enums.LightPattern;

/**
 * 虚拟无人机灯光状态（FR-12，不可变值对象）。
 *
 * 字段：on/pattern/brightness/freq/phaseStartUs/colorR/G/B。
 * 由 VirtualDrone 持有 volatile 引用，接收线程写、tick 线程读。
 * 未收到灯光命令时为 {@link #off()}，不产生灯光状态变更（DFX 4.5）。
 */
public final class LedState {

    public final boolean on;
    public final int pattern;       // LightPattern.ordinal()
    public final int brightness;    // 0-100
    public final int freq;          // Hz
    public final long phaseStartUs;
    public final int colorR;
    public final int colorG;
    public final int colorB;

    public LedState(boolean on, int pattern, int brightness, int freq,
                    long phaseStartUs, int r, int g, int b) {
        this.on = on;
        this.pattern = pattern;
        this.brightness = brightness;
        this.freq = freq;
        this.phaseStartUs = phaseStartUs;
        this.colorR = r;
        this.colorG = g;
        this.colorB = b;
    }

    /** 全关默认状态（DFX 4.5：未收到灯光命令时 ledState=off()）。 */
    public static LedState off() {
        return new LedState(false, 0, 0, 0, 0, 0, 0, 0);
    }

    /**
     * 计算时刻 clockUs（微秒，基于队内时钟基准）的当前亮度百分比（0-100）。
     *
     * @param clockUs 当前时钟（微秒）
     * @return 亮度 0-100；未开灯返回 0
     */
    public double currentBrightnessAt(long clockUs) {
        if (!on) {
            return 0;
        }
        double tSec = (clockUs - phaseStartUs) / 1_000_000.0;
        LightPattern p = pattern >= 0 && pattern < LightPattern.values().length
                ? LightPattern.values()[pattern]
                : LightPattern.STEADY;
        return p.brightnessAt(brightness, freq, tSec, 0);
    }
}
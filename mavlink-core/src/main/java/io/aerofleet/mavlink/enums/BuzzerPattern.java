package io.aerofleet.mavlink.enums;

/**
 * 蜂鸣器报警模式枚举（千元级灾害应急配置）。
 * <p>
 * 定义三种蜂鸣器声光报警模式：
 * <ul>
 *   <li>{@link #CONTINUOUS} — 连续鸣响：持续不间断报警，紧急程度最高</li>
 *   <li>{@link #INTERMITTENT} — 间歇鸣响：周期性断续报警，提示注意</li>
 *   <li>{@link #SOS_MORSE} — SOS 摩斯码：三短三长三短循环，国际通用求救信号</li>
 * </ul>
 * 纯函数无状态，线程安全。
 */
public enum BuzzerPattern {
    /** 连续鸣响：持续不间断报警，紧急程度最高。 */
    CONTINUOUS,
    /** 间歇鸣响：周期性断续报警，提示注意。 */
    INTERMITTENT,
    /** SOS 摩斯码：三短三长三短循环，国际通用求救信号。 */
    SOS_MORSE;

    /**
     * 计算时刻 t（秒）蜂鸣器是否鸣响。
     *
     * @param freq 频率（Hz，仅用于 INTERMITTENT 模式）
     * @param tSec 当前时间（秒）
     * @return true=鸣响，false=静默
     */
    public boolean isOnAt(double freq, double tSec) {
        return switch (this) {
            case CONTINUOUS -> true;
            case INTERMITTENT -> {
                double phase = 2 * Math.PI * freq * tSec;
                yield Math.sin(phase) >= 0;
            }
            case SOS_MORSE -> {
                // SOS 摩斯码：三短三长三短循环，周期约 6 秒
                double cycle = 6.0;
                double t = tSec % cycle;
                if (t < 0) t += cycle;
                yield isSosBuzzing(t);
            }
        };
    }

    /**
     * SOS 摩斯码时间线判断：在给定时刻 t（0~6s 周期内）蜂鸣器是否鸣响。
     * <p>
     * 时间线与 {@code SearchLightPattern.SOS_MORSE} 一致：
     * 三短(0.2s ON + 0.2s OFF) × 3 + 词间隔(0.4s OFF) +
     * 三长(0.6s ON + 0.2s OFF) × 3 + 词间隔(0.4s OFF) +
     * 三短(0.2s ON + 0.2s OFF) × 3 + 循环间隔(1.0s OFF)
     *
     * @param t 周期内时间（秒，0~6）
     * @return true=鸣响，false=静默
     */
    private static boolean isSosBuzzing(double t) {
        // 三短
        if (t >= 0.0 && t < 0.2) return true;
        if (t >= 0.4 && t < 0.6) return true;
        if (t >= 0.8 && t < 1.0) return true;
        // 三长
        if (t >= 1.4 && t < 2.0) return true;
        if (t >= 2.2 && t < 2.8) return true;
        if (t >= 3.0 && t < 3.6) return true;
        // 三短
        if (t >= 4.0 && t < 4.2) return true;
        if (t >= 4.4 && t < 4.6) return true;
        if (t >= 4.8 && t < 5.0) return true;
        return false;
    }
}
package io.aerofleet.sim;

/**
 * 搜救信号灯模式枚举（千元级灾害应急配置）。
 * <p>
 * 扩展 LED 搜救信号灯控制，定义四种搜救灯效模式：
 * <ul>
 *   <li>{@link #FLASH} — 闪烁：高频方波切换，远距离可见性最强</li>
 *   <li>{@link #STEADY} — 常亮：亮度恒定，近距离定位标识</li>
 *   <li>{@link #SOS_MORSE} — SOS 摩斯码：三短三长三短循环，国际通用求救信号</li>
 *   <li>{@link #BREATHING} — 呼吸灯：正弦渐变，低功耗持续指示</li>
 * </ul>
 * 纯函数无状态，线程安全。
 */
public enum SearchLightPattern {
    /** 闪烁：高频方波切换，远距离可见性最强。 */
    FLASH,
    /** 常亮：亮度恒定，近距离定位标识。 */
    STEADY,
    /** SOS 摩斯码：三短三长三短循环，国际通用求救信号。 */
    SOS_MORSE,
    /** 呼吸灯：正弦渐变，低功耗持续指示。 */
    BREATHING;

    /**
     * 计算时刻 t（秒）的亮度百分比（0-100）。
     *
     * @param baseBrightness 基础亮度（0-100）
     * @param freq           频率（Hz）
     * @param tSec           当前时间（秒）
     * @return 亮度 0-100
     */
    public double brightnessAt(double baseBrightness, double freq, double tSec) {
        return switch (this) {
            case FLASH -> {
                double phase = 2 * Math.PI * freq * tSec;
                yield (Math.sin(phase) >= 0) ? baseBrightness : 0;
            }
            case STEADY -> baseBrightness;
            case SOS_MORSE -> {
                // SOS 摩斯码：三短(·)三长(—)三短(·)，循环周期约 6 秒
                // 每个短信号 0.2s，长信号 0.6s，间隔 0.2s，词间隔 1.0s
                double cycle = 6.0;
                double t = tSec % cycle;
                if (t < 0) t += cycle;
                yield isSosOn(t) ? baseBrightness : 0;
            }
            case BREATHING -> {
                double phase = 2 * Math.PI * freq * tSec;
                yield baseBrightness * (0.5 + 0.5 * Math.sin(phase));
            }
        };
    }

    /**
     * SOS 摩斯码时间线判断：在给定时刻 t（0~6s 周期内）灯是否亮。
     * <p>
     * 时间线（秒）：
     * <pre>
     *   0.0-0.2  短(·)  ON
     *   0.2-0.4  间隔   OFF
     *   0.4-0.6  短(·)  ON
     *   0.6-0.8  间隔   OFF
     *   0.8-1.0  短(·)  ON
     *   1.0-1.4  词间隔 OFF
     *   1.4-2.0  长(—)  ON
     *   2.0-2.2  间隔   OFF
     *   2.2-2.8  长(—)  ON
     *   2.8-3.0  间隔   OFF
     *   3.0-3.6  长(—)  ON
     *   3.6-4.0  词间隔 OFF
     *   4.0-4.2  短(·)  ON
     *   4.2-4.4  间隔   OFF
     *   4.4-4.6  短(·)  ON
     *   4.6-4.8  间隔   OFF
     *   4.8-5.0  短(·)  ON
     *   5.0-6.0  循环间隔 OFF
     * </pre>
     *
     * @param t 周期内时间（秒，0~6）
     * @return true=灯亮，false=灯灭
     */
    private static boolean isSosOn(double t) {
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
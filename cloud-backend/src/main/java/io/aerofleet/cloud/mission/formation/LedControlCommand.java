package io.aerofleet.cloud.mission.formation;

/**
 * 灯光控制命令 DTO（FR-11，数据约束 6.4）。
 *
 * 承载编队级灯光命令参数：开关、颜色、灯效模式、亮度、频率、同步标志。
 * 由 REST 端点反序列化，委托 {@code FormationService.lights()} 扇出到各机。
 *
 * 不可变值对象；{@code Formation.lastLightCommand} 持有 volatile 引用。
 */
public final class LedControlCommand {

    /** 是否开灯。 */
    public final boolean on;
    /** 红色分量 0-255。 */
    public final int colorR;
    /** 绿色分量 0-255。 */
    public final int colorG;
    /** 蓝色分量 0-255。 */
    public final int colorB;
    /** 灯效模式（LightPattern.ordinal()）。 */
    public final int pattern;
    /** 亮度 0-100%。 */
    public final int brightness;
    /** 频率 0-20 Hz。 */
    public final int freq;
    /** 是否同步（true 时使用队内时钟基准统一相位起点）。 */
    public final boolean sync;

    public LedControlCommand(boolean on, int colorR, int colorG, int colorB,
                             int pattern, int brightness, int freq, boolean sync) {
        this.on = on;
        this.colorR = colorR;
        this.colorG = colorG;
        this.colorB = colorB;
        this.pattern = pattern;
        this.brightness = brightness;
        this.freq = freq;
        this.sync = sync;
    }
}
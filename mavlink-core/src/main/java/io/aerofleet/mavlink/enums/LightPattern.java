package io.aerofleet.mavlink.enums;

/**
 * 灯效模式枚举（FR-10）：定义五种灯效与亮度-时间函数。
 *
 * 每种灯效有明确的亮度-时间函数：
 *   STEADY  - 常亮：亮度恒定
 *   BLINK   - 闪烁：方波切换
 *   BREATHE - 呼吸：正弦渐变
 *   CHASE   - 追逐：相位偏移的窄脉冲（多灯组流水效果）
 *   RAINBOW - 彩虹：色相旋转，亮度恒定
 *
 * 纯函数无状态，线程安全。
 */
public enum LightPattern {
    STEADY,    // 常亮：亮度恒定
    BLINK,     // 闪烁：方波切换
    BREATHE,   // 呼吸：正弦渐变
    CHASE,     // 追逐：相位偏移的闪烁（多灯组流水效果）
    RAINBOW;   // 彩虹：色相旋转

    /**
     * 计算时刻 t（秒）的亮度百分比（0-100）。
     *
     * @param baseBrightness 基础亮度（0-100）
     * @param freq           频率（Hz）
     * @param tSec           当前时间（秒，基于队内时钟基准）
     * @param phaseOffset    相位偏移（0-1，用于 CHASE 多机错相）
     * @return 亮度 0-100
     */
    public double brightnessAt(double baseBrightness, double freq, double tSec, double phaseOffset) {
        double phase = 2 * Math.PI * freq * tSec + phaseOffset * 2 * Math.PI;
        return switch (this) {
            case STEADY -> baseBrightness;
            case BLINK -> ((Math.sin(phase) >= 0) ? baseBrightness : 0);
            case BREATHE -> baseBrightness * (0.5 + 0.5 * Math.sin(phase));
            case CHASE -> ((Math.sin(phase) >= 0.7) ? baseBrightness : 0);  // 窄脉冲流水
            case RAINBOW -> baseBrightness;  // 彩虹由色相旋转控制，亮度恒定
        };
    }

    /**
     * 计算时刻 t 的 RGB 颜色（RAINBOW 色相旋转，其余保持原色）。
     *
     * @param r            红色分量 0-255
     * @param g            绿色分量 0-255
     * @param b            蓝色分量 0-255
     * @param freq         频率（Hz）
     * @param tSec         当前时间（秒）
     * @param phaseOffset  相位偏移（0-1）
     * @return RGB 三元组 [r, g, b]，每个 0-255
     */
    public int[] colorAt(int r, int g, int b, double freq, double tSec, double phaseOffset) {
        if (this != RAINBOW) {
            return new int[]{r, g, b};
        }
        double hue = (freq * tSec + phaseOffset) % 1.0;
        if (hue < 0) {
            hue += 1.0;  // 保证非负
        }
        return hsvToRgb(hue, 1.0, 1.0);  // 色相旋转，饱和度/明度=1
    }

    /**
     * HSV → RGB 转换（h ∈ [0,1], s ∈ [0,1], v ∈ [0,1]）。
     * 返回 [r, g, b]，每个 0-255。
     */
    private static int[] hsvToRgb(double h, double s, double v) {
        double c = v * s;
        double hp = h * 6.0;
        double x = c * (1 - Math.abs(hp % 2 - 1));
        double r1, g1, b1;
        if (hp < 1) {
            r1 = c; g1 = x; b1 = 0;
        } else if (hp < 2) {
            r1 = x; g1 = c; b1 = 0;
        } else if (hp < 3) {
            r1 = 0; g1 = c; b1 = x;
        } else if (hp < 4) {
            r1 = 0; g1 = x; b1 = c;
        } else if (hp < 5) {
            r1 = x; g1 = 0; b1 = c;
        } else {
            r1 = c; g1 = 0; b1 = x;
        }
        double m = v - c;
        return new int[]{
                (int) Math.round((r1 + m) * 255),
                (int) Math.round((g1 + m) * 255),
                (int) Math.round((b1 + m) * 255)
        };
    }
}
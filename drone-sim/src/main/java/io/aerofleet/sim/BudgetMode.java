package io.aerofleet.sim;

/**
 * 丐版预算模式枚举。
 * <p>
 * 定义所有预算档位，包括通用丐版模式（toy/standard/advanced）
 * 和灾害应急模式（emergency-toy/emergency-standard）。
 * <p>
 * 灾害应急模式在通用丐版基础上进一步裁剪/替代传感器，
 * 适配灾区断网、废墟飞行、搜救信号等应急场景。
 */
public enum BudgetMode {

    /** 百元级通用丐版（~63元）：IMU悬停 + WiFi图传 + 超声波避障。 */
    TOY("toy", false),

    /** 千元级通用丐版（~356元）：GPS航点 + LoRa Mesh + MAVLink接入。 */
    STANDARD("standard", false),

    /** 进阶版丐版（~766元）：千元级 + 光流 + 红外阵列 + 双频GPS。 */
    ADVANCED("advanced", false),

    /** 百元级灾害应急（~74元）：WiFi ESP-NOW Mesh + HC-SR04超声波 + ESP32-CAM + LED/蜂鸣器。 */
    EMERGENCY_TOY("emergency-toy", true),

    /** 千元级灾害应急（~429元）：LoRa Mesh 5km + VL53L0X ToF + NEO-M8N GPS + AMG8833 + LED/蜂鸣器。 */
    EMERGENCY_STANDARD("emergency-standard", true);

    /** CLI 参数值（--budget=xxx）。 */
    private final String cliValue;

    /** 是否为灾害应急模式。 */
    private final boolean emergency;

    BudgetMode(String cliValue, boolean emergency) {
        this.cliValue = cliValue;
        this.emergency = emergency;
    }

    /** CLI 参数值，如 "toy"、"emergency-toy"。 */
    public String cliValue() {
        return cliValue;
    }

    /** 是否为灾害应急模式。 */
    public boolean isEmergency() {
        return emergency;
    }

    /**
     * 从 CLI 字符串值解析为 BudgetMode 枚举。
     *
     * @param value CLI 参数值（如 "toy"、"emergency-toy"）
     * @return 对应的 BudgetMode，无效值返回 null
     */
    public static BudgetMode fromCliValue(String value) {
        if (value == null) {
            return null;
        }
        for (BudgetMode mode : values()) {
            if (mode.cliValue.equals(value)) {
                return mode;
            }
        }
        return null;
    }
}
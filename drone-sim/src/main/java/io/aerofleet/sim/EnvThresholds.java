package io.aerofleet.sim;

/**
 * 环境告警阈值配置（FR-18/19/20，数据约束 6.3）。
 * 包私有——由同包 {@link EnvAlertEngine} 与 {@link EnvironmentModel} 构造。
 */
final class EnvThresholds {
    final double windWarn;       // 风速警告阈值 m/s
    final double windCrit;       // 风速危险阈值 m/s
    final double tempLowWarn;    // 低温警告阈值 °C
    final double tempLowCrit;    // 低温危险阈值 °C
    final double tempHighWarn;   // 高温警告阈值 °C
    final double tempHighCrit;   // 高温危险阈值 °C
    final double humidityCondensation;  // 凝露湿度阈值 %

    EnvThresholds(double windWarn, double windCrit,
                  double tempLowWarn, double tempLowCrit,
                  double tempHighWarn, double tempHighCrit,
                  double humidityCondensation) {
        this.windWarn = windWarn;
        this.windCrit = windCrit;
        this.tempLowWarn = tempLowWarn;
        this.tempLowCrit = tempLowCrit;
        this.tempHighWarn = tempHighWarn;
        this.tempHighCrit = tempHighCrit;
        this.humidityCondensation = humidityCondensation;
    }

    /** 默认阈值：(8, 12, 0, -10, 45, 50, 95)。 */
    static EnvThresholds defaults() {
        return new EnvThresholds(8, 12, 0, -10, 45, 50, 95);
    }
}
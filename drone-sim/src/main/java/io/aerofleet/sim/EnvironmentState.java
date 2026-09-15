package io.aerofleet.sim;

/**
 * 不可变环境状态 record（FR-02 五元组 + 阵风 + 能见度 + 降雨率）。
 * <p>
 * 不可变性保证遥测发送线程读取到的五元组一致（DFX 4.2 并发安全——volatile 引用 + 不可变内容）。
 * record 自动生成 equals/hashCode/toString，便于单测断言逐 tick 序列相等（FR-05 确定性）。
 *
 * @param temperature   环境气温 °C，定义域 [-40, 55]
 * @param humidity      相对湿度 %，定义域 [0, 100]
 * @param windSpeed     水平风速 m/s，定义域 [0, 50]
 * @param windDirection 风来源方位角 deg，定义域 [0, 360)
 * @param gust          阵风瞬时增量 m/s
 * @param weather       天气状态枚举
 * @param rainRate      降雨率 mm/h，定义域 [0, 255]
 */
public record EnvironmentState(
        double temperature,
        double humidity,
        double windSpeed,
        double windDirection,
        double gust,
        Weather weather,
        int rainRate
) {
    /** 能见度由天气状态推导（FR-17）。 */
    public int visibilityM() {
        return weather.visibilityM;
    }

    /**
     * 风北分量（m/s）：风向 0°=正北风（风从北吹向南）→ windNorth < 0（FR-07）。
     * 约定：北为正、东为正；风从某方向吹来，故取负号。
     */
    public double windNorth() {
        return -windSpeed * Math.cos(Math.toRadians(windDirection));
    }

    /**
     * 风东分量（m/s）：风向 90°=正东风（风从东吹向西）→ windEast < 0（FR-07）。
     */
    public double windEast() {
        return -windSpeed * Math.sin(Math.toRadians(windDirection));
    }

    /**
     * 阵风北分量（m/s）。阵风方向由伪随机决定（seed 可复现）。
     *
     * @param gustDirRad 阵风方向（弧度）
     */
    public double gustNorth(double gustDirRad) {
        return gust * Math.cos(gustDirRad);
    }

    /** 阵风东分量（m/s）。 */
    public double gustEast(double gustDirRad) {
        return gust * Math.sin(gustDirRad);
    }

    /**
     * 凝露风险（FR-12）：湿度 ≥ 95% 且温度 ≤ 5°C 高湿即凝露。
     * 简化判定：以 5°C 作为露点近似阈值。
     */
    public boolean condensationRisk() {
        return humidity >= 95.0 && temperature <= 5.0;
    }
}
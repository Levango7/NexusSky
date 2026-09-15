package io.aerofleet.sim;

import java.util.Random;

/**
 * 模拟器假数据源（FR-05 确定性）：场景脚本基线值 + {@link Random}(seed) 伪随机游走。
 * <p>
 * 同 seed + 同场景 → 逐 tick 序列完全相等（确定性，可复现）。
 * 演化逻辑：
 * <ol>
 *   <li>温湿度慢漂移：基线 + 伪随机游走，钳位到定义域</li>
 *   <li>风速：场景基线 + 抖动，钳位 [0, 50]</li>
 *   <li>风向：缓慢漂移，归一化到 [0, 360)</li>
 *   <li>阵风：伪随机脉冲，幅度由场景决定</li>
 *   <li>天气状态转移：按场景稳定性概率保持或转移</li>
 *   <li>降雨率：RAIN/SNOW 时按基线 + 抖动，否则 0</li>
 * </ol>
 */
public final class SimulatedEnvSource implements EnvironmentSource {
    private final EnvScenario scenario;
    private final Random rng;  // java.util.Random(seed)，确定性
    private volatile EnvironmentState current;

    /**
     * 构造假数据源。
     *
     * @param scenario 气象场景
     * @param seed     伪随机种子（同 seed 同场景 → 序列可复现）
     */
    public SimulatedEnvSource(EnvScenario scenario, long seed) {
        this.scenario = scenario;
        this.rng = new Random(seed);
        this.current = scenario.baseline();  // 场景基线状态
    }

    @Override
    public EnvironmentState snapshot() {
        return current;  // 不可变 record，直接返回
    }

    @Override
    public void evolve(double dt) {
        // 1. 温湿度慢漂移：基线 + 伪随机游走（钳位到定义域）
        double temp = clamp(current.temperature() + rng.nextGaussian() * scenario.tempJitter * dt,
                -40, 55);
        double humid = clamp(current.humidity() + rng.nextGaussian() * scenario.humidJitter * dt,
                0, 100);

        // 2. 风速：场景基线 + 抖动，钳位 [0, 50]
        double windSpeed = clamp(scenario.windBaseline + rng.nextGaussian() * scenario.windJitter,
                0, 50);

        // 3. 风向：缓慢漂移，归一化到 [0, 360)
        double windDir = (current.windDirection() + rng.nextGaussian() * 5.0 * dt) % 360;
        if (windDir < 0) windDir += 360;

        // 4. 阵风：伪随机脉冲，幅度由场景决定
        double gust = rng.nextGaussian() * scenario.gustAmplitude;

        // 5. 天气状态转移：按场景稳定性概率保持或转移
        Weather weather = transitionWeather(current.weather(), scenario, rng);

        // 6. 降雨率：RAIN/SNOW 时按场景基线 + 抖动钳位 [0, 255]，否则 0
        int rainRate = (weather == Weather.RAIN || weather == Weather.SNOW)
                ? (int) clamp(scenario.rainBaseline + rng.nextGaussian() * 2, 0, 255)
                : 0;

        current = new EnvironmentState(temp, humid, windSpeed, windDir, gust, weather, rainRate);
    }

    /**
     * 天气状态转移：场景定义转移概率矩阵。
     * p < weatherStability 保持当前；否则按场景偏向转移。
     */
    private Weather transitionWeather(Weather current, EnvScenario scen, Random rng) {
        double p = rng.nextDouble();
        if (p < scen.weatherStability) return current;  // 大概率保持
        return scen.nextWeather(current, rng);  // 小概率转移
    }

    /** 钳位到 [min, max]。 */
    private static double clamp(double v, double min, double max) {
        return v < min ? min : (v > max ? max : v);
    }

    /**
     * 运行时覆盖当前状态（FR-28，由 {@link EnvironmentModel} 调用）。
     * 下次 evolve 从该状态继续演化。
     */
    void overrideCurrent(EnvironmentState s) {
        this.current = s;
    }
}
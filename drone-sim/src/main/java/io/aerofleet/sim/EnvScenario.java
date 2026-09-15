package io.aerofleet.sim;

import java.util.Random;

/**
 * 气象场景枚举（FR-03）：calm/windy/storm/cold/hot/rainy/foggy 七场景。
 * 每个场景持基线值与扰动参数，供 {@link SimulatedEnvSource} 伪随机游走使用。
 * <p>
 * 字段含义：
 * <ul>
 *   <li>windBaseline / windJitter：风速基线与抖动幅度</li>
 *   <li>gustAmplitude：阵风脉冲幅度</li>
 *   <li>tempBaseline / tempJitter：温度基线与抖动</li>
 *   <li>humidBaseline / humidJitter：湿度基线与抖动</li>
 *   <li>rainBaseline：降雨率基线</li>
 *   <li>weatherStability：天气保持当前状态的概率（0~1，越大越稳定）</li>
 *   <li>defaultWeather：场景默认天气</li>
 * </ul>
 */
public enum EnvScenario {
    // name, windBaseline, windJitter, gustAmp, tempBaseline, tempJitter,
    // humidBaseline, humidJitter, rainBaseline, weatherStability, defaultWeather
    CALM("calm", 2, 0.5, 0.5, 20, 0.5, 50, 1, 0, 0.99, Weather.CLEAR),
    WINDY("windy", 9, 2, 2, 18, 1, 45, 2, 0, 0.95, Weather.CLOUDY),
    STORM("storm", 14, 4, 6, 15, 2, 80, 5, 15, 0.90, Weather.RAIN),
    COLD("cold", 3, 1, 1, -10, 1, 40, 2, 0, 0.97, Weather.CLEAR),
    HOT("hot", 3, 1, 1, 45, 1, 30, 2, 0, 0.97, Weather.CLEAR),
    RAINY("rainy", 5, 1.5, 2, 18, 1, 85, 3, 10, 0.92, Weather.RAIN),
    FOGGY("foggy", 1, 0.5, 0.5, 8, 0.5, 96, 1, 0, 0.98, Weather.FOG);

    public final String name;
    public final double windBaseline, windJitter, gustAmplitude;
    public final double tempBaseline, tempJitter;
    public final double humidBaseline, humidJitter;
    public final double rainBaseline;
    /** 天气保持当前的概率（0~1，越大越稳定）。 */
    public final double weatherStability;
    public final Weather defaultWeather;

    EnvScenario(String name, double windBaseline, double windJitter, double gustAmp,
                double tempBaseline, double tempJitter,
                double humidBaseline, double humidJitter,
                double rainBaseline, double weatherStability, Weather defaultWeather) {
        this.name = name;
        this.windBaseline = windBaseline;
        this.windJitter = windJitter;
        this.gustAmplitude = gustAmp;
        this.tempBaseline = tempBaseline;
        this.tempJitter = tempJitter;
        this.humidBaseline = humidBaseline;
        this.humidJitter = humidJitter;
        this.rainBaseline = rainBaseline;
        this.weatherStability = weatherStability;
        this.defaultWeather = defaultWeather;
    }

    /** 场景基线状态（FR-03 场景选择初始值）。 */
    EnvironmentState baseline() {
        return new EnvironmentState(tempBaseline, humidBaseline, windBaseline, 0, 0,
                defaultWeather, (int) rainBaseline);
    }

    /**
     * 天气转移：按场景偏向选择下一状态。
     * 简化实现：均匀随机到任意天气状态；场景稳定性由 {@link #weatherStability} 在调用方控制（小概率转移）。
     */
    Weather nextWeather(Weather current, Random rng) {
        Weather[] all = Weather.values();
        return all[rng.nextInt(all.length)];
    }

    /**
     * 按名称查找场景，大小写不敏感；未知返回 {@link #CALM} 并告警（FR-03 异常容错 5.2.3-1）。
     *
     * @param name 场景名（可为 null）
     * @return 对应 EnvScenario，未知时返回 CALM
     */
    public static EnvScenario of(String name) {
        if (name == null) return CALM;
        for (EnvScenario s : values()) {
            if (s.name.equalsIgnoreCase(name)) return s;
        }
        SimLog.warn("unknown env-scenario: " + name + ", falling back to calm");
        return CALM;
    }

    /** 所有场景名（空格分隔，用于 printUsage）。 */
    public static String names() {
        StringBuilder sb = new StringBuilder();
        for (EnvScenario s : values()) {
            sb.append(s.name).append(' ');
        }
        return sb.toString().trim();
    }
}
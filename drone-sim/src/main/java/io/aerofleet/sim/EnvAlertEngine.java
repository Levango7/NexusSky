package io.aerofleet.sim;

import io.aerofleet.mavlink.enums.MavEnums;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 环境告警引擎（FR-18~23）：每 tick 检测环境状态超限 → 5s 去抖 → 返回告警列表。
 * <p>
 * 检测项：
 * <ul>
 *   <li>FR-18 风速超限：&gt;windCrit critical / &gt;windWarn warning</li>
 *   <li>FR-19 温度异常：&lt;tempLowCrit / &gt;tempHighCrit critical / &lt;tempLowWarn / &gt;tempHighWarn warning</li>
 *   <li>FR-20 天气恶化：CLEAR/CLOUDY → RAIN/SNOW/FOG 或 RAIN/SNOW → FOG（FOG critical / 其他 warning）</li>
 *   <li>FR-12 凝露风险：humidity ≥ 95% && temp ≤ 5°C（info 级）</li>
 * </ul>
 * 去抖（FR-22）：同类型告警在 {@link #debounceMs} 冷却窗口内不重复触发。
 * 分级（FR-21）：severity ∈ {2=CRITICAL, 4=WARNING, 6=INFO}，对齐 MAV_SEVERITY。
 * <p>
 * 包私有——由同包 {@link EnvironmentModel} 持有与调用。
 */
final class EnvAlertEngine {
    private EnvThresholds thresholds;  // 非 final，支持 FR-28 运行时覆盖
    private final long debounceMs;  // 去抖窗口（ms，默认 5000）
    private final long[] lastTriggerMs;  // 每类型上次触发时间戳
    private Weather lastWeather;  // 天气恶化检测：上次天气状态

    /**
     * 构造告警引擎。
     *
     * @param thresholds  阈值配置
     * @param debounceMs  去抖窗口（ms）
     */
    EnvAlertEngine(EnvThresholds thresholds, long debounceMs) {
        this.thresholds = thresholds;
        this.debounceMs = debounceMs;
        this.lastTriggerMs = new long[EnvAlertType.values().length];
        Arrays.fill(lastTriggerMs, 0);
        this.lastWeather = Weather.CLEAR;
    }

    /**
     * 检测环境状态，返回需下传的告警列表（可能为空）。
     * 由 tick 线程独占调用。
     */
    List<EnvAlert> check(EnvironmentState state) {
        List<EnvAlert> alerts = new ArrayList<>();
        long now = System.currentTimeMillis();

        // FR-18 风速超限
        if (state.windSpeed() > thresholds.windCrit) {
            tryAlert(alerts, EnvAlertType.WIND, MavEnums.MAV_SEVERITY_CRITICAL,
                    state.windSpeed(), thresholds.windCrit, now,
                    String.format("Wind critical: %.1f m/s > %.1f", state.windSpeed(), thresholds.windCrit));
        } else if (state.windSpeed() > thresholds.windWarn) {
            tryAlert(alerts, EnvAlertType.WIND, MavEnums.MAV_SEVERITY_WARNING,
                    state.windSpeed(), thresholds.windWarn, now,
                    String.format("Wind warning: %.1f m/s > %.1f", state.windSpeed(), thresholds.windWarn));
        }

        // FR-19 温度异常
        if (state.temperature() < thresholds.tempLowCrit) {
            tryAlert(alerts, EnvAlertType.TEMP, MavEnums.MAV_SEVERITY_CRITICAL,
                    state.temperature(), thresholds.tempLowCrit, now,
                    String.format("Temp critical: %.1fC < %.1f", state.temperature(), thresholds.tempLowCrit));
        } else if (state.temperature() > thresholds.tempHighCrit) {
            tryAlert(alerts, EnvAlertType.TEMP, MavEnums.MAV_SEVERITY_CRITICAL,
                    state.temperature(), thresholds.tempHighCrit, now,
                    String.format("Temp critical: %.1fC > %.1f", state.temperature(), thresholds.tempHighCrit));
        } else if (state.temperature() < thresholds.tempLowWarn) {
            tryAlert(alerts, EnvAlertType.TEMP, MavEnums.MAV_SEVERITY_WARNING,
                    state.temperature(), thresholds.tempLowWarn, now,
                    String.format("Temp warning: %.1fC < %.1f", state.temperature(), thresholds.tempLowWarn));
        } else if (state.temperature() > thresholds.tempHighWarn) {
            tryAlert(alerts, EnvAlertType.TEMP, MavEnums.MAV_SEVERITY_WARNING,
                    state.temperature(), thresholds.tempHighWarn, now,
                    String.format("Temp warning: %.1fC > %.1f", state.temperature(), thresholds.tempHighWarn));
        }

        // FR-20 天气恶化
        if (isDegrade(lastWeather, state.weather())) {
            int sev = (state.weather() == Weather.FOG) ? MavEnums.MAV_SEVERITY_CRITICAL
                    : MavEnums.MAV_SEVERITY_WARNING;
            tryAlert(alerts, EnvAlertType.WEATHER, sev,
                    state.weather().code, lastWeather.code, now,
                    "Weather degrade: " + lastWeather + " -> " + state.weather());
        }
        lastWeather = state.weather();

        // FR-12 凝露风险（info 级）
        if (state.condensationRisk()) {
            tryAlert(alerts, EnvAlertType.HUMIDITY, MavEnums.MAV_SEVERITY_INFO,
                    state.humidity(), thresholds.humidityCondensation, now,
                    String.format("Condensation risk: humidity %.0f%%", state.humidity()));
        }

        return alerts;
    }

    /**
     * 去抖检查（FR-22）：同类型在 debounceMs 内不重复触发。
     */
    private void tryAlert(List<EnvAlert> out, EnvAlertType type, int severity,
                          double value, double threshold, long now, String text) {
        int idx = type.ordinal();
        if (now - lastTriggerMs[idx] < debounceMs) return;  // 冷却窗口内，跳过
        lastTriggerMs[idx] = now;
        out.add(new EnvAlert(type, severity, value, threshold, text));
    }

    /**
     * 天气恶化判定（FR-20）：
     * CLEAR/CLOUDY → RAIN/SNOW/FOG 或 RAIN/SNOW → FOG。
     */
    private boolean isDegrade(Weather from, Weather to) {
        if (from == to) return false;
        return (from == Weather.CLEAR || from == Weather.CLOUDY)
                && (to == Weather.RAIN || to == Weather.SNOW || to == Weather.FOG)
                || (from == Weather.RAIN || from == Weather.SNOW) && to == Weather.FOG;
    }

    /**
     * 运行时覆盖风速告警阈值（FR-28，command 312）。
     * 仅覆盖 windWarn/windCrit，其他阈值保持不变。
     */
    void overrideThresholds(double windWarn, double windCrit) {
        // EnvThresholds 字段为 final，通过重建实例覆盖。
        this.thresholds = new EnvThresholds(windWarn, windCrit,
                thresholds.tempLowWarn, thresholds.tempLowCrit,
                thresholds.tempHighWarn, thresholds.tempHighCrit,
                thresholds.humidityCondensation);
    }
}
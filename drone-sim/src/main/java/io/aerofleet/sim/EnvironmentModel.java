package io.aerofleet.sim;

import io.aerofleet.mavlink.messages.EnvironmentStatus;

import java.util.List;
import java.util.Random;

/**
 * 环境模型门面（FR-01/04/07/08/09/11/18/24/28/34）：组合数据源 + 告警引擎，
 * 提供演化、风向量（含阵风）、温度因子、告警检测、状态消息构造与运行时覆盖 API。
 * <p>
 * 生命周期：由 {@link VirtualDrone} 构造期创建（config.envEnabled 时），tickOnce 每 tick 调
 * {@link #evolve(double)} + {@link #windVector()} + {@link #tempDrainFactor()}，
 * 1Hz 调 {@link #toStatusMessage()}，Ctrl+C 随 VirtualDrone 关闭。
 * <p>
 * 线程安全：{@code current} 为 volatile 引用 + 不可变 record（DFX 4.2）；
 * {@code enabled} 为 volatile；演化由 tick 线程独占调用。
 * <p>
 * 包私有——由同包 {@link VirtualDrone} 持有与调用。
 */
final class EnvironmentModel {
    private final EnvironmentSource source;
    private final EnvAlertEngine alerts;
    private final Random rng;  // 阵风方向伪随机（seed 可复现）
    private volatile EnvironmentState current;
    private volatile boolean enabled;

    /**
     * 构造环境模型门面。
     *
     * @param source 环境数据源（假数据源或真实数据源）
     * @param alerts 告警引擎
     * @param seed   阵风方向伪随机种子（同 seed 可复现）
     */
    EnvironmentModel(EnvironmentSource source, EnvAlertEngine alerts, long seed) {
        this.source = source;
        this.alerts = alerts;
        this.rng = new Random(seed);
        this.current = source.snapshot();
        this.enabled = true;
    }

    /**
     * 演化一个 tick：数据源推进 + 刷新当前状态快照（FR-04）。
     * 由 tick 线程独占调用。
     */
    void evolve(double dt) {
        source.evolve(dt);
        current = source.snapshot();  // volatile 写
    }

    /**
     * 当前环境状态快照（FR-02）。
     * 线程安全：volatile 读 + 不可变 record。
     */
    EnvironmentState snapshot() {
        return current;
    }

    /**
     * 风向量（含阵风叠加，FR-07/09）：返回 {windNorth + gustNorth, windEast + gustEast}。
     * 阵风方向由伪随机决定（seed 可复现）。
     *
     * @return [windNorthTotal, windEastTotal] m/s
     */
    double[] windVector() {
        double[] out = new double[2];
        windVector(out);
        return out;
    }

    /**
     * P2-2: 写入预分配数组，避免每 tick 分配 new double[2]。
     * 语义与 {@link #windVector()} 完全一致，结果写入 out[0]/out[1]。
     */
    void windVector(double[] out) {
        EnvironmentState s = current;
        double gustDirRad = rng.nextDouble() * 2 * Math.PI;
        out[0] = s.windNorth() + s.gustNorth(gustDirRad);
        out[1] = s.windEast() + s.gustEast(gustDirRad);
    }

    /**
     * 温度影响电池能耗的乘性因子（FR-11/13）。
     * <ul>
     *   <li>T &lt; 5°C → 1.3（低温电池内阻增大）</li>
     *   <li>T &gt; 40°C → 1.15（高温散热负荷）</li>
     *   <li>常温 → 1.0（基线不变，DFX 4.5）</li>
     * </ul>
     */
    double tempDrainFactor() {
        double t = current.temperature();
        if (t < 5.0) return 1.3;
        if (t > 40.0) return 1.15;
        return 1.0;
    }

    /**
     * 检测环境告警（FR-18~22），返回需下传的告警列表（可能为空）。
     * 由 tick 线程独占调用。
     */
    List<EnvAlert> checkAlerts() {
        return alerts.check(current);
    }

    /**
     * 构造 ENVIRONMENT_STATUS MAVLink 消息（FR-24，1Hz 周期下传）。
     * 单位转换：temperature×100 c°C / humidity % / windSpeed×100 cm/s /
     * windDirection×100 cdeg / gust×100 cm/s / weather.code / visibilityM m / rainRate mm/h。
     */
    EnvironmentStatus toStatusMessage() {
        return from(current);
    }

    /**
     * 从 EnvironmentState 构造 ENVIRONMENT_STATUS 消息（单位转换，FR-24）。
     */
    static EnvironmentStatus from(EnvironmentState s) {
        return new EnvironmentStatus(
                (int) Math.round(s.temperature() * 100),    // c°C
                (int) Math.round(s.humidity()),              // %
                (int) Math.round(s.windSpeed() * 100),       // cm/s
                (int) Math.round(s.windDirection() * 100),   // cdeg
                (int) Math.round(s.gust() * 100),            // cm/s
                s.weather().code,                            // 枚举
                s.visibilityM(),                             // m
                s.rainRate());                               // mm/h
    }

    // ---- 运行时覆盖 API（FR-28）----

    /**
     * 运行时覆盖风速/风向（FR-28，command 310）。
     * 覆盖 source 当前状态 + 门面快照，下次 evolve 从该状态继续演化。
     */
    void overrideWind(double speed, double dir) {
        EnvironmentState s = current;
        EnvironmentState next = new EnvironmentState(s.temperature(), s.humidity(), speed, dir,
                s.gust(), s.weather(), s.rainRate());
        if (source instanceof SimulatedEnvSource sim) {
            sim.overrideCurrent(next);
        }
        current = next;
    }

    /**
     * 运行时覆盖天气/降雨率（FR-28，command 311）。
     */
    void overrideWeather(Weather weather, int rainRate) {
        EnvironmentState s = current;
        EnvironmentState next = new EnvironmentState(s.temperature(), s.humidity(), s.windSpeed(),
                s.windDirection(), s.gust(), weather, rainRate);
        if (source instanceof SimulatedEnvSource sim) {
            sim.overrideCurrent(next);
        }
        current = next;
    }

    /**
     * 运行时覆盖告警阈值（FR-28，command 312）。
     */
    void overrideThresholds(double windWarn, double windCrit) {
        alerts.overrideThresholds(windWarn, windCrit);
    }

    /** 禁用环境模型（FR-34）：evolve/windVector/tempDrainFactor 将返回基线无影响。 */
    void disable() {
        enabled = false;
    }

    /** 环境模型是否启用。 */
    boolean isEnabled() {
        return enabled;
    }
}
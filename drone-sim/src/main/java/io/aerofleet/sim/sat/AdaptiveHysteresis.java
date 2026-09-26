package io.aerofleet.sim.sat;

import io.aerofleet.sim.mesh.DisasterModeManager;

/**
 * 自适应滞后阈值（FR-22 灾害场景层级切换自适应阈值）。
 * <p>
 * 在灾害模式下，层级切换滞后阈值自动缩短，以更快响应链路变化：
 * <ul>
 *   <li>正常模式：hysteresis = 5s（5000ms）</li>
 *   <li>灾害模式：hysteresis = 2s（2000ms）</li>
 * </ul>
 * <p>
 * 根据 {@link DisasterModeManager} 的灾害模式状态自动切换阈值。
 * <p>
 * 线程安全：hysteresisMs 为 volatile，灾害模式查询委托给 DisasterModeManager。
 */
public final class AdaptiveHysteresis {

    /** 正常模式滞后阈值（ms）。 */
    public static final long NORMAL_HYSTERESIS_MS = 5_000L;
    /** 灾害模式滞后阈值（ms）。 */
    public static final long DISASTER_HYSTERESIS_MS = 2_000L;

    private final DisasterModeManager disasterModeManager;

    /** 当前滞后阈值（volatile，灾害模式切换时更新）。 */
    private volatile long hysteresisMs;

    /** 低层链路恢复起始时间（layer name → recovery start ms）。 */
    private final java.util.concurrent.ConcurrentHashMap<String, Long> recoveryStartMs =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 构造自适应滞后阈值。
     *
     * @param disasterModeManager 灾害模式管理器（不可为 null）
     */
    public AdaptiveHysteresis(DisasterModeManager disasterModeManager) {
        if (disasterModeManager == null) {
            throw new IllegalArgumentException("disasterModeManager must not be null");
        }
        this.disasterModeManager = disasterModeManager;
        // 初始阈值取决于灾害模式状态
        this.hysteresisMs = disasterModeManager.isDisasterMode()
                ? DISASTER_HYSTERESIS_MS : NORMAL_HYSTERESIS_MS;
    }

    /**
     * 获取当前滞后阈值（ms）。
     * <p>
     * 自动根据灾害模式状态切换：灾害模式 2s，正常模式 5s。
     *
     * @return 当前滞后阈值（ms）
     */
    public long getHysteresisMs() {
        boolean disaster = disasterModeManager.isDisasterMode();
        long expected = disaster ? DISASTER_HYSTERESIS_MS : NORMAL_HYSTERESIS_MS;
        if (hysteresisMs != expected) {
            hysteresisMs = expected;
        }
        return hysteresisMs;
    }

    /**
     * 判断是否满足降级条件（低层链路持续可用达阈值）。
     * <p>
     * 若低层链路恢复时间已达滞后阈值，则允许降级；否则维持当前高层路径。
     *
     * @param layerName   低层链路名称（如 "L1", "L2"）
     * @param nowMs       当前仿真时钟（ms）
     * @return true 若满足降级条件（可降级到低层）
     */
    public boolean canDowngrade(String layerName, long nowMs) {
        long threshold = getHysteresisMs();
        Long startMs = recoveryStartMs.get(layerName);
        if (startMs == null) {
            // 首次记录恢复起始时间
            recoveryStartMs.putIfAbsent(layerName, nowMs);
            return false;
        }
        return (nowMs - startMs) >= threshold;
    }

    /**
     * 记录低层链路恢复起始时间。
     *
     * @param layerName 低层链路名称
     * @param nowMs     当前仿真时钟（ms）
     */
    public void markRecoveryStart(String layerName, long nowMs) {
        recoveryStartMs.put(layerName, nowMs);
    }

    /**
     * 清除指定层的恢复记录（降级完成后调用）。
     *
     * @param layerName 低层链路名称
     */
    public void clearRecovery(String layerName) {
        recoveryStartMs.remove(layerName);
    }

    /**
     * 清除所有恢复记录。
     */
    public void clearAllRecovery() {
        recoveryStartMs.clear();
    }

    /**
     * 当前是否处于灾害模式。
     *
     * @return true 若灾害模式激活
     */
    public boolean isDisasterMode() {
        return disasterModeManager.isDisasterMode();
    }

    @Override
    public String toString() {
        return "AdaptiveHysteresis{hysteresis=" + getHysteresisMs() + "ms"
                + ", disaster=" + isDisasterMode() + "}";
    }
}
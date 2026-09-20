package io.aerofleet.cloud.autodispatch;

/**
 * 自动出警配置（P0-1 安防报警→无人机自动出警闭环）。
 * <p>
 * 描述自动出警模块的运行时配置：是否启用自动触发、选机的最小电量阈值、
 * 最大派遣距离（米）、默认派遣无人机数量、悬停侦察高度与时长。
 * <p>
 * 字段使用 volatile 以便在 {@code ConcurrentHashMap} 等并发结构中安全共享，
 * 配置可在运行时通过 {@code PUT /api/autodispatch/config} 端点更新。
 *
 * @see AutoDispatchService
 * @see AutoDispatchController
 */
public class AutoDispatchConfig {

    /** 是否启用自动出警（报警事件触发时自动派遣无人机）。 */
    private volatile boolean enabled;
    /** 选机的最小电量百分比阈值（低于此值的无人机不参与选机）。 */
    private volatile int minBatteryPct;
    /** 最大派遣距离（米），超过此距离的无人机不参与选机。 */
    private volatile int maxDispatchDistanceM;
    /** 默认派遣无人机数量（单次出警同时派遣的无人机数）。 */
    private volatile int defaultDroneCount;
    /** 悬停侦察高度（米）。 */
    private volatile int hoverAltitudeM;
    /** 悬停侦察时长（秒）。 */
    private volatile int hoverDurationSec;

    /**
     * 使用默认值构造配置：
     * enabled=false, minBatteryPct=30, maxDispatchDistanceM=10000,
     * defaultDroneCount=1, hoverAltitudeM=50, hoverDurationSec=300。
     */
    public AutoDispatchConfig() {
        this.enabled = false;
        this.minBatteryPct = 30;
        this.maxDispatchDistanceM = 10000;
        this.defaultDroneCount = 1;
        this.hoverAltitudeM = 50;
        this.hoverDurationSec = 300;
    }

    public AutoDispatchConfig(boolean enabled, int minBatteryPct, int maxDispatchDistanceM,
                              int defaultDroneCount, int hoverAltitudeM, int hoverDurationSec) {
        this.enabled = enabled;
        this.minBatteryPct = minBatteryPct;
        this.maxDispatchDistanceM = maxDispatchDistanceM;
        this.defaultDroneCount = defaultDroneCount;
        this.hoverAltitudeM = hoverAltitudeM;
        this.hoverDurationSec = hoverDurationSec;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMinBatteryPct() {
        return minBatteryPct;
    }

    public void setMinBatteryPct(int minBatteryPct) {
        this.minBatteryPct = minBatteryPct;
    }

    public int getMaxDispatchDistanceM() {
        return maxDispatchDistanceM;
    }

    public void setMaxDispatchDistanceM(int maxDispatchDistanceM) {
        this.maxDispatchDistanceM = maxDispatchDistanceM;
    }

    public int getDefaultDroneCount() {
        return defaultDroneCount;
    }

    public void setDefaultDroneCount(int defaultDroneCount) {
        this.defaultDroneCount = defaultDroneCount;
    }

    public int getHoverAltitudeM() {
        return hoverAltitudeM;
    }

    public void setHoverAltitudeM(int hoverAltitudeM) {
        this.hoverAltitudeM = hoverAltitudeM;
    }

    public int getHoverDurationSec() {
        return hoverDurationSec;
    }

    public void setHoverDurationSec(int hoverDurationSec) {
        this.hoverDurationSec = hoverDurationSec;
    }

    @Override
    public String toString() {
        return "AutoDispatchConfig{enabled=" + enabled
                + ", minBatteryPct=" + minBatteryPct
                + ", maxDispatchDistanceM=" + maxDispatchDistanceM
                + ", defaultDroneCount=" + defaultDroneCount
                + ", hoverAltitudeM=" + hoverAltitudeM
                + ", hoverDurationSec=" + hoverDurationSec + '}';
    }
}
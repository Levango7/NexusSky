package io.aerofleet.cloud.commadapt;

import org.springframework.stereotype.Component;

/**
 * 多模态通信自适应配置。
 * <p>
 * 描述通信自适应模块的运行时配置：链路切换阈值、故障检测阈值、
 * 检测间隔、是否启用自动切换、最小稳定时间等。
 * <p>
 * 字段使用 volatile 以便在并发结构中安全共享，
 * 配置可在运行时通过 {@code PUT /api/comm-adapt/config} 端点更新。
 *
 * @see CommSituationController
 * @see AdaptiveRouter
 * @see FailoverManager
 */
@Component
public class CommAdaptConfig {

    /** 链路切换阈值：当前链路评分低于此值且备选链路评分高于 80 时建议切换 */
    private volatile int switchThreshold;
    /** 故障检测阈值：连续3次质量评分低于此值判定为故障 */
    private volatile int failoverThreshold;
    /** 质量检测间隔（毫秒） */
    private volatile long detectionIntervalMs;
    /** 是否启用自动切换 */
    private volatile boolean autoSwitchEnabled;
    /** 最小稳定时间（毫秒）：切换后需稳定此时间才允许再次切换 */
    private volatile long minStableTimeMs;

    // P1-fix: 用于保护组合更新的锁对象
    private final Object configLock = new Object();

    /**
     * 使用默认值构造配置：
     * switchThreshold=60, failoverThreshold=40, detectionIntervalMs=5000,
     * autoSwitchEnabled=true, minStableTimeMs=10000。
     */
    public CommAdaptConfig() {
        this.switchThreshold = 60;
        this.failoverThreshold = 40;
        this.detectionIntervalMs = 5000;
        this.autoSwitchEnabled = true;
        this.minStableTimeMs = 10000;
    }

    public CommAdaptConfig(int switchThreshold, int failoverThreshold,
                           long detectionIntervalMs, boolean autoSwitchEnabled,
                           long minStableTimeMs) {
        this.switchThreshold = switchThreshold;
        this.failoverThreshold = failoverThreshold;
        this.detectionIntervalMs = detectionIntervalMs;
        this.autoSwitchEnabled = autoSwitchEnabled;
        this.minStableTimeMs = minStableTimeMs;
    }

    public int getSwitchThreshold() {
        return switchThreshold;
    }

    public void setSwitchThreshold(int switchThreshold) {
        synchronized (configLock) {
            this.switchThreshold = switchThreshold;
        }
    }

    public int getFailoverThreshold() {
        return failoverThreshold;
    }

    public void setFailoverThreshold(int failoverThreshold) {
        synchronized (configLock) {
            this.failoverThreshold = failoverThreshold;
        }
    }

    public long getDetectionIntervalMs() {
        return detectionIntervalMs;
    }

    public void setDetectionIntervalMs(long detectionIntervalMs) {
        synchronized (configLock) {
            this.detectionIntervalMs = detectionIntervalMs;
        }
    }

    public boolean isAutoSwitchEnabled() {
        return autoSwitchEnabled;
    }

    public void setAutoSwitchEnabled(boolean autoSwitchEnabled) {
        synchronized (configLock) {
            this.autoSwitchEnabled = autoSwitchEnabled;
        }
    }

    public long getMinStableTimeMs() {
        return minStableTimeMs;
    }

    public void setMinStableTimeMs(long minStableTimeMs) {
        synchronized (configLock) {
            this.minStableTimeMs = minStableTimeMs;
        }
    }

    /**
     * P1-fix: 原子更新多个配置字段，避免组合写非原子问题。
     * <p>
     * 所有提供的字段在同一 synchronized 块内更新，保证读取者不会看到部分更新的状态。
     *
     * @param switchThreshold    新的链路切换阈值（null 表示不更新）
     * @param failoverThreshold  新的故障检测阈值（null 表示不更新）
     * @param detectionIntervalMs 新的检测间隔（null 表示不更新）
     * @param autoSwitchEnabled  新的自动切换开关（null 表示不更新）
     * @param minStableTimeMs    新的最小稳定时间（null 表示不更新）
     */
    public void updateConfig(Integer switchThreshold, Integer failoverThreshold,
                             Long detectionIntervalMs, Boolean autoSwitchEnabled,
                             Long minStableTimeMs) {
        synchronized (configLock) {
            if (switchThreshold != null) {
                this.switchThreshold = switchThreshold;
            }
            if (failoverThreshold != null) {
                this.failoverThreshold = failoverThreshold;
            }
            if (detectionIntervalMs != null) {
                this.detectionIntervalMs = detectionIntervalMs;
            }
            if (autoSwitchEnabled != null) {
                this.autoSwitchEnabled = autoSwitchEnabled;
            }
            if (minStableTimeMs != null) {
                this.minStableTimeMs = minStableTimeMs;
            }
        }
    }

    @Override
    public String toString() {
        return "CommAdaptConfig{switchThreshold=" + switchThreshold
                + ", failoverThreshold=" + failoverThreshold
                + ", detectionIntervalMs=" + detectionIntervalMs
                + ", autoSwitchEnabled=" + autoSwitchEnabled
                + ", minStableTimeMs=" + minStableTimeMs + '}';
    }
}
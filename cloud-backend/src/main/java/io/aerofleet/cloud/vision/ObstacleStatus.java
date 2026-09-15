package io.aerofleet.cloud.vision;

import io.aerofleet.mavlink.enums.AvoidanceMode;
import io.aerofleet.mavlink.enums.ThreatLevel;

/**
 * 避障状态（M3 感知成像增强，FR-18/DFX 4.2）。
 * <p>
 * 各机实时避障状态，volatile 字段保证 REST 线程与遥测线程并发读写安全。
 */
public class ObstacleStatus {

    public volatile ThreatLevel currentThreat = ThreatLevel.NONE;
    public volatile double nearestDistance = Double.MAX_VALUE;
    public volatile double nearestDirectionDeg = 0;
    public volatile boolean inEmergencyHover = false;
    public volatile long lastReportTime = 0;
    public volatile long lastCommandTime = 0;
    /** 最近一次避障模式（用于解除时恢复）。 */
    public volatile AvoidanceMode lastMode = AvoidanceMode.DISABLED;
    /** 上一次威胁等级（用于降级检测）。 */
    public volatile ThreatLevel previousThreat = ThreatLevel.NONE;

    public ObstacleStatus() {
    }
}
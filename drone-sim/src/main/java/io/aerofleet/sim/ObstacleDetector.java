package io.aerofleet.sim;

import io.aerofleet.mavlink.enums.ObstacleType;
import io.aerofleet.mavlink.enums.ThreatLevel;

/**
 * 障碍物检测器（M3 感知成像增强，FR-13/FR-14）。
 * <p>
 * 基于 {@link DepthSource} 评估障碍物威胁等级（NONE/LOW/MEDIUM/HIGH/CRITICAL），
 * 产出 {@link ObstacleReport}。5Hz 周期运行（由 VirtualDrone tickOnce 分频调用）。
 * <p>
 * 威胁等级映射（FR-13）：
 * <ul>
 *   <li>距离 &lt; 紧急悬停阈值 → CRITICAL</li>
 *   <li>距离 &lt; 安全距离 → HIGH</li>
 *   <li>距离 &lt; 2×安全距离 → MEDIUM</li>
 *   <li>距离 &lt; 4×安全距离 → LOW</li>
 *   <li>否则 → NONE</li>
 * </ul>
 * 无障碍时返回 threat=NONE（FR-13）。
 */
public class ObstacleDetector {

    private final DepthSource depthSource;
    private double safetyDistanceM;
    private double emergencyHoverM;
    /**
     * M4 可选 LiDAR 融合数据源（FR-14）：null 表示不融合，既有 detect 行为不变（DFX 4.5）。
     * 由 {@link #setLidarSource} 注入，fusion=true 时 detect() 从 LiDAR 读取最近距离。
     */
    private LiDARSource lidarSource = null;
    private volatile boolean lidarFusion = false;

    /**
     * @param depthSource     深度数据源
     * @param safetyDistanceM 安全距离阈值（米，≥1）
     * @param emergencyHoverM 紧急悬停阈值（米，&lt; safetyDistanceM）
     */
    public ObstacleDetector(DepthSource depthSource,
                            double safetyDistanceM, double emergencyHoverM) {
        if (depthSource == null) {
            throw new IllegalArgumentException("depthSource must not be null");
        }
        setThresholds(safetyDistanceM, emergencyHoverM);
        this.depthSource = depthSource;
    }

    /**
     * FR-13 威胁等级评估，产出 ObstacleReport。
     * <p>
     * 默认从 DepthSource 读取最近障碍；当 LiDAR 融合启用（fusion=true && lidarSource!=null）
     * 时从 LiDARSource 读取最近距离（FR-14），既有 detect 行为不变（DFX 4.5）。
     * 无障碍时返回 threat=NONE。
     *
     * @return 障碍物报告（距离/方向/威胁/类型）
     */
    public ObstacleReport detect() {
        if (lidarFusion && lidarSource != null) {
            // FR-14 LiDAR 融合：从 LiDARSource 读取最近距离
            double dist = lidarSource.nearestDistance();
            if (dist == Double.MAX_VALUE) {
                return new ObstacleReport(Double.MAX_VALUE, 0,
                        ThreatLevel.NONE, ObstacleType.UNKNOWN);
            }
            ThreatLevel threat = threatOf(dist);
            return new ObstacleReport(dist, 0, threat, ObstacleType.STATIC);
        }
        DepthSource.NearestObstacle nearest = depthSource.nearestObstacle();
        if (nearest.distance() == Double.MAX_VALUE) {
            // 无障碍 → NONE
            return new ObstacleReport(Double.MAX_VALUE, 0,
                    ThreatLevel.NONE, ObstacleType.UNKNOWN);
        }
        ThreatLevel threat = threatOf(nearest.distance());
        return new ObstacleReport(nearest.distance(), nearest.directionDeg(),
                threat, ObstacleType.STATIC);
    }

    /**
     * FR-14 注入 LiDAR 融合数据源。
     *
     * @param lidar  LiDAR 数据源（null 表示关闭融合）
     * @param fusion true=启用 LiDAR 融合替代 DepthSource，false=仅注入不启用
     */
    public void setLidarSource(LiDARSource lidar, boolean fusion) {
        this.lidarSource = lidar;
        this.lidarFusion = lidar != null && fusion;
    }

    /** LiDAR 融合是否启用。 */
    public boolean isLidarFusionEnabled() {
        return lidarFusion;
    }

    /** 距离 → 威胁等级映射（FR-13）。 */
    public ThreatLevel threatOf(double distance) {
        if (distance < emergencyHoverM) return ThreatLevel.CRITICAL;
        if (distance < safetyDistanceM) return ThreatLevel.HIGH;
        if (distance < 2 * safetyDistanceM) return ThreatLevel.MEDIUM;
        if (distance < 4 * safetyDistanceM) return ThreatLevel.LOW;
        return ThreatLevel.NONE;
    }

    /**
     * 配置阈值（FR-13）。
     *
     * @param safetyDistanceM 安全距离（≥1）
     * @param emergencyHoverM 紧停阈值（&lt; safetyDistanceM）
     */
    public void setThresholds(double safetyDistanceM, double emergencyHoverM) {
        if (safetyDistanceM < 1.0) {
            throw new IllegalArgumentException(
                    "safetyDistanceM must be >= 1.0, got " + safetyDistanceM);
        }
        if (emergencyHoverM >= safetyDistanceM) {
            throw new IllegalArgumentException(
                    "emergencyHoverM must be < safetyDistanceM: " + emergencyHoverM
                            + " >= " + safetyDistanceM);
        }
        this.safetyDistanceM = safetyDistanceM;
        this.emergencyHoverM = emergencyHoverM;
    }

    public double safetyDistanceM() {
        return safetyDistanceM;
    }

    public double emergencyHoverM() {
        return emergencyHoverM;
    }

    /** 障碍物报告（距离/方向/威胁/类型）。 */
    public record ObstacleReport(double distance, double directionDeg,
                                 ThreatLevel threat, ObstacleType type) {
    }
}
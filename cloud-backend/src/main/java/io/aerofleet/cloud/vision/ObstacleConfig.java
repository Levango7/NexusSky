package io.aerofleet.cloud.vision;

import io.aerofleet.mavlink.enums.AvoidanceMode;

/**
 * 避障配置 DTO（M3 感知成像增强，FR-15/数据约束 6.1）。
 * <p>
 * 不可变 record，由 REST 端点 POST /api/v1/obstacle/config 入站，
 * {@link ObstacleAvoidanceController} 校验后存储。
 *
 * @param sysid           目标飞机 sysid
 * @param safetyDistanceM 安全距离阈值（米，≥1.0）
 * @param emergencyHoverM 紧急悬停阈值（米，&lt; safetyDistanceM）
 * @param mode            避障模式（WAYPOINT_OFFSET/SPEED_LIMIT/EMERGENCY_HOVER/DISABLED）
 * @param enabled         启用标志
 * @param maxSpeedMs      速度限制上限（m/s，≥0，SPEED_LIMIT 模式用）
 */
public record ObstacleConfig(int sysid, double safetyDistanceM, double emergencyHoverM,
                             AvoidanceMode mode, boolean enabled, double maxSpeedMs) {
    public ObstacleConfig {
        if (safetyDistanceM < 1.0) {
            throw new IllegalArgumentException(
                    "safetyDistanceM must be >= 1.0, got " + safetyDistanceM);
        }
        if (emergencyHoverM >= safetyDistanceM) {
            throw new IllegalArgumentException(
                    "emergencyHoverM must be < safetyDistanceM: " + emergencyHoverM
                            + " >= " + safetyDistanceM);
        }
        if (mode == null) {
            throw new IllegalArgumentException("mode must not be null");
        }
        if (maxSpeedMs < 0) {
            throw new IllegalArgumentException(
                    "maxSpeedMs must be >= 0, got " + maxSpeedMs);
        }
    }
}
package io.aerofleet.sim;

import io.aerofleet.mavlink.enums.ScanMode;

/**
 * 雷达扫描配置（M4 硬件抽象，FR-03/数据约束 6.1）。
 * <p>
 * 紧凑构造校验所有参数在物理范围内。
 *
 * @param sysid        目标飞机 sysid（1-254）
 * @param mode         扫描模式
 * @param azimCenter   方位角中心（0-359°）
 * @param azimWidth    方位角扫描宽度（1-360°）
 * @param elevCenter   俯仰角中心（-90~90°）
 * @param beamWidth    波束宽度（1-30°）
 * @param range        探测距离（10-10000m）
 * @param scanPeriodMs 扫描周期（100-10000ms）
 * @param enabled      雷达启用标志
 */
public record RadarScanConfig(int sysid, ScanMode mode, double azimCenter, double azimWidth,
                              double elevCenter, double beamWidth, double range,
                              int scanPeriodMs, boolean enabled) {
    public RadarScanConfig {
        if (sysid < 1 || sysid > 254) {
            throw new IllegalArgumentException("sysid must be 1-254, got " + sysid);
        }
        if (mode == null) {
            throw new IllegalArgumentException("mode must be non-null");
        }
        if (azimCenter < 0 || azimCenter > 359) {
            throw new IllegalArgumentException("azimCenter must be 0-359, got " + azimCenter);
        }
        if (azimWidth < 1 || azimWidth > 360) {
            throw new IllegalArgumentException("azimWidth must be 1-360, got " + azimWidth);
        }
        if (elevCenter < -90 || elevCenter > 90) {
            throw new IllegalArgumentException("elevCenter must be -90~90, got " + elevCenter);
        }
        if (beamWidth < 1 || beamWidth > 30) {
            throw new IllegalArgumentException("beamWidth must be 1-30, got " + beamWidth);
        }
        if (range < 10 || range > 10000) {
            throw new IllegalArgumentException("range must be 10-10000m, got " + range);
        }
        if (scanPeriodMs < 100 || scanPeriodMs > 10000) {
            throw new IllegalArgumentException(
                    "scanPeriodMs must be 100-10000ms, got " + scanPeriodMs);
        }
    }
}
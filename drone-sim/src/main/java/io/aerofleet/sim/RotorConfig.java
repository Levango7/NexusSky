package io.aerofleet.sim;

/**
 * 旋翼气动配置（M4 硬件抽象，数据约束 6.3）。
 * <p>
 * 紧凑构造校验所有参数在物理范围内。
 *
 * @param sysid      目标飞机 sysid（1-254）
 * @param rotorCount 旋翼数量（1-12）
 * @param diameter   桨叶直径（>0，米，典型 0.1-1.0m）
 * @param pitch      桨距（0-30°）
 * @param maxRpm     转速上限（0-20000 RPM）
 * @param airDensity 空气密度（>0，kg/m³，海平面 1.225）
 */
public record RotorConfig(int sysid, int rotorCount, double diameter, double pitch,
                          double maxRpm, double airDensity) {
    public RotorConfig {
        if (sysid < 1 || sysid > 254) {
            throw new IllegalArgumentException("sysid must be 1-254, got " + sysid);
        }
        if (rotorCount < 1 || rotorCount > 12) {
            throw new IllegalArgumentException("rotorCount must be 1-12, got " + rotorCount);
        }
        if (diameter <= 0) {
            throw new IllegalArgumentException("diameter must be > 0, got " + diameter);
        }
        if (pitch < 0 || pitch > 30) {
            throw new IllegalArgumentException("pitch must be 0-30, got " + pitch);
        }
        if (maxRpm < 0 || maxRpm > 20000) {
            throw new IllegalArgumentException("maxRpm must be 0-20000, got " + maxRpm);
        }
        if (airDensity <= 0) {
            throw new IllegalArgumentException("airDensity must be > 0, got " + airDensity);
        }
    }
}
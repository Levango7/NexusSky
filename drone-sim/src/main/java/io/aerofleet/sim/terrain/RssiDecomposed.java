package io.aerofleet.sim.terrain;

/**
 * 增强 RSSI 估算分量分解（FR-13）。
 * <p>
 * 增强 RSSI 估算结果可分解为各衰减分量（自由空间 / 地形遮挡 / 植被 / 建筑 / 多径），
 * 便于调试与可视化（§5.4.2 衰减分量可分解）。
 *
 * @param rssiDbm            最终 RSSI (dBm)
 * @param freeSpaceLossDb    自由空间损耗 (dB)
 * @param terrainShadowLossDb 地形遮挡损耗 (dB)
 * @param vegetationLossDb   植被衰减 (dB)
 * @param buildingLossDb     建筑穿透损耗 (dB)
 * @param multipathLossDb    多径损耗 (dB)
 * @param los                是否视距
 */
public record RssiDecomposed(
        double rssiDbm,
        double freeSpaceLossDb,
        double terrainShadowLossDb,
        double vegetationLossDb,
        double buildingLossDb,
        double multipathLossDb,
        boolean los
) {
}
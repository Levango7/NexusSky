package io.aerofleet.sim.celltower;

import io.aerofleet.sim.RadioEnvironment;
import io.aerofleet.sim.TerrainModel;

/**
 * 基站载荷工厂（M6 移动基站载荷抽象，FR-CT-01 / FR-CT-06）。
 * <p>
 * 根据制式创建对应的 {@link CellTowerPayload} 实现类。
 * 非法制式抛出 {@link IllegalArgumentException}（FR-CT-06）。
 */
public final class CellTowerFactory {

    private CellTowerFactory() {
    }

    /**
     * 创建基站载荷实例。
     *
     * @param cellType              制式
     * @param sysid                 无人机 sysid
     * @param txPowerDbm            发射功率 dBm
     * @param maxTerminals          最大并发终端数
     * @param frequencyChannel      频段编号
     * @param radio                 RF 环境（可为 null）
     * @param terrain               地形模型（可为 null）
     * @param signalThresholdDbm    接入信号阈值 dBm
     * @param loadBalanceThreshold  负载均衡阈值
     * @param heartbeatTimeoutMs    心跳超时 ms
     * @return 基站载荷实例
     * @throws IllegalArgumentException 非法制式（FR-CT-06）
     */
    public static CellTowerPayload create(CellType cellType, int sysid,
                                          int txPowerDbm, int maxTerminals, int frequencyChannel,
                                          RadioEnvironment radio, TerrainModel terrain,
                                          double signalThresholdDbm, double loadBalanceThreshold,
                                          long heartbeatTimeoutMs) {
        return switch (cellType) {
            case LTE_MICRO_CELL -> new LteCellTower(sysid, txPowerDbm, maxTerminals, frequencyChannel,
                    radio, terrain, signalThresholdDbm, loadBalanceThreshold, heartbeatTimeoutMs);
            case WIFI_MESH -> new WifiCellTower(sysid, txPowerDbm, maxTerminals, frequencyChannel,
                    radio, terrain, signalThresholdDbm, loadBalanceThreshold, heartbeatTimeoutMs);
            case LORA -> new LoRaCellTower(sysid, txPowerDbm, maxTerminals, frequencyChannel,
                    radio, terrain, signalThresholdDbm, loadBalanceThreshold, heartbeatTimeoutMs);
        };
    }

    /**
     * 创建默认参数的基站载荷实例。
     */
    public static CellTowerPayload createDefault(CellType cellType, int sysid,
                                                 RadioEnvironment radio, TerrainModel terrain) {
        return switch (cellType) {
            case LTE_MICRO_CELL -> new LteCellTower(sysid, radio, terrain);
            case WIFI_MESH -> new WifiCellTower(sysid, radio, terrain);
            case LORA -> new LoRaCellTower(sysid, radio, terrain);
        };
    }
}
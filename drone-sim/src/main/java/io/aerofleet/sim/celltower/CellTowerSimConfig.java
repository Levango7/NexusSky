package io.aerofleet.sim.celltower;

/**
 * M6 移动基站载荷抽象配置（FR-01，DFX 4.4 配置可追溯）。
 * <p>
 * 封装所有 celltower 相关参数，由 {@code SimConfig.parse} 收集后构造。
 * 默认值对应 {@code --celltower} 开关关闭时的安全缺省。
 */
public final class CellTowerSimConfig {

    /** 基站载荷启用开关（--celltower）。false 时 VirtualDrone.cellTower=null，既有行为不变（DFX 4.5）。 */
    public final boolean enabled;
    /** 初始制式（--cell-type，默认 LTE_MICRO_CELL）。 */
    public final CellType cellType;
    /** 发射功率 dBm（--cell-tx-power，默认 20）。 */
    public final int txPowerDbm;
    /** 最大并发终端数（--cell-max-terminals，默认 200）。 */
    public final int maxTerminals;
    /** 频段编号（--cell-freq，默认 1）。 */
    public final int frequencyChannel;
    /** 接入信号阈值 dBm（--cell-signal-threshold，默认 -80）。 */
    public final double signalThresholdDbm;
    /** 漫游切换阈值 dBm（--cell-handover-threshold，默认 -80）。 */
    public final double handoverThresholdDbm;
    /** 负载均衡阈值（--cell-load-balance-threshold，默认 0.8）。 */
    public final double loadBalanceThreshold;
    /** 心跳超时 ms（--cell-heartbeat-timeout-ms，默认 30000）。 */
    public final long heartbeatTimeoutMs;

    public CellTowerSimConfig(boolean enabled, CellType cellType, int txPowerDbm,
                              int maxTerminals, int frequencyChannel,
                              double signalThresholdDbm, double handoverThresholdDbm,
                              double loadBalanceThreshold, long heartbeatTimeoutMs) {
        this.enabled = enabled;
        this.cellType = cellType;
        this.txPowerDbm = txPowerDbm;
        this.maxTerminals = maxTerminals;
        this.frequencyChannel = frequencyChannel;
        this.signalThresholdDbm = signalThresholdDbm;
        this.handoverThresholdDbm = handoverThresholdDbm;
        this.loadBalanceThreshold = loadBalanceThreshold;
        this.heartbeatTimeoutMs = heartbeatTimeoutMs;
    }

    /** 默认配置（disabled）。 */
    public static CellTowerSimConfig defaults() {
        return new CellTowerSimConfig(false, CellType.LTE_MICRO_CELL, 20, 200, 1,
                -80.0, -80.0, 0.8, 30_000L);
    }

    /**
     * 从已收集的参数构造。
     */
    public static CellTowerSimConfig of(boolean enabled, String cellTypeStr, int txPowerDbm,
                                        int maxTerminals, int frequencyChannel,
                                        double signalThresholdDbm, double handoverThresholdDbm,
                                        double loadBalanceThreshold, long heartbeatTimeoutMs) {
        CellType cellType = parseCellType(cellTypeStr);
        return new CellTowerSimConfig(enabled, cellType, txPowerDbm, maxTerminals, frequencyChannel,
                signalThresholdDbm, handoverThresholdDbm, loadBalanceThreshold, heartbeatTimeoutMs);
    }

    /** 解析制式字符串。 */
    public static CellType parseCellType(String s) {
        if (s == null || s.isBlank()) {
            return CellType.LTE_MICRO_CELL;
        }
        return switch (s.toUpperCase()) {
            case "LTE", "LTE_MICRO_CELL" -> CellType.LTE_MICRO_CELL;
            case "WIFI", "WIFI_MESH" -> CellType.WIFI_MESH;
            case "LORA" -> CellType.LORA;
            default -> CellType.LTE_MICRO_CELL;
        };
    }

    @Override
    public String toString() {
        return "CellTowerSimConfig{enabled=" + enabled
                + ", cellType=" + cellType
                + ", txPower=" + txPowerDbm + "dBm"
                + ", maxTerminals=" + maxTerminals
                + ", freq=" + frequencyChannel
                + ", signalThreshold=" + signalThresholdDbm
                + ", loadBalance=" + loadBalanceThreshold
                + ", heartbeatTimeout=" + heartbeatTimeoutMs + "ms}";
    }
}
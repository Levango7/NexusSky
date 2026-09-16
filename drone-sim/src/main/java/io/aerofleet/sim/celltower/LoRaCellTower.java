package io.aerofleet.sim.celltower;

import io.aerofleet.sim.RadioEnvironment;
import io.aerofleet.sim.TerrainModel;

/**
 * LoRa 基站载荷实现（M6 移动基站载荷抽象，FR-CT-04）。
 * <p>
 * 参数区间：
 * <ul>
 *   <li>覆盖半径 [2000, 15000] 米</li>
 *   <li>最大并发用户数 ≥ 1000</li>
 *   <li>吞吐量上限 [0.1, 5] kbps（低功耗广域）</li>
 * </ul>
 */
public final class LoRaCellTower extends AbstractCellTower {

    public static final double MIN_RADIUS_M = 2000;
    public static final double MAX_RADIUS_M = 15000;
    public static final int MIN_MAX_TERMINALS = 1000;
    /** LoRa 最大并发用户数上限（实际无硬上限，此处取大值表示 ≥1000）。 */
    public static final int MAX_MAX_TERMINALS = 100_000;
    /** LoRa 吞吐量上限中值（kbps）。 */
    public static final double THROUGHPUT_KBPS = 2.5;

    public LoRaCellTower(int sysid, int txPowerDbm, int maxTerminals, int frequencyChannel,
                         RadioEnvironment radio, TerrainModel terrain,
                         double signalThresholdDbm, double loadBalanceThreshold,
                         long heartbeatTimeoutMs) {
        super(sysid, CellType.LORA, txPowerDbm, maxTerminals, frequencyChannel,
                radio, terrain, signalThresholdDbm, loadBalanceThreshold, heartbeatTimeoutMs);
    }

    /** 默认参数构造（maxTerminals=5000, txPower=20dBm, freq=0）。 */
    public LoRaCellTower(int sysid, RadioEnvironment radio, TerrainModel terrain) {
        this(sysid, 20, 5000, 0, radio, terrain, -80.0, 0.8, 30_000L);
    }

    @Override
    double minRadiusM() {
        return MIN_RADIUS_M;
    }

    @Override
    double maxRadiusM() {
        return MAX_RADIUS_M;
    }

    @Override
    int minMaxTerminals() {
        return MIN_MAX_TERMINALS;
    }

    @Override
    int maxMaxTerminals() {
        return MAX_MAX_TERMINALS;
    }

    @Override
    public double throughputUpperBound() {
        return THROUGHPUT_KBPS;
    }
}
package io.aerofleet.sim.celltower;

import io.aerofleet.sim.RadioEnvironment;
import io.aerofleet.sim.TerrainModel;

/**
 * LTE micro-cell 基站载荷实现（M6 移动基站载荷抽象，FR-CT-02）。
 * <p>
 * 参数区间：
 * <ul>
 *   <li>覆盖半径 [1000, 5000] 米</li>
 *   <li>最大并发用户数 [100, 500]</li>
 *   <li>吞吐量上限 [10, 50] Mbps</li>
 * </ul>
 */
public final class LteCellTower extends AbstractCellTower {

    /** LTE 覆盖半径下限（米）。 */
    public static final double MIN_RADIUS_M = 1000;
    /** LTE 覆盖半径上限（米）。 */
    public static final double MAX_RADIUS_M = 5000;
    /** LTE 最大并发用户数下限。 */
    public static final int MIN_MAX_TERMINALS = 100;
    /** LTE 最大并发用户数上限。 */
    public static final int MAX_MAX_TERMINALS = 500;
    /** LTE 吞吐量上限中值（Mbps）。 */
    public static final double THROUGHPUT_MBPS = 30.0;

    public LteCellTower(int sysid, int txPowerDbm, int maxTerminals, int frequencyChannel,
                        RadioEnvironment radio, TerrainModel terrain,
                        double signalThresholdDbm, double loadBalanceThreshold,
                        long heartbeatTimeoutMs) {
        super(sysid, CellType.LTE_MICRO_CELL, txPowerDbm, maxTerminals, frequencyChannel,
                radio, terrain, signalThresholdDbm, loadBalanceThreshold, heartbeatTimeoutMs);
    }

    /** 默认参数构造（maxTerminals=200, txPower=20dBm, freq=1）。 */
    public LteCellTower(int sysid, RadioEnvironment radio, TerrainModel terrain) {
        this(sysid, 20, 200, 1, radio, terrain, -80.0, 0.8, 30_000L);
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
        return THROUGHPUT_MBPS;
    }
}
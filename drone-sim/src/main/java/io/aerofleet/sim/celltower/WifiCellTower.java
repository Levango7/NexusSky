package io.aerofleet.sim.celltower;

import io.aerofleet.sim.RadioEnvironment;
import io.aerofleet.sim.TerrainModel;

/**
 * WiFi mesh 基站载荷实现（M6 移动基站载荷抽象，FR-CT-03）。
 * <p>
 * 参数区间：
 * <ul>
 *   <li>覆盖半径 [300, 1000] 米</li>
 *   <li>最大并发用户数 [50, 200]</li>
 *   <li>吞吐量上限 [5, 20] Mbps</li>
 * </ul>
 */
public final class WifiCellTower extends AbstractCellTower {

    public static final double MIN_RADIUS_M = 300;
    public static final double MAX_RADIUS_M = 1000;
    public static final int MIN_MAX_TERMINALS = 50;
    public static final int MAX_MAX_TERMINALS = 200;
    public static final double THROUGHPUT_MBPS = 12.0;

    public WifiCellTower(int sysid, int txPowerDbm, int maxTerminals, int frequencyChannel,
                         RadioEnvironment radio, TerrainModel terrain,
                         double signalThresholdDbm, double loadBalanceThreshold,
                         long heartbeatTimeoutMs) {
        super(sysid, CellType.WIFI_MESH, txPowerDbm, maxTerminals, frequencyChannel,
                radio, terrain, signalThresholdDbm, loadBalanceThreshold, heartbeatTimeoutMs);
    }

    /** 默认参数构造（maxTerminals=100, txPower=20dBm, freq=6）。 */
    public WifiCellTower(int sysid, RadioEnvironment radio, TerrainModel terrain) {
        this(sysid, 20, 100, 6, radio, terrain, -80.0, 0.8, 30_000L);
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
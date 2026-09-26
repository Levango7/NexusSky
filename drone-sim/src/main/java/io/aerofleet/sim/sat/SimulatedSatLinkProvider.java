package io.aerofleet.sim.sat;

import io.aerofleet.sim.SimLog;

import java.util.Random;

/**
 * 仿真卫星链路提供者（FR-20 真实卫星接入预留）。
 * <p>
 * 模拟天通/铱星/星链三种体制的链路特性，用于开发测试阶段替代真实卫星接入。
 * <p>
 * 各体制仿真参数：
 * <ul>
 *   <li>天通（S 波段）：带宽 9.6kbps，延迟 500ms，仰角范围 10-85°</li>
 *   <li>铱星（L 波段）：带宽 2.4kbps，延迟 1500ms，仰角范围 10-85°</li>
 *   <li>星链（Ku/Ka 波段）：带宽 100Mbps，延迟 20ms，仰角范围 25-90°</li>
 * </ul>
 * <p>
 * 仿真波动：带宽/延迟/仰角在标称值附近随机波动 ±10%，模拟真实链路动态变化。
 */
public final class SimulatedSatLinkProvider implements SatLinkProvider {

    /** 各体制标称参数。 */
    private static final class NominalParams {
        final long bandwidthBps;
        final long delayMs;
        final double minElevationDeg;
        final double maxElevationDeg;

        NominalParams(long bandwidthBps, long delayMs,
                      double minElevationDeg, double maxElevationDeg) {
            this.bandwidthBps = bandwidthBps;
            this.delayMs = delayMs;
            this.minElevationDeg = minElevationDeg;
            this.maxElevationDeg = maxElevationDeg;
        }
    }

    /** 天通标称参数。 */
    private static final NominalParams TIANTONG_PARAMS = new NominalParams(
            9_600L, 500L, 10.0, 85.0);
    /** 铱星标称参数。 */
    private static final NominalParams IRIDIUM_PARAMS = new NominalParams(
            2_400L, 1_500L, 10.0, 85.0);
    /** 星链标称参数。 */
    private static final NominalParams STARLINK_PARAMS = new NominalParams(
            100_000_000L, 20L, 25.0, 90.0);

    /** 波动幅度（±10%）。 */
    private static final double FLUCTUATION_RATIO = 0.10;

    // ===== 实例状态 =====

    private final SatType satType;
    private final String satId;
    private final NominalParams params;
    private final Random random;

    private volatile boolean connected = false;
    private volatile double elevationDeg;
    private volatile long bandwidthBps;
    private volatile long delayMs;

    /**
     * 构造仿真卫星链路提供者。
     *
     * @param satType  卫星体制类型
     * @param satId    卫星标识
     * @param seed     随机种子（用于可复现测试）
     */
    public SimulatedSatLinkProvider(SatType satType, String satId, long seed) {
        this.satType = satType;
        this.satId = satId;
        this.random = new Random(seed);
        this.params = switch (satType) {
            case TIANTONG -> TIANTONG_PARAMS;
            case IRIDIUM -> IRIDIUM_PARAMS;
            case STARLINK -> STARLINK_PARAMS;
        };
        // 初始状态：未连接时使用标称值
        this.elevationDeg = (params.minElevationDeg + params.maxElevationDeg) / 2.0;
        this.bandwidthBps = params.bandwidthBps;
        this.delayMs = params.delayMs;
    }

    /**
     * 构造仿真卫星链路提供者（默认随机种子）。
     *
     * @param satType 卫星体制类型
     * @param satId   卫星标识
     */
    public SimulatedSatLinkProvider(SatType satType, String satId) {
        this(satType, satId, System.nanoTime());
    }

    /**
     * 创建天通仿真链路提供者。
     *
     * @param satId 卫星标识
     * @return 天通仿真 provider
     */
    public static SimulatedSatLinkProvider tiantong(String satId) {
        return new SimulatedSatLinkProvider(SatType.TIANTONG, satId);
    }

    /**
     * 创建铱星仿真链路提供者。
     *
     * @param satId 卫星标识
     * @return 铱星仿真 provider
     */
    public static SimulatedSatLinkProvider iridium(String satId) {
        return new SimulatedSatLinkProvider(SatType.IRIDIUM, satId);
    }

    /**
     * 创建星链仿真链路提供者。
     *
     * @param satId 卫星标识
     * @return 星链仿真 provider
     */
    public static SimulatedSatLinkProvider starlink(String satId) {
        return new SimulatedSatLinkProvider(SatType.STARLINK, satId);
    }

    @Override
    public boolean connect() {
        if (connected) {
            SimLog.warn("[sat-link] already connected: " + satId);
            return true;
        }
        connected = true;
        // 连接时刷新仿真参数
        refreshSimulation();
        SimLog.info("[sat-link] connected: " + satType.label() + " " + satId
                + " bw=" + bandwidthBps + "bps delay=" + delayMs + "ms"
                + " elev=" + String.format("%.1f", elevationDeg) + "°");
        return true;
    }

    @Override
    public void disconnect() {
        if (!connected) return;
        connected = false;
        SimLog.info("[sat-link] disconnected: " + satType.label() + " " + satId);
    }

    @Override
    public double getLinkQuality() {
        if (!connected) return 0.0;
        // 链路质量 = 仰角归一化 × (1 - 延迟波动影响)
        double elevNorm = (elevationDeg - params.minElevationDeg)
                / (params.maxElevationDeg - params.minElevationDeg);
        elevNorm = Math.max(0.0, Math.min(1.0, elevNorm));
        double delayFactor = 1.0 - (delayMs - params.delayMs) / (double) params.delayMs * FLUCTUATION_RATIO;
        delayFactor = Math.max(0.0, Math.min(1.0, delayFactor));
        return Math.max(0.0, Math.min(1.0, elevNorm * delayFactor));
    }

    @Override
    public long getBandwidth() {
        return connected ? bandwidthBps : 0L;
    }

    @Override
    public long getDelay() {
        return connected ? delayMs : Long.MAX_VALUE;
    }

    @Override
    public double getElevation() {
        return connected ? elevationDeg : 0.0;
    }

    @Override
    public SatType getSatType() {
        return satType;
    }

    @Override
    public String getSatId() {
        return satId;
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    /**
     * 刷新仿真参数（模拟链路动态波动）。
     * <p>
     * 在标称值附近 ±10% 随机波动，仰角在有效范围内随机变化。
     */
    public void refreshSimulation() {
        // 带宽波动 ±10%
        double bwFluct = 1.0 + (random.nextDouble() - 0.5) * 2 * FLUCTUATION_RATIO;
        bandwidthBps = Math.max(1L, (long) (params.bandwidthBps * bwFluct));

        // 延迟波动 ±10%
        double delayFluct = 1.0 + (random.nextDouble() - 0.5) * 2 * FLUCTUATION_RATIO;
        delayMs = Math.max(1L, (long) (params.delayMs * delayFluct));

        // 仰角在有效范围内随机变化
        double elevRange = params.maxElevationDeg - params.minElevationDeg;
        elevationDeg = params.minElevationDeg + random.nextDouble() * elevRange;
    }

    /**
     * 获取标称带宽（无波动）。
     *
     * @return 标称带宽（bps）
     */
    public long nominalBandwidth() {
        return params.bandwidthBps;
    }

    /**
     * 获取标称延迟（无波动）。
     *
     * @return 标称延迟（ms）
     */
    public long nominalDelay() {
        return params.delayMs;
    }

    @Override
    public String toString() {
        return "SimulatedSatLinkProvider{" + satType.label()
                + " " + satId + ", connected=" + connected
                + ", bw=" + bandwidthBps + "bps"
                + ", delay=" + delayMs + "ms"
                + ", elev=" + String.format("%.1f", elevationDeg) + "°}";
    }
}
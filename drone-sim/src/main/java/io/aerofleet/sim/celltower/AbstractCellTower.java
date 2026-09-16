package io.aerofleet.sim.celltower;

import io.aerofleet.mavlink.messages.CellTowerConfigMsg;
import io.aerofleet.sim.RadioEnvironment;
import io.aerofleet.sim.SimLog;
import io.aerofleet.sim.TerrainModel;

/**
 * 基站载荷抽象基类（M6 移动基站载荷抽象）。
 * <p>
 * 封装三制式共用的覆盖模型、容量模型、终端注册表、接入校验链逻辑，
 * 子类只需提供制式特定的参数区间（覆盖半径 / 最大并发 / 吞吐上限）。
 * <p>
 * 覆盖模型复用 {@link RadioEnvironment} + {@link TerrainModel} 通过组合方式接入（FR-NFR-COMP-02）。
 */
abstract class AbstractCellTower implements CellTowerPayload {

    protected final int sysid;
    protected CellType cellType;
    protected int txPowerDbm;
    protected int maxTerminalsValue;
    protected int frequencyChannel;

    /** 当前无人机位姿（覆盖中心随位姿动态更新，FR-COV-05）。 */
    protected volatile int currentLatE7;
    protected volatile int currentLonE7;
    protected volatile int currentAltMm;
    protected volatile boolean positionValid = false;

    /** 当前覆盖区域快照。 */
    protected volatile CoverageArea currentCoverage;

    /** 终端注册表。 */
    protected final TerminalRegistry registry;

    /** RF 环境（复用 Baseline RadioEnvironment，FR-COV-02）。 */
    protected final RadioEnvironment radio;
    protected final TerrainModel terrain;

    /** 接入信号阈值 dBm（FR-TERM-02）。 */
    protected final double signalThresholdDbm;
    /** 负载均衡阈值（FR-CAP-03，默认 0.8）。 */
    protected final double loadBalanceThreshold;
    /** 心跳超时阈值 ms（FR-TERM-04，默认 30s）。 */
    protected final long heartbeatTimeoutMs;

    AbstractCellTower(int sysid, CellType cellType, int txPowerDbm, int maxTerminals,
                      int frequencyChannel,
                      RadioEnvironment radio, TerrainModel terrain,
                      double signalThresholdDbm, double loadBalanceThreshold,
                      long heartbeatTimeoutMs) {
        this.sysid = sysid;
        this.cellType = cellType;
        this.txPowerDbm = txPowerDbm;
        this.maxTerminalsValue = maxTerminals;
        this.frequencyChannel = frequencyChannel;
        this.radio = radio;
        this.terrain = terrain;
        this.signalThresholdDbm = signalThresholdDbm;
        this.loadBalanceThreshold = loadBalanceThreshold;
        this.heartbeatTimeoutMs = heartbeatTimeoutMs;
        this.registry = new TerminalRegistry();
    }

    // ===== 子类提供的制式参数 =====

    /** 制式覆盖半径下限（米）。 */
    abstract double minRadiusM();
    /** 制式覆盖半径上限（米）。 */
    abstract double maxRadiusM();
    /** 制式最大并发用户数下限。 */
    abstract int minMaxTerminals();
    /** 制式最大并发用户数上限（LoRa 返回 Integer.MAX_VALUE 表示 ≥1000）。 */
    abstract int maxMaxTerminals();
    /** 制式吞吐量上限（Mbps 或 kbps）。 */
    public abstract double throughputUpperBound();

    // ===== CellTowerPayload 接口实现 =====

    @Override
    public int sysid() {
        return sysid;
    }

    @Override
    public CellType cellType() {
        return cellType;
    }

    @Override
    public CoverageArea currentCoverage() {
        return currentCoverage;
    }

    @Override
    public int connectedTerminals() {
        return registry.size();
    }

    @Override
    public int maxTerminals() {
        return maxTerminalsValue;
    }

    @Override
    public double capacityUtilization() {
        if (maxTerminalsValue <= 0) {
            return 0.0;
        }
        return Math.min(1.0, (double) registry.size() / maxTerminalsValue);
    }

    /**
     * 处理终端注册（FR-TERM-02 全校验链）。
     */
    @Override
    public AccessResult handleRegister(GroundTerminal terminal) {
        // 1. 唯一性校验（FR-TERM-07）
        if (registry.contains(terminal.terminalId)) {
            return AccessResult.alreadyRegistered();
        }
        // 2. GPS 校验
        if (!GroundTerminal.isGpsValid(terminal.gpsLatE7, terminal.gpsLonE7)) {
            return AccessResult.fail(AccessResult.TERMINAL_GPS_INVALID,
                    "terminal gps invalid: lat=" + terminal.gpsLatE7 + " lon=" + terminal.gpsLonE7);
        }
        // 3. terminalType 校验（构造期已校验，此处冗余保护）
        if (terminal.terminalType == null) {
            return AccessResult.fail(AccessResult.TERMINAL_TYPE_UNSUPPORTED, "terminalType is null");
        }
        // 4. 覆盖区域校验
        if (currentCoverage == null) {
            return AccessResult.fail(AccessResult.OUT_OF_COVERAGE, "coverage not initialized");
        }
        if (!currentCoverage.contains(terminal.gpsLatE7, terminal.gpsLonE7)) {
            return AccessResult.fail(AccessResult.OUT_OF_COVERAGE,
                    "terminal outside coverage area");
        }
        // 5. 信号强度校验（简化：基于距离衰减）
        double rssi = estimateRssi(terminal.gpsLatE7, terminal.gpsLonE7);
        if (rssi < signalThresholdDbm) {
            return AccessResult.fail(AccessResult.SIGNAL_TOO_WEAK,
                    "rssi=" + rssi + " < threshold=" + signalThresholdDbm);
        }
        // 6. 容量校验（FR-CAP-02 满载拒绝 / FR-CAP-03 负载均衡）
        double util = capacityUtilization();
        if (util >= 1.0) {
            return AccessResult.fail(AccessResult.CELL_CAPACITY_FULL,
                    "capacity full: " + registry.size() + "/" + maxTerminalsValue);
        }
        if (util > loadBalanceThreshold) {
            // 负载均衡触发：建议引导至邻近低载机（此处无邻近机信息，返回 redirect=0 降级为接受）
            // 真实场景由 HandoverManager 查询重叠区邻近机负载
            SimLog.info("celltower load balance triggered: sysid=" + sysid
                    + " util=" + util + " accepting terminal " + terminal.terminalId);
        }
        // 7. 全部通过 → 注册
        terminal.connect(sysid);
        boolean added = registry.add(terminal);
        if (!added) {
            return AccessResult.fail(AccessResult.REGISTRY_FULL, "registry at capacity");
        }
        SimLog.info("celltower terminal registered: sysid=" + sysid
                + " terminal=" + terminal.terminalId + " type=" + terminal.terminalType);
        return AccessResult.ok();
    }

    /** 处理终端注销（FR-CAP-06）。 */
    @Override
    public boolean handleLogout(int terminalId) {
        boolean removed = registry.remove(terminalId);
        if (removed) {
            SimLog.info("celltower terminal logout: sysid=" + sysid + " terminal=" + terminalId);
        }
        return removed;
    }

    /**
     * 应用配置变更（FR-CT-05 制式切换）。
     */
    @Override
    public void applyConfig(CellTowerConfigMsg config) {
        // 校验目标 sysid
        if (config.sysid != sysid) {
            SimLog.warn("celltower config ignored: target sysid=" + config.sysid + " != self=" + sysid);
            return;
        }
        // 校验制式
        CellType newType;
        try {
            newType = CellType.fromOrdinal(config.cellType);
        } catch (IllegalArgumentException e) {
            SimLog.warn("celltower config rejected: " + e.getMessage());
            return;
        }
        // 校验参数区间
        if (config.txPowerDbm < -10 || config.txPowerDbm > 30) {
            SimLog.warn("celltower config rejected: txPowerDbm=" + config.txPowerDbm + " out of [-10,30]");
            return;
        }
        if (config.maxTerminals < minMaxTerminals() || config.maxTerminals > maxMaxTerminals()) {
            SimLog.warn("celltower config rejected: maxTerminals=" + config.maxTerminals
                    + " out of [" + minMaxTerminals() + "," + maxMaxTerminals() + "]");
            return;
        }
        // 制式切换：重置覆盖模型与容量模型，清除已接入终端（FR-CT-05）
        if (newType != cellType) {
            SimLog.info("celltower type switch: " + cellType + " -> " + newType
                    + " clearing " + registry.size() + " terminals");
            registry.clear();
            cellType = newType;
        }
        txPowerDbm = config.txPowerDbm;
        maxTerminalsValue = config.maxTerminals;
        frequencyChannel = config.frequencyChannel;
        // 重算覆盖
        recomputeCoverage();
    }

    /**
     * 周期驱动（FR-NFR-PERF-04）。
     * <p>
     * 心跳超时检测 + 覆盖重算。
     */
    @Override
    public void tick(long nowMs) {
        // 心跳超时检测（FR-TERM-04）
        for (int expiredId : registry.findExpired(nowMs, heartbeatTimeoutMs)) {
            handleLogout(expiredId);
            SimLog.info("celltower terminal expired: sysid=" + sysid + " terminal=" + expiredId);
        }
        // 覆盖重算（FR-COV-05 动态更新）
        if (positionValid) {
            recomputeCoverage();
        }
    }

    /**
     * 更新无人机位姿（FR-COV-05）。
     */
    @Override
    public void updatePosition(int latE7, int lonE7, int altMm) {
        // 位姿有效性校验（FR-COV-02 异常场景 2）
        if (latE7 == 0 && lonE7 == 0) {
            SimLog.warn("celltower POSITION_STALE: invalid position lat=0 lon=0");
            return;
        }
        this.currentLatE7 = latE7;
        this.currentLonE7 = lonE7;
        this.currentAltMm = altMm;
        this.positionValid = true;
        recomputeCoverage();
    }

    // ===== 覆盖模型 =====

    /**
     * 重算覆盖区域（FR-COV-01~06）。
     * <p>
     * 覆盖半径 = baseRadius(cellType, txPower) * heightFactor(alt) * terrainFactor(occluded)
     */
    protected void recomputeCoverage() {
        if (!positionValid) {
            return;
        }
        double baseRadius = baseRadiusM();
        double heightFactor = 1.0 + (currentAltMm / 1000.0) / 1000.0; // 高度每升 1km 半径翻倍
        // 地形遮挡判定（复用 TerrainModel.elevationAt，FR-COV-02 / FR-NFR-COMP-02）
        boolean occluded = false;
        if (terrain != null) {
            double altM = currentAltMm / 1000.0;
            try {
                // 采样覆盖区域边缘几个点判定地形高程是否超过无人机高度
                double centerNorth = 0; // 无人机投影点为本地坐标原点
                double centerEast = 0;
                double sampleRadius = baseRadius * 0.8;
                for (int i = 0; i < 8; i++) {
                    double angle = 2 * Math.PI * i / 8;
                    double sN = centerNorth + sampleRadius * Math.cos(angle);
                    double sE = centerEast + sampleRadius * Math.sin(angle);
                    double elev = terrain.elevationAt(sN, sE);
                    if (elev > altM - 3.0) { // Fresnel-ish clearance margin
                        occluded = true;
                        break;
                    }
                }
            } catch (Exception e) {
                // 地形数据缺失降级（FR-COV-02 异常场景 1）
                SimLog.warn("celltower TERRAIN_DEGRADED: " + e.getMessage());
                occluded = false;
            }
        }
        double terrainFactor = occluded ? 0.7 : 1.0;
        double radius = baseRadius * heightFactor * terrainFactor;
        // 限制在制式区间内
        radius = Math.max(minRadiusM(), Math.min(maxRadiusM(), radius));
        currentCoverage = CoverageArea.circle(currentLatE7, currentLonE7, radius, occluded);
    }

    /** 基础覆盖半径（受 txPower 调整）。 */
    protected double baseRadiusM() {
        double mid = (minRadiusM() + maxRadiusM()) / 2;
        return mid + (txPowerDbm - 20) * 50;
    }

    /** 估算终端信号强度（简化：基于距离的自由空间路径损耗）。 */
    protected double estimateRssi(int latE7, int lonE7) {
        if (currentCoverage == null) {
            return -200;
        }
        double dLat = (latE7 - currentLatE7) / 1e7 * 111_320.0;
        double dLon = (lonE7 - currentLonE7) / 1e7 * 111_320.0
                * Math.cos(Math.toRadians(currentLatE7 / 1e7));
        double dist = Math.sqrt(dLat * dLat + dLon * dLon);
        if (dist < 1) {
            dist = 1;
        }
        // FSPL(dB) = 20*log10(d) + 20*log10(f_MHz) - 27.55，取 2400 MHz
        double fspl = 20 * Math.log10(dist) + 20 * Math.log10(2400) - 27.55;
        return txPowerDbm - fspl;
    }
}
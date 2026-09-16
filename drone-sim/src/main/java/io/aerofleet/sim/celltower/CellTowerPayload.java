package io.aerofleet.sim.celltower;

import io.aerofleet.mavlink.messages.CellTowerConfigMsg;

/**
 * 基站载荷统一接口（M6 移动基站载荷抽象，FR-CT-01 / §7.4）。
 * <p>
 * 三种制式（LTE micro-cell / WiFi mesh / LoRa）各自实现该接口并暴露制式特定的覆盖与容量参数。
 * <p>
 * <b>扩展点预留</b>：接口设计预留"真实基站数据源"替换点，未来接真硬件时替换实现类，
 * 接口与上层逻辑不变（延续项目"软件协议层先行、数据源可替换"哲学）。
 */
public interface CellTowerPayload {

    /** 所属无人机 sysid。 */
    int sysid();

    /** 返回制式。 */
    CellType cellType();

    /** 返回当前覆盖区域。 */
    CoverageArea currentCoverage();

    /** 已接入终端数。 */
    int connectedTerminals();

    /** 最大并发终端数。 */
    int maxTerminals();

    /** 容量利用率 = connectedTerminals / maxTerminals ∈ [0.0, 1.0]（FR-CAP-01）。 */
    double capacityUtilization();

    /** 吞吐量上限（Mbps 或 kbps，由制式决定）。 */
    double throughputUpperBound();

    /** 处理终端注册（FR-TERM-02 校验链）。 */
    AccessResult handleRegister(GroundTerminal terminal);

    /** 处理终端注销（FR-CAP-06）。 */
    boolean handleLogout(int terminalId);

    /** 应用配置变更（FR-CT-05 制式切换）。 */
    void applyConfig(CellTowerConfigMsg config);

    /** 周期驱动（由 VirtualDrone tick 调用，FR-NFR-PERF-04）。 */
    void tick(long nowMs);

    /** 更新无人机位姿（覆盖中心随位姿动态更新，FR-COV-05）。 */
    void updatePosition(int latE7, int lonE7, int altMm);
}
package io.aerofleet.cloud.api.dto;

import java.util.Collections;
import java.util.List;

/**
 * 基站拓扑快照 DTO（M6 移动基站载荷抽象，FR-NFR-OBS-02）。
 * <p>
 * 承载单架无人机基站的状态快照：sysid + 制式 + 覆盖中心 + 覆盖半径 + 已接入终端数 + 容量利用率。
 */
public final class CellTowerSnapshot {

    public final int sysid;
    public final int cellType;
    public final int centerLatE7;
    public final int centerLonE7;
    public final int coverageRadiusM;
    public final int connectedTerminals;
    public final int capacityUtilization;  // 0-100
    public final long lastUpdateMs;
    public final List<TerminalInfo> terminals;

    public CellTowerSnapshot(int sysid, int cellType, int centerLatE7, int centerLonE7,
                             int coverageRadiusM, int connectedTerminals, int capacityUtilization,
                             long lastUpdateMs, List<TerminalInfo> terminals) {
        this.sysid = sysid;
        this.cellType = cellType;
        this.centerLatE7 = centerLatE7;
        this.centerLonE7 = centerLonE7;
        this.coverageRadiusM = coverageRadiusM;
        this.connectedTerminals = connectedTerminals;
        this.capacityUtilization = capacityUtilization;
        this.lastUpdateMs = lastUpdateMs;
        this.terminals = terminals == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(terminals);
    }

    /** 终端信息子 DTO。 */
    public record TerminalInfo(int terminalId, int terminalType, int connectedSysid) {}

    @Override
    public String toString() {
        return "CellTowerSnapshot{sysid=" + sysid
                + ", cellType=" + cellType
                + ", radius=" + coverageRadiusM + "m"
                + ", connected=" + connectedTerminals
                + ", util=" + capacityUtilization + "%}";
    }
}
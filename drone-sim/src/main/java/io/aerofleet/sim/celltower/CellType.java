package io.aerofleet.sim.celltower;

/**
 * 基站制式枚举（M6 移动基站载荷抽象，FR-CT-01）。
 * <p>
 * 三种制式：LTE micro-cell / WiFi mesh / LoRa，各自覆盖半径与容量参数区间见
 * {@link LteCellTower} / {@link WifiCellTower} / {@link LoRaCellTower}。
 * <p>
 * 对应 MAVLink {@code CellTowerStatus.cellType} / {@code CellTowerConfig.cellType} 字段的序数值。
 */
public enum CellType {
    /** LTE micro-cell：覆盖 [1000,5000]m / 容量 [100,500] / 吞吐 [10,50] Mbps。 */
    LTE_MICRO_CELL(0),
    /** WiFi mesh：覆盖 [300,1000]m / 容量 [50,200] / 吞吐 [5,20] Mbps。 */
    WIFI_MESH(1),
    /** LoRa：覆盖 [2000,15000]m / 容量 ≥1000 / 吞吐 [0.1,5] kbps。 */
    LORA(2);

    private final int ordinalCode;

    CellType(int ordinalCode) {
        this.ordinalCode = ordinalCode;
    }

    /** MAVLink 线上序数值。 */
    public int ordinalCode() {
        return ordinalCode;
    }

    /** 从 MAVLink 序数值还原枚举；非法值抛出 {@link IllegalArgumentException}（FR-CT-06）。 */
    public static CellType fromOrdinal(int code) {
        return switch (code) {
            case 0 -> LTE_MICRO_CELL;
            case 1 -> WIFI_MESH;
            case 2 -> LORA;
            default -> throw new IllegalArgumentException(
                    "CELL_TYPE_INVALID: unknown cellType ordinal " + code);
        };
    }
}
package io.aerofleet.sim.celltower;

/**
 * 漫游切换原因枚举（M6 移动基站载荷抽象，FR-HO-04）。
 * <p>
 * 对应 MAVLink {@code CellHandover.handoverReason} 字段的序数值。
 */
public enum HandoverReason {
    /** 信号强度低于漫游切换阈值。 */
    SIGNAL_WEAK(0),
    /** 负载均衡引导。 */
    LOAD_BALANCE(1),
    /** 基站关闭（制式切换 / 下线）。 */
    CELL_SHUTDOWN(2);

    private final int ordinalCode;

    HandoverReason(int ordinalCode) {
        this.ordinalCode = ordinalCode;
    }

    public int ordinalCode() {
        return ordinalCode;
    }

    /** 从 MAVLink 序数值还原；非法值默认返回 SIGNAL_WEAK（容忍解码）。 */
    public static HandoverReason fromOrdinal(int code) {
        return switch (code) {
            case 0 -> SIGNAL_WEAK;
            case 1 -> LOAD_BALANCE;
            case 2 -> CELL_SHUTDOWN;
            default -> SIGNAL_WEAK;
        };
    }
}
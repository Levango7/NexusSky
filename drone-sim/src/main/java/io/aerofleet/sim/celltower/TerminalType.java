package io.aerofleet.sim.celltower;

/**
 * 地面终端类型枚举（M6 移动基站载荷抽象，FR-TERM-01）。
 * <p>
 * 对应 MAVLink {@code GroundTerminalRegister.terminalType} 字段的序数值。
 */
public enum TerminalType {
    /** 手机。 */
    PHONE(0),
    /** 对讲机。 */
    WALKIE_TALKIE(1),
    /** 传感器。 */
    SENSOR(2);

    private final int ordinalCode;

    TerminalType(int ordinalCode) {
        this.ordinalCode = ordinalCode;
    }

    public int ordinalCode() {
        return ordinalCode;
    }

    /** 从 MAVLink 序数值还原；非法值抛出 {@link IllegalArgumentException}。 */
    public static TerminalType fromOrdinal(int code) {
        return switch (code) {
            case 0 -> PHONE;
            case 1 -> WALKIE_TALKIE;
            case 2 -> SENSOR;
            default -> throw new IllegalArgumentException(
                    "TERMINAL_TYPE_UNSUPPORTED: unknown terminalType ordinal " + code);
        };
    }
}
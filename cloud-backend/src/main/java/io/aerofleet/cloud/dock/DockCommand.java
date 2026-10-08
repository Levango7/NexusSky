package io.aerofleet.cloud.dock;

/** 机巢命令（DJI Cloud API services 通道 method 语义的子集）。 */
public enum DockCommand {
    OPEN_DOOR("door_open"),
    CLOSE_DOOR("door_close"),
    SWAP_BATTERY("battery_swap"),
    REBOOT("reboot");

    private final String method;

    DockCommand(String method) {
        this.method = method;
    }

    /** DJI Cloud API services 通道的 method 字符串。 */
    public String method() {
        return method;
    }

    public static DockCommand fromMethod(String method) {
        for (DockCommand c : values()) {
            if (c.method.equals(method)) {
                return c;
            }
        }
        throw new IllegalArgumentException("unknown dock command method: " + method);
    }
}

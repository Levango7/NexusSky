package io.aerofleet.cloud.dock;

/**
 * 机巢命令（services 通道 method 语义的子集）。
 *
 * <p><b>口径说明（2026-11-07）</b>：本枚举的 method 字符串是 <b>NexusSky 侧语义</b>，
 * 不是 DJI 官方 method 名——官方文档（{@code dji-sdk/Cloud-API-Doc} v1.18）中不存在
 * {@code door_open}/{@code door_close}/{@code battery_swap}/{@code reboot}。
 * 官方下行有 {@code services}（服务调用）与 {@code property/set}（属性设置）两条通道，
 * 且机巢侧可写属性仅 {@code silent_mode}/{@code air_transfer_enable} 等；
 * {@code mode_code}/{@code putter_state}/{@code drone_battery_maintenance_info}
 * 均 {@code accessMode=r} 只读——真实 DJI 机巢的开盖/换电是设备自主动作。
 * 因此本枚举当前对应的是<b>模拟对端</b>语义；接真实机巢前需按官方属性/服务做映射层，
 * 不把模拟语义直接投到真机。
 */
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

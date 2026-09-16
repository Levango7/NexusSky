package io.aerofleet.sim.celltower;

/**
 * 地面终端领域对象（M6 移动基站载荷抽象，FR-TERM-01 / §6.2）。
 * <p>
 * 接入无人机基站进行应急通讯的地面设备，包括手机、对讲机、传感器等。
 * 携带终端 ID、类型、GPS 坐标、接入时间、最后心跳时间、移动速度。可变（lastSeenMs 随心跳更新）。
 */
public final class GroundTerminal {

    public final int terminalId;
    public final TerminalType terminalType;
    public final int gpsLatE7;
    public final int gpsLonE7;
    /** 当前接入的无人机 sysid；未接入时为 0。 */
    public int connectedSysid;
    /** 注册时间戳（Unix ms）。 */
    public final long registeredAtMs;
    /** 最后心跳时间戳（Unix ms）。 */
    public long lastSeenMs;
    /** 移动速度（m/s）：步行 ≈ 1.4，车辆 ≈ 15。 */
    public final double moveSpeedMps;

    public GroundTerminal(int terminalId, TerminalType terminalType,
                          int gpsLatE7, int gpsLonE7,
                          long registeredAtMs, long lastSeenMs, double moveSpeedMps) {
        this.terminalId = terminalId;
        this.terminalType = terminalType;
        this.gpsLatE7 = gpsLatE7;
        this.gpsLonE7 = gpsLonE7;
        this.connectedSysid = 0;
        this.registeredAtMs = registeredAtMs;
        this.lastSeenMs = lastSeenMs;
        this.moveSpeedMps = moveSpeedMps;
    }

    /** 从 MAVLink GroundTerminalRegisterMsg 构造（FR-TERM-06）。 */
    public static GroundTerminal fromRegister(int terminalId, TerminalType type,
                                              int gpsLatE7, int gpsLonE7, long nowMs) {
        double speed = switch (type) {
            case PHONE -> 1.4;           // 步行
            case WALKIE_TALKIE -> 1.4;   // 步行
            case SENSOR -> 0.0;          // 静止
        };
        return new GroundTerminal(terminalId, type, gpsLatE7, gpsLonE7, nowMs, nowMs, speed);
    }

    /** GPS 坐标合法性校验（FR-TERM-02 异常场景 1）。 */
    public static boolean isGpsValid(int latE7, int lonE7) {
        if (latE7 == 0 && lonE7 == 0) {
            return false; // 未初始化
        }
        return latE7 >= -90_0000_000 && latE7 <= 90_0000_000
                && lonE7 >= -180_0000_000 && lonE7 <= 180_0000_000;
    }

    /** 更新最后心跳时间。 */
    public void updateLastSeen(long nowMs) {
        this.lastSeenMs = nowMs;
    }

    /** 标记接入某无人机。 */
    public void connect(int sysid) {
        this.connectedSysid = sysid;
    }

    /** 标记注销。 */
    public void disconnect() {
        this.connectedSysid = 0;
    }

    @Override
    public String toString() {
        return "GroundTerminal{id=" + terminalId
                + ", type=" + terminalType
                + ", lat=" + gpsLatE7 + ", lon=" + gpsLonE7
                + ", connected=" + connectedSysid
                + ", lastSeen=" + lastSeenMs + "}";
    }
}
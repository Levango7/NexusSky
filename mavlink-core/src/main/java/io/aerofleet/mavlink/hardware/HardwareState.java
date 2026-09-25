package io.aerofleet.mavlink.hardware;

/**
 * 硬件状态数据类：飞控当前状态的不可变快照。
 * <p>
 * 所有字段通过 {@link Builder} 设置，创建后不可修改，保证多线程读取安全。
 */
public final class HardwareState {

    private final boolean connected;
    private final boolean armed;
    private final String mode;
    private final double lat;          // 纬度（度）
    private final double lon;          // 经度（度）
    private final double alt;          // 高度（米，相对起飞点）
    private final double battery;      // 电池剩余百分比（0-100）
    private final double heading;      // 航向角（度，0-360）
    private final double airspeed;     // 空速（m/s）
    private final double groundspeed;  // 地速（m/s）

    private HardwareState(Builder b) {
        this.connected = b.connected;
        this.armed = b.armed;
        this.mode = b.mode;
        this.lat = b.lat;
        this.lon = b.lon;
        this.alt = b.alt;
        this.battery = b.battery;
        this.heading = b.heading;
        this.airspeed = b.airspeed;
        this.groundspeed = b.groundspeed;
    }

    public boolean isConnected() { return connected; }
    public boolean isArmed() { return armed; }
    public String getMode() { return mode; }
    public double getLat() { return lat; }
    public double getLon() { return lon; }
    public double getAlt() { return alt; }
    public double getBattery() { return battery; }
    public double getHeading() { return heading; }
    public double getAirspeed() { return airspeed; }
    public double getGroundspeed() { return groundspeed; }

    /**
     * 创建一个断开连接的默认状态。
     *
     * @return 未连接的默认状态
     */
    public static HardwareState disconnected() {
        return new Builder()
                .connected(false)
                .armed(false)
                .mode("UNKNOWN")
                .build();
    }

    @Override
    public String toString() {
        return "HardwareState{connected=" + connected
                + ", armed=" + armed
                + ", mode=" + mode
                + ", lat=" + lat
                + ", lon=" + lon
                + ", alt=" + alt
                + ", battery=" + battery
                + ", heading=" + heading
                + ", airspeed=" + airspeed
                + ", groundspeed=" + groundspeed
                + "}";
    }

    /**
     * 状态构建器：逐步设置状态字段后构建不可变状态对象。
     */
    public static final class Builder {
        private boolean connected = false;
        private boolean armed = false;
        private String mode = "UNKNOWN";
        private double lat = 0.0;
        private double lon = 0.0;
        private double alt = 0.0;
        private double battery = 0.0;
        private double heading = 0.0;
        private double airspeed = 0.0;
        private double groundspeed = 0.0;

        public Builder connected(boolean v) { this.connected = v; return this; }
        public Builder armed(boolean v) { this.armed = v; return this; }
        public Builder mode(String v) { this.mode = v; return this; }
        public Builder lat(double v) { this.lat = v; return this; }
        public Builder lon(double v) { this.lon = v; return this; }
        public Builder alt(double v) { this.alt = v; return this; }
        public Builder battery(double v) { this.battery = v; return this; }
        public Builder heading(double v) { this.heading = v; return this; }
        public Builder airspeed(double v) { this.airspeed = v; return this; }
        public Builder groundspeed(double v) { this.groundspeed = v; return this; }

        public HardwareState build() {
            return new HardwareState(this);
        }
    }
}
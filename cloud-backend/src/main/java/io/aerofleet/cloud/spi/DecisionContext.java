package io.aerofleet.cloud.spi;

/**
 * AI 决策上下文，封装决策评估所需的全部态势信息。
 * <p>
 * 由决策引擎在调用 {@link DecisionStrategyPlugin#evaluate(DecisionContext)} 前构建，
 * 包含无人机状态、环境信息、任务上下文等。
 */
public class DecisionContext {

    /** 无人机系统 ID */
    private final int sysid;

    /** 当前位置纬度（度） */
    private final double latitude;

    /** 当前位置经度（度） */
    private final double longitude;

    /** 当前高度（米） */
    private final double altitude;

    /** 当前速度（米/秒） */
    private final double speed;

    /** 当前航向角（度，0-360） */
    private final double heading;

    /** 电池剩余百分比（0-100） */
    private final double batteryPercent;

    /** 当前飞行模式 */
    private final String flightMode;

    /** 是否检测到障碍物 */
    private final boolean obstacleDetected;

    /** 是否处于紧急状态 */
    private final boolean emergency;

    /** 附加属性键值对（可扩展） */
    private final java.util.Map<String, Object> extraProperties;

    public DecisionContext(int sysid, double latitude, double longitude, double altitude,
                           double speed, double heading, double batteryPercent,
                           String flightMode, boolean obstacleDetected, boolean emergency,
                           java.util.Map<String, Object> extraProperties) {
        this.sysid = sysid;
        this.latitude = latitude;
        this.longitude = longitude;
        this.altitude = altitude;
        this.speed = speed;
        this.heading = heading;
        this.batteryPercent = batteryPercent;
        this.flightMode = flightMode;
        this.obstacleDetected = obstacleDetected;
        this.emergency = emergency;
        this.extraProperties = extraProperties != null ? extraProperties : java.util.Collections.emptyMap();
    }

    public int getSysid() {
        return sysid;
    }

    public double getLatitude() {
        return latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    public double getAltitude() {
        return altitude;
    }

    public double getSpeed() {
        return speed;
    }

    public double getHeading() {
        return heading;
    }

    public double getBatteryPercent() {
        return batteryPercent;
    }

    public String getFlightMode() {
        return flightMode;
    }

    public boolean isObstacleDetected() {
        return obstacleDetected;
    }

    public boolean isEmergency() {
        return emergency;
    }

    public java.util.Map<String, Object> getExtraProperties() {
        return extraProperties;
    }

    /**
     * 获取附加属性。
     *
     * @param key 属性键
     * @return 属性值；不存在时返回 null
     */
    public Object getExtra(String key) {
        return extraProperties.get(key);
    }
}
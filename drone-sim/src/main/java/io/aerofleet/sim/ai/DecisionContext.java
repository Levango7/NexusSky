package io.aerofleet.sim.ai;

/**
 * M11 决策上下文：封装无人机态势输入，供决策引擎、权重调整器、决策树统一消费。
 * <p>
 * 采用不可变对象设计，避免在多策略评估过程中被误改（参考经验：volatile 复合写非原子陷阱，
 * 这里通过不可变快照从源头规避并发写问题）。
 */
public final class DecisionContext {

    /** 电量百分比 0-100 */
    public final double battery;
    /** 链路是否健康 */
    public final boolean linkHealthy;
    /** GPS 是否健康 */
    public final boolean gpsHealthy;
    /** 当前高度 m */
    public final double alt;
    /** 距 Home 距离 m */
    public final double distanceToHome;
    /** 是否检测到障碍物 */
    public final boolean obstacleDetected;
    /** 障碍物距离 m（无障碍物时为 {@link Double#MAX_VALUE}） */
    public final double obstacleDistance;
    /** 风速 m/s */
    public final double windSpeed;
    /** 任务紧急度 0-1（1 表示最紧急） */
    public final double missionUrgency;

    public DecisionContext(double battery, boolean linkHealthy, boolean gpsHealthy,
                           double alt, double distanceToHome, boolean obstacleDetected,
                           double obstacleDistance, double windSpeed, double missionUrgency) {
        this.battery = battery;
        this.linkHealthy = linkHealthy;
        this.gpsHealthy = gpsHealthy;
        this.alt = alt;
        this.distanceToHome = distanceToHome;
        this.obstacleDetected = obstacleDetected;
        this.obstacleDistance = obstacleDistance;
        this.windSpeed = windSpeed;
        this.missionUrgency = missionUrgency;
    }

    /**
     * 从旧版参数构造上下文（无障碍物距离、无任务紧急度）。
     * 旧接口未提供障碍物距离，默认填充一个不触发"近距离避障加权"的安全值 50m，
     * 以保证既有测试行为不变。
     */
    public static DecisionContext legacy(double battery, boolean linkHealthy, boolean gpsHealthy,
                                         double alt, double distanceToHome, boolean obstacleDetected,
                                         double windSpeed) {
        return new DecisionContext(battery, linkHealthy, gpsHealthy, alt, distanceToHome,
                obstacleDetected, obstacleDetected ? 50.0 : Double.MAX_VALUE, windSpeed, 0.0);
    }

    /** 是否存在紧急情况（电量危急 / 链路丢失 / GPS 降级） */
    public boolean hasEmergency() {
        return battery < 15.0 || !linkHealthy || !gpsHealthy;
    }

    /** 电量是否低于危急阈值（15%） */
    public boolean batteryCritical() {
        return battery < 15.0;
    }

    /** 障碍物是否处于近距离危险区（&lt;10m） */
    public boolean obstacleCloseRange() {
        return obstacleDetected && obstacleDistance < 10.0;
    }

    @Override
    public String toString() {
        return "DecisionContext{battery=" + battery + ", link=" + linkHealthy + ", gps=" + gpsHealthy
                + ", alt=" + alt + ", distHome=" + distanceToHome + ", obstacle=" + obstacleDetected
                + ", obstacleDist=" + (obstacleDistance == Double.MAX_VALUE ? "INF" : obstacleDistance)
                + ", wind=" + windSpeed + ", urgency=" + missionUrgency + "}";
    }
}
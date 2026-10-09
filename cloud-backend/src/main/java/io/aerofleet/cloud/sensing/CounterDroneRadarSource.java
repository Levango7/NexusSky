package io.aerofleet.cloud.sensing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * E2 反制雷达侦测源（模拟）——**只接侦测、不做反制**。
 * <p>
 * 立场声明（spec §4）：本仓交付侦测数据接入与态势呈现，**无任何干扰/打击控制面**——
 * 反制器材属受管制装备，接入真实雷达厂商 SDK 属生产阶段（实现本 SPI 即可）。
 * <p>
 * 模拟行为：基点周边 2 条非合作航迹——一条巡飞（环行）、一条逼近基点
 * （intruder，用于触发近域告警）；纯几何确定性生成（tick 序号驱动，可测）。
 */
@Component
@ConditionalOnProperty(name = "aerofleet.sensing.counterdrone.enabled",
        havingValue = "true", matchIfMissing = true)
public class CounterDroneRadarSource implements AerialSensingSource {

    private static final Logger log = LoggerFactory.getLogger(CounterDroneRadarSource.class);

    private static final String SOURCE_ID = "RADAR-SIM-01";
    /** 巡飞航迹环行半径 m。 */
    static final double PATROL_RADIUS_M = 2500;
    /** 逼近航迹起点距基点 m（每 tick 递减逼近）。 */
    static final double INTRUDER_START_M = 4000;
    static final double INTRUDER_STEP_M = 250;

    private final double baseLat;
    private final double baseLon;

    /** tick 序号（poll 次数）——确定性演示数据的关键。 */
    private volatile long tick = 0;

    public CounterDroneRadarSource(
            @Value("${aerofleet.sensing.base-lat:22.5907}") double baseLat,
            @Value("${aerofleet.sensing.base-lon:113.9345}") double baseLon) {
        this.baseLat = baseLat;
        this.baseLon = baseLon;
        log.info("CounterDroneRadarSource (模拟) 就绪: base=({}, {}) — 只接侦测，不做反制",
                baseLat, baseLon);
    }

    @Override
    public String sourceType() {
        return SensingTrack.TYPE_COUNTER_DRONE_RADAR;
    }

    @Override
    public String sourceId() {
        return SOURCE_ID;
    }

    @Override
    public List<SensingTrack> poll() {
        long now = System.currentTimeMillis();
        long t = likelyTick();
        List<SensingTrack> out = new ArrayList<>(2);

        // 1) 巡飞航迹：环行
        double angle = Math.toRadians((t * 15) % 360);
        double[] patrol = offset(baseLat, baseLon, PATROL_RADIUS_M, angle);
        out.add(new SensingTrack(
                SOURCE_ID + "-PATROL-1", sourceType(), SOURCE_ID,
                patrol[0], patrol[1], 120, 18, (t * 15) % 360, 0.9, "DRONE",
                firstSeenOf(SOURCE_ID + "-PATROL-1", now), now));

        // 2) 逼近航迹：向基点直线逼近（intruder——触发近域告警的形态）
        double dist = Math.max(300, INTRUDER_START_M - t * INTRUDER_STEP_M);
        double[] intruder = offset(baseLat, baseLon, dist, 45);   // 东北方向逼近
        out.add(new SensingTrack(
                SOURCE_ID + "-INTRUDER-1", sourceType(), SOURCE_ID,
                intruder[0], intruder[1], 90 + (t % 30), 25, 225, 0.75, "DRONE",
                firstSeenOf(SOURCE_ID + "-INTRUDER-1", now), now));
        return out;
    }

    private final java.util.Map<String, Long> firstSeen = new java.util.concurrent.ConcurrentHashMap<>();

    private long firstSeenOf(String trackId, long now) {
        return firstSeen.computeIfAbsent(trackId, k -> now);
    }

    private long likelyTick() {
        return tick++;
    }

    /** 以 (lat,lon) 为基点向 bearing 方向偏移 distM 的坐标。 */
    static double[] offset(double lat, double lon, double distM, double bearingDeg) {
        double dLat = distM * Math.cos(Math.toRadians(bearingDeg)) / 111_320.0;
        double dLon = distM * Math.sin(Math.toRadians(bearingDeg))
                / (111_320.0 * Math.cos(Math.toRadians(lat)));
        return new double[]{lat + dLat, lon + dLon};
    }
}

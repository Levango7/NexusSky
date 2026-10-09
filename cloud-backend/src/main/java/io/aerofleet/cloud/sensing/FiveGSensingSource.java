package io.aerofleet.cloud.sensing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * E4 5G-A 通感（ISAC）感知数据源（模拟）——**接入点预留**。
 * <p>
 * 立场声明（spec §4）：3GPP 通感标准仍在演进（R19），真实通感基站无统一开放 API——
 * 本仓交付 SPI + 模拟源 + 接入说明；真实网元接入（专用 MEC/网管北向接口）
 * 属生产阶段，实现本 SPI 即可接入聚合层。
 * <p>
 * 模拟行为：基点东南 3.2km 一条向西北移动的航迹；确定性 tick 驱动。
 * 基站侧附加语义（rangeM/径向速度）在 5G-A 真实数据里由感知测量量提供——
 * 本仓 PoC 用几何反算（见 {@link #rangeMOf}），字段口径留作接入映射样板。
 */
@Component
@ConditionalOnProperty(name = "aerofleet.sensing.fiveg.enabled",
        havingValue = "true", matchIfMissing = true)
public class FiveGSensingSource implements AerialSensingSource {

    private static final Logger log = LoggerFactory.getLogger(FiveGSensingSource.class);

    private static final String SOURCE_ID = "GNB-SIM-A1";
    /** 通感覆盖半径（真实 5G-A 单站感知覆盖通常数百米级——PoC 取 km 级演示）。 */
    static final double COVERAGE_RADIUS_M = 5000;

    private final double baseLat;
    private final double baseLon;
    private volatile long tick = 0;

    public FiveGSensingSource(
            @Value("${aerofleet.sensing.base-lat:22.5907}") double baseLat,
            @Value("${aerofleet.sensing.base-lon:113.9345}") double baseLon) {
        this.baseLat = baseLat;
        this.baseLon = baseLon;
        log.info("FiveGSensingSource (模拟) 就绪: gNB=({}, {}) 覆盖 {}m — 接入点预留（真实网元属生产阶段）",
                baseLat, baseLon, (int) COVERAGE_RADIUS_M);
    }

    @Override
    public String sourceType() {
        return SensingTrack.TYPE_FIVE_G_SENSING;
    }

    @Override
    public String sourceId() {
        return SOURCE_ID;
    }

    @Override
    public List<SensingTrack> poll() {
        long t = tick++;
        long now = System.currentTimeMillis();
        // 从东南 3.2km 向基站方向移动（每 tick 逼近 300m，进入覆盖后可穿出）
        double dist = Math.abs(3200 - (t * 300) % 6400);
        double[] p = CounterDroneRadarSource.offset(baseLat, baseLon, dist, 135);
        // classification=BIRD：PoC 演示"非无人机误报"形态（通感识别能力有限——诚实呈现）
        String classification = (t % 4 == 0) ? "BIRD" : "UNKNOWN";
        SensingTrack track = new SensingTrack(
                SOURCE_ID + "-T1", sourceType(), SOURCE_ID,
                p[0], p[1], 60 + (t % 20), 12, 315, 0.6, classification,
                now, now);
        return List.of(track);
    }

    /**
     * 基站侧距离语义（rangeM）：真实通感数据由测量量直接给出——
     * 本仓 PoC 由坐标反算，作接入映射样板（新源接入时此字段换成网元原始值）。
     */
    public double rangeMOf(SensingTrack track) {
        return UspaceGeometry.haversineM(baseLat, baseLon, track.lat(), track.lon());
    }

    /** 几何工具（本包内共用，避免跨包耦合）。 */
    static final class UspaceGeometry {
        static double haversineM(double lat1, double lon1, double lat2, double lon2) {
            double dLat = Math.toRadians(lat2 - lat1);
            double dLon = Math.toRadians(lon2 - lon1);
            double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                    + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                    * Math.sin(dLon / 2) * Math.sin(dLon / 2);
            return 6_371_000.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        }
    }
}

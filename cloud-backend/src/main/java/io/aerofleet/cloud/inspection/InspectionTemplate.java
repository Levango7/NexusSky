package io.aerofleet.cloud.inspection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 巡检模板（行业巡检参数化配置）。
 * <p>
 * 模板封装了特定行业的巡检参数（航高、速度、重叠率、相机角度、航点兴趣点等），
 * 可被多个巡检任务复用。预设模板由 {@link InspectionPresetFactory} 创建。
 */
public final class InspectionTemplate {

    /** 兴趣点（巡检重点关注的地理坐标）。 */
    public static final class POI {
        public final double lat;
        public final double lon;
        public final String label;

        public POI(double lat, double lon, String label) {
            this.lat = lat;
            this.lon = lon;
            this.label = label;
        }
    }

    private final String id;
    private final String name;
    private final IndustryType industryType;
    private final String description;
    private final RouteType routeType;
    private final double altitudeM;
    private final double speedMps;
    private final double overlapPct;
    private final double cameraAngleDeg;
    private final List<POI> pointsOfInterest;
    private final double totalDistanceKm;
    private final double estimatedDurationMin;
    private final Instant createdAt;

    public InspectionTemplate(String id, String name, IndustryType industryType,
                              String description, RouteType routeType,
                              double altitudeM, double speedMps, double overlapPct,
                              double cameraAngleDeg, List<POI> pointsOfInterest,
                              double totalDistanceKm, double estimatedDurationMin,
                              Instant createdAt) {
        this.id = id;
        this.name = name;
        this.industryType = industryType;
        this.description = description;
        this.routeType = routeType;
        this.altitudeM = altitudeM;
        this.speedMps = speedMps;
        this.overlapPct = overlapPct;
        this.cameraAngleDeg = cameraAngleDeg;
        this.pointsOfInterest = pointsOfInterest == null
                ? Collections.emptyList()
                : new ArrayList<>(pointsOfInterest);
        this.totalDistanceKm = totalDistanceKm;
        this.estimatedDurationMin = estimatedDurationMin;
        this.createdAt = createdAt;
    }

    public String id() { return id; }
    public String name() { return name; }
    public IndustryType industryType() { return industryType; }
    public String description() { return description; }
    public RouteType routeType() { return routeType; }
    public double altitudeM() { return altitudeM; }
    public double speedMps() { return speedMps; }
    public double overlapPct() { return overlapPct; }
    public double cameraAngleDeg() { return cameraAngleDeg; }
    public List<POI> pointsOfInterest() { return Collections.unmodifiableList(pointsOfInterest); }
    public double totalDistanceKm() { return totalDistanceKm; }
    public double estimatedDurationMin() { return estimatedDurationMin; }
    public Instant createdAt() { return createdAt; }
}
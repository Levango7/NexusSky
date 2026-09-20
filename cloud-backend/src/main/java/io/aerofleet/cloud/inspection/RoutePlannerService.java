package io.aerofleet.cloud.inspection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 航线规划服务。
 * <p>
 * 根据巡检模板的 {@link RouteType} 和巡检区域生成航点序列：
 * <ul>
 *   <li>{@link RouteType#LINEAR_GRID} 沿线网格扫描 — 按区域生成平行航线，按 overlap 重叠</li>
 *   <li>{@link RouteType#CROSS_GRID} 交叉网格 — 两组平行线交叉覆盖</li>
 *   <li>{@link RouteType#ORBIT} 环绕飞行 — 围绕中心点生成圆形航点</li>
 *   <li>{@link RouteType#PERIMETER} 周界巡逻 — 沿区域边界生成航点</li>
 * </ul>
 * <p>
 * 航点间距由航高、相机角度与重叠率推导：
 * <pre>
 *   groundSpacing = 2 × alt × tan(cameraAngle/2) × (1 - overlap/100)
 * </pre>
 */
@Service
public class RoutePlannerService {

    private static final Logger log = LoggerFactory.getLogger(RoutePlannerService.class);

    /** 地球半径 m。 */
    private static final double EARTH_R = 6371000.0;
    /** 默认相机水平 FOV（度），用于推算地面覆盖宽度。 */
    private static final double DEFAULT_FOV_DEG = 75.0;
    /** ORBIT 默认航点数。 */
    private static final int ORBIT_POINTS = 16;
    /** 网格扫描默认航线间距系数（乘以地面覆盖宽度）。 */
    private static final double GRID_LINE_SPACING_FACTOR = 1.0;

    /**
     * 根据巡检模板和区域生成航点。
     *
     * @param template 巡检模板（含航高、速度、重叠率、相机角度、航线类型）
     * @param startLat 起始纬度
     * @param startLon 起始经度
     * @param area     巡检区域（null 时以起始点为中心构造默认区域）
     * @return 航点列表（按执行顺序排列）
     */
    public List<Waypoint> planRoute(InspectionTemplate template,
                                    double startLat, double startLon,
                                    InspectionArea area) {
        InspectionArea effectiveArea = area != null ? area : defaultArea(startLat, startLon);
        List<Waypoint> waypoints = switch (template.routeType()) {
            case LINEAR_GRID -> planLinearGrid(template, startLat, startLon, effectiveArea);
            case CROSS_GRID -> planCrossGrid(template, startLat, startLon, effectiveArea);
            case ORBIT -> planOrbit(template, startLat, startLon, effectiveArea);
            case PERIMETER -> planPerimeter(template, startLat, startLon, effectiveArea);
        };
        log.info("Route planned: type={} waypoints={} area={}",
                template.routeType(), waypoints.size(), effectiveArea.kind());
        return waypoints;
    }

    /** 沿线网格扫描：沿区域主轴生成平行航线，按 overlap 重叠。 */
    private List<Waypoint> planLinearGrid(InspectionTemplate template,
                                          double startLat, double startLon,
                                          InspectionArea area) {
        double[] bbox = area.boundingBox();
        double minLat = bbox[0], maxLat = bbox[1], minLon = bbox[2], maxLon = bbox[3];
        double alt = template.altitudeM();
        double speed = template.speedMps();
        double overlap = template.overlapPct();

        double groundWidth = computeGroundWidth(alt, template.cameraAngleDeg());
        double lineSpacingM = groundWidth * (1 - overlap / 100.0) * GRID_LINE_SPACING_FACTOR;
        double latSpacing = lineSpacingM / 111320.0;

        List<Waypoint> waypoints = new ArrayList<>();
        int seq = 0;
        double lat = minLat;
        boolean forward = true;
        while (lat <= maxLat + 1e-9) {
            double fromLon = forward ? minLon : maxLon;
            double toLon = forward ? maxLon : minLon;
            // 航线起点
            waypoints.add(Waypoint.fly(seq++, lat, fromLon, alt,
                    heading(lat, fromLon, lat, toLon), speed));
            // 航线终点（拍照）
            waypoints.add(Waypoint.photo(seq++, lat, toLon, alt,
                    heading(lat, fromLon, lat, toLon), speed, 2.0));
            lat += latSpacing;
            forward = !forward;
        }
        return waypoints;
    }

    /** 交叉网格：两组平行线交叉覆盖。 */
    private List<Waypoint> planCrossGrid(InspectionTemplate template,
                                         double startLat, double startLon,
                                         InspectionArea area) {
        List<Waypoint> first = planLinearGrid(template, startLat, startLon, area);
        // 旋转 90 度生成第二组平行线（交换 lat/lon 扫描方向）
        double[] bbox = area.boundingBox();
        double minLat = bbox[0], maxLat = bbox[1], minLon = bbox[2], maxLon = bbox[3];
        double alt = template.altitudeM();
        double speed = template.speedMps();
        double overlap = template.overlapPct();

        double groundWidth = computeGroundWidth(alt, template.cameraAngleDeg());
        double lineSpacingM = groundWidth * (1 - overlap / 100.0) * GRID_LINE_SPACING_FACTOR;
        double lonSpacing = lineSpacingM
                / (111320.0 * Math.cos(Math.toRadians((minLat + maxLat) / 2)));

        List<Waypoint> waypoints = new ArrayList<>(first);
        int seq = first.size();
        double lon = minLon;
        boolean forward = true;
        while (lon <= maxLon + 1e-9) {
            double fromLat = forward ? minLat : maxLat;
            double toLat = forward ? maxLat : minLat;
            waypoints.add(Waypoint.fly(seq++, fromLat, lon, alt,
                    heading(fromLat, lon, toLat, lon), speed));
            waypoints.add(Waypoint.photo(seq++, toLat, lon, alt,
                    heading(fromLat, lon, toLat, lon), speed, 2.0));
            lon += lonSpacing;
            forward = !forward;
        }
        return waypoints;
    }

    /** 环绕飞行：围绕中心点生成圆形航点。 */
    private List<Waypoint> planOrbit(InspectionTemplate template,
                                     double startLat, double startLon,
                                     InspectionArea area) {
        double centerLat, centerLon, radiusM;
        if (area.kind() == InspectionArea.Kind.CIRCLE) {
            centerLat = area.centerLat();
            centerLon = area.centerLon();
            radiusM = area.radiusM();
        } else {
            double[] bbox = area.boundingBox();
            centerLat = (bbox[0] + bbox[1]) / 2;
            centerLon = (bbox[2] + bbox[3]) / 2;
            radiusM = Math.min(
                    (bbox[1] - bbox[0]) / 2 * 111320.0,
                    (bbox[3] - bbox[2]) / 2 * 111320.0
                            * Math.cos(Math.toRadians(centerLat)));
        }
        double alt = template.altitudeM();
        double speed = template.speedMps();
        int points = ORBIT_POINTS;

        List<Waypoint> waypoints = new ArrayList<>(points);
        for (int i = 0; i < points; i++) {
            double angle = 2 * Math.PI * i / points;
            double dLat = radiusM * Math.cos(angle) / 111320.0;
            double dLon = radiusM * Math.sin(angle)
                    / (111320.0 * Math.cos(Math.toRadians(centerLat)));
            double lat = centerLat + dLat;
            double lon = centerLon + dLon;
            double heading = Math.toDegrees(angle) + 90; // 切线方向
            if (heading >= 360) heading -= 360;
            // 交替 FLY/PHOTO，确保环绕一圈拍照覆盖
            Waypoint.Action action = (i % 2 == 0) ? Waypoint.Action.PHOTO : Waypoint.Action.FLY;
            double hold = action == Waypoint.Action.PHOTO ? 2.0 : 0;
            waypoints.add(new Waypoint(i, lat, lon, alt, heading, speed, action, hold));
        }
        return waypoints;
    }

    /** 周界巡逻：沿区域边界生成航点。 */
    private List<Waypoint> planPerimeter(InspectionTemplate template,
                                         double startLat, double startLon,
                                         InspectionArea area) {
        double alt = template.altitudeM();
        double speed = template.speedMps();
        List<Waypoint> waypoints = new ArrayList<>();

        if (area.kind() == InspectionArea.Kind.POLYGON) {
            List<double[]> pts = area.points();
            int n = pts.size();
            for (int i = 0; i < n; i++) {
                double[] cur = pts.get(i);
                double[] next = pts.get((i + 1) % n);
                double heading = heading(cur[0], cur[1], next[0], next[1]);
                Waypoint.Action action = Waypoint.Action.SCAN;
                waypoints.add(new Waypoint(i, cur[0], cur[1], alt, heading, speed, action, 3.0));
            }
        } else {
            // CIRCLE 周界即圆周
            double centerLat = area.centerLat();
            double centerLon = area.centerLon();
            double radiusM = area.radiusM();
            int points = ORBIT_POINTS;
            for (int i = 0; i < points; i++) {
                double angle = 2 * Math.PI * i / points;
                double dLat = radiusM * Math.cos(angle) / 111320.0;
                double dLon = radiusM * Math.sin(angle)
                        / (111320.0 * Math.cos(Math.toRadians(centerLat)));
                double lat = centerLat + dLat;
                double lon = centerLon + dLon;
                double nextAngle = 2 * Math.PI * ((i + 1) % points) / points;
                double nLat = centerLat + radiusM * Math.cos(nextAngle) / 111320.0;
                double nLon = centerLon + radiusM * Math.sin(nextAngle)
                        / (111320.0 * Math.cos(Math.toRadians(centerLat)));
                double heading = heading(lat, lon, nLat, nLon);
                waypoints.add(new Waypoint(i, lat, lon, alt, heading, speed,
                        Waypoint.Action.SCAN, 3.0));
            }
        }
        return waypoints;
    }

    /** 计算地面覆盖宽度（m），基于航高与相机角度。 */
    private double computeGroundWidth(double altM, double cameraAngleDeg) {
        double fov = cameraAngleDeg > 0 ? cameraAngleDeg : DEFAULT_FOV_DEG;
        return 2 * altM * Math.tan(Math.toRadians(fov / 2));
    }

    /** 计算两点间航向角（degrees, 0~360）。 */
    private static double heading(double lat1, double lon1, double lat2, double lon2) {
        double dLon = Math.toRadians(lon2 - lon1);
        double y = Math.sin(dLon) * Math.cos(Math.toRadians(lat2));
        double x = Math.cos(Math.toRadians(lat1)) * Math.sin(Math.toRadians(lat2))
                - Math.sin(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.cos(dLon);
        double bearing = Math.toDegrees(Math.atan2(y, x));
        return (bearing + 360) % 360;
    }

    /** 以起始点为中心构造默认区域（1km × 1km 方形）。 */
    private static InspectionArea defaultArea(double startLat, double startLon) {
        double half = 500.0 / 111320.0; // 500m in degrees
        List<double[]> pts = new ArrayList<>();
        pts.add(new double[]{startLat - half, startLon - half});
        pts.add(new double[]{startLat - half, startLon + half});
        pts.add(new double[]{startLat + half, startLon + half});
        pts.add(new double[]{startLat + half, startLon - half});
        return InspectionArea.polygon(pts);
    }
}
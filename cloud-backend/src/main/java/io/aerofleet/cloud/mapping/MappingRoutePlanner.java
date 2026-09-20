package io.aerofleet.cloud.mapping;

import io.aerofleet.cloud.api.ApiExceptionHandler.BadRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 测绘航线规划服务。
 * <p>
 * 根据测绘类型和区域生成航点序列：
 * <ul>
 *   <li>正射影像：平行航线 + 航向重叠率 + 侧向重叠率</li>
 *   <li>DEM：交叉航线（两组 90° 交叉的平行航线）</li>
 *   <li>三维建模：环绕飞行 + 倾斜摄影（1 个垂直 + 4 个倾斜角度）</li>
 * </ul>
 * <p>
 * 航点间距由航高、重叠率推导：
 * <pre>
 *   groundWidth = 2 × alt × tan(FOV/2)
 *   lineSpacing = groundWidth × (1 - sidelap/100)
 *   photoSpacing = groundWidth × (1 - overlap/100)
 * </pre>
 * GSD（地面采样距离）计算：
 * <pre>
 *   GSD = (altitude × sensorWidth) / (focalLength × imageWidth)
 * </pre>
 * 简化模型：GSD ≈ altitude / 100（cm/pixel，近似值）。
 */
@Service
public class MappingRoutePlanner {

    private static final Logger log = LoggerFactory.getLogger(MappingRoutePlanner.class);

    /** 地球半径 m。 */
    private static final double EARTH_R = 6371000.0;
    /** 默认相机水平 FOV（度）。 */
    private static final double DEFAULT_FOV_DEG = 75.0;
    /** 环绕飞行默认航点数。 */
    private static final int ORBIT_POINTS = 16;
    /** 倾斜摄影角度数（度）。 */
    private static final double[] TILT_ANGLES = {0.0, 45.0, 45.0, 45.0, 45.0};
    /** 倾斜摄影航向偏移（度），对应 4 个倾斜方向的航向角偏移。 */
    private static final double[] TILT_HEADINGS = {0.0, 0.0, 90.0, 180.0, 270.0};

    /**
     * 规划正射影像航线：平行航线 + 重叠率 + 侧向重叠率。
     *
     * @param area       测绘区域
     * @param altitudeM  飞行高度（m）
     * @param overlapPct 航向重叠率（%）
     * @param sidelapPct 侧向重叠率（%）
     * @return 航点列表
     */
    public List<MappingWaypoint> planOrthophotoRoute(MappingArea area, double altitudeM,
                                                     double overlapPct, double sidelapPct) {
        // P1-fix: 校验 overlapPct 和 sidelapPct 范围，防止 >=100 导致无限循环
        if (overlapPct < 0 || overlapPct > 95) {
            throw new BadRequestException("overlapPct must be between 0 and 95, got: " + overlapPct);
        }
        if (sidelapPct < 0 || sidelapPct > 95) {
            throw new BadRequestException("sidelapPct must be between 0 and 95, got: " + sidelapPct);
        }
        // P1-fix: 校验航高 > 0，防止除零或无限循环
        if (altitudeM <= 0) {
            throw new BadRequestException("altitudeM must be positive, got: " + altitudeM);
        }

        double[] bbox = area.boundingBox();
        double minLat = bbox[0], maxLat = bbox[1], minLon = bbox[2], maxLon = bbox[3];

        double groundWidth = computeGroundWidth(altitudeM, DEFAULT_FOV_DEG);
        double lineSpacingM = groundWidth * (1 - sidelapPct / 100.0);
        double photoSpacingM = groundWidth * (1 - overlapPct / 100.0);

        double latSpacing = lineSpacingM / 111320.0;
        double lonSpacingPerPhoto = photoSpacingM
                / (111320.0 * Math.cos(Math.toRadians((minLat + maxLat) / 2)));

        List<MappingWaypoint> waypoints = new ArrayList<>();
        int seq = 0;
        int photoId = 0;
        double lat = minLat;
        boolean forward = true;

        while (lat <= maxLat + 1e-9) {
            double fromLon = forward ? minLon : maxLon;
            double toLon = forward ? maxLon : minLon;
            double heading = heading(lat, fromLon, lat, toLon);

            // 航线起点（飞越）
            waypoints.add(MappingWaypoint.fly(seq++, lat, fromLon, altitudeM, heading));

            // 沿航线按 photoSpacing 拍照
            double lon = fromLon;
            while (forward ? lon <= toLon + 1e-9 : lon >= toLon - 1e-9) {
                photoId++;
                waypoints.add(MappingWaypoint.photo(seq++, lat, lon, altitudeM,
                        heading, 0.0, photoId));
                lon += forward ? lonSpacingPerPhoto : -lonSpacingPerPhoto;
            }

            lat += latSpacing;
            forward = !forward;
        }

        log.info("Orthophoto route planned: waypoints={} photos={} area={}",
                waypoints.size(), photoId, area.kind());
        return waypoints;
    }

    /**
     * 规划 DEM 航线：交叉航线（两组 90° 交叉的平行航线）。
     *
     * @param area      测绘区域
     * @param altitudeM 飞行高度（m）
     * @return 航点列表
     */
    public List<MappingWaypoint> planDemRoute(MappingArea area, double altitudeM) {
        // P1-fix: 校验航高 > 0，防止除零或无限循环
        if (altitudeM <= 0) {
            throw new BadRequestException("altitudeM must be positive, got: " + altitudeM);
        }

        double[] bbox = area.boundingBox();
        double minLat = bbox[0], maxLat = bbox[1], minLon = bbox[2], maxLon = bbox[3];

        double groundWidth = computeGroundWidth(altitudeM, DEFAULT_FOV_DEG);
        // DEM 使用较高重叠率以确保高程精度
        double lineSpacingM = groundWidth * 0.3; // 70% 侧向重叠
        double photoSpacingM = groundWidth * 0.2; // 80% 航向重叠

        // 第一组：东西方向平行航线
        List<MappingWaypoint> firstPass = planParallelPass(
                area, altitudeM, minLat, maxLat, minLon, maxLon,
                lineSpacingM, photoSpacingM, true);

        // 第二组：南北方向平行航线（90°交叉）
        List<MappingWaypoint> secondPass = planParallelPass(
                area, altitudeM, minLon, maxLon, minLat, maxLat,
                lineSpacingM, photoSpacingM, false);

        // 合并两组航线，重新编号
        List<MappingWaypoint> all = new ArrayList<>(firstPass.size() + secondPass.size());
        int seq = 0;
        int photoId = 0;
        for (MappingWaypoint wp : firstPass) {
            if (wp.action == MappingWaypoint.Action.PHOTO) {
                photoId++;
                all.add(new MappingWaypoint(seq++, wp.lat, wp.lon, wp.alt,
                        wp.headingDeg, wp.cameraAngleDeg, MappingWaypoint.Action.PHOTO, photoId));
            } else {
                all.add(new MappingWaypoint(seq++, wp.lat, wp.lon, wp.alt,
                        wp.headingDeg, wp.cameraAngleDeg, MappingWaypoint.Action.FLY, 0));
            }
        }
        for (MappingWaypoint wp : secondPass) {
            if (wp.action == MappingWaypoint.Action.PHOTO) {
                photoId++;
                all.add(new MappingWaypoint(seq++, wp.lat, wp.lon, wp.alt,
                        wp.headingDeg, wp.cameraAngleDeg, MappingWaypoint.Action.PHOTO, photoId));
            } else {
                all.add(new MappingWaypoint(seq++, wp.lat, wp.lon, wp.alt,
                        wp.headingDeg, wp.cameraAngleDeg, MappingWaypoint.Action.FLY, 0));
            }
        }

        log.info("DEM route planned: waypoints={} photos={} area={}",
                all.size(), photoId, area.kind());
        return all;
    }

    /**
     * 规划三维建模航线：环绕飞行 + 倾斜摄影（1垂直 + 4倾斜）。
     *
     * @param area      测绘区域
     * @param altitudeM 飞行高度（m）
     * @return 航点列表
     */
    public List<MappingWaypoint> plan3DModelRoute(MappingArea area, double altitudeM) {
        // P1-fix: 校验航高 > 0，防止除零或无限循环
        if (altitudeM <= 0) {
            throw new BadRequestException("altitudeM must be positive, got: " + altitudeM);
        }

        double centerLat, centerLon, radiusM;
        if (area.kind() == MappingArea.Kind.CIRCLE) {
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

        List<MappingWaypoint> waypoints = new ArrayList<>();
        int seq = 0;
        int photoId = 0;

        // 5 个角度的环绕飞行：1 垂直 + 4 倾斜
        for (int angleIdx = 0; angleIdx < TILT_ANGLES.length; angleIdx++) {
            double cameraAngle = TILT_ANGLES[angleIdx];
            double headingOffset = TILT_HEADINGS[angleIdx];

            // 每个角度环绕一圈
            for (int i = 0; i < ORBIT_POINTS; i++) {
                double orbitAngle = 2 * Math.PI * i / ORBIT_POINTS;
                double dLat = radiusM * Math.cos(orbitAngle) / 111320.0;
                double dLon = radiusM * Math.sin(orbitAngle)
                        / (111320.0 * Math.cos(Math.toRadians(centerLat)));
                double lat = centerLat + dLat;
                double lon = centerLon + dLon;
                double heading = (Math.toDegrees(orbitAngle) + 90 + headingOffset) % 360;

                if (i == 0 && angleIdx > 0) {
                    // 切换角度前先飞到新起点
                    waypoints.add(MappingWaypoint.fly(seq++, lat, lon, altitudeM, heading));
                }

                photoId++;
                waypoints.add(MappingWaypoint.photo(seq++, lat, lon, altitudeM,
                        heading, cameraAngle, photoId));
            }
        }

        log.info("3D model route planned: waypoints={} photos={} area={}",
                waypoints.size(), photoId, area.kind());
        return waypoints;
    }

    /**
     * 计算 GSD（地面采样距离，cm/pixel）。
     * <p>
     * 简化模型：GSD ≈ altitude / 100。
     *
     * @param altitudeM 飞行高度（m）
     * @return GSD（cm/pixel）
     */
    public double computeGsdCm(double altitudeM) {
        return altitudeM / 100.0;
    }

    /** 计算地面覆盖宽度（m），基于航高与相机 FOV。 */
    private double computeGroundWidth(double altM, double fovDeg) {
        return 2 * altM * Math.tan(Math.toRadians(fovDeg / 2));
    }

    /** 生成一组平行航线航点。 */
    private List<MappingWaypoint> planParallelPass(MappingArea area, double altitudeM,
                                                   double minA, double maxA,
                                                   double minB, double maxB,
                                                   double lineSpacingM, double photoSpacingM,
                                                   boolean latIsA) {
        List<MappingWaypoint> waypoints = new ArrayList<>();
        int seq = 0;
        int photoId = 0;
        double a = minA;
        boolean forward = true;
        double midLat = latIsA ? (minA + maxA) / 2 : (minB + maxB) / 2;

        double aSpacing = lineSpacingM / 111320.0;
        double bSpacingPerPhoto = photoSpacingM
                / (111320.0 * Math.cos(Math.toRadians(midLat)));

        while (a <= maxA + 1e-9) {
            double fromB = forward ? minB : maxB;
            double toB = forward ? maxB : minB;
            double heading;
            if (latIsA) {
                heading = heading(a, fromB, a, toB);
            } else {
                heading = heading(fromB, a, toB, a);
            }

            // 航线起点
            if (latIsA) {
                waypoints.add(MappingWaypoint.fly(seq++, a, fromB, altitudeM, heading));
            } else {
                waypoints.add(MappingWaypoint.fly(seq++, fromB, a, altitudeM, heading));
            }

            // 沿航线拍照
            double b = fromB;
            while (forward ? b <= toB + 1e-9 : b >= toB - 1e-9) {
                photoId++;
                if (latIsA) {
                    waypoints.add(MappingWaypoint.photo(seq++, a, b, altitudeM,
                            heading, 0.0, photoId));
                } else {
                    waypoints.add(MappingWaypoint.photo(seq++, b, a, altitudeM,
                            heading, 0.0, photoId));
                }
                b += forward ? bSpacingPerPhoto : -bSpacingPerPhoto;
            }

            a += aSpacing;
            forward = !forward;
        }

        return waypoints;
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
}
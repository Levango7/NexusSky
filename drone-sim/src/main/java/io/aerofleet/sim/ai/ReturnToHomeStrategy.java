package io.aerofleet.sim.ai;

import io.aerofleet.sim.GeoUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * M11 应急返航策略：能耗最优返航 + 风向利用 + 地形规避 + 滑翔路径。
 * <p>
 * 保留旧接口 {@link #evaluate(double, boolean, boolean, double)} 供
 * {@link DecisionEngine} 调用（向后兼容）。
 * <p>
 * 新增真实最优返航算法：
 * <ul>
 *   <li>{@link #computeOptimalReturnPath} — 能耗最优返航路径（顺风直线 / 逆风 Z 字形 / 低电量最短路径）</li>
 *   <li>{@link #computeWindAssistedHeading} — 风向利用（顺风加速 / 侧风 crab 修正 / 逆风 tacking 偏航）</li>
 *   <li>{@link #avoidTerrain} — 地形规避（检测相交 → 抬升高度越过障碍）</li>
 *   <li>{@link #computeGlidePath} — 无动力滑翔路径评估（高度足够→纯滑翔 / 高度不足→需动力辅助）</li>
 *   <li>{@link #planReturnHome} — 综合返航决策（组合以上算法生成完整返航计划）</li>
 * </ul>
 * <p>
 * 物理模型说明：
 * <ul>
 *   <li>航向/风向单位均为度，0=正北，顺时针增加，范围 [0, 360)</li>
 *   <li>风向 {@code windDirection} 定义为风吹 <b>向</b> 的方向（风向量指向方向）</li>
 *   <li>地速 = 空速向量 + 风向量；顺风时地速 = airspeed + windSpeed，逆风时地速 = airspeed - windSpeed</li>
 *   <li>能耗 = 功耗 × 时间；巡航功耗 200W，满电续航约 25 分钟</li>
 *   <li>滑翔比 glideRatio = 水平距离 / 下降高度（如 10:1 表示每下降 1m 飞行 10m）</li>
 * </ul>
 * <p>
 * 注意：drone-sim 模块未引入 slf4j，统一使用 {@code System.out.println} 输出日志。
 */
public class ReturnToHomeStrategy {
    private static final double BATTERY_THRESHOLD = 25.0;
    private static final double LINK_TIMEOUT_SEC = 10.0;

    // ====== 能耗模型参数 ======
    private static final double CRUISE_POWER_W = 200.0;                 // 巡航功耗 200W
    private static final double CLIMB_POWER_W = 300.0;                 // 爬升功耗 300W
    private static final double CLIMB_RATE_MPS = 2.0;                  // 爬升率 2 m/s
    private static final double FULL_BATTERY_CRUISE_TIME_SEC = 25.0 * 60.0; // 满电巡航 25 分钟

    // ====== 滑翔参数 ======
    private static final double DEFAULT_GLIDE_RATIO = 10.0;            // 默认滑翔比 10:1

    // ====== 风向处理参数 ======
    private static final double TAILWIND_ANGLE_THRESHOLD = 45.0;       // |夹角| < 45° → 顺风
    private static final double HEADWIND_ANGLE_THRESHOLD = 135.0;      // |夹角| > 135° → 逆风
    private static final double TACKING_OFFSET_DEG = 45.0;             // 逆风 tacking 偏航角
    private static final double ZIGZAG_OFFSET_RATIO = 0.2;             // Z 字形横向偏移占直线距离比例

    // ====== 地形规避参数 ======
    private static final double TERRAIN_CLEARANCE_M = 20.0;            // 地形安全余度（抬升到障碍顶 + 此值）

    // ====== 结果类（Java 8 兼容，不用 record） ======

    /** 能耗最优返航路径结果。 */
    public static final class RtlPathResult {
        /** 路径点列表，每点 [lat, lon, alt] */
        public final List<double[]> path;
        /** 预估飞行时间（秒） */
        public final double estimatedTimeSec;
        /** 预估能耗（焦耳） */
        public final double estimatedEnergy;
        /** 是否可达（电量能否支撑返航） */
        public final boolean reachable;

        public RtlPathResult(List<double[]> path, double estimatedTimeSec, double estimatedEnergy, boolean reachable) {
            this.path = path;
            this.estimatedTimeSec = estimatedTimeSec;
            this.estimatedEnergy = estimatedEnergy;
            this.reachable = reachable;
        }
    }

    /** 风向辅助航向计算结果。 */
    public static final class WindAssistedHeading {
        /** 最优航向（度，[0, 360)） */
        public final double heading;
        /** 预估地速（m/s） */
        public final double groundSpeed;

        public WindAssistedHeading(double heading, double groundSpeed) {
            this.heading = heading;
            this.groundSpeed = groundSpeed;
        }
    }

    /** 滑翔路径评估结果。 */
    public static final class GlideResult {
        /** 滑翔路径点列表，每点 [lat, lon, alt] */
        public final List<double[]> path;
        /** 是否可纯滑翔到达家 */
        public final boolean pureGlideReachable;
        /** 高度不足时所需的额外动力（焦耳）；纯滑翔可达时为 0 */
        public final double requiredAdditionalPower;

        public GlideResult(List<double[]> path, boolean pureGlideReachable, double requiredAdditionalPower) {
            this.path = path;
            this.pureGlideReachable = pureGlideReachable;
            this.requiredAdditionalPower = requiredAdditionalPower;
        }
    }

    /** 综合返航计划。 */
    public static final class ReturnHomePlan {
        /** 返航路径点列表，每点 [lat, lon, alt] */
        public final List<double[]> path;
        /** 预估飞行时间（秒） */
        public final double estimatedTimeSec;
        /** 预估能耗（焦耳） */
        public final double estimatedEnergy;
        /** 是否可达 */
        public final boolean reachable;
        /** 是否采用纯滑翔（无动力） */
        public final boolean pureGlide;

        public ReturnHomePlan(List<double[]> path, double estimatedTimeSec, double estimatedEnergy,
                              boolean reachable, boolean pureGlide) {
            this.path = path;
            this.estimatedTimeSec = estimatedTimeSec;
            this.estimatedEnergy = estimatedEnergy;
            this.reachable = reachable;
            this.pureGlide = pureGlide;
        }
    }

    // ----------------------------------------------------------------------
    // 旧接口（向后兼容 DecisionEngine 调用）
    // ----------------------------------------------------------------------

    /**
     * 简单应急返航决策（无路径规划）。
     * <p>保留原 M11 逻辑：低电量 / 链路丢失 → RTL；GPS 退化 → EMERGENCY_LAND。
     */
    public DecisionResult evaluate(double battery, boolean linkHealthy, boolean gpsHealthy, double distanceToHome) {
        if (battery < BATTERY_THRESHOLD) {
            return new DecisionResult("RTL", "low battery", battery, 0.9);
        }
        if (!linkHealthy) {
            return new DecisionResult("RTL", "link lost", 0, 0.85);
        }
        if (!gpsHealthy) {
            return new DecisionResult("EMERGENCY_LAND", "GPS degraded", 0, 0.8);
        }
        return null;
    }

    // ----------------------------------------------------------------------
    // 风向利用
    // ----------------------------------------------------------------------

    /**
     * 计算利用风向的最优航向与预估地速。
     * <p>
     * 策略：
     * <ul>
     *   <li><b>顺风</b>（|夹角| &lt; 45°）：直接沿期望航向飞，地速 = airspeed + windSpeed·cos(夹角)</li>
     *   <li><b>侧风</b>（45° ≤ |夹角| &lt; 135°）：crab angle 修正航向以抵消侧风，
     *       地速 = airspeed·cos(crab) + windSpeed·cos(夹角)</li>
     *   <li><b>逆风</b>（|夹角| ≥ 135°）：tacking 偏航 45° 减少逆风分量，
     *       地速 = airspeed + windSpeed·cos(夹角 - 45°)</li>
     * </ul>
     * 安全处理：风速 ≥ 空速且为逆风时，沿风向飞（避免被风吹退）。
     *
     * @param directHeading 期望航向（度，0=正北，顺时针）
     * @param windSpeed     风速（m/s，≥ 0）
     * @param windDirection 风向（度，风吹向的方向）
     * @param airspeed      空速（m/s，> 0）
     * @return {@link WindAssistedHeading}，含最优航向 [0,360) 与地速（≥ 0）
     */
    public WindAssistedHeading computeWindAssistedHeading(double directHeading, double windSpeed,
                                                          double windDirection, double airspeed) {
        // 防御性参数处理
        double safeAirspeed = Math.max(0.1, airspeed);
        double safeWindSpeed = Math.max(0.0, windSpeed);

        // 风与期望航向的夹角，归一化到 [-180, 180]
        double windAngle = normalizeAngleDiff(windDirection - directHeading);
        double absAngle = Math.abs(windAngle);

        // 安全处理：风速 >= 空速 且 非顺风（逆风/强侧风）→ 沿风向飞避免被吹退
        if (safeWindSpeed >= safeAirspeed && absAngle > 90.0) {
            double safeHeading = normalizeHeading(windDirection);
            double groundSpeed = Math.max(0.0, safeWindSpeed - safeAirspeed);
            System.out.println("[RTL-Wind] windSpeed(" + windSpeed + ") >= airspeed(" + airspeed
                    + ") headwind, fly downwind: heading=" + String.format("%.1f", safeHeading)
                    + " gs=" + String.format("%.2f", groundSpeed));
            return new WindAssistedHeading(safeHeading, groundSpeed);
        }

        double heading;
        double groundSpeed;

        if (absAngle < TAILWIND_ANGLE_THRESHOLD) {
            // 顺风：直接沿期望航向飞
            heading = directHeading;
            groundSpeed = safeAirspeed + safeWindSpeed * Math.cos(Math.toRadians(windAngle));
        } else if (absAngle < HEADWIND_ANGLE_THRESHOLD) {
            // 侧风：crab angle 修正
            double crosswind = safeWindSpeed * Math.sin(Math.toRadians(windAngle));
            double headwind = safeWindSpeed * Math.cos(Math.toRadians(windAngle));
            double ratio = crosswind / safeAirspeed;
            if (Math.abs(ratio) >= 1.0) {
                // 侧风超过空速，无法完全抵消 → 尽力偏航 45°
                heading = directHeading - Math.signum(ratio) * TACKING_OFFSET_DEG;
                groundSpeed = safeAirspeed * Math.cos(Math.toRadians(TACKING_OFFSET_DEG)) + headwind;
            } else {
                double crabAngleDeg = Math.toDegrees(Math.asin(ratio));
                heading = directHeading - crabAngleDeg;
                groundSpeed = safeAirspeed * Math.cos(Math.toRadians(crabAngleDeg)) + headwind;
            }
        } else {
            // 逆风：tacking 偏航 45° 减少逆风分量
            heading = directHeading + TACKING_OFFSET_DEG;
            double newWindAngle = windAngle - TACKING_OFFSET_DEG;
            groundSpeed = safeAirspeed + safeWindSpeed * Math.cos(Math.toRadians(newWindAngle));
        }

        double normHeading = normalizeHeading(heading);
        double safeGs = Math.max(0.0, groundSpeed);
        System.out.println("[RTL-Wind] direct=" + String.format("%.1f", directHeading)
                + " windDir=" + String.format("%.1f", windDirection)
                + " windSpd=" + String.format("%.1f", windSpeed)
                + " as=" + String.format("%.1f", airspeed)
                + " -> heading=" + String.format("%.1f", normHeading)
                + " gs=" + String.format("%.2f", safeGs));
        return new WindAssistedHeading(normHeading, safeGs);
    }

    // ----------------------------------------------------------------------
    // 能耗最优返航路径
    // ----------------------------------------------------------------------

    /**
     * 计算能耗最优返航路径。
     * <p>
     * 路径选择策略：
     * <ul>
     *   <li><b>顺风</b> 或 <b>低电量</b>（&lt; 25%）：直线返回（最短路径，能耗最低）</li>
     *   <li><b>逆风</b> 且 <b>电量充足</b>（≥ 40%）：Z 字形路径利用侧风分量，
     *       虽然距离更长但每段地速更高，总能耗可能更低</li>
     *   <li>其他（侧风 / 中等电量）：直线返回</li>
     * </ul>
     * 能耗 = 巡航功耗 × 飞行时间；飞行时间 = 距离 / 地速。
     * 可达性 = 预估时间 ≤ 满电续航时间 × (batteryPct / 100)。
     *
     * @param currentLat  当前纬度
     * @param currentLon  当前经度
     * @param currentAlt  当前高度（m）
     * @param homeLat     家点纬度
     * @param homeLon     家点经度
     * @param homeAlt     家点高度（m）
     * @param batteryPct  电量百分比 [0, 100]
     * @param windSpeed   风速（m/s）
     * @param windDirection 风向（度，风吹向方向）
     * @param airspeed    空速（m/s）
     * @return {@link RtlPathResult}，含路径、预估时间、预估能耗、是否可达
     */
    public RtlPathResult computeOptimalReturnPath(double currentLat, double currentLon, double currentAlt,
                                                  double homeLat, double homeLon, double homeAlt,
                                                  double batteryPct, double windSpeed,
                                                  double windDirection, double airspeed) {
        // 直线路径几何
        double north = GeoUtil.north(currentLat, currentLon, homeLat, homeLon);
        double east = GeoUtil.east(currentLat, currentLon, homeLat, homeLon);
        double horizontalDist = Math.sqrt(north * north + east * east);
        double altDiff = homeAlt - currentAlt;
        double directDist = Math.sqrt(horizontalDist * horizontalDist + altDiff * altDiff);

        // 起点等于终点
        if (directDist < 1e-6) {
            List<double[]> path = new ArrayList<>();
            path.add(new double[]{currentLat, currentLon, currentAlt});
            return new RtlPathResult(path, 0.0, 0.0, true);
        }

        // 直线航向
        double directHeading = normalizeHeading(Math.toDegrees(Math.atan2(east, north)));
        double windAngle = normalizeAngleDiff(windDirection - directHeading);
        double absWindAngle = Math.abs(windAngle);
        boolean isTailwind = absWindAngle < TAILWIND_ANGLE_THRESHOLD;
        boolean isHeadwind = absWindAngle >= HEADWIND_ANGLE_THRESHOLD;

        List<double[]> path = new ArrayList<>();
        double estimatedTime;
        double estimatedEnergy;

        if (isHeadwind && batteryPct >= 40.0) {
            // 逆风 + 电量充足 → Z 字形路径利用侧风
            path = buildZigzagPath(currentLat, currentLon, currentAlt,
                    homeLat, homeLon, homeAlt, directHeading, horizontalDist);
            estimatedTime = estimatePathTime(path, windSpeed, windDirection, airspeed);
            estimatedEnergy = CRUISE_POWER_W * estimatedTime;
            System.out.println("[RTL-Path] zigzag (headwind + sufficient battery): points=" + path.size()
                    + " time=" + String.format("%.1f", estimatedTime) + "s");
        } else {
            // 顺风 / 低电量 / 侧风 → 直线返回（最短路径）
            path.add(new double[]{currentLat, currentLon, currentAlt});
            path.add(new double[]{homeLat, homeLon, homeAlt});
            WindAssistedHeading wah = computeWindAssistedHeading(directHeading, windSpeed, windDirection, airspeed);
            double groundSpeed = Math.max(0.5, wah.groundSpeed);
            estimatedTime = directDist / groundSpeed;
            estimatedEnergy = CRUISE_POWER_W * estimatedTime;
            System.out.println("[RTL-Path] direct (" + (isTailwind ? "tailwind" : "shortest")
                    + "): dist=" + String.format("%.1f", directDist) + "m"
                    + " time=" + String.format("%.1f", estimatedTime) + "s");
        }

        // 可达性：预估时间 ≤ 满电续航 × 电量百分比
        double maxFlightTimeSec = Math.max(0.0, batteryPct) / 100.0 * FULL_BATTERY_CRUISE_TIME_SEC;
        boolean reachable = estimatedTime <= maxFlightTimeSec;

        return new RtlPathResult(path, estimatedTime, estimatedEnergy, reachable);
    }

    /**
     * 构建 Z 字形路径：起点 → 偏航中点 → 终点。
     * 中点沿垂直于直线路径方向偏移 horizontalDist × {@link #ZIGZAG_OFFSET_RATIO}。
     */
    private List<double[]> buildZigzagPath(double curLat, double curLon, double curAlt,
                                           double homeLat, double homeLon, double homeAlt,
                                           double directHeading, double horizontalDist) {
        List<double[]> path = new ArrayList<>();
        path.add(new double[]{curLat, curLon, curAlt});

        // 中点本地坐标（相对于起点）
        double midNorth = GeoUtil.north(curLat, curLon, homeLat, homeLon) / 2.0;
        double midEast = GeoUtil.east(curLat, curLon, homeLat, homeLon) / 2.0;
        // 垂直偏移：沿 directHeading + 90° 方向
        double offsetDist = horizontalDist * ZIGZAG_OFFSET_RATIO;
        double perpRad = Math.toRadians(directHeading + 90.0);
        midNorth += offsetDist * Math.cos(perpRad);
        midEast += offsetDist * Math.sin(perpRad);

        double detourLat = GeoUtil.latOf(curLat, curLon, midNorth, midEast);
        double detourLon = GeoUtil.lonOf(curLat, curLon, midNorth, midEast);
        double midAlt = (curAlt + homeAlt) / 2.0;
        path.add(new double[]{detourLat, detourLon, midAlt});
        path.add(new double[]{homeLat, homeLon, homeAlt});
        return path;
    }

    /** 按路径分段累加飞行时间（每段用时 = 段长 / 该段地速）。 */
    private double estimatePathTime(List<double[]> path, double windSpeed, double windDirection, double airspeed) {
        double totalTime = 0.0;
        for (int i = 0; i < path.size() - 1; i++) {
            double[] a = path.get(i);
            double[] b = path.get(i + 1);
            double segNorth = GeoUtil.north(a[0], a[1], b[0], b[1]);
            double segEast = GeoUtil.east(a[0], a[1], b[0], b[1]);
            double segAlt = b[2] - a[2];
            double segLen = Math.sqrt(segNorth * segNorth + segEast * segEast + segAlt * segAlt);
            if (segLen < 1e-9) continue;
            double segHeading = normalizeHeading(Math.toDegrees(Math.atan2(segEast, segNorth)));
            WindAssistedHeading wah = computeWindAssistedHeading(segHeading, windSpeed, windDirection, airspeed);
            double gs = Math.max(0.5, wah.groundSpeed);
            totalTime += segLen / gs;
        }
        return totalTime;
    }

    // ----------------------------------------------------------------------
    // 地形规避
    // ----------------------------------------------------------------------

    /**
     * 地形规避：检查路径是否与地形障碍相交，相交则抬升路径高度越过障碍。
     * <p>
     * 障碍物模型：圆柱体 [lat, lon, alt, radius]，水平面内半径 radius 的圆，
     * 地面以上 0~alt 高度范围内不可通行。
     * <p>
     * 修正策略：若任一路径段与障碍物相交，将该段所有点高度抬升至
     * max(原高度, 障碍顶高 + {@link #TERRAIN_CLEARANCE_M}, clearanceAlt)。
     *
     * @param path            路径点列表 [lat, lon, alt]
     * @param terrainObstacles 地形障碍列表，每个 [lat, lon, alt, radius]
     * @param clearanceAlt    最低安全高度（m）
     * @return 修正后的路径（无相交时返回原路径副本）
     */
    public List<double[]> avoidTerrain(List<double[]> path, List<double[]> terrainObstacles,
                                       double clearanceAlt) {
        if (path == null || path.isEmpty()) {
            return new ArrayList<>();
        }
        if (terrainObstacles == null || terrainObstacles.isEmpty()) {
            return new ArrayList<>(path);
        }

        // 检测是否需要修正
        double requiredAlt = clearanceAlt;
        boolean needsAdjustment = false;
        for (double[] obstacle : terrainObstacles) {
            double obsLat = obstacle[0];
            double obsLon = obstacle[1];
            double obsAlt = obstacle[2];
            double obsRadius = obstacle[3];
            for (int i = 0; i < path.size() - 1; i++) {
                if (segmentIntersectsObstacle(path.get(i), path.get(i + 1),
                        obsLat, obsLon, obsAlt, obsRadius)) {
                    needsAdjustment = true;
                    requiredAlt = Math.max(requiredAlt, obsAlt + TERRAIN_CLEARANCE_M);
                    System.out.println("[RTL-Terrain] segment " + i + " intersects obstacle at ("
                            + obsLat + "," + obsLon + ") alt=" + obsAlt + " r=" + obsRadius
                            + " -> raise to " + requiredAlt);
                }
            }
        }

        if (!needsAdjustment) {
            System.out.println("[RTL-Terrain] no intersection, path unchanged");
            return new ArrayList<>(path);
        }

        // 抬升路径高度
        List<double[]> adjusted = new ArrayList<>(path.size());
        for (double[] p : path) {
            double newAlt = Math.max(p[2], requiredAlt);
            adjusted.add(new double[]{p[0], p[1], newAlt});
        }
        return adjusted;
    }

    /**
     * 检查线段 [a, b] 是否与圆柱体障碍相交。
     * 采样检查：线段上每点若水平距离 ≤ radius 且高度 < obsAlt 则相交。
     */
    private boolean segmentIntersectsObstacle(double[] a, double[] b,
                                              double obsLat, double obsLon,
                                              double obsAlt, double obsRadius) {
        double northA = GeoUtil.north(obsLat, obsLon, a[0], a[1]);
        double eastA = GeoUtil.east(obsLat, obsLon, a[0], a[1]);
        double northB = GeoUtil.north(obsLat, obsLon, b[0], b[1]);
        double eastB = GeoUtil.east(obsLat, obsLon, b[0], b[1]);
        double segLen = Math.sqrt((northB - northA) * (northB - northA)
                + (eastB - eastA) * (eastB - eastA));
        int steps = Math.max(1, (int) Math.ceil(segLen));
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            double north = northA + (northB - northA) * t;
            double east = eastA + (eastB - eastA) * t;
            double alt = a[2] + (b[2] - a[2]) * t;
            double horizDist = Math.sqrt(north * north + east * east);
            if (horizDist <= obsRadius && alt < obsAlt) {
                return true;
            }
        }
        return false;
    }

    // ----------------------------------------------------------------------
    // 滑翔路径
    // ----------------------------------------------------------------------

    /**
     * 计算无动力滑翔路径。
     * <p>
     * 滑翔比 glideRatio = 水平距离 / 下降高度。从当前位置滑翔到家所需下降高度 =
     * 水平距离 / glideRatio。若当前高度 - 所需下降 ≥ 家点高度，可纯滑翔到达。
     *
     * @param currentLat  当前纬度
     * @param currentLon  当前经度
     * @param currentAlt  当前高度（m）
     * @param homeLat     家点纬度
     * @param homeLon     家点经度
     * @param homeAlt     家点高度（m）
     * @param glideRatio  滑翔比（> 0，如 10 表示 10:1）
     * @return {@link GlideResult}，含滑翔路径、是否纯滑翔可达、所需额外动力
     */
    public GlideResult computeGlidePath(double currentLat, double currentLon, double currentAlt,
                                        double homeLat, double homeLon, double homeAlt,
                                        double glideRatio) {
        double north = GeoUtil.north(currentLat, currentLon, homeLat, homeLon);
        double east = GeoUtil.east(currentLat, currentLon, homeLat, homeLon);
        double horizontalDist = Math.sqrt(north * north + east * east);
        double safeGlideRatio = Math.max(0.1, glideRatio);

        // 滑翔所需下降高度
        double requiredDescent = horizontalDist / safeGlideRatio;
        // 滑翔到家时的剩余高度
        double arrivalAlt = currentAlt - requiredDescent;

        List<double[]> path = new ArrayList<>();
        path.add(new double[]{currentLat, currentLon, currentAlt});

        boolean pureGlideReachable = arrivalAlt >= homeAlt;
        double requiredAdditionalPower = 0.0;

        if (pureGlideReachable) {
            // 纯滑翔可达：终点高度为滑翔自然到达的高度（≥ homeAlt）
            path.add(new double[]{homeLat, homeLon, arrivalAlt});
            System.out.println("[RTL-Glide] pure glide reachable: dist="
                    + String.format("%.1f", horizontalDist) + "m descent="
                    + String.format("%.1f", requiredDescent) + "m arrivalAlt="
                    + String.format("%.1f", arrivalAlt) + " >= homeAlt=" + homeAlt);
        } else {
            // 高度不足：需要动力辅助爬升补足高度差
            double altitudeDeficit = homeAlt - arrivalAlt;
            double climbTime = altitudeDeficit / CLIMB_RATE_MPS;
            requiredAdditionalPower = CLIMB_POWER_W * climbTime;
            // 路径终点高度设为 homeAlt（动力辅助维持高度）
            path.add(new double[]{homeLat, homeLon, homeAlt});
            System.out.println("[RTL-Glide] altitude deficit=" + String.format("%.1f", altitudeDeficit)
                    + "m, need additional power=" + String.format("%.1f", requiredAdditionalPower) + "J");
        }

        return new GlideResult(path, pureGlideReachable, requiredAdditionalPower);
    }

    // ----------------------------------------------------------------------
    // 综合返航决策
    // ----------------------------------------------------------------------

    /**
     * 综合返航决策：组合能耗最优路径 + 地形规避 + 滑翔评估，生成完整返航计划。
     * <p>
     * 决策逻辑：
     * <ol>
     *   <li>计算能耗最优返航路径 {@link #computeOptimalReturnPath}</li>
     *   <li>对路径做地形规避 {@link #avoidTerrain}</li>
     *   <li>评估滑翔可行性 {@link #computeGlidePath}</li>
     *   <li>若电量极低（&lt; 15%）且可纯滑翔，优先采用滑翔路径（节能保命）</li>
     *   <li>否则采用地形规避后的动力路径</li>
     * </ol>
     *
     * @param currentLat      当前纬度
     * @param currentLon      当前经度
     * @param currentAlt      当前高度（m）
     * @param homeLat         家点纬度
     * @param homeLon         家点经度
     * @param homeAlt         家点高度（m）
     * @param batteryPct      电量百分比 [0, 100]
     * @param windSpeed       风速（m/s）
     * @param windDirection   风向（度，风吹向方向）
     * @param airspeed        空速（m/s）
     * @param terrainObstacles 地形障碍列表 [lat, lon, alt, radius]，可为 null
     * @return {@link ReturnHomePlan}，含路径、预估时间、预估能耗、是否可达、是否纯滑翔
     */
    public ReturnHomePlan planReturnHome(double currentLat, double currentLon, double currentAlt,
                                         double homeLat, double homeLon, double homeAlt,
                                         double batteryPct, double windSpeed, double windDirection,
                                         double airspeed, List<double[]> terrainObstacles) {
        System.out.println("[RTL-Plan] start: battery=" + batteryPct + " wind=" + windSpeed
                + "m/s dir=" + windDirection + " as=" + airspeed);

        // 1. 能耗最优返航路径
        RtlPathResult rtlResult = computeOptimalReturnPath(currentLat, currentLon, currentAlt,
                homeLat, homeLon, homeAlt, batteryPct, windSpeed, windDirection, airspeed);

        // 2. 地形规避
        double clearanceAlt = Math.max(currentAlt, homeAlt) + TERRAIN_CLEARANCE_M;
        List<double[]> safePath = avoidTerrain(rtlResult.path, terrainObstacles, clearanceAlt);

        // 3. 滑翔评估
        GlideResult glideResult = computeGlidePath(currentLat, currentLon, currentAlt,
                homeLat, homeLon, homeAlt, DEFAULT_GLIDE_RATIO);

        // 4. 综合决策：电量极低且可纯滑翔 → 优先滑翔（节能保命）
        boolean useGlide = batteryPct < 15.0 && glideResult.pureGlideReachable;

        List<double[]> finalPath = useGlide ? glideResult.path : safePath;
        boolean reachable = rtlResult.reachable || glideResult.pureGlideReachable;
        boolean pureGlide = useGlide;

        System.out.println("[RTL-Plan] done: useGlide=" + useGlide + " reachable=" + reachable
                + " pathPoints=" + finalPath.size()
                + " time=" + String.format("%.1f", rtlResult.estimatedTimeSec) + "s");

        return new ReturnHomePlan(finalPath, rtlResult.estimatedTimeSec, rtlResult.estimatedEnergy,
                reachable, pureGlide);
    }

    // ----------------------------------------------------------------------
    // 角度工具方法
    // ----------------------------------------------------------------------

    /** 归一化航向到 [0, 360)。 */
    private static double normalizeHeading(double angle) {
        angle = angle % 360.0;
        if (angle < 0.0) angle += 360.0;
        return angle;
    }

    /** 归一化夹角到 [-180, 180)。 */
    private static double normalizeAngleDiff(double angle) {
        angle = angle % 360.0;
        if (angle >= 180.0) angle -= 360.0;
        if (angle < -180.0) angle += 360.0;
        return angle;
    }
}

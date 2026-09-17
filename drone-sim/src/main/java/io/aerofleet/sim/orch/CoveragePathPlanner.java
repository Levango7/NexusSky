package io.aerofleet.sim.orch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

/**
 * 覆盖路径规划算法（M9 应急任务编排，覆盖路径规划扩展）。
 * <p>
 * 实现三大核心能力：
 * <pre>
 * 1. Boustrophedon 牛耕式覆盖：在矩形/多边形区域内生成平行扫描线，交替方向连接，
 *    形成连续的"牛耕田"式覆盖路径。
 * 2. Voronoi 分区多机覆盖：用最近邻原则将区域划分为 N 个子区域，每架无人机负责一个子区域，
 *    各自生成牛耕式路径（简化实现，不依赖外部 Voronoi 库）。
 * 3. 转弯优化：将路径中的尖角转弯替换为圆弧，减少总路径长度和飞行时间。
 * </pre>
 * <p>
 * 坐标约定：
 * <pre>
 * - 所有经纬度单位为度，高度/距离单位为米
 * - bbox = [minLat, maxLat, minLon, maxLon]
 * - 路径点格式 [lat, lon, alt]，alt 默认 0
 * - 局部平面近似：1度纬度 ≈ 111000m，1度经度 ≈ 111000 * cos(lat) m
 * </pre>
 */
public class CoveragePathPlanner {

    /** 地球半径（m）。 */
    private static final double EARTH_RADIUS_M = 6371000.0;
    /** 1 度纬度对应的距离（m）。 */
    private static final double M_PER_DEGREE_LAT = 111000.0;
    /** 最小转弯角度阈值（弧度），小于此值视为直行。 */
    private static final double MIN_TURN_ANGLE_RAD = Math.toRadians(5.0);
    /** 最大可优化转弯角度（弧度），大于此值（接近 U 型）不做圆弧优化。 */
    private static final double MAX_TURN_ANGLE_RAD = Math.toRadians(170.0);
    /** 覆盖率采样上限，避免大区域内存爆炸。 */
    private static final int MAX_SAMPLES = 100_000;

    /**
     * 牛耕式覆盖路径（矩形区域）。
     * <p>
     * 在 bbox 内沿纬度方向生成平行扫描线，间隔 spacing 米，交替方向连接。
     * 扫描线均匀分布在 [minLat, maxLat]，每条扫描线在条带中心，确保边缘覆盖均匀。
     *
     * @param bbox    边界框 [minLat, maxLat, minLon, maxLon]
     * @param spacing 航线间距（m），通常等于相机幅宽
     * @param alt     飞行高度（m）
     * @return 路径点列表，每个元素 [lat, lon, alt]；空区域返回空列表
     */
    public List<double[]> boustrophedon(double[] bbox, double spacing, double alt) {
        if (bbox == null || bbox.length < 4 || spacing <= 0) {
            return Collections.emptyList();
        }
        double minLat = bbox[0];
        double maxLat = bbox[1];
        double minLon = bbox[2];
        double maxLon = bbox[3];
        if (maxLat <= minLat || maxLon <= minLon) {
            return Collections.emptyList();
        }

        double cosLat = Math.cos(Math.toRadians((minLat + maxLat) / 2.0));
        if (Math.abs(cosLat) < 1e-6) {
            cosLat = 1e-6;
        }
        double latStep = spacing / M_PER_DEGREE_LAT;

        // 均匀分布扫描线：每条扫描线在条带中心
        int numSwaths = (int) Math.ceil((maxLat - minLat) / latStep);
        if (numSwaths < 1) {
            numSwaths = 1;
        }
        double actualStep = (maxLat - minLat) / numSwaths;

        List<double[]> path = new ArrayList<>();
        for (int i = 0; i < numSwaths; i++) {
            double lat = minLat + (i + 0.5) * actualStep;
            if (i % 2 == 0) {
                // 偶数行：从 minLon 到 maxLon
                path.add(new double[]{lat, minLon, alt});
                path.add(new double[]{lat, maxLon, alt});
            } else {
                // 奇数行：从 maxLon 到 minLon
                path.add(new double[]{lat, maxLon, alt});
                path.add(new double[]{lat, minLon, alt});
            }
        }
        return path;
    }

    /**
     * 牛耕式覆盖路径（多边形区域）。
     * <p>
     * 沿纬度方向生成扫描线，每条扫描线与多边形边界求交得到进出点，
     * 成对连接形成扫描段，交替方向连接相邻扫描线。
     *
     * @param polygon 多边形顶点列表，每个元素 [lat, lon]（顺时针或逆时针）
     * @param spacing 航线间距（m）
     * @param alt     飞行高度（m）
     * @return 路径点列表，每个元素 [lat, lon, alt]；无效多边形返回空列表
     */
    public List<double[]> boustrophedonPolygon(List<double[]> polygon, double spacing, double alt) {
        if (polygon == null || polygon.size() < 3 || spacing <= 0) {
            return Collections.emptyList();
        }

        // 计算多边形 bbox
        double minLat = Double.MAX_VALUE;
        double maxLat = -Double.MAX_VALUE;
        double minLon = Double.MAX_VALUE;
        double maxLon = -Double.MAX_VALUE;
        for (double[] p : polygon) {
            minLat = Math.min(minLat, p[0]);
            maxLat = Math.max(maxLat, p[0]);
            minLon = Math.min(minLon, p[1]);
            maxLon = Math.max(maxLon, p[1]);
        }
        if (maxLat <= minLat || maxLon <= minLon) {
            return Collections.emptyList();
        }

        double cosLat = Math.cos(Math.toRadians((minLat + maxLat) / 2.0));
        if (Math.abs(cosLat) < 1e-6) {
            cosLat = 1e-6;
        }
        double latStep = spacing / M_PER_DEGREE_LAT;

        // 均匀分布扫描线
        int numSwaths = (int) Math.ceil((maxLat - minLat) / latStep);
        if (numSwaths < 1) {
            numSwaths = 1;
        }
        double actualStep = (maxLat - minLat) / numSwaths;

        List<double[]> path = new ArrayList<>();
        for (int i = 0; i < numSwaths; i++) {
            double lat = minLat + (i + 0.5) * actualStep;
            // 求扫描线（lat = const）与多边形边界的交点经度
            List<Double> intersections = scanLineIntersections(polygon, lat);
            if (intersections.size() < 2) {
                continue;
            }
            // 交点已排序，成对取出形成扫描段
            if (i % 2 == 0) {
                // 偶数行：从低经度到高经度
                for (int j = 0; j + 1 < intersections.size(); j += 2) {
                    path.add(new double[]{lat, intersections.get(j), alt});
                    path.add(new double[]{lat, intersections.get(j + 1), alt});
                }
            } else {
                // 奇数行：从高经度到低经度
                for (int j = intersections.size() - 2; j >= 0; j -= 2) {
                    path.add(new double[]{lat, intersections.get(j + 1), alt});
                    path.add(new double[]{lat, intersections.get(j), alt});
                }
            }
        }
        return path;
    }

    /**
     * Voronoi 分区多机覆盖。
     * <p>
     * 简化实现：按无人机起始经度排序，将 bbox 沿经度方向均分为 N 个子区域，
     * 每架无人机负责一个子区域，各自生成牛耕式路径。
     * <p>
     * 注：这是最近邻分区的简化版本，不依赖外部 Voronoi 库。
     * 子区域并集覆盖整个 bbox，交集最多为边界线（零面积）。
     *
     * @param dronePositions 无人机起始位置列表，每个元素 [lat, lon]
     * @param bbox           边界框 [minLat, maxLat, minLon, maxLon]
     * @param spacing        航线间距（m）
     * @param alt            飞行高度（m）
     * @return 每架无人机的路径列表，result[i] 是第 i 架无人机的路径
     */
    public List<List<double[]>> partitionAndPlan(List<double[]> dronePositions, double[] bbox,
                                                 double spacing, double alt) {
        if (dronePositions == null || dronePositions.isEmpty()) {
            return Collections.emptyList();
        }
        if (bbox == null || bbox.length < 4 || spacing <= 0) {
            return Collections.emptyList();
        }
        double minLat = bbox[0];
        double maxLat = bbox[1];
        double minLon = bbox[2];
        double maxLon = bbox[3];
        if (maxLat <= minLat || maxLon <= minLon) {
            return Collections.emptyList();
        }

        int n = dronePositions.size();
        // 按经度排序，分配子区域
        List<double[]> sorted = new ArrayList<>(dronePositions);
        sorted.sort((a, b) -> Double.compare(a[1], b[1]));

        double lonStep = (maxLon - minLon) / n;
        List<List<double[]>> result = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double subMinLon = minLon + i * lonStep;
            double subMaxLon = minLon + (i + 1) * lonStep;
            double[] subBbox = new double[]{minLat, maxLat, subMinLon, subMaxLon};
            result.add(boustrophedon(subBbox, spacing, alt));
        }
        return result;
    }

    /**
     * 转弯优化：将路径中的尖角转弯替换为圆弧。
     * <p>
     * 算法：
     * <pre>
     * - 遍历路径中每个中间点，计算入射/出射方向与转弯角度
     * - 5° &lt; 转弯角度 &le; 170°：用半径 minTurnRadius 的圆弧替换尖角
     *   圆弧切角距离 d = r * tan(θ/2)，在前后段各切掉 d 长度
     * - 转弯角度 &lt; 5°：视为直行，保留原点
     * - 转弯角度 &gt; 170°（接近 U 型）：保留尖角（简化处理）
     * - 前后段长度不足：保留尖角（保证圆弧半径 = minTurnRadius）
     * </pre>
     * <p>
     * 对 90° 转弯，优化后路径节省 r * (2 - π/2) ≈ 0.43r，路径更短且更平滑。
     *
     * @param rawPath       原始路径，每个元素 [lat, lon, alt]
     * @param minTurnRadius 最小转弯半径（m），必须 &gt; 0
     * @return 优化后路径
     */
    public List<double[]> optimizeTurns(List<double[]> rawPath, double minTurnRadius) {
        if (rawPath == null || rawPath.isEmpty()) {
            return Collections.emptyList();
        }
        if (rawPath.size() < 3 || minTurnRadius <= 0) {
            return new ArrayList<>(rawPath);
        }

        List<double[]> optimized = new ArrayList<>();
        optimized.add(copyPoint(rawPath.get(0)));

        for (int i = 1; i < rawPath.size() - 1; i++) {
            double[] prev = rawPath.get(i - 1);
            double[] curr = rawPath.get(i);
            double[] next = rawPath.get(i + 1);
            double alt = curr.length > 2 ? curr[2] : 0.0;

            double cosLat = Math.cos(Math.toRadians(curr[0]));
            if (Math.abs(cosLat) < 1e-6) {
                cosLat = 1e-6;
            }
            double mPerDegLon = M_PER_DEGREE_LAT * cosLat;

            // 入射向量（米，指向 curr）
            double inE = (curr[1] - prev[1]) * mPerDegLon;
            double inN = (curr[0] - prev[0]) * M_PER_DEGREE_LAT;
            // 出射向量（米，从 curr 出发）
            double outE = (next[1] - curr[1]) * mPerDegLon;
            double outN = (next[0] - curr[0]) * M_PER_DEGREE_LAT;

            double inLen = Math.hypot(inE, inN);
            double outLen = Math.hypot(outE, outN);

            // 零长度段，保留原点
            if (inLen < 1e-9 || outLen < 1e-9) {
                optimized.add(copyPoint(curr));
                continue;
            }

            double inUe = inE / inLen;
            double inUn = inN / inLen;
            double outUe = outE / outLen;
            double outUn = outN / outLen;

            // 转弯角度（inU 和 outU 的夹角）
            double dot = inUe * outUe + inUn * outUn;
            dot = Math.max(-1.0, Math.min(1.0, dot));
            double turnAngle = Math.acos(dot);

            // 角度太小或太大（U 型），保留尖角
            if (turnAngle < MIN_TURN_ANGLE_RAD || turnAngle > MAX_TURN_ANGLE_RAD) {
                optimized.add(copyPoint(curr));
                continue;
            }

            // 切角距离 d = r * tan(θ/2)
            double d = minTurnRadius * Math.tan(turnAngle / 2.0);

            // 前后段长度不足，保留尖角（保证半径 = minTurnRadius）
            if (inLen < d + 1e-6 || outLen < d + 1e-6) {
                optimized.add(copyPoint(curr));
                continue;
            }

            // 转弯方向：cross > 0 左转（逆时针），cross < 0 右转（顺时针）
            double cross = inUe * outUn - inUn * outUe;
            double sign = cross >= 0 ? 1.0 : -1.0;
            // inU 的法线，指向转弯内侧
            double ne = -inUn * sign;
            double nn = inUe * sign;

            // arcStart = curr - d * inU（相对 curr，米）
            double arcStartE = -d * inUe;
            double arcStartN = -d * inUn;
            // arcEnd = curr + d * outU（相对 curr，米）
            double arcEndE = d * outUe;
            double arcEndN = d * outUn;
            // 圆心 = arcStart + r * n（相对 curr，米）
            double centerE = arcStartE + minTurnRadius * ne;
            double centerN = arcStartN + minTurnRadius * nn;

            // 圆弧角度范围
            double startAngle = Math.atan2(arcStartN - centerN, arcStartE - centerE);
            double endAngle = Math.atan2(arcEndN - centerN, arcEndE - centerE);
            double dAngle = endAngle - startAngle;
            if (sign > 0 && dAngle < 0) {
                dAngle += 2 * Math.PI;
            }
            if (sign < 0 && dAngle > 0) {
                dAngle -= 2 * Math.PI;
            }

            // 生成圆弧采样点
            int numSamples = Math.max(4, (int) Math.ceil(turnAngle / Math.toRadians(10.0)));

            // 添加 arcStart
            optimized.add(new double[]{
                    curr[0] + arcStartN / M_PER_DEGREE_LAT,
                    curr[1] + arcStartE / mPerDegLon,
                    alt
            });
            // 添加圆弧中间点
            for (int j = 1; j < numSamples; j++) {
                double t = (double) j / numSamples;
                double a = startAngle + t * dAngle;
                double relE = centerE + minTurnRadius * Math.cos(a);
                double relN = centerN + minTurnRadius * Math.sin(a);
                optimized.add(new double[]{
                        curr[0] + relN / M_PER_DEGREE_LAT,
                        curr[1] + relE / mPerDegLon,
                        alt
                });
            }
            // 添加 arcEnd
            optimized.add(new double[]{
                    curr[0] + arcEndN / M_PER_DEGREE_LAT,
                    curr[1] + arcEndE / mPerDegLon,
                    alt
            });
        }
        optimized.add(copyPoint(rawPath.get(rawPath.size() - 1)));
        return optimized;
    }

    /**
     * 覆盖率计算。
     * <p>
     * 用网格采样法：在 bbox 内生成均匀采样点，检测每个点是否在路径的 swathWidth/2 范围内，
     * 统计被覆盖的比例。
     *
     * @param path       路径点列表，每个元素 [lat, lon, alt]
     * @param bbox       边界框 [minLat, maxLat, minLon, maxLon]
     * @param swathWidth 扫描幅宽（m），路径两侧各覆盖 swathWidth/2
     * @return 覆盖统计信息
     */
    public CoverageStats computeCoverage(List<double[]> path, double[] bbox, double swathWidth) {
        if (bbox == null || bbox.length < 4) {
            return new CoverageStats(0.0, 0.0, 0.0, 0, 0.0);
        }
        double minLat = bbox[0];
        double maxLat = bbox[1];
        double minLon = bbox[2];
        double maxLon = bbox[3];

        double cosLat = Math.cos(Math.toRadians((minLat + maxLat) / 2.0));
        if (Math.abs(cosLat) < 1e-6) {
            cosLat = 1e-6;
        }
        double mPerDegLon = M_PER_DEGREE_LAT * cosLat;

        double widthM = (maxLon - minLon) * mPerDegLon;
        double heightM = (maxLat - minLat) * M_PER_DEGREE_LAT;
        double totalArea = widthM * heightM;

        if (totalArea <= 0 || path == null || path.isEmpty() || swathWidth <= 0) {
            return new CoverageStats(totalArea, 0.0, 0.0, 0, 0.0);
        }

        // 路径总长度
        double totalPathLength = 0.0;
        for (int i = 1; i < path.size(); i++) {
            totalPathLength += distanceM(path.get(i - 1), path.get(i));
        }

        // 扫描线数量（不同纬度值）
        TreeSet<Long> uniqueLats = new TreeSet<>();
        for (double[] p : path) {
            uniqueLats.add(Math.round(p[0] * 1e9));
        }
        int numSwaths = uniqueLats.size();

        // 网格采样
        double sampleStep = Math.min(swathWidth / 4.0, 10.0);
        int latSamples = Math.max(1, (int) Math.floor(heightM / sampleStep));
        int lonSamples = Math.max(1, (int) Math.floor(widthM / sampleStep));
        // 限制总采样数
        if (latSamples * lonSamples > MAX_SAMPLES) {
            double scale = Math.sqrt((double) MAX_SAMPLES / (latSamples * lonSamples));
            latSamples = Math.max(1, (int) (latSamples * scale));
            lonSamples = Math.max(1, (int) (lonSamples * scale));
        }

        double latSampleStep = (maxLat - minLat) / latSamples;
        double lonSampleStep = (maxLon - minLon) / lonSamples;
        double halfSwath = swathWidth / 2.0;
        double halfSwathSq = halfSwath * halfSwath;

        int totalSamples = 0;
        int coveredSamples = 0;
        for (int i = 0; i < latSamples; i++) {
            double lat = minLat + (i + 0.5) * latSampleStep;
            for (int j = 0; j < lonSamples; j++) {
                double lon = minLon + (j + 0.5) * lonSampleStep;
                totalSamples++;
                if (isCovered(lat, lon, path, halfSwathSq, mPerDegLon)) {
                    coveredSamples++;
                }
            }
        }

        double coverageRatio = totalSamples > 0 ? (double) coveredSamples / totalSamples : 0.0;
        double coveredArea = totalArea * coverageRatio;
        return new CoverageStats(totalArea, coveredArea, coverageRatio, numSwaths, totalPathLength);
    }

    /**
     * 遗漏区域检测。
     * <p>
     * 在 bbox 内生成采样点，返回未覆盖的点列表（简化：返回未覆盖采样点，限制数量）。
     *
     * @param path       路径点列表
     * @param bbox       边界框
     * @param swathWidth 扫描幅宽（m）
     * @return 未覆盖点列表，每个元素 [lat, lon]；全覆盖返回空列表
     */
    public List<double[]> detectGaps(List<double[]> path, double[] bbox, double swathWidth) {
        if (bbox == null || bbox.length < 4 || swathWidth <= 0) {
            return Collections.emptyList();
        }
        double minLat = bbox[0];
        double maxLat = bbox[1];
        double minLon = bbox[2];
        double maxLon = bbox[3];
        if (maxLat <= minLat || maxLon <= minLon) {
            return Collections.emptyList();
        }
        if (path == null || path.isEmpty()) {
            // 整个区域都是遗漏
            return Collections.singletonList(new double[]{(minLat + maxLat) / 2.0, (minLon + maxLon) / 2.0});
        }

        double cosLat = Math.cos(Math.toRadians((minLat + maxLat) / 2.0));
        if (Math.abs(cosLat) < 1e-6) {
            cosLat = 1e-6;
        }
        double mPerDegLon = M_PER_DEGREE_LAT * cosLat;
        double widthM = (maxLon - minLon) * mPerDegLon;
        double heightM = (maxLat - minLat) * M_PER_DEGREE_LAT;

        double sampleStep = Math.min(swathWidth / 4.0, 10.0);
        int latSamples = Math.max(1, (int) Math.floor(heightM / sampleStep));
        int lonSamples = Math.max(1, (int) Math.floor(widthM / sampleStep));
        if (latSamples * lonSamples > MAX_SAMPLES) {
            double scale = Math.sqrt((double) MAX_SAMPLES / (latSamples * lonSamples));
            latSamples = Math.max(1, (int) (latSamples * scale));
            lonSamples = Math.max(1, (int) (lonSamples * scale));
        }

        double latSampleStep = (maxLat - minLat) / latSamples;
        double lonSampleStep = (maxLon - minLon) / lonSamples;
        double halfSwath = swathWidth / 2.0;
        double halfSwathSq = halfSwath * halfSwath;

        List<double[]> gaps = new ArrayList<>();
        int gapLimit = 1000; // 限制返回点数
        for (int i = 0; i < latSamples && gaps.size() < gapLimit; i++) {
            double lat = minLat + (i + 0.5) * latSampleStep;
            for (int j = 0; j < lonSamples && gaps.size() < gapLimit; j++) {
                double lon = minLon + (j + 0.5) * lonSampleStep;
                if (!isCovered(lat, lon, path, halfSwathSq, mPerDegLon)) {
                    gaps.add(new double[]{lat, lon});
                }
            }
        }
        return gaps;
    }

    // ==================== 内部辅助方法 ====================

    /**
     * 计算扫描线（lat = const）与多边形边界的交点经度。
     *
     * @param polygon 多边形顶点列表
     * @param lat     扫描线纬度
     * @return 排序后的交点经度列表
     */
    private List<Double> scanLineIntersections(List<double[]> polygon, double lat) {
        List<Double> intersections = new ArrayList<>();
        int n = polygon.size();
        for (int i = 0; i < n; i++) {
            double[] p1 = polygon.get(i);
            double[] p2 = polygon.get((i + 1) % n);
            double lat1 = p1[0];
            double lat2 = p2[0];
            double lon1 = p1[1];
            double lon2 = p2[1];
            // 边跨越扫描线（半开区间避免顶点重复计数）
            if ((lat1 <= lat && lat2 > lat) || (lat2 <= lat && lat1 > lat)) {
                double t = (lat - lat1) / (lat2 - lat1);
                intersections.add(lon1 + t * (lon2 - lon1));
            }
        }
        Collections.sort(intersections);
        return intersections;
    }

    /**
     * 检测点是否被路径覆盖（到任一路径段距离 <= swathWidth/2）。
     *
     * @param lat          点纬度
     * @param lon          点经度
     * @param path         路径点列表
     * @param halfSwathSq  (swathWidth/2)^2（m^2）
     * @param mPerDegLon   1 度经度对应的距离（m）
     * @return true 如果被覆盖
     */
    private boolean isCovered(double lat, double lon, List<double[]> path,
                              double halfSwathSq, double mPerDegLon) {
        for (int i = 1; i < path.size(); i++) {
            if (distPointToSegmentSqM(lat, lon, path.get(i - 1), path.get(i), mPerDegLon) <= halfSwathSq) {
                return true;
            }
        }
        return false;
    }

    /**
     * 点到线段的距离平方（米坐标系下）。
     *
     * @param lat       点纬度
     * @param lon       点经度
     * @param p1        线段起点 [lat, lon, ...]
     * @param p2        线段终点 [lat, lon, ...]
     * @param mPerDegLon 1 度经度对应的距离（m）
     * @return 距离平方（m^2）
     */
    private double distPointToSegmentSqM(double lat, double lon, double[] p1, double[] p2,
                                         double mPerDegLon) {
        // 转换到米坐标系（以 p1 为原点）
        double pe = (lon - p1[1]) * mPerDegLon;
        double pn = (lat - p1[0]) * M_PER_DEGREE_LAT;
        double se = (p2[1] - p1[1]) * mPerDegLon;
        double sn = (p2[0] - p1[0]) * M_PER_DEGREE_LAT;
        double segLenSq = se * se + sn * sn;
        if (segLenSq < 1e-18) {
            // 退化线段，返回点到 p1 的距离平方
            return pe * pe + pn * pn;
        }
        // 投影参数 t
        double t = (pe * se + pn * sn) / segLenSq;
        if (t < 0.0) {
            t = 0.0;
        } else if (t > 1.0) {
            t = 1.0;
        }
        double closestE = t * se;
        double closestN = t * sn;
        double dx = pe - closestE;
        double dy = pn - closestN;
        return dx * dx + dy * dy;
    }

    /**
     * 两点间距离（米，平面近似）。
     *
     * @param p1 [lat, lon, ...]
     * @param p2 [lat, lon, ...]
     * @return 距离（m）
     */
    private double distanceM(double[] p1, double[] p2) {
        double cosLat = Math.cos(Math.toRadians((p1[0] + p2[0]) / 2.0));
        if (Math.abs(cosLat) < 1e-6) {
            cosLat = 1e-6;
        }
        double dLat = (p2[0] - p1[0]) * M_PER_DEGREE_LAT;
        double dLon = (p2[1] - p1[1]) * M_PER_DEGREE_LAT * cosLat;
        return Math.hypot(dLat, dLon);
    }

    /**
     * 复制路径点（保证不可变）。
     *
     * @param p 原始点
     * @return 副本
     */
    private double[] copyPoint(double[] p) {
        return p.clone();
    }

    /**
     * 覆盖统计信息。
     */
    public static class CoverageStats {
        /** 总面积（m^2）。 */
        public final double totalArea;
        /** 已覆盖面积（m^2）。 */
        public final double coveredArea;
        /** 覆盖率（0-1）。 */
        public final double coverageRatio;
        /** 扫描线数量。 */
        public final int numSwaths;
        /** 路径总长度（m）。 */
        public final double totalPathLength;

        /**
         * 构造覆盖统计。
         *
         * @param totalArea       总面积（m^2）
         * @param coveredArea     已覆盖面积（m^2）
         * @param coverageRatio   覆盖率（0-1）
         * @param numSwaths       扫描线数量
         * @param totalPathLength 路径总长度（m）
         */
        public CoverageStats(double totalArea, double coveredArea, double coverageRatio,
                             int numSwaths, double totalPathLength) {
            this.totalArea = totalArea;
            this.coveredArea = coveredArea;
            this.coverageRatio = coverageRatio;
            this.numSwaths = numSwaths;
            this.totalPathLength = totalPathLength;
        }

        @Override
        public String toString() {
            return "CoverageStats{totalArea=" + String.format("%.1f", totalArea) + " m²"
                    + ", coveredArea=" + String.format("%.1f", coveredArea) + " m²"
                    + ", coverageRatio=" + String.format("%.4f", coverageRatio)
                    + ", numSwaths=" + numSwaths
                    + ", totalPathLength=" + String.format("%.1f", totalPathLength) + " m}";
        }
    }
}
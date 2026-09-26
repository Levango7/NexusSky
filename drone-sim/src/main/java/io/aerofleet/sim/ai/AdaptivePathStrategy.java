package io.aerofleet.sim.ai;

import io.aerofleet.sim.GeoUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * M11 自适应航线策略。
 * <p>
 * 保留旧接口 {@link #evaluate(double, double)} 供 {@link DecisionEngine} 调用（向后兼容）。
 * 新增真实自适应航线算法，包含三大能力：
 * <ol>
 *   <li><b>风补偿</b>：{@link #computeWindCorrectedHeading} 基于风修正角（WCA）公式
 *       调整航向，使无人机在侧风/逆风下仍能沿期望航线飞行。
 *       WCA = arcsin(windSpeed · sin(windDir − course) / airspeed)。</li>
 *   <li><b>能耗优化</b>：{@link #computeEnergyOptimalSpeed} 根据逆风/顺风/低电量
 *       动态调整巡航速度，能耗模型 ∝ v³ + headwind·v²。</li>
 *   <li><b>Dubins 曲线平滑</b>：{@link #smoothPathWithDubins} 对路径中尖角转弯
 *       用最小转弯半径圆弧（CSC 形式的简化）替换，生成可飞路径。</li>
 * </ol>
 * 综合接口 {@link #adaptPath} 串联三者，输出 {@link AdaptivePathResult}：
 * 修正路径 + 各段速度 + 各段航向 + 总能耗估算。
 * <p>
 * 注意：drone-sim 模块使用 SLF4J Logger 输出日志。
 */
public class AdaptivePathStrategy {

    private static final Logger log = LoggerFactory.getLogger(AdaptivePathStrategy.class);

    private static final double WIND_THRESHOLD = 8.0; // m/s，旧接口强风阈值

    // ====== 风补偿参数 ======
    /** 风速 ≥ 空速时的安全最大修正角（度），避免 arcsin 参数 >1 产生 NaN */
    private static final double MAX_WCA_DEG = 90.0;

    // ====== 能耗优化参数 ======
    private static final double LOW_BATTERY_THRESHOLD = 40.0;   // 低电量阈值 %
    private static final double LOW_BATTERY_SPEED_FACTOR = 0.7; // 低电量降速系数
    private static final double HEADWIND_SPEED_REDUCTION = 0.3; // 逆风降速系数
    private static final double TAILWIND_SPEED_BOOST = 0.2;     // 顺风提速系数
    private static final double MIN_SPEED_FACTOR = 0.6;         // 最小速度系数（逆风）
    private static final double MAX_SPEED_FACTOR = 1.2;         // 最大速度系数（顺风）

    // ====== Dubins 平滑参数 ======
    /** 转向角阈值（度），绝对值超过此值的转角用圆弧替换 */
    private static final double SHARP_TURN_THRESHOLD_DEG = 5.0;
    /** 每段圆弧采样点数 */
    private static final int ARC_SAMPLES = 8;
    /** 切线长度占相邻段长度的上限（避免切点越过邻居） */
    private static final double TANGENT_LEN_FRACTION = 0.45;

    // ----------------------------------------------------------------------
    // 旧接口（向后兼容 DecisionEngine 调用）
    // ----------------------------------------------------------------------

    /**
     * 简单自适应决策（无路径规划）。
     * <p>保留原 M11 逻辑：强风 → ADAPT_PATH(strong wind, 0.6)；
     * 低电量 → ADAPT_PATH(battery optimization, 0.5)；否则返回 null。
     */
    public DecisionResult evaluate(double windSpeed, double battery) {
        if (windSpeed > WIND_THRESHOLD) {
            return new DecisionResult("ADAPT_PATH", "strong wind", windSpeed, 0.6);
        }
        if (battery < 40.0) {
            return new DecisionResult("ADAPT_PATH", "battery optimization", battery, 0.5);
        }
        return null;
    }

    // ----------------------------------------------------------------------
    // 风补偿
    // ----------------------------------------------------------------------

    /**
     * 计算风修正航向（Wind Correction Angle, WCA）。
     * <p>
     * WCA = arcsin(windSpeed · sin(windDirection − courseHeading) / airspeed)
     * <br>修正后航向 = courseHeading + WCA
     * <p>
     * 安全处理：
     * <ul>
     *   <li>airspeed ≤ 0 或 windSpeed ≤ 0：返回归一化的 courseHeading（不修正）</li>
     *   <li>|ratio| ≥ 1（风速 ≥ 空速的侧风分量）：限制 WCA 到 ±90°，
     *       避免 arcsin 产生 NaN。表示无人机无法完全对抗侧风，最大修正到与风正交方向。</li>
     * </ul>
     *
     * @param courseHeading 期望航向（度，0=正北，顺时针）
     * @param windSpeed     风速（m/s，≥0）
     * @param windDirection 风向（度，0=正北，顺时针，风吹来的方向）
     * @param airspeed      空速（m/s，>0）
     * @return 修正后航向（度，归一化到 [0, 360)）
     */
    public double computeWindCorrectedHeading(double courseHeading, double windSpeed,
                                              double windDirection, double airspeed) {
        // 无风或无空速：航向不变
        if (windSpeed <= 0.0 || airspeed <= 0.0) {
            return normalizeAngle(courseHeading);
        }

        // 风修正角参数：sin(windDirection - courseHeading) * windSpeed / airspeed
        double crossAngle = Math.toRadians(windDirection - courseHeading);
        double ratio = windSpeed * Math.sin(crossAngle) / airspeed;

        double wcaDeg;
        if (ratio >= 1.0) {
            // 侧风分量 ≥ 空速：最大修正 +90°
            wcaDeg = MAX_WCA_DEG;
        } else if (ratio <= -1.0) {
            // 最大修正 -90°
            wcaDeg = -MAX_WCA_DEG;
        } else {
            wcaDeg = Math.toDegrees(Math.asin(ratio));
        }

        return normalizeAngle(courseHeading + wcaDeg);
    }

    // ----------------------------------------------------------------------
    // 能耗优化
    // ----------------------------------------------------------------------

    /**
     * 计算能耗最优巡航速度。
     * <p>
     * 能耗模型：E ∝ v³ + headwind·v²（v 为空速，headwind 为逆风分量，顺风时 headwind<0）。
     * <ul>
     *   <li><b>低电量</b>（batteryPct &lt; 40）：降速到 baseSpeed × 0.7 节能（最高优先级）</li>
     *   <li><b>逆风</b>（headwind &gt; 0）：降速，factor = 1 − 0.3·headwind/baseSpeed，
     *       下限 baseSpeed × 0.6</li>
     *   <li><b>顺风</b>（headwind &lt; 0）：提速，factor = 1 + 0.2·|headwind|/baseSpeed，
     *       上限 baseSpeed × 1.2</li>
     *   <li><b>无风</b>：保持 baseSpeed</li>
     * </ul>
     *
     * @param distance   剩余航段距离（m，预留参数，当前未使用）
     * @param batteryPct 当前电量百分比（0~100）
     * @param headwind   逆风分量（m/s，>0 逆风，<0 顺风）
     * @param baseSpeed  基础巡航速度（m/s，>0）
     * @return 最优巡航速度（m/s，≥0）；baseSpeed ≤ 0 时返回 0
     */
    public double computeEnergyOptimalSpeed(double distance, double batteryPct,
                                            double headwind, double baseSpeed) {
        if (baseSpeed <= 0.0) {
            return 0.0;
        }

        // 1. 低电量优先：降速到节能速度
        if (batteryPct < LOW_BATTERY_THRESHOLD) {
            return baseSpeed * LOW_BATTERY_SPEED_FACTOR;
        }

        // 2. 逆风降速 / 顺风提速
        double speed;
        if (headwind > 0.0) {
            // 逆风：降速，下限 baseSpeed * 0.6
            double factor = 1.0 - HEADWIND_SPEED_REDUCTION * headwind / baseSpeed;
            factor = Math.max(factor, MIN_SPEED_FACTOR);
            speed = baseSpeed * factor;
        } else if (headwind < 0.0) {
            // 顺风：提速，上限 baseSpeed * 1.2
            double factor = 1.0 + TAILWIND_SPEED_BOOST * (-headwind) / baseSpeed;
            factor = Math.min(factor, MAX_SPEED_FACTOR);
            speed = baseSpeed * factor;
        } else {
            // 无风
            speed = baseSpeed;
        }

        return speed;
    }

    // ----------------------------------------------------------------------
    // Dubins 曲线平滑
    // ----------------------------------------------------------------------

    /**
     * 用 Dubins 曲线（最小转弯半径圆弧）平滑路径中的尖角转弯。
     * <p>
     * 简化实现（CSC 形式的 C 段）：遍历每个内部转角（三个连续点 P_{i-1}, P_i, P_{i+1}），
     * 计算转向角。若 |转向角| &gt; 5°，则在 P_i 附近用圆弧（半径 = turnRadius）替换 P_i，
     * 圆弧采样 {@value #ARC_SAMPLES} 个点。
     * <p>
     * 圆弧几何：切线长度 t = R·tan(θ/2)，切点 start = P_i − in·t，end = P_i + out·t，
     * 圆心 O = start + R·n̂（n̂ 为入射方向法向，指向转角内侧）。
     * <p>
     * 直线路径（所有转角 ≤ 5°）原样返回（副本）。
     *
     * @param path       原始路径点列表 {@code [lat, lon, alt]}
     * @param turnRadius 最小转弯半径（米，>0）
     * @return 平滑后路径点列表；输入为 null 返回空列表，≤2 点返回副本；
     *         turnRadius ≤ 0 返回副本
     */
    public List<double[]> smoothPathWithDubins(List<double[]> path, double turnRadius) {
        if (path == null || path.size() <= 2) {
            return path == null ? Collections.emptyList() : new ArrayList<>(path);
        }
        if (turnRadius <= 0.0) {
            return new ArrayList<>(path);
        }

        List<double[]> smoothed = new ArrayList<>();
        smoothed.add(copyPoint(path.get(0)));

        for (int i = 1; i < path.size() - 1; i++) {
            double[] prev = path.get(i - 1);
            double[] curr = path.get(i);
            double[] next = path.get(i + 1);

            double turnAngleDeg = computeTurnAngle(prev, curr, next);

            if (Math.abs(turnAngleDeg) > SHARP_TURN_THRESHOLD_DEG) {
                // 尖角转弯：用圆弧替换 curr
                List<double[]> arc = dubinsArc(prev, curr, next, turnRadius);
                smoothed.addAll(arc);
            } else {
                // 小转角：保留原点
                smoothed.add(copyPoint(curr));
            }
        }

        smoothed.add(copyPoint(path.get(path.size() - 1)));
        return smoothed;
    }

    // ----------------------------------------------------------------------
    // 综合自适应
    // ----------------------------------------------------------------------

    /**
     * 综合自适应航线：风补偿 + 能耗优化 + Dubins 平滑。
     * <p>
     * 流程：
     * <ol>
     *   <li>用 {@link #smoothPathWithDubins} 平滑原始路径（消除尖角）</li>
     *   <li>对平滑后路径的每个航段：
     *     <ul>
     *       <li>计算期望航向（atan2(east, north)）</li>
     *       <li>用 {@link #computeWindCorrectedHeading} 风修正</li>
     *       <li>计算逆风分量 headwind = windSpeed·cos(windDir − correctedHeading)</li>
     *       <li>用 {@link #computeEnergyOptimalSpeed} 调整速度</li>
     *       <li>累加能耗 E = (v² + headwind·v)·segLen</li>
     *     </ul>
     *   </li>
     * </ol>
     *
     * @param originalPath  原始路径点列表 {@code [lat, lon, alt]}，至少 2 点
     * @param windSpeed     风速（m/s，≥0）
     * @param windDirection 风向（度，0=正北，顺时针）
     * @param airspeed      空速（m/s，>0）
     * @param batteryPct    电量百分比（0~100）
     * @param turnRadius    最小转弯半径（米，>0）
     * @return {@link AdaptivePathResult}；输入非法（null 或 <2 点）时返回空结果
     */
    public AdaptivePathResult adaptPath(List<double[]> originalPath,
                                        double windSpeed, double windDirection,
                                        double airspeed, double batteryPct,
                                        double turnRadius) {
        if (originalPath == null || originalPath.size() < 2) {
            return new AdaptivePathResult(
                    Collections.emptyList(), Collections.emptyList(),
                    Collections.emptyList(), 0.0);
        }

        // 1. 先用 Dubins 平滑原始路径
        List<double[]> smoothedPath = smoothPathWithDubins(originalPath, turnRadius);

        // 2. 逐段计算风修正航向 + 速度 + 能耗
        int segCount = smoothedPath.size() - 1;
        List<Double> segmentSpeeds = new ArrayList<>(segCount);
        List<Double> correctedHeadings = new ArrayList<>(segCount);
        double totalEnergy = 0.0;

        for (int i = 1; i < smoothedPath.size(); i++) {
            double[] a = smoothedPath.get(i - 1);
            double[] b = smoothedPath.get(i);

            // 航段距离与期望航向（本地平面坐标）
            double segNorth = GeoUtil.north(a[0], a[1], b[0], b[1]);
            double segEast = GeoUtil.east(a[0], a[1], b[0], b[1]);
            double segLen = Math.hypot(segNorth, segEast);

            if (segLen < 1e-9) {
                // 零长度段：速度 0，航向 0
                segmentSpeeds.add(0.0);
                correctedHeadings.add(0.0);
                continue;
            }

            // 期望航向：atan2(east, north)，0=正北，顺时针
            double courseHeading = Math.toDegrees(Math.atan2(segEast, segNorth));
            if (courseHeading < 0) {
                courseHeading += 360.0;
            }

            // 风修正航向
            double correctedHeading = computeWindCorrectedHeading(
                    courseHeading, windSpeed, windDirection, airspeed);
            correctedHeadings.add(correctedHeading);

            // 逆风分量：风从 windDirection 吹来，与航向 correctedHeading 的夹角
            // headwind = windSpeed * cos(windDir − heading)，正值逆风
            double windDiff = Math.toRadians(windDirection - correctedHeading);
            double headwind = windSpeed * Math.cos(windDiff);

            // 能耗最优速度
            double speed = computeEnergyOptimalSpeed(segLen, batteryPct, headwind, airspeed);
            segmentSpeeds.add(speed);

            // 能耗估算：E = (v³ + headwind·v²) · dt, dt = segLen / v
            // 简化为 E = (v² + headwind·v) · segLen（每米能耗 × 距离）
            if (speed > 0.0) {
                double energy = (speed * speed + headwind * speed) * segLen;
                totalEnergy += Math.max(0.0, energy);
            }
        }

        log.debug("[AdaptivePath] adaptPath: segments={} windSpeed={} battery={} totalEnergy={}",
                segCount, windSpeed, batteryPct, String.format("%.2f", totalEnergy));

        return new AdaptivePathResult(smoothedPath, segmentSpeeds,
                correctedHeadings, totalEnergy);
    }

    // ----------------------------------------------------------------------
    // 内部工具方法
    // ----------------------------------------------------------------------

    /** 将角度归一化到 [0, 360) */
    private static double normalizeAngle(double angle) {
        double a = angle % 360.0;
        if (a < 0) {
            a += 360.0;
        }
        return a;
    }

    /**
     * 计算转向角（在 curr 处，从 prev→curr 转向 curr→next）。
     * <p>返回转向角（度），正值右转，负值左转，0° 表示直行。
     * 退化情况（任一相邻段长度 < 1e-9）返回 0。
     */
    private static double computeTurnAngle(double[] prev, double[] curr, double[] next) {
        // 本地坐标（以 curr 为原点）
        double v1n = GeoUtil.north(curr[0], curr[1], prev[0], prev[1]);
        double v1e = GeoUtil.east(curr[0], curr[1], prev[0], prev[1]);
        double v2n = GeoUtil.north(curr[0], curr[1], next[0], next[1]);
        double v2e = GeoUtil.east(curr[0], curr[1], next[0], next[1]);

        double len1 = Math.hypot(v1n, v1e);
        double len2 = Math.hypot(v2n, v2e);
        if (len1 < 1e-9 || len2 < 1e-9) {
            return 0.0;
        }

        // 入射方向（curr→prev 反向 = prev→curr 方向）
        double inN = -v1n / len1, inE = -v1e / len1;
        // 出射方向（curr→next）
        double outN = v2n / len2, outE = v2e / len2;

        // 夹角 = atan2(叉积, 点积)，叉积 z = inN·outE − inE·outN
        double cross = inN * outE - inE * outN;
        double dot = inN * outN + inE * outE;
        return Math.toDegrees(Math.atan2(cross, dot));
    }

    /**
     * 在 curr 处用 Dubins 圆弧连接 prev→curr→next。
     * <p>圆弧几何（CSC 形式的单 C 段）：
     * <ul>
     *   <li>入射方向 in（prev→curr 单位向量），出射方向 out（curr→next 单位向量）</li>
     *   <li>转角 θ = acos(in·out)</li>
     *   <li>切线长度 t = R·tan(θ/2)，限制不超过相邻段长度的 {@value #TANGENT_LEN_FRACTION}</li>
     *   <li>切点 start = curr − in·t，end = curr + out·t（本地坐标）</li>
     *   <li>圆心 O = start + R·n̂，n̂ 为入射方向法向（指向转角内侧）</li>
     *   <li>圆弧从 start 到 end 绕 O 采样 {@value #ARC_SAMPLES} 个点</li>
     * </ul>
     * 退化情况（相邻段过短、转角过小、切线长度≈0）直接返回 curr 单点。
     */
    private static List<double[]> dubinsArc(double[] prev, double[] curr, double[] next,
                                            double turnRadius) {
        List<double[]> arc = new ArrayList<>(ARC_SAMPLES + 1);

        // 本地坐标（以 curr 为原点）
        double v1n = GeoUtil.north(curr[0], curr[1], prev[0], prev[1]);
        double v1e = GeoUtil.east(curr[0], curr[1], prev[0], prev[1]);
        double v2n = GeoUtil.north(curr[0], curr[1], next[0], next[1]);
        double v2e = GeoUtil.east(curr[0], curr[1], next[0], next[1]);

        double len1 = Math.hypot(v1n, v1e);
        double len2 = Math.hypot(v2n, v2e);

        if (len1 < 1e-9 || len2 < 1e-9) {
            arc.add(copyPoint(curr));
            return arc;
        }

        // 入射方向（prev→curr）和出射方向（curr→next）
        double inN = -v1n / len1, inE = -v1e / len1;
        double outN = v2n / len2, outE = v2e / len2;

        // 转角（叉积判断左右转，点积算角度）
        double cross = inN * outE - inE * outN;
        double dot = inN * outN + inE * outE;
        dot = Math.max(-1.0, Math.min(1.0, dot));
        double turnAngle = Math.acos(dot); // 0~π

        if (turnAngle < Math.toRadians(SHARP_TURN_THRESHOLD_DEG)) {
            arc.add(copyPoint(curr));
            return arc;
        }

        // 切线长度 t = R * tan(θ/2)
        double tangentLen = turnRadius * Math.tan(turnAngle / 2.0);
        // 限制切线长度不超过相邻段的 TANGENT_LEN_FRACTION，避免切点越过 prev/next
        tangentLen = Math.min(tangentLen, len1 * TANGENT_LEN_FRACTION);
        tangentLen = Math.min(tangentLen, len2 * TANGENT_LEN_FRACTION);

        if (tangentLen < 1e-9) {
            arc.add(copyPoint(curr));
            return arc;
        }

        // 切点（本地坐标，以 curr 为原点）
        double startN = -inN * tangentLen;
        double startE = -inE * tangentLen;
        double endN = outN * tangentLen;
        double endE = outE * tangentLen;

        // 圆弧圆心：从 start 沿入射方向法向走 R
        // 在 (north, east) 坐标系中：
        //   右转（cross > 0，无人机视角）：法向 = (-inE, inN)（入射方向逆时针旋转 90°）
        //   左转（cross < 0）：法向 = (inE, -inN)（入射方向顺时针旋转 90°）
        double normalN, normalE;
        if (cross >= 0) {
            // 右转：法向 = (-inE, inN)
            normalN = -inE;
            normalE = inN;
        } else {
            // 左转：法向 = (inE, -inN)
            normalN = inE;
            normalE = -inN;
        }
        double centerN = startN + turnRadius * normalN;
        double centerE = startE + turnRadius * normalE;

        // 起始角和终止角（从圆心到切点的极角）
        double startAngle = Math.atan2(startE - centerE, startN - centerN);
        double endAngle = Math.atan2(endE - centerE, endN - centerN);

        // 选择短弧方向
        // 在 (north, east) 坐标系中（数学上 y=north, x=east）：
        //   右转（无人机视角）= 数学逆时针 = 角度递增
        //   左转（无人机视角）= 数学顺时针 = 角度递减
        double deltaAngle = endAngle - startAngle;
        if (cross >= 0) {
            // 右转：逆时针（角度递增），取 [0, π] 范围
            while (deltaAngle < 0) {
                deltaAngle += 2 * Math.PI;
            }
            if (deltaAngle > Math.PI) {
                deltaAngle -= 2 * Math.PI;
            }
        } else {
            // 左转：顺时针（角度递减），取 [-π, 0] 范围
            while (deltaAngle > 0) {
                deltaAngle -= 2 * Math.PI;
            }
            if (deltaAngle < -Math.PI) {
                deltaAngle += 2 * Math.PI;
            }
        }

        // 采样圆弧
        for (int s = 0; s <= ARC_SAMPLES; s++) {
            double t = (double) s / ARC_SAMPLES;
            double angle = startAngle + deltaAngle * t;
            double n = centerN + turnRadius * Math.cos(angle);
            double e = centerE + turnRadius * Math.sin(angle);
            arc.add(new double[]{
                    GeoUtil.latOf(curr[0], curr[1], n, e),
                    GeoUtil.lonOf(curr[0], curr[1], n, e),
                    curr[2]
            });
        }

        return arc;
    }

    /** 复制路径点（防御性拷贝，避免外部修改） */
    private static double[] copyPoint(double[] p) {
        return new double[]{p[0], p[1], p[2]};
    }
}

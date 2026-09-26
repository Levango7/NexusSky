package io.aerofleet.sim.ai;

import io.aerofleet.sim.GeoUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;

/**
 * 路径规划器：实现 A* 网格搜索与 RRT 快速扩展随机树两种算法。
 * <p>
 * 两种算法在本地切平面坐标系（米）下工作，输入输出均为经纬度（+高度）。
 * 坐标转换委托 {@link GeoUtil}，对模拟器尺度（数公里以内）精度足够。
 * <p>
 * <b>A* 网格搜索</b>：将连续空间离散化为正方形网格，8 方向移动（含对角线），
 * 代价函数 g(n)=起点到 n 的实际代价，h(n)=n 到终点的欧氏距离启发。
 * 适合低维（2D）已知地图的全局最优路径规划。
 * <p>
 * <b>RRT 快速扩展随机树</b>：连续空间随机采样 → 找最近树节点 → 向采样点方向
 * 扩展固定步长 → 碰撞检测 → 加入树；到目标距离小于阈值时连接目标。
 * 适合高维 / 复杂障碍 / 动态环境，不保证最优但收敛快。
 * <p>
 * 注意：drone-sim 模块使用 SLF4J Logger 输出日志。
 */
public class PathPlanner {

    private static final Logger log = LoggerFactory.getLogger(PathPlanner.class);

    // ====== 公共参数 ======
    private static final double DEFAULT_GRID_RESOLUTION = 5.0;   // A* 默认网格分辨率 5m
    private static final int    MAX_GRID_DIM             = 256;  // A* 网格单边最大格数（防内存爆炸）
    private static final double GRID_PADDING             = 10.0; // 边界外扩 10m

    private static final int    RRT_MAX_NODES     = 5000; // RRT 最大节点数
    private static final double RRT_STEP_SIZE     = 10.0; // RRT 扩展步长 10m
    private static final double RRT_GOAL_BIAS     = 0.1;  // RRT 目标偏置概率
    private static final double RRT_GOAL_THRESHOLD = 15.0; // RRT 到目标连接阈值（1.5×步长）

    // RRT 随机数生成器：固定种子保证可重复性（测试稳定 + 生产可复现）
    private final Random rng = new Random(42L);

    // ====== 障碍物模型 ======

    /**
     * 圆柱体障碍物（lat/lon 中心 + 半径，alt 为顶部高度，地面以上 0~alt 范围内不可通行）。
     * <p>对 2D A* 仅用 lat/lon/radius；对 3D RRT 额外检查 alt。
     */
    public static final class Obstacle {
        public final double lat, lon, alt, radius;

        public Obstacle(double lat, double lon, double alt, double radius) {
            this.lat = lat;
            this.lon = lon;
            this.alt = alt;
            this.radius = radius;
        }
    }

    // ======================================================================
    // A* 网格搜索
    // ======================================================================

    /**
     * A* 网格搜索路径规划（2D，高度取起点与终点线性插值）。
     *
     * @param startLat  起点纬度
     * @param startLon  起点经度
     * @param goalLat   终点纬度
     * @param goalLon   终点经度
     * @param obstacles 圆柱体障碍物列表（可为空）
     * @param gridResolution 网格分辨率（米），&lt;=0 时用默认 5m
     * @return 路径点列表 {@code List<double[]{lat, lon, alt}}，起点→终点；
     *         无可行路径或输入非法时返回空列表
     */
    public List<double[]> planAStar(double startLat, double startLon,
                                     double goalLat, double goalLon,
                                     List<Obstacle> obstacles, double gridResolution) {
        if (gridResolution <= 0) {
            gridResolution = DEFAULT_GRID_RESOLUTION;
        }
        if (obstacles == null) {
            obstacles = Collections.emptyList();
        }
        // 起点等于终点：直接返回单点
        if (startLat == goalLat && startLon == goalLon) {
            return new ArrayList<>(Arrays.asList(new double[]{startLat, startLon, 0.0}));
        }

        // 以起点为参考建立本地平面坐标系（米）
        double refLat = startLat, refLon = startLon;
        double startN = 0.0, startE = 0.0;
        double goalN = GeoUtil.north(refLat, refLon, goalLat, goalLon);
        double goalE = GeoUtil.east(refLat, refLon, goalLat, goalLon);

        // 障碍物转本地坐标
        double[][] obsLocal = new double[obstacles.size()][3]; // [n, e, r]
        for (int i = 0; i < obstacles.size(); i++) {
            Obstacle o = obstacles.get(i);
            obsLocal[i][0] = GeoUtil.north(refLat, refLon, o.lat, o.lon);
            obsLocal[i][1] = GeoUtil.east(refLat, refLon, o.lat, o.lon);
            obsLocal[i][2] = o.radius;
        }

        // 计算边界框（包含起点、终点、所有障碍物外接圆）
        double minN = Math.min(startN, goalN);
        double maxN = Math.max(startN, goalN);
        double minE = Math.min(startE, goalE);
        double maxE = Math.max(startE, goalE);
        for (double[] o : obsLocal) {
            minN = Math.min(minN, o[0] - o[2]);
            maxN = Math.max(maxN, o[0] + o[2]);
            minE = Math.min(minE, o[1] - o[2]);
            maxE = Math.max(maxE, o[1] + o[2]);
        }
        // 外扩 padding，给绕行留空间
        minN -= GRID_PADDING; maxN += GRID_PADDING;
        minE -= GRID_PADDING; maxE += GRID_PADDING;

        // 离散化网格尺寸（自动调整分辨率防超过 MAX_GRID_DIM）
        double spanN = maxN - minN;
        double spanE = maxE - minE;
        int cellsN = (int) Math.ceil(spanN / gridResolution);
        int cellsE = (int) Math.ceil(spanE / gridResolution);
        if (cellsN > MAX_GRID_DIM || cellsE > MAX_GRID_DIM) {
            double scale = Math.max((double) cellsN / MAX_GRID_DIM, (double) cellsE / MAX_GRID_DIM);
            gridResolution *= scale;
            cellsN = (int) Math.ceil(spanN / gridResolution);
            cellsE = (int) Math.ceil(spanE / gridResolution);
            // 再保险一次
            cellsN = Math.min(cellsN, MAX_GRID_DIM);
            cellsE = Math.min(cellsE, MAX_GRID_DIM);
        }
        if (cellsN <= 0 || cellsE <= 0) {
            return Collections.emptyList();
        }

        // 标记障碍物网格为不可通行
        boolean[][] blocked = new boolean[cellsN][cellsE];
        for (double[] o : obsLocal) {
            // 障碍物圆 + 安全余量（1 格）外扩
            int n0 = toCellIdx(o[0] - o[2] - gridResolution, minN, gridResolution, cellsN);
            int n1 = toCellIdx(o[0] + o[2] + gridResolution, minN, gridResolution, cellsN);
            int e0 = toCellIdx(o[1] - o[2] - gridResolution, minE, gridResolution, cellsE);
            int e1 = toCellIdx(o[1] + o[2] + gridResolution, minE, gridResolution, cellsE);
            for (int n = n0; n <= n1; n++) {
                for (int e = e0; e <= e1; e++) {
                    // 精确检查：网格中心是否在障碍物圆内
                    double cellN = minN + (n + 0.5) * gridResolution;
                    double cellE = minE + (e + 0.5) * gridResolution;
                    double dn = cellN - o[0];
                    double de = cellE - o[1];
                    if (dn * dn + de * de <= (o[2] + gridResolution * 0.5) * (o[2] + gridResolution * 0.5)) {
                        blocked[n][e] = true;
                    }
                }
            }
        }

        // 起终点网格索引
        int sn = toCellIdx(startN, minN, gridResolution, cellsN);
        int se = toCellIdx(startE, minE, gridResolution, cellsE);
        int gn = toCellIdx(goalN, minN, gridResolution, cellsN);
        int ge = toCellIdx(goalE, minE, gridResolution, cellsE);

        // 起点或终点被障碍物覆盖 → 无可行路径
        if (blocked[sn][se] || blocked[gn][ge]) {
            log.debug("[PathPlanner] A*: start or goal blocked, no path");
            return Collections.emptyList();
        }

        // A* 搜索
        int[] parent = new int[cellsN * cellsE];
        Arrays.fill(parent, -1);
        double[] gScore = new double[cellsN * cellsE];
        Arrays.fill(gScore, Double.POSITIVE_INFINITY);
        boolean[] closed = new boolean[cellsN * cellsE];

        // lambda 要求变量 effectively final，cellsE/gridResolution 在上面网格调整时被重新赋值，故引入 final 副本
        final int cellsEFinal = cellsE;
        final double[] gScoreFinal = gScore;
        final double gridResolutionFinal = gridResolution;
        PriorityQueue<int[]> open = new PriorityQueue<>(Comparator.comparingDouble(
                (int[] a) -> gScoreFinal[a[0] * cellsEFinal + a[1]] + heuristic(a[0], a[1], gn, ge, gridResolutionFinal))
                .thenComparingInt(a -> a[0] * cellsEFinal + a[1]));

        gScore[sn * cellsE + se] = 0.0;
        open.add(new int[]{sn, se});

        // 8 方向移动：N, S, E, W + 4 对角线
        int[] dnArr = {-1, 1, 0, 0, -1, -1, 1, 1};
        int[] deArr = {0, 0, -1, 1, -1, 1, -1, 1};
        double[] costArr = {1, 1, 1, 1, Math.sqrt(2), Math.sqrt(2), Math.sqrt(2), Math.sqrt(2)};

        boolean found = false;
        while (!open.isEmpty()) {
            int[] cur = open.poll();
            int cn = cur[0], ce = cur[1];
            int cIdx = cn * cellsE + ce;
            if (closed[cIdx]) continue;
            closed[cIdx] = true;

            if (cn == gn && ce == ge) {
                found = true;
                break;
            }

            for (int d = 0; d < 8; d++) {
                int nn = cn + dnArr[d];
                int ne = ce + deArr[d];
                if (nn < 0 || nn >= cellsN || ne < 0 || ne >= cellsE) continue;
                if (blocked[nn][ne]) continue;
                int nIdx = nn * cellsE + ne;
                if (closed[nIdx]) continue;
                double tentativeG = gScore[cIdx] + costArr[d] * gridResolution;
                if (tentativeG < gScore[nIdx]) {
                    gScore[nIdx] = tentativeG;
                    parent[nIdx] = cIdx;
                    open.add(new int[]{nn, ne});
                }
            }
        }

        if (!found) {
            log.debug("[PathPlanner] A*: no path found");
            return Collections.emptyList();
        }

        // 重建路径（终点→起点），然后反转
        List<int[]> cellPath = new ArrayList<>();
        int cur = gn * cellsE + ge;
        while (cur != -1) {
            cellPath.add(new int[]{cur / cellsE, cur % cellsE});
            cur = parent[cur];
        }
        Collections.reverse(cellPath);

        // 转回 lat/lon/alt（高度线性插值）
        double totalDist = Math.hypot(goalN - startN, goalE - startE);
        List<double[]> path = new ArrayList<>(cellPath.size());
        for (int[] cell : cellPath) {
            double cellN = minN + (cell[0] + 0.5) * gridResolution;
            double cellE = minE + (cell[1] + 0.5) * gridResolution;
            double lat = GeoUtil.latOf(refLat, refLon, cellN, cellE);
            double lon = GeoUtil.lonOf(refLat, refLon, cellN, cellE);
            double t = totalDist > 1e-9 ? Math.hypot(cellN - startN, cellE - startE) / totalDist : 0.0;
            path.add(new double[]{lat, lon, t * 0.0}); // A* 不含高度信息，alt=0（avoidWithPath 会覆盖）
        }
        // 确保终点精确
        path.set(path.size() - 1, new double[]{goalLat, goalLon, 0.0});
        path.set(0, new double[]{startLat, startLon, 0.0});
        return path;
    }

    private static double heuristic(int n, int e, int gn, int ge, double res) {
        double dn = (n - gn) * res;
        double de = (e - ge) * res;
        return Math.sqrt(dn * dn + de * de);
    }

    private static int toCellIdx(double coord, double min, double res, int cells) {
        int idx = (int) Math.floor((coord - min) / res);
        return Math.max(0, Math.min(cells - 1, idx));
    }

    // ======================================================================
    // RRT 快速扩展随机树
    // ======================================================================

    /**
     * RRT 路径规划（3D：north/east/alt）。
     *
     * @param startLat  起点纬度
     * @param startLon  起点经度
     * @param startAlt  起点高度
     * @param goalLat   终点纬度
     * @param goalLon   终点经度
     * @param goalAlt   终点高度
     * @param obstacles 圆柱体障碍物列表（可为空）
     * @param boundaryLat  边界圆心纬度（规划空间限制在该圆内）
     * @param boundaryLon  边界圆心经度
     * @param boundaryRadius 边界圆半径（米）
     * @return 路径点列表 {@code List<double[]{lat, lon, alt}}，起点→终点；
     *         达到最大节点数仍未连接目标时返回空列表
     */
    public List<double[]> planRRT(double startLat, double startLon, double startAlt,
                                   double goalLat, double goalLon, double goalAlt,
                                   List<Obstacle> obstacles,
                                   double boundaryLat, double boundaryLon, double boundaryRadius) {
        if (obstacles == null) {
            obstacles = Collections.emptyList();
        }
        if (boundaryRadius <= 0) {
            // 默认边界：起点到终点距离的 2 倍
            boundaryRadius = 2.0 * Math.hypot(
                    GeoUtil.north(startLat, startLon, goalLat, goalLon),
                    GeoUtil.east(startLat, startLon, goalLat, goalLon)) + 100.0;
            boundaryLat = startLat;
            boundaryLon = startLon;
        }

        // 以边界圆心为参考建立本地坐标系
        double refLat = boundaryLat, refLon = boundaryLon;
        double[] start = {
                GeoUtil.north(refLat, refLon, startLat, startLon),
                GeoUtil.east(refLat, refLon, startLat, startLon),
                startAlt
        };
        double[] goal = {
                GeoUtil.north(refLat, refLon, goalLat, goalLon),
                GeoUtil.east(refLat, refLon, goalLat, goalLon),
                goalAlt
        };

        // 障碍物转本地坐标 [n, e, alt, r]
        double[][] obsLocal = new double[obstacles.size()][4];
        for (int i = 0; i < obstacles.size(); i++) {
            Obstacle o = obstacles.get(i);
            obsLocal[i][0] = GeoUtil.north(refLat, refLon, o.lat, o.lon);
            obsLocal[i][1] = GeoUtil.east(refLat, refLon, o.lat, o.lon);
            obsLocal[i][2] = o.alt;
            obsLocal[i][3] = o.radius;
        }

        // RRT 树：每个节点存 [n, e, alt] + parent 索引
        List<double[]> nodes = new ArrayList<>(RRT_MAX_NODES);
        List<Integer> parents = new ArrayList<>(RRT_MAX_NODES);
        nodes.add(start);
        parents.add(-1);

        int goalNodeIdx = -1;
        double[] goalNearest = start;
        double goalNearestDist = dist3(start, goal);

        for (int iter = 0; iter < RRT_MAX_NODES; iter++) {
            // 采样：goalBias 概率采样目标，否则边界圆内随机
            double[] sample;
            if (rng.nextDouble() < RRT_GOAL_BIAS) {
                sample = goal;
            } else {
                double r = boundaryRadius * Math.sqrt(rng.nextDouble()); // 均匀采样圆内
                double theta = rng.nextDouble() * 2.0 * Math.PI;
                double sampleAlt = Math.min(startAlt, goalAlt) + rng.nextDouble() * Math.abs(goalAlt - startAlt + 1);
                sample = new double[]{r * Math.cos(theta), r * Math.sin(theta), sampleAlt};
            }

            // 找最近节点
            int nearestIdx = 0;
            double nearestDist = dist3(nodes.get(0), sample);
            for (int i = 1; i < nodes.size(); i++) {
                double d = dist3(nodes.get(i), sample);
                if (d < nearestDist) {
                    nearestDist = d;
                    nearestIdx = i;
                }
            }
            double[] nearest = nodes.get(nearestIdx);

            // 向采样点方向扩展 stepSize
            double dirN = sample[0] - nearest[0];
            double dirE = sample[1] - nearest[1];
            double dirA = sample[2] - nearest[2];
            double dirLen = Math.sqrt(dirN * dirN + dirE * dirE + dirA * dirA);
            if (dirLen < 1e-9) continue;
            double[] newNode = new double[]{
                    nearest[0] + dirN / dirLen * RRT_STEP_SIZE,
                    nearest[1] + dirE / dirLen * RRT_STEP_SIZE,
                    nearest[2] + dirA / dirLen * RRT_STEP_SIZE
            };

            // 碰撞检测：新节点本身 + 从 nearest 到 newNode 的线段
            if (collides(newNode, obsLocal) || segmentCollides(nearest, newNode, obsLocal)) {
                continue;
            }

            // 加入树
            nodes.add(newNode);
            parents.add(nearestIdx);

            // 检查目标连接
            double distToGoal = dist3(newNode, goal);
            if (distToGoal < goalNearestDist) {
                goalNearestDist = distToGoal;
                goalNearest = newNode;
            }
            if (distToGoal < RRT_GOAL_THRESHOLD && !segmentCollides(newNode, goal, obsLocal)) {
                nodes.add(goal);
                parents.add(nodes.size() - 2); // 指向 newNode
                goalNodeIdx = nodes.size() - 1;
                break;
            }
        }

        if (goalNodeIdx == -1) {
            log.debug("[PathPlanner] RRT: did not reach goal, nearest dist={}", goalNearestDist);
            return Collections.emptyList();
        }

        // 回溯路径（终点→起点），然后反转
        List<double[]> localPath = new ArrayList<>();
        int cur = goalNodeIdx;
        while (cur != -1) {
            localPath.add(nodes.get(cur));
            cur = parents.get(cur);
        }
        Collections.reverse(localPath);

        // 转回 lat/lon/alt
        List<double[]> path = new ArrayList<>(localPath.size());
        for (double[] p : localPath) {
            double lat = GeoUtil.latOf(refLat, refLon, p[0], p[1]);
            double lon = GeoUtil.lonOf(refLat, refLon, p[0], p[1]);
            path.add(new double[]{lat, lon, p[2]});
        }
        // 确保起终点精确
        path.set(0, new double[]{startLat, startLon, startAlt});
        path.set(path.size() - 1, new double[]{goalLat, goalLon, goalAlt});
        return path;
    }

    /** 3D 欧氏距离 */
    private static double dist3(double[] a, double[] b) {
        double dn = a[0] - b[0], de = a[1] - b[1], da = a[2] - b[2];
        return Math.sqrt(dn * dn + de * de + da * da);
    }

    /** 点是否与任一障碍物碰撞（圆柱体：n-e 平面距离 < r 且 alt < obstacle.alt） */
    private static boolean collides(double[] p, double[][] obstacles) {
        for (double[] o : obstacles) {
            double dn = p[0] - o[0];
            double de = p[1] - o[1];
            if (dn * dn + de * de <= o[3] * o[3] && p[2] < o[2]) {
                return true;
            }
        }
        return false;
    }

    /** 线段是否与任一障碍物碰撞（采样检查，步长 1m） */
    private static boolean segmentCollides(double[] a, double[] b, double[][] obstacles) {
        if (obstacles.length == 0) return false;
        double len = dist3(a, b);
        int steps = Math.max(1, (int) Math.ceil(len));
        for (int i = 1; i < steps; i++) {
            double t = (double) i / steps;
            double[] p = new double[]{
                    a[0] + (b[0] - a[0]) * t,
                    a[1] + (b[1] - a[1]) * t,
                    a[2] + (b[2] - a[2]) * t
            };
            if (collides(p, obstacles)) return true;
        }
        return false;
    }

    // ======================================================================
    // 路径平滑
    // ======================================================================

    /**
     * 路径平滑：shortcut smoothing（移除可直连的中间点）+ 中点插值平滑折线。
     * <p>平滑后路径总长度 &le; 原路径，且不引入与障碍物相交的新段（无障碍信息时仅做几何平滑）。
     *
     * @param rawPath 原始路径点列表 {@code [lat, lon, alt]}
     * @return 平滑后路径点列表；输入为空或单点时原样返回副本
     */
    public List<double[]> smoothPath(List<double[]> rawPath) {
        if (rawPath == null || rawPath.size() <= 1) {
            return rawPath == null ? Collections.emptyList() : new ArrayList<>(rawPath);
        }

        // ---- 阶段 1：shortcut smoothing ----
        // 尝试用直线连接非相邻点，若连线与原路径偏差小则跳过中间点
        List<double[]> shortcut = new ArrayList<>();
        shortcut.add(rawPath.get(0));
        int i = 0;
        while (i < rawPath.size() - 1) {
            int j = rawPath.size() - 1;
            // 找最远的 j 使得 i→j 直线"接近"原路径 i..j 段（用最大垂直偏差衡量）
            while (j > i + 1 && !isStraightApprox(rawPath, i, j)) {
                j--;
            }
            shortcut.add(rawPath.get(j));
            i = j;
        }

        // ---- 阶段 2：中点插值平滑（Catmull-Rom 风格，3 点抛物插值）----
        List<double[]> smoothed = new ArrayList<>();
        smoothed.add(shortcut.get(0));
        for (int k = 0; k < shortcut.size() - 1; k++) {
            double[] p0 = shortcut.get(Math.max(0, k - 1));
            double[] p1 = shortcut.get(k);
            double[] p2 = shortcut.get(k + 1);
            double[] p3 = shortcut.get(Math.min(shortcut.size() - 1, k + 2));
            // 在 p1→p2 之间插 2 个中间点（t=1/3, 2/3），用 Catmull-Rom
            for (int s = 1; s <= 2; s++) {
                double t = s / 3.0;
                smoothed.add(catmullRom(p0, p1, p2, p3, t));
            }
            smoothed.add(p2);
        }

        return smoothed;
    }

    /** 检查 rawPath[i..j] 段是否近似直线（最大垂直偏差 < 0.5m） */
    private static boolean isStraightApprox(List<double[]> rawPath, int i, int j) {
        double[] a = rawPath.get(i);
        double[] b = rawPath.get(j);
        double dx = b[0] - a[0], dy = b[1] - a[1], dz = b[2] - a[2];
        double segLen = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (segLen < 1e-9) return false;
        double maxDev = 0;
        for (int k = i + 1; k < j; k++) {
            double[] p = rawPath.get(k);
            double px = p[0] - a[0], py = p[1] - a[1], pz = p[2] - a[2];
            // 点到直线距离 = |p-a - ((p-a)·(b-a)/|b-a|²)(b-a)|
            double dot = px * dx + py * dy + pz * dz;
            double t = dot / (segLen * segLen);
            double projX = t * dx, projY = t * dy, projZ = t * dz;
            double devX = px - projX, devY = py - projY, devZ = pz - projZ;
            double dev = Math.sqrt(devX * devX + devY * devY + devZ * devZ);
            if (dev > maxDev) maxDev = dev;
        }
        // 阈值：0.5m（约 0.5 个 A* 网格分辨率）
        return maxDev < 0.5;
    }

    /** Catmull-Rom 样条插值（3D） */
    private static double[] catmullRom(double[] p0, double[] p1, double[] p2, double[] p3, double t) {
        double t2 = t * t, t3 = t2 * t;
        double[] result = new double[3];
        for (int d = 0; d < 3; d++) {
            result[d] = 0.5 * (
                    (2 * p1[d]) +
                            (-p0[d] + p2[d]) * t +
                            (2 * p0[d] - 5 * p1[d] + 4 * p2[d] - p3[d]) * t2 +
                            (-p0[d] + 3 * p1[d] - 3 * p2[d] + p3[d]) * t3);
        }
        return result;
    }

    // ======================================================================
    // 工具方法（包私有，供测试与 ObstacleAvoidanceStrategy 使用）
    // ======================================================================

    /** 计算路径总长度（米，3D） */
    static double pathLength(List<double[]> path) {
        if (path == null || path.size() < 2) return 0.0;
        double len = 0;
        for (int i = 1; i < path.size(); i++) {
            double[] a = path.get(i - 1);
            double[] b = path.get(i);
            double dn = GeoUtil.north(a[0], a[1], b[0], b[1]);
            double de = GeoUtil.east(a[0], a[1], b[0], b[1]);
            double da = b[2] - a[2];
            len += Math.sqrt(dn * dn + de * de + da * da);
        }
        return len;
    }

    /**
     * 检查路径是否与任一障碍物相交（碰撞检测）。
     * <p>对每段路径线段采样，检查采样点是否落在任一障碍物圆柱体内。
     *
     * @param path      路径点列表 {@code [lat, lon, alt]}
     * @param obstacles 障碍物列表
     * @param sampleStep 采样步长（米），默认 1m
     * @return true 若路径与任一障碍物相交
     */
    public boolean pathCollides(List<double[]> path, List<Obstacle> obstacles, double sampleStep) {
        if (path == null || path.size() < 2 || obstacles == null || obstacles.isEmpty()) {
            return false;
        }
        if (sampleStep <= 0) sampleStep = 1.0;
        // 用第一个路径点作为参考
        double refLat = path.get(0)[0], refLon = path.get(0)[1];
        double[][] obsLocal = new double[obstacles.size()][4];
        for (int i = 0; i < obstacles.size(); i++) {
            Obstacle o = obstacles.get(i);
            obsLocal[i][0] = GeoUtil.north(refLat, refLon, o.lat, o.lon);
            obsLocal[i][1] = GeoUtil.east(refLat, refLon, o.lat, o.lon);
            obsLocal[i][2] = o.alt;
            obsLocal[i][3] = o.radius;
        }
        for (int i = 1; i < path.size(); i++) {
            double[] a = path.get(i - 1);
            double[] b = path.get(i);
            double[] aLocal = {
                    GeoUtil.north(refLat, refLon, a[0], a[1]),
                    GeoUtil.east(refLat, refLon, a[0], a[1]),
                    a[2]
            };
            double[] bLocal = {
                    GeoUtil.north(refLat, refLon, b[0], b[1]),
                    GeoUtil.east(refLat, refLon, b[0], b[1]),
                    b[2]
            };
            double segLen = dist3(aLocal, bLocal);
            int steps = Math.max(1, (int) Math.ceil(segLen / sampleStep));
            for (int s = 0; s <= steps; s++) {
                double t = (double) s / steps;
                double[] p = new double[]{
                        aLocal[0] + (bLocal[0] - aLocal[0]) * t,
                        aLocal[1] + (bLocal[1] - aLocal[1]) * t,
                        aLocal[2] + (bLocal[2] - aLocal[2]) * t
                };
                if (collides(p, obsLocal)) return true;
            }
        }
        return false;
    }

    /** 便捷重载：默认 1m 采样步长 */
    public boolean pathCollides(List<double[]> path, List<Obstacle> obstacles) {
        return pathCollides(path, obstacles, 1.0);
    }
}
package io.aerofleet.sim.ai;

import java.util.Collections;
import java.util.List;

/**
 * M11 自动避障策略。
 * <p>
 * 保留旧接口 {@link #evaluate(boolean, double)} 供 {@link DecisionEngine} 调用（向后兼容）。
 * 新增 {@link #avoidWithPath} 接口：基于 A* / RRT 路径规划器生成避障路径，
 * 返回包含路径点列表的 {@link DecisionResult}。
 * <p>
 * 路径规划策略：
 * <ol>
 *   <li>先用 A* 网格搜索（全局最优，速度快）</li>
 *   <li>A* 失败（无可行路径）则降级用 RRT（连续空间采样，适合复杂障碍）</li>
 *   <li>RRT 路径做平滑处理</li>
 * </ol>
 * <p>
 * 注意：drone-sim 模块未引入 slf4j，统一使用 {@code System.out.println} 输出日志。
 */
public class ObstacleAvoidanceStrategy {

    private static final double DEFAULT_GRID_RESOLUTION = 5.0; // A* 默认网格分辨率 5m
    private static final double DEFAULT_BOUNDARY_RADIUS  = 500.0; // RRT 默认边界半径 500m

    private final PathPlanner planner;

    public ObstacleAvoidanceStrategy() {
        this(new PathPlanner());
    }

    /** 测试 / 依赖注入用：可传入自定义 PathPlanner（如固定随机种子） */
    ObstacleAvoidanceStrategy(PathPlanner planner) {
        this.planner = planner;
    }

    // ----------------------------------------------------------------------
    // 旧接口（向后兼容 DecisionEngine 调用）
    // ----------------------------------------------------------------------

    /**
     * 简单避障决策（无路径规划）。
     * <p>保留原 M11 逻辑：障碍物在前 + 高度 &lt; 50 → 爬升；否则 → 重规划。
     */
    public DecisionResult evaluate(boolean obstacleDetected, double alt) {
        if (obstacleDetected) {
            if (alt < 50) {
                return new DecisionResult("AVOID", "obstacle ahead, climb", alt, 0.75);
            } else {
                return new DecisionResult("AVOID", "obstacle ahead, reroute", 0, 0.7);
            }
        }
        return null;
    }

    // ----------------------------------------------------------------------
    // 新接口：基于路径规划的避障
    // ----------------------------------------------------------------------

    /**
     * 基于路径规划的避障：先用 A* 规划，失败则用 RRT，返回包含避障路径的决策结果。
     *
     * @param curLat     当前纬度
     * @param curLon     当前经度
     * @param curAlt     当前高度
     * @param curHeading 当前航向（度，0=正北，顺时针）；预留参数，当前未使用
     * @param obstacles  圆柱体障碍物列表
     * @param goalLat    目标纬度
     * @param goalLon    目标经度
     * @param goalAlt    目标高度
     * @return {@link DecisionResult}，decisionType="AVOID"，path 为避障路径点列表；
     *         规划失败时 path 为空列表但 decisionType 仍为 "AVOID"（置信度降低）
     */
    public DecisionResult avoidWithPath(double curLat, double curLon, double curAlt, double curHeading,
                                        List<PathPlanner.Obstacle> obstacles,
                                        double goalLat, double goalLon, double goalAlt) {
        if (obstacles == null) {
            obstacles = Collections.emptyList();
        }

        // 1. 尝试 A* 规划（2D，高度线性插值）
        List<double[]> path = null;
        try {
            path = planner.planAStar(curLat, curLon, goalLat, goalLon, obstacles, DEFAULT_GRID_RESOLUTION);
        } catch (Exception e) {
            System.out.println("[ObstacleAvoidance] A* failed: " + e.getMessage());
        }

        String method = "A*";
        double confidence = 0.8;

        // 2. A* 失败 → 降级 RRT
        if (path == null || path.isEmpty()) {
            System.out.println("[ObstacleAvoidance] A* no path, falling back to RRT");
            double boundaryLat = curLat;
            double boundaryLon = curLon;
            // 边界半径：覆盖起点到终点距离 + 障碍物 + 余量
            double distToGoal = Math.hypot(
                    io.aerofleet.sim.GeoUtil.north(curLat, curLon, goalLat, goalLon),
                    io.aerofleet.sim.GeoUtil.east(curLat, curLon, goalLat, goalLon));
            double boundaryRadius = Math.max(DEFAULT_BOUNDARY_RADIUS, distToGoal * 2.0 + 100.0);
            try {
                path = planner.planRRT(curLat, curLon, curAlt, goalLat, goalLon, goalAlt,
                        obstacles, boundaryLat, boundaryLon, boundaryRadius);
            } catch (Exception e) {
                System.out.println("[ObstacleAvoidance] RRT failed: " + e.getMessage());
            }
            method = "RRT";
            confidence = 0.7;

            // 3. RRT 路径平滑
            if (path != null && !path.isEmpty()) {
                path = planner.smoothPath(path);
            }
        }

        // 4. 构造决策结果
        if (path == null || path.isEmpty()) {
            System.out.println("[ObstacleAvoidance] no path found by A* or RRT");
            return new DecisionResult("AVOID", "obstacle ahead, no path", 0, 0.3,
                    Collections.emptyList());
        }

        // 路径高度填充：A* 路径 alt=0，这里用起点/终点高度线性插值覆盖
        path = fillAltitude(path, curAlt, goalAlt);

        System.out.println("[ObstacleAvoidance] path planned: method=" + method
                + " points=" + path.size()
                + " length=" + String.format("%.2f", PathPlanner.pathLength(path)) + "m");
        return new DecisionResult("AVOID", "obstacle ahead, reroute via " + method, 0, confidence, path);
    }

    /** 将路径每点的 alt 用起点/终点高度线性插值填充 */
    private static List<double[]> fillAltitude(List<double[]> path, double startAlt, double goalAlt) {
        if (path.isEmpty()) return path;
        List<double[]> filled = new java.util.ArrayList<>(path.size());
        int last = path.size() - 1;
        for (int i = 0; i <= last; i++) {
            double[] p = path.get(i);
            double t = last == 0 ? 0.0 : (double) i / last;
            filled.add(new double[]{p[0], p[1], startAlt + (goalAlt - startAlt) * t});
        }
        return filled;
    }
}

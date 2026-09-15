package io.aerofleet.sim;

import java.util.List;

/**
 * 模拟深度数据源（M3 感知成像增强，FR-11/FR-12）。
 * <p>
 * 基于合成障碍物列表 + 无人机位置/航向，产出深度图与最近障碍。
 * <p>
 * 深度图：在合成障碍物位置填入对应距离值，其余填远距值（{@link #FAR_DISTANCE_M}）。
 * 最近障碍：遍历合成障碍物计算最近距离 + 方向（相对无人机航向归一化 0-359°）。
 */
public class SimulatedDepthSource implements DepthSource {

    /** 无障碍位置的远距填充值（米）。 */
    public static final double FAR_DISTANCE_M = 100.0;

    private final List<SyntheticObstacle> obstacles;
    private final double droneNorth;
    private final double droneEast;
    private final double droneYawDeg;
    /** 深度图分辨率（用于 depthMap 矩阵尺寸）。 */
    private final int mapWidth;
    private final int mapHeight;
    /** 深度图覆盖的物理范围（米，以无人机为中心的方形区域边长）。 */
    private final double coverageM;

    public SimulatedDepthSource(List<SyntheticObstacle> obstacles,
                                double droneNorth, double droneEast, double droneYawDeg) {
        this(obstacles, droneNorth, droneEast, droneYawDeg, 64, 48, 50.0);
    }

    public SimulatedDepthSource(List<SyntheticObstacle> obstacles,
                                double droneNorth, double droneEast, double droneYawDeg,
                                int mapWidth, int mapHeight, double coverageM) {
        this.obstacles = obstacles == null ? List.of() : List.copyOf(obstacles);
        this.droneNorth = droneNorth;
        this.droneEast = droneEast;
        this.droneYawDeg = droneYawDeg;
        this.mapWidth = mapWidth;
        this.mapHeight = mapHeight;
        this.coverageM = coverageM;
    }

    @Override
    public double[][] depthMap() {
        double[][] map = new double[mapHeight][mapWidth];
        // 初始化为远距
        for (int y = 0; y < mapHeight; y++) {
            for (int x = 0; x < mapWidth; x++) {
                map[y][x] = FAR_DISTANCE_M;
            }
        }
        // 在障碍物位置填入距离值
        double half = coverageM / 2;
        double step = coverageM / Math.max(mapWidth, mapHeight);
        for (int y = 0; y < mapHeight; y++) {
            for (int x = 0; x < mapWidth; x++) {
                // 像素 → 物理坐标（以无人机为中心）
                double pn = droneNorth + (y - mapHeight / 2.0) * step;
                double pe = droneEast + (x - mapWidth / 2.0) * step;
                // 查找覆盖此位置的障碍物
                for (SyntheticObstacle obs : obstacles) {
                    double dist = Math.hypot(obs.north() - pn, obs.east() - pe);
                    if (dist <= obs.radius()) {
                        // 像素在障碍物内：填入无人机到此像素的距离
                        double droneDist = Math.hypot(pn - droneNorth, pe - droneEast);
                        map[y][x] = Math.min(map[y][x], droneDist);
                    }
                }
            }
        }
        return map;
    }

    @Override
    public NearestObstacle nearestObstacle() {
        if (obstacles.isEmpty()) {
            return new NearestObstacle(Double.MAX_VALUE, 0);
        }
        double nearest = Double.MAX_VALUE;
        double direction = 0;
        for (SyntheticObstacle obs : obstacles) {
            double dn = obs.north() - droneNorth;
            double de = obs.east() - droneEast;
            double dist = Math.hypot(dn, de);
            if (dist < nearest) {
                nearest = dist;
                // 方向：atan2(east, north) - yaw，归一化 0-359
                direction = Math.toDegrees(Math.atan2(de, dn)) - droneYawDeg;
                direction = ((direction % 360) + 360) % 360;
            }
        }
        return new NearestObstacle(nearest, direction);
    }

    @Override
    public PointCloudStats pointCloudStats() {
        // 简化：点数 = 障碍物数 × 100（模拟每个障碍物贡献 100 个点）
        int pointCount = obstacles.size() * 100;
        // 密度 = 点数 / 覆盖体积（简化：覆盖面积 × 1m 深度）
        double density = pointCount / (coverageM * coverageM);
        double nearest = nearestObstacle().distance();
        return new PointCloudStats(pointCount, density, nearest);
    }

    /** 暴露障碍物列表（供 ObstacleDetector 等读取）。 */
    public List<SyntheticObstacle> obstacles() {
        return obstacles;
    }
}
package io.aerofleet.sim;

import java.util.List;

/**
 * 合成障碍物 record（M3 感知成像增强，FR-12）。
 * <p>
 * 模拟器中的合成障碍物，用于 {@link SimulatedDepthSource} 产出深度图。
 *
 * @param north  障碍物北向坐标（米，相对起飞点）
 * @param east   障碍物东向坐标（米）
 * @param radius 障碍物半径（米，用于深度图填充）
 */
public record SyntheticObstacle(double north, double east, double radius) {
    public SyntheticObstacle {
        if (radius <= 0) {
            throw new IllegalArgumentException("radius must be positive");
        }
    }

    /** 便利构造：从障碍物列表中查找最近者。 */
    public static SyntheticObstacle nearest(List<SyntheticObstacle> obstacles,
                                           double droneNorth, double droneEast) {
        SyntheticObstacle best = null;
        double bestDist = Double.MAX_VALUE;
        for (SyntheticObstacle o : obstacles) {
            double d = Math.hypot(o.north - droneNorth, o.east - droneEast);
            if (d < bestDist) {
                bestDist = d;
                best = o;
            }
        }
        return best;
    }
}
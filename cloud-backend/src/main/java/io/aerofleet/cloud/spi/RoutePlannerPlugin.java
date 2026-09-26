package io.aerofleet.cloud.spi;

import java.util.List;

/**
 * 航线规划算法插件 SPI 接口，支持不同规划算法（A*、RRT、Dijkstra 等）的可插拔扩展。
 * <p>
 * 每种算法实现此接口，标注 {@code @Component} 即可被 {@link PluginRegistry} 自动发现注册。
 * 通过 {@code getAlgorithmType()} 标识算法类型，注册中心按类型建立映射。
 */
public interface RoutePlannerPlugin extends PluginLifecycle {

    /**
     * 获取算法类型标识。
     * <p>
     * 如 "astar"、"rrt"、"dijkstra"、"voronoi" 等，用于注册中心索引。
     *
     * @return 算法类型字符串
     */
    String getAlgorithmType();

    /**
     * 规划航线。
     * <p>
     * 根据请求参数计算最优航线，返回有序航路点列表。
     *
     * @param request 航线规划请求
     * @return 航路点列表（从起点到终点的有序路径）
     */
    List<Waypoint> plan(RoutePlanRequest request);

    /**
     * 获取算法适用场景描述。
     * <p>
     * 如 "开阔地形全局规划"、"复杂避障局部规划" 等，供航线规划服务选择合适算法。
     *
     * @return 适用场景描述
     */
    String getApplicableScenario();
}
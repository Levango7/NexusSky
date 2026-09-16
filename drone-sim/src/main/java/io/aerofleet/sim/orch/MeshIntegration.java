package io.aerofleet.sim.orch;

/**
 * Mesh 网络集成接口（M9 应急任务编排，T2 编排引擎核心）。
 * <p>
 * 由 mesh 子系统（M5）实现，编排引擎通过此接口在组网部署阶段
 * 添加/移除节点、查询可达性与跳数。实现可为 null（表示 mesh 模块未启用）。
 */
public interface MeshIntegration {

    /**
     * 添加节点到 mesh 网络。
     *
     * @param droneId 无人机 ID
     * @param lat     纬度（度）
     * @param lon     经度（度）
     */
    void addNode(int droneId, double lat, double lon);

    /**
     * 从 mesh 网络移除节点。
     *
     * @param droneId 无人机 ID
     */
    void removeNode(int droneId);

    /**
     * 查询两节点是否可达。
     *
     * @param fromId 源节点 ID
     * @param toId   目标节点 ID
     * @return true 表示可达
     */
    boolean isReachable(int fromId, int toId);

    /**
     * 查询两节点之间的跳数。
     *
     * @param fromId 源节点 ID
     * @param toId   目标节点 ID
     * @return 跳数，不可达返回 -1
     */
    int getHopCount(int fromId, int toId);
}
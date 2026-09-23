package io.aerofleet.sim.terrain;

import io.aerofleet.sim.GeoUtil;
import io.aerofleet.sim.SimLog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 灾后动态遮挡建模（FR-10, §6.6）。
 * <p>
 * 地震导致建筑倒塌后，系统通过卫星遥感数据或无人机测绘数据自动更新建筑遮挡模型，
 * 重新计算 RF 传播路径。
 * <p>
 * 工作流程：
 * <ol>
 *   <li>接收建筑倒塌事件（位置 + 高度变化）</li>
 *   <li>更新遮挡模型：移除倒塌建筑遮挡区域，添加废墟遮挡区域</li>
 *   <li>重新计算 RF 传播路径影响：遮挡区域变化 → 视距/非视距判定变化</li>
 *   <li>通知订阅者（RF 模型/飞行约束等）刷新</li>
 * </ol>
 * <p>
 * 遮挡建模原理：
 * <ul>
 *   <li>建筑倒塌前：建筑高度 H → 遮挡区域为建筑轮廓 × H</li>
 *   <li>建筑倒塌后：废墟高度 H'（典型为原高度的 10-30%）→ 遮挡区域缩小</li>
 *   <li>RF 传播影响：遮挡区域缩小 → 原非视距路径可能恢复视距 → RSSI 改善</li>
 * </ul>
 * <p>
 * 异常处理（§6.6.3）：
 * <ul>
 *   <li>倒塌事件数据不完整 → 跳过更新并告警</li>
 *   <li>订阅者通知失败 → 记录告警但不中断</li>
 * </ul>
 */
public final class DynamicObstacleModel {

    /** 废墟高度占原建筑高度的比例（典型值 0.15, §7.6）。 */
    public static final double RUBBLE_HEIGHT_RATIO = 0.15;
    /** 废墟水平扩展系数（废墟堆积范围比原建筑轮廓扩大，典型 1.3）。 */
    public static final double RUBBLE_FOOTPRINT_SCALE = 1.3;

    private final List<ObstacleEntry> obstacles = new CopyOnWriteArrayList<>();
    private final List<ObstacleUpdateSubscriber> subscribers = new CopyOnWriteArrayList<>();
    private volatile long version;

    /** 遮挡更新订阅者接口。 */
    public interface ObstacleUpdateSubscriber {
        /** 收到遮挡模型更新通知。 */
        void onObstacleUpdated(ObstacleUpdateEvent event);
    }

    /**
     * 建筑遮挡条目。
     *
     * @param id        建筑标识
     * @param lat       建筑中心纬度
     * @param lon       建筑中心经度
     * @param heightM   建筑高度 (m)
     * @param footprintM 建筑占地范围 (m, 半边长)
     * @param collapsed 是否已倒塌
     */
    public record ObstacleEntry(
            String id,
            double lat,
            double lon,
            double heightM,
            double footprintM,
            boolean collapsed
    ) {
        /**
         * 有效遮挡高度（倒塌后为废墟高度）。
         *
         * @return 当前有效遮挡高度 (m)
         */
        public double effectiveHeightM() {
            return collapsed ? heightM * RUBBLE_HEIGHT_RATIO : heightM;
        }

        /**
         * 有效遮挡范围（倒塌后废墟堆积范围扩大）。
         *
         * @return 当前有效遮挡范围 (m, 半边长)
         */
        public double effectiveFootprintM() {
            return collapsed ? footprintM * RUBBLE_FOOTPRINT_SCALE : footprintM;
        }
    }

    /**
     * 遮挡模型更新事件。
     *
     * @param alert          告警类型
     * @param updatedEntry   更新后的遮挡条目
     * @param rfImpactDesc   RF 传播影响描述
     * @param newVersion     新版本号
     */
    public record ObstacleUpdateEvent(
            TerrainAlertType alert,
            ObstacleEntry updatedEntry,
            String rfImpactDesc,
            long newVersion
    ) {
    }

    public DynamicObstacleModel() {
        this.version = 0;
    }

    /**
     * 添加建筑遮挡条目（初始建模）。
     *
     * @param entry 遮挡条目
     */
    public void addObstacle(ObstacleEntry entry) {
        if (entry == null) {
            SimLog.warn("遮挡条目为 null，跳过添加");
            return;
        }
        obstacles.add(entry);
        version++;
    }

    /**
     * 处理建筑倒塌事件（FR-10）。
     * <p>
     * 更新遮挡模型：将对应建筑标记为倒塌，重新计算遮挡高度/范围，
     * 生成 RF 传播影响描述，通知所有订阅者。
     *
     * @param buildingId    倒塌建筑标识
     * @param newHeightM    倒塌后高度变化（0 = 完全倒塌，可为废墟高度）
     * @param sourceLat     数据源纬度（卫星遥感/无人机测绘位置）
     * @param sourceLon     数据源经度
     * @return 遮挡更新事件（null = 未找到对应建筑或数据异常）
     */
    public ObstacleUpdateEvent onBuildingCollapse(String buildingId, double newHeightM,
                                                   double sourceLat, double sourceLon) {
        if (buildingId == null || buildingId.isBlank()) {
            SimLog.warn("建筑倒塌事件：buildingId 为空，跳过更新");
            return null;
        }

        int idx = -1;
        for (int i = 0; i < obstacles.size(); i++) {
            if (obstacles.get(i).id().equals(buildingId)) {
                idx = i;
                break;
            }
        }

        if (idx < 0) {
            SimLog.warn("建筑倒塌事件：未找到建筑 " + buildingId + "，跳过更新");
            return null;
        }

        ObstacleEntry oldEntry = obstacles.get(idx);
        ObstacleEntry newEntry = new ObstacleEntry(
                oldEntry.id(),
                oldEntry.lat(),
                oldEntry.lon(),
                oldEntry.heightM(),  // 保留原始高度（effectiveHeightM 会按倒塌状态计算）
                oldEntry.footprintM(),
                true                 // 标记为倒塌
        );

        obstacles.set(idx, newEntry);
        long newVersion = ++version;

        // 计算 RF 传播影响
        String rfImpact = computeRfImpact(oldEntry, newEntry);

        ObstacleUpdateEvent event = new ObstacleUpdateEvent(
                TerrainAlertType.OBSTACLE_UPDATE,
                newEntry,
                rfImpact,
                newVersion
        );

        // 通知订阅者
        for (ObstacleUpdateSubscriber sub : subscribers) {
            try {
                sub.onObstacleUpdated(event);
            } catch (Exception e) {
                SimLog.warn("遮挡更新订阅者通知失败: " + e.getMessage());
            }
        }

        return event;
    }

    /**
     * 计算遮挡变化对 RF 传播的影响（FR-10）。
     * <p>
     * 建筑倒塌 → 遮挡高度降低 → 原非视距路径可能恢复视距 → RSSI 改善。
     *
     * @param oldEntry 倒塌前遮挡条目
     * @param newEntry 倒塌后遮挡条目
     * @return RF 传播影响描述
     */
    private String computeRfImpact(ObstacleEntry oldEntry, ObstacleEntry newEntry) {
        double oldEffHeight = oldEntry.effectiveHeightM();
        double newEffHeight = newEntry.effectiveHeightM();
        double heightDelta = oldEffHeight - newEffHeight;

        if (heightDelta <= 0) {
            return "遮挡高度无变化";
        }

        return String.format(
                "建筑倒塌: 遮挡高度 %.1fm → %.1fm (降低 %.1fm), " +
                "遮挡范围 %.1fm → %.1fm, 原非视距路径可能恢复视距",
                oldEffHeight, newEffHeight, heightDelta,
                oldEntry.effectiveFootprintM(), newEntry.effectiveFootprintM());
    }

    /**
     * 判定两点间 RF 传播路径是否被遮挡（简化模型）。
     * <p>
     * 遍历所有遮挡条目，若路径穿过遮挡条目的有效范围且高度低于有效遮挡高度，
     * 则判定为遮挡。
     *
     * @param lat1    起点纬度
     * @param lon1    起点经度
     * @param alt1M   起点高度 (m AMSL)
     * @param lat2    终点纬度
     * @param lon2    终点经度
     * @param alt2M   终点高度 (m AMSL)
     * @return 遮挡条目列表（空列表 = 无遮挡，视距传播）
     */
    public List<ObstacleEntry> occludingObstacles(double lat1, double lon1, double alt1M,
                                                   double lat2, double lon2, double alt2M) {
        List<ObstacleEntry> result = new ArrayList<>();
        double pathDistance = GeoUtil.north(lat1, lon1, lat2, lon2);

        for (ObstacleEntry obs : obstacles) {
            // 简化判定：检查遮挡条目中心是否在路径附近
            double obsNorthFromStart = GeoUtil.north(lat1, lon1, obs.lat(), obs.lon());
            double obsEastFromStart = GeoUtil.east(lat1, lon1, obs.lat(), obs.lon());

            // 路径方向上的投影距离
            double pathLen = Math.hypot(
                    GeoUtil.north(lat1, lon1, lat2, lon2),
                    GeoUtil.east(lat1, lon1, lat2, lon2));
            if (pathLen < 1) {
                continue;
            }

            // 路径法线方向上的偏移距离
            double pathDirN = GeoUtil.north(lat1, lon1, lat2, lon2) / pathLen;
            double pathDirE = GeoUtil.east(lat1, lon1, lat2, lon2) / pathLen;
            double projAlong = obsNorthFromStart * pathDirN + obsEastFromStart * pathDirE;
            double perpDist = Math.abs(obsEastFromStart * pathDirN - obsNorthFromStart * pathDirE);

            // 在路径范围内且垂直偏移小于遮挡范围
            if (projAlong > 0 && projAlong < pathLen &&
                    perpDist < obs.effectiveFootprintM()) {
                // 路径在该点的高度
                double t = projAlong / pathLen;
                double pathAltAtObs = alt1M + t * (alt2M - alt1M);

                // 路径高度低于遮挡高度 → 遮挡
                if (pathAltAtObs < obs.effectiveHeightM()) {
                    result.add(obs);
                }
            }
        }

        return result;
    }

    /** 订阅遮挡模型更新通知。 */
    public void subscribe(ObstacleUpdateSubscriber subscriber) {
        subscribers.add(subscriber);
    }

    /** 取消订阅。 */
    public void unsubscribe(ObstacleUpdateSubscriber subscriber) {
        subscribers.remove(subscriber);
    }

    /** 当前遮挡模型版本号。 */
    public long version() {
        return version;
    }

    /** 所有遮挡条目（不可变副本）。 */
    public List<ObstacleEntry> obstacles() {
        return Collections.unmodifiableList(obstacles);
    }

    /** 遮挡条目数量。 */
    public int obstacleCount() {
        return obstacles.size();
    }
}
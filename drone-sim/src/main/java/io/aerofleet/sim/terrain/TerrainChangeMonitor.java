package io.aerofleet.sim.terrain;

import io.aerofleet.mavlink.messages.TerrainUpdateMsg;
import io.aerofleet.sim.SimLog;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 灾害地形变更监视器（FR-22 ~ FR-27, §6.4）。
 * <p>
 * 接收灾害事件（地震/泥石流/火灾），更新地形数据，广播 {@link TerrainUpdateMsg} 通知
 * 所有相关模块（RF 模型/飞行约束/mesh 路由）刷新（FR-25）。
 * <p>
 * 变更处理：
 * <ul>
 *   <li>地震（FR-22）：更新建筑高度 → 地形类型可能从 SUPER_HIGH_RISE → OLD_CITY_DENSE（倒塌）→ 刷新遮挡模型</li>
 *   <li>泥石流（FR-23）：更新高程 → 地形类型可能从 MOUNTAIN → HILL → 触发飞行路径重规划</li>
 *   <li>火灾（FR-24）：标注烟雾区域 → 能见度降低 → RF 传播施加轻微烟雾影响</li>
 * </ul>
 * <p>
 * 变更通知广播（FR-25）：同步刷新 TerrainGrid 版本号 + 异步通知所有订阅者。
 * 变更原因标注（FR-27）：携带 EARTHQUAKE/LANDSLIDE/FIRE。
 * <p>
 * 异常处理（§6.4.3）：
 * <ul>
 *   <li>受影响网格数 &gt; 65535 → 分批发送</li>
 *   <li>多灾害并发 → 分别处理 + 合并网格 + 统一通知</li>
 * </ul>
 */
public final class TerrainChangeMonitor {

    private final TerrainGrid terrainGrid;
    private final List<ChangeSubscriber> subscribers = new CopyOnWriteArrayList<>();
    private volatile long terrainVersion;

    /** 变更订阅者接口。 */
    public interface ChangeSubscriber {
        /** 收到变更通知。 */
        void onTerrainChanged(ChangeReason reason, List<Integer> affectedGridIndices,
                              List<TerrainType> newTypes, long newVersion);
    }

    public TerrainChangeMonitor(TerrainGrid terrainGrid) {
        this.terrainGrid = terrainGrid;
        this.terrainVersion = terrainGrid != null ? terrainGrid.version() : 0;
    }

    /**
     * 触发灾害变更（FR-22/23/24）。
     * <p>
     * 更新地形数据 + 广播 {@link TerrainUpdateMsg} 通知所有订阅者。
     *
     * @param reason              变更原因
     * @param affectedGridIndices 受影响网格索引列表
     * @param newTypes            新地形类型列表（与 affectedGridIndices 等长）
     * @return 构造的 TerrainUpdateMsg（可分批）
     */
    public TerrainUpdateMsg onChange(ChangeReason reason,
                                     List<Integer> affectedGridIndices,
                                     List<TerrainType> newTypes) {
        if (terrainGrid == null || affectedGridIndices == null || newTypes == null) {
            return null;
        }
        int n = Math.min(affectedGridIndices.size(), newTypes.size());
        // 1. 更新地形数据
        List<TerrainUpdateMsg.AffectedCell> cells = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int gridIndex = affectedGridIndices.get(i);
            TerrainType newType = newTypes.get(i);
            // 灾害特定处理
            TerrainType effectiveType = applyDisasterEffect(reason, newType);
            terrainGrid.updateCell(gridIndex, effectiveType);
            cells.add(new TerrainUpdateMsg.AffectedCell(gridIndex, effectiveType.code));
        }
        terrainVersion = terrainGrid.version();
        // 2. 构造变更通知消息
        TerrainUpdateMsg msg = new TerrainUpdateMsg(terrainVersion, reason.code, cells);
        // 3. 异步通知所有订阅者（FR-25）
        for (ChangeSubscriber sub : subscribers) {
            try {
                sub.onTerrainChanged(reason, affectedGridIndices, newTypes, terrainVersion);
            } catch (Exception e) {
                SimLog.warn("terrain change subscriber failed: " + e.getMessage());
            }
        }
        return msg;
    }

    /**
     * 灾害特定处理（FR-22/23/24）。
     * <p>
     * 地震：SUPER_HIGH_RISE → OLD_CITY_DENSE（建筑倒塌）。
     * 泥石流：MOUNTAIN → HILL（高程变更）。
     * 火灾：保持原类型但增加烟雾影响（由 RF 模型处理）。
     */
    private TerrainType applyDisasterEffect(ChangeReason reason, TerrainType newType) {
        return switch (reason) {
            case EARTHQUAKE -> newType == TerrainType.SUPER_HIGH_RISE
                    ? TerrainType.OLD_CITY_DENSE
                    : newType;
            case LANDSLIDE -> newType == TerrainType.MOUNTAIN
                    ? TerrainType.HILL
                    : newType;
            case FIRE -> newType;  // 火灾不改变地形类型，仅增加烟雾影响
        };
    }

    /** 订阅变更通知（FR-25）。 */
    public void subscribe(ChangeSubscriber subscriber) {
        subscribers.add(subscriber);
    }

    /** 取消订阅。 */
    public void unsubscribe(ChangeSubscriber subscriber) {
        subscribers.remove(subscriber);
    }

    /** 当前地形版本号。 */
    public long terrainVersion() {
        return terrainVersion;
    }

    /**
     * 分批发送变更通知（§6.4.3 受影响网格数 > 65535）。
     *
     * @param reason              变更原因
     * @param affectedGridIndices 受影响网格索引列表
     * @param newTypes            新地形类型列表
     * @param batchSize           每批大小
     * @return 分批消息列表
     */
    public List<TerrainUpdateMsg> onChangeBatched(ChangeReason reason,
                                                  List<Integer> affectedGridIndices,
                                                  List<TerrainType> newTypes,
                                                  int batchSize) {
        List<TerrainUpdateMsg> batches = new ArrayList<>();
        int total = Math.min(affectedGridIndices.size(), newTypes.size());
        int size = Math.max(1, batchSize);
        for (int start = 0; start < total; start += size) {
            int end = Math.min(start + size, total);
            List<Integer> batchIndices = affectedGridIndices.subList(start, end);
            List<TerrainType> batchTypes = newTypes.subList(start, end);
            batches.add(onChange(reason, batchIndices, batchTypes));
        }
        return batches;
    }
}
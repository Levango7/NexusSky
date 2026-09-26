package io.aerofleet.sim.mesh;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 灾区管理器（灾区通信隔离，FR-04）。
 * <p>
 * 当灾害模式下存在多个独立灾区时，系统支持灾区通信隔离——每个灾区的 mesh 网络
 * 独立运行，跨灾区通信仅通过卫星中继或 HAPS 中继。
 * <p>
 * 核心职责：
 * <ul>
 *   <li>创建灾区（DisasterZone）：每个灾区有唯一 zoneId、地理边界、成员节点集合</li>
 *   <li>节点入区判定：根据节点经纬度判断属于哪个灾区</li>
 *   <li>区内通信隔离：灾区内的 mesh 通信仅走区内路由，不跨区广播</li>
 *   <li>跨区通信仅经卫星/HAPS：跨灾区帧必须标记为 CROSS_ZONE，只能通过卫星或 HAPS 中继转发</li>
 *   <li>灾区合并/分裂：灾区边界变化时支持动态调整</li>
 * </ul>
 * <p>
 * 线程安全：内部 {@link ConcurrentHashMap}，关键字段 volatile。
 */
public final class DisasterZoneManager {

    /** 跨区帧标记。 */
    public static final String CROSS_ZONE_FLAG = "CROSS_ZONE";

    /** 灾区 ID 序列。 */
    private int nextZoneId = 1;

    /** 灾区集合：zoneId → DisasterZone。 */
    private final ConcurrentHashMap<Integer, DisasterZone> zones = new ConcurrentHashMap<>();

    /** 节点到灾区的映射：sysid → zoneId。 */
    private final ConcurrentHashMap<Integer, Integer> nodeToZone = new ConcurrentHashMap<>();

    /** 跨区中继策略。 */
    private final CrossZoneRelayPolicy relayPolicy;

    /** 是否已启用灾区通信隔离。 */
    private volatile boolean isolationEnabled = false;

    /**
     * 构造灾区管理器，使用默认跨区中继策略（仅 SATELLITE + HAPS）。
     */
    public DisasterZoneManager() {
        this.relayPolicy = new CrossZoneRelayPolicy();
    }

    /**
     * 构造灾区管理器，自定义跨区中继策略。
     *
     * @param relayPolicy 跨区中继策略
     */
    public DisasterZoneManager(CrossZoneRelayPolicy relayPolicy) {
        this.relayPolicy = relayPolicy;
    }

    // ------------------------------------------------------------------
    // 灾区生命周期
    // ------------------------------------------------------------------

    /**
     * 创建灾区（FR-04）。
     * <p>
     * 每个灾区有唯一 zoneId、地理边界（GeoBoundary）、成员节点集合。
     * 灾区创建后自动启用通信隔离。
     *
     * @param boundary 地理边界
     * @param members  初始成员节点集合
     * @return 新创建的灾区
     */
    public DisasterZone createZone(GeoBoundary boundary, Set<Integer> members) {
        int zoneId = nextZoneId++;
        long now = System.currentTimeMillis();
        DisasterZone zone = new DisasterZone(zoneId, boundary, members, now,
                DisasterZone.ZoneStatus.ACTIVE);
        zones.put(zoneId, zone);
        for (int sysid : members) {
            nodeToZone.put(sysid, zoneId);
        }
        isolationEnabled = true;
        return zone;
    }

    /**
     * 解散灾区：将灾区状态置为 RECOVERED，清除节点映射。
     *
     * @param zoneId 灾区 ID
     * @return true 若成功解散
     */
    public boolean dissolveZone(int zoneId) {
        DisasterZone zone = zones.get(zoneId);
        if (zone == null) {
            return false;
        }
        zones.put(zoneId, zone.withStatus(DisasterZone.ZoneStatus.RECOVERED));
        for (int sysid : zone.members) {
            nodeToZone.remove(sysid);
        }
        return true;
    }

    /**
     * 灾区合并（FR-04）：将两个灾区合并为一个，被合并灾区状态置为 MERGED。
     *
     * @param sourceZoneId 源灾区 ID（将被合并）
     * @param targetZoneId 目标灾区 ID（合并后保留）
     * @return 合并后的目标灾区；若任一灾区不存在返回 null
     */
    public DisasterZone mergeZones(int sourceZoneId, int targetZoneId) {
        DisasterZone source = zones.get(sourceZoneId);
        DisasterZone target = zones.get(targetZoneId);
        if (source == null || target == null) {
            return null;
        }

        // 合并成员集合
        Set<Integer> mergedMembers = new HashSet<>(target.members);
        mergedMembers.addAll(source.members);

        // 更新节点映射
        for (int sysid : source.members) {
            nodeToZone.put(sysid, targetZoneId);
        }

        // 源灾区标记为 MERGED
        zones.put(sourceZoneId, source.withStatus(DisasterZone.ZoneStatus.MERGED));

        // 目标灾区更新成员
        DisasterZone mergedZone = new DisasterZone(targetZoneId, target.boundary,
                mergedMembers, target.createdAtMs, DisasterZone.ZoneStatus.ACTIVE);
        zones.put(targetZoneId, mergedZone);
        return mergedZone;
    }

    /**
     * 灾区分裂（FR-04）：将一个灾区分裂为两个，原灾区保留一部分成员，
     * 新灾区承载另一部分成员。
     *
     * @param zoneId        原灾区 ID
     * @param splitMembers  分裂出去的成员集合（将归入新灾区）
     * @param newBoundary   新灾区的地理边界
     * @return 新创建的灾区；若原灾区不存在返回 null
     */
    public DisasterZone splitZone(int zoneId, Set<Integer> splitMembers,
                                   GeoBoundary newBoundary) {
        DisasterZone zone = zones.get(zoneId);
        if (zone == null) {
            return null;
        }

        // 原灾区移除分裂成员
        Set<Integer> remainingMembers = new HashSet<>(zone.members);
        remainingMembers.removeAll(splitMembers);
        DisasterZone updatedZone = new DisasterZone(zoneId, zone.boundary,
                remainingMembers, zone.createdAtMs, zone.status);
        zones.put(zoneId, updatedZone);

        // 创建新灾区
        int newZoneId = nextZoneId++;
        long now = System.currentTimeMillis();
        DisasterZone newZone = new DisasterZone(newZoneId, newBoundary,
                splitMembers, now, DisasterZone.ZoneStatus.ACTIVE);
        zones.put(newZoneId, newZone);

        // 更新节点映射
        for (int sysid : splitMembers) {
            nodeToZone.put(sysid, newZoneId);
        }

        return newZone;
    }

    // ------------------------------------------------------------------
    // 节点入区判定
    // ------------------------------------------------------------------

    /**
     * 根据节点经纬度判断属于哪个灾区（FR-04）。
     * <p>
     * 遍历所有活跃灾区，返回第一个包含该坐标的灾区 ID。
     *
     * @param lat 纬度
     * @param lon 经度
     * @return 灾区 ID；不属于任何灾区返回 -1
     */
    public int findZoneByLocation(double lat, double lon) {
        for (DisasterZone zone : zones.values()) {
            if (zone.isActive() && zone.boundary.contains(lat, lon)) {
                return zone.zoneId;
            }
        }
        return -1;
    }

    /**
     * 将节点加入指定灾区。
     *
     * @param sysid 节点 sysid
     * @param zoneId 灾区 ID
     * @return true 若成功加入
     */
    public boolean addNodeToZone(int sysid, int zoneId) {
        DisasterZone zone = zones.get(zoneId);
        if (zone == null || !zone.isActive()) {
            return false;
        }
        // 若节点已在其他灾区，先移除
        Integer oldZoneId = nodeToZone.get(sysid);
        if (oldZoneId != null && oldZoneId != zoneId) {
            DisasterZone oldZone = zones.get(oldZoneId);
            if (oldZone != null) {
                zones.put(oldZoneId, oldZone.removeMember(sysid));
            }
        }
        zones.put(zoneId, zone.addMember(sysid));
        nodeToZone.put(sysid, zoneId);
        return true;
    }

    /**
     * 将节点从灾区移除。
     *
     * @param sysid 节点 sysid
     * @return true 若成功移除
     */
    public boolean removeNodeFromZone(int sysid) {
        Integer zoneId = nodeToZone.remove(sysid);
        if (zoneId == null) {
            return false;
        }
        DisasterZone zone = zones.get(zoneId);
        if (zone != null) {
            zones.put(zoneId, zone.removeMember(sysid));
        }
        return true;
    }

    /**
     * 查询节点所属灾区。
     *
     * @param sysid 节点 sysid
     * @return 灾区 ID；未归属任何灾区返回 -1
     */
    public int getZoneOfNode(int sysid) {
        Integer zid = nodeToZone.get(sysid);
        return zid != null ? zid : -1;
    }

    // ------------------------------------------------------------------
    // 通信隔离判定
    // ------------------------------------------------------------------

    /**
     * 判定两个节点是否在同一灾区内（FR-04）。
     * <p>
     * 同一灾区内的节点可以自主 mesh 通信，不跨区广播。
     *
     * @param sysid1 节点1 sysid
     * @param sysid2 节点2 sysid
     * @return true 若两节点在同一活跃灾区内
     */
    public boolean isSameZone(int sysid1, int sysid2) {
        int z1 = getZoneOfNode(sysid1);
        int z2 = getZoneOfNode(sysid2);
        return z1 != -1 && z1 == z2;
    }

    /**
     * 判定通信是否为跨灾区通信（FR-04）。
     * <p>
     * 跨灾区帧必须标记为 CROSS_ZONE，只能通过卫星或 HAPS 中继转发。
     *
     * @param srcSysid  源节点 sysid
     * @param dstSysid  目标节点 sysid
     * @return true 若为跨灾区通信
     */
    public boolean isCrossZoneCommunication(int srcSysid, int dstSysid) {
        int srcZone = getZoneOfNode(srcSysid);
        int dstZone = getZoneOfNode(dstSysid);
        return srcZone != -1 && dstZone != -1 && srcZone != dstZone;
    }

    /**
     * 判定跨灾区通信是否允许通过指定中继类型转发（FR-04）。
     * <p>
     * 仅允许 SATELLITE / HAPS 中继类型，拒绝 WiFi / LoRa / LTE 跨区转发。
     *
     * @param relayType 中继类型
     * @return true 若该中继类型允许跨区转发
     */
    public boolean isCrossZoneRelayAllowed(CrossZoneRelayPolicy.RelayType relayType) {
        return relayPolicy.isAllowed(relayType);
    }

    /**
     * 判定跨灾区通信是否应被拒绝（非允许中继类型）。
     *
     * @param relayType 中继类型
     * @return true 若该中继类型被拒绝跨区转发
     */
    public boolean isCrossZoneRelayRejected(CrossZoneRelayPolicy.RelayType relayType) {
        return relayPolicy.isRejected(relayType);
    }

    /**
     * 获取跨区中继策略。
     */
    public CrossZoneRelayPolicy getRelayPolicy() {
        return relayPolicy;
    }

    // ------------------------------------------------------------------
    // 灾区查询
    // ------------------------------------------------------------------

    /**
     * 获取指定灾区信息。
     *
     * @param zoneId 灾区 ID
     * @return 灾区对象；不存在返回 null
     */
    public DisasterZone getZone(int zoneId) {
        return zones.get(zoneId);
    }

    /**
     * 获取所有灾区的不可变快照。
     */
    public List<DisasterZone> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(zones.values()));
    }

    /**
     * 获取所有活跃灾区的不可变快照。
     */
    public List<DisasterZone> activeZones() {
        List<DisasterZone> active = new ArrayList<>();
        for (DisasterZone zone : zones.values()) {
            if (zone.isActive()) {
                active.add(zone);
            }
        }
        return Collections.unmodifiableList(active);
    }

    /**
     * 灾区总数。
     */
    public int zoneCount() {
        return zones.size();
    }

    /**
     * 活跃灾区数。
     */
    public int activeZoneCount() {
        int count = 0;
        for (DisasterZone zone : zones.values()) {
            if (zone.isActive()) {
                count++;
            }
        }
        return count;
    }

    /**
     * 获取灾区成员集合。
     *
     * @param zoneId 灾区 ID
     * @return 成员集合的不可变副本；灾区不存在返回空集合
     */
    public Set<Integer> getZoneMembers(int zoneId) {
        DisasterZone zone = zones.get(zoneId);
        return zone != null ? zone.members : Collections.emptySet();
    }

    /**
     * 是否已启用灾区通信隔离。
     */
    public boolean isIsolationEnabled() {
        return isolationEnabled;
    }

    /**
     * 手动启用灾区通信隔离。
     */
    public void enableIsolation() {
        isolationEnabled = true;
    }

    /**
     * 手动禁用灾区通信隔离：清除所有灾区信息。
     */
    public void disableIsolation() {
        isolationEnabled = false;
        zones.clear();
        nodeToZone.clear();
    }
}
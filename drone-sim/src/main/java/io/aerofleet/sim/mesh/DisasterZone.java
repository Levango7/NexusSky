package io.aerofleet.sim.mesh;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 灾区数据模型（灾区通信隔离，FR-04）。
 * <p>
 * 不可变值对象：每次修改返回新实例。承载灾区 ID、地理边界、成员节点集合、
 * 创建时间与状态（ACTIVE/RECOVERED/MERGED）。
 * <p>
 * 灾区内 mesh 通信自主运行，跨灾区通信仅通过卫星/HAPS 中继。
 */
public final class DisasterZone {

    /** 灾区状态枚举。 */
    public enum ZoneStatus {
        /** 活跃：灾区正在运行，通信隔离生效。 */
        ACTIVE(0),
        /** 已恢复：灾区恢复正常，通信隔离解除。 */
        RECOVERED(1),
        /** 已合并：灾区已与其他灾区合并。 */
        MERGED(2);

        private final int code;

        ZoneStatus(int code) {
            this.code = code;
        }

        public int code() {
            return code;
        }

        public static ZoneStatus fromCode(int code) {
            return switch (code) {
                case 0 -> ACTIVE;
                case 1 -> RECOVERED;
                default -> MERGED;
            };
        }
    }

    /** 灾区唯一标识。 */
    public final int zoneId;
    /** 地理边界（多边形）。 */
    public final GeoBoundary boundary;
    /** 成员节点 sysid 集合。 */
    public final Set<Integer> members;
    /** 创建时间戳（ms）。 */
    public final long createdAtMs;
    /** 灾区状态。 */
    public final ZoneStatus status;

    /**
     * 构造灾区。
     *
     * @param zoneId      灾区唯一标识
     * @param boundary    地理边界
     * @param members     成员节点集合
     * @param createdAtMs 创建时间戳
     * @param status      灾区状态
     */
    public DisasterZone(int zoneId, GeoBoundary boundary, Set<Integer> members,
                        long createdAtMs, ZoneStatus status) {
        this.zoneId = zoneId;
        this.boundary = boundary;
        this.members = Collections.unmodifiableSet(new HashSet<>(members));
        this.createdAtMs = createdAtMs;
        this.status = status;
    }

    /**
     * 添加成员：返回包含新成员的新 DisasterZone 实例。
     *
     * @param sysid 新成员 sysid
     * @return 新实例
     */
    public DisasterZone addMember(int sysid) {
        Set<Integer> newMembers = new HashSet<>(this.members);
        newMembers.add(sysid);
        return new DisasterZone(this.zoneId, this.boundary, newMembers,
                this.createdAtMs, this.status);
    }

    /**
     * 移除成员：返回不包含该成员的新 DisasterZone 实例。
     *
     * @param sysid 要移除的成员 sysid
     * @return 新实例
     */
    public DisasterZone removeMember(int sysid) {
        Set<Integer> newMembers = new HashSet<>(this.members);
        newMembers.remove(sysid);
        return new DisasterZone(this.zoneId, this.boundary, newMembers,
                this.createdAtMs, this.status);
    }

    /**
     * 更新边界：返回新边界的新 DisasterZone 实例。
     *
     * @param newBoundary 新地理边界
     * @return 新实例
     */
    public DisasterZone withBoundary(GeoBoundary newBoundary) {
        return new DisasterZone(this.zoneId, newBoundary, this.members,
                this.createdAtMs, this.status);
    }

    /**
     * 更新状态：返回新状态的新 DisasterZone 实例。
     *
     * @param newStatus 新状态
     * @return 新实例
     */
    public DisasterZone withStatus(ZoneStatus newStatus) {
        return new DisasterZone(this.zoneId, this.boundary, this.members,
                this.createdAtMs, newStatus);
    }

    /** 是否包含指定成员。 */
    public boolean contains(int sysid) {
        return members.contains(sysid);
    }

    /** 成员数量。 */
    public int size() {
        return members.size();
    }

    /** 是否为活跃状态。 */
    public boolean isActive() {
        return status == ZoneStatus.ACTIVE;
    }

    @Override
    public String toString() {
        return "DisasterZone{id=" + zoneId + ", status=" + status
                + ", members=" + members + ", boundary=" + boundary + "}";
    }
}
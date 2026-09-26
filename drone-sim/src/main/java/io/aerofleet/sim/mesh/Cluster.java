package io.aerofleet.sim.mesh;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 簇数据模型（灾害应急通讯组网，FR-13/14/15）。
 * <p>
 * 不可变值对象：每次修改返回新实例。承载簇 ID、簇头 sysid、成员集合与簇半径。
 * 簇内通信走簇头转发，簇间通信走簇头间路由。
 */
public final class Cluster {

    /** 簇 ID。 */
    public final int clusterId;
    /** 簇头 sysid（1-255）。 */
    public final int clusterHead;
    /** 簇成员 sysid 集合（含簇头）。 */
    public final Set<Integer> members;
    /** 簇半径（米），簇内成员到簇头的最大距离。 */
    public final double clusterRadius;

    public Cluster(int clusterId, int clusterHead, Set<Integer> members, double clusterRadius) {
        this.clusterId = clusterId;
        this.clusterHead = clusterHead;
        this.members = Collections.unmodifiableSet(new HashSet<>(members));
        this.clusterRadius = clusterRadius;
    }

    /**
     * 添加成员：返回包含新成员的新 Cluster 实例。
     *
     * @param sysid 新成员 sysid
     * @return 新实例
     */
    public Cluster addMember(int sysid) {
        Set<Integer> newMembers = new HashSet<>(this.members);
        newMembers.add(sysid);
        return new Cluster(this.clusterId, this.clusterHead, newMembers, this.clusterRadius);
    }

    /**
     * 移除成员：返回不包含该成员的新 Cluster 实例。
     *
     * @param sysid 要移除的成员 sysid
     * @return 新实例
     */
    public Cluster removeMember(int sysid) {
        Set<Integer> newMembers = new HashSet<>(this.members);
        newMembers.remove(sysid);
        return new Cluster(this.clusterId, this.clusterHead, newMembers, this.clusterRadius);
    }

    /**
     * 更换簇头：返回新簇头的新 Cluster 实例。
     *
     * @param newHead 新簇头 sysid
     * @return 新实例
     */
    public Cluster withClusterHead(int newHead) {
        Set<Integer> newMembers = new HashSet<>(this.members);
        newMembers.add(newHead);
        return new Cluster(this.clusterId, newHead, newMembers, this.clusterRadius);
    }

    /**
     * 更新簇半径：返回新半径的新 Cluster 实例。
     */
    public Cluster withClusterRadius(double newRadius) {
        return new Cluster(this.clusterId, this.clusterHead, this.members, newRadius);
    }

    /** 是否包含指定成员。 */
    public boolean contains(int sysid) {
        return members.contains(sysid);
    }

    /** 簇成员数量（含簇头）。 */
    public int size() {
        return members.size();
    }

    @Override
    public String toString() {
        return "Cluster{id=" + clusterId + ", head=" + clusterHead
                + ", members=" + members + ", radius=" + clusterRadius + "}";
    }
}
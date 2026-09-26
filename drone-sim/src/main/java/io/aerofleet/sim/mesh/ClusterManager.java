package io.aerofleet.sim.mesh;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 分簇管理（灾害应急通讯组网，FR-13/14/15）。
 * <p>
 * 当 mesh 节点数量超过 20 时自动执行分簇，基于节点位置与链路质量将节点划分为簇。
 * 每簇选举簇头（电量>50%、链路质量好、位置居中），簇内通信走簇头转发。
 * 簇头电量<30% 或链路 POOR 时触发簇头轮换。
 * <p>
 * 线程安全：内部 {@link ConcurrentHashMap}。
 */
public final class ClusterManager {

    /** 自动分簇触发阈值：节点数超过此值时自动分簇。 */
    public static final int AUTO_CLUSTER_THRESHOLD = 20;

    /** 簇头选举电量下限（%）。 */
    public static final int CLUSTER_HEAD_BATTERY_MIN = 50;

    /** 簇头轮换电量阈值（%）：低于此值触发轮换。 */
    public static final int CLUSTER_HEAD_ROTATE_BATTERY = 30;

    /** 每簇最大成员数。 */
    public static final int MAX_CLUSTER_SIZE = 10;

    /** 簇 ID 序列。 */
    private int nextClusterId = 1;

    /** 簇集合：clusterId → Cluster。 */
    private final ConcurrentHashMap<Integer, Cluster> clusters = new ConcurrentHashMap<>();

    /** 节点到簇的映射：sysid → clusterId。 */
    private final ConcurrentHashMap<Integer, Integer> nodeToCluster = new ConcurrentHashMap<>();

    /** 节点状态信息（供簇头选举用）。 */
    private final ConcurrentHashMap<Integer, NodeInfo> nodeInfos = new ConcurrentHashMap<>();

    /** 是否已启用分簇。 */
    private volatile boolean clusteringEnabled = false;

    /** 节点状态信息记录。 */
    public record NodeInfo(
            int sysid,
            int batteryPercent,
            LinkQuality linkQuality,
            double lat,
            double lon,
            double avgDistanceToPeers
    ) {}

    /**
     * 自动分簇（FR-13）：节点数超过阈值时自动执行分簇。
     *
     * @param nodeCount 当前节点总数
     * @return true 若触发了分簇操作
     */
    public boolean autoCluster(int nodeCount) {
        if (nodeCount <= AUTO_CLUSTER_THRESHOLD) {
            return false;
        }
        clusteringEnabled = true;
        performClustering();
        return true;
    }

    /**
     * 手动启用分簇。
     */
    public void enableClustering() {
        clusteringEnabled = true;
    }

    /**
     * 手动禁用分簇：清除所有簇信息。
     */
    public void disableClustering() {
        clusteringEnabled = false;
        clusters.clear();
        nodeToCluster.clear();
    }

    /**
     * 执行分簇算法：基于节点位置与链路质量将节点划分为簇。
     * <p>
     * 简化算法：按地理位置就近分组，每簇至多 {@link #MAX_CLUSTER_SIZE} 个成员。
     */
    private void performClustering() {
        List<NodeInfo> nodes = new ArrayList<>(nodeInfos.values());
        if (nodes.isEmpty()) {
            return;
        }

        // 清除旧簇
        clusters.clear();
        nodeToCluster.clear();

        // 按经纬度排序后分组（简化分簇算法）
        nodes.sort((a, b) -> {
            int cmp = Double.compare(a.lat(), b.lat());
            return cmp != 0 ? cmp : Double.compare(a.lon(), b.lon());
        });

        int clusterId = nextClusterId;
        Set<Integer> currentMembers = new HashSet<>();
        double clusterRadius = 0;

        for (NodeInfo node : nodes) {
            currentMembers.add(node.sysid());

            // 计算簇半径（成员到簇内中心的近似距离）
            if (currentMembers.size() > 1) {
                clusterRadius = Math.max(clusterRadius, estimateClusterRadius(currentMembers));
            }

            // 簇满或最后一个节点：创建簇
            if (currentMembers.size() >= MAX_CLUSTER_SIZE || node == nodes.get(nodes.size() - 1)) {
                Cluster cluster = new Cluster(clusterId, -1, currentMembers, clusterRadius);
                // 选举簇头
                int head = electClusterHeadInternal(clusterId, currentMembers);
                cluster = cluster.withClusterHead(head);
                clusters.put(clusterId, cluster);
                for (int member : currentMembers) {
                    nodeToCluster.put(member, clusterId);
                }
                clusterId++;
                currentMembers = new HashSet<>();
                clusterRadius = 0;
            }
        }
        nextClusterId = clusterId;
    }

    /**
     * 估算簇半径：成员间最大距离的一半。
     */
    private double estimateClusterRadius(Set<Integer> members) {
        double maxDist = 0;
        List<NodeInfo> infos = new ArrayList<>();
        for (int sysid : members) {
            NodeInfo info = nodeInfos.get(sysid);
            if (info != null) {
                infos.add(info);
            }
        }
        for (int i = 0; i < infos.size(); i++) {
            for (int j = i + 1; j < infos.size(); j++) {
                double dist = haversineDistance(
                        infos.get(i).lat(), infos.get(i).lon(),
                        infos.get(j).lat(), infos.get(j).lon());
                maxDist = Math.max(maxDist, dist);
            }
        }
        return maxDist / 2.0;
    }

    /**
     * Haversine 公式计算两点间距离（米）。
     */
    private static double haversineDistance(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371000; // 地球半径（米）
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }

    /**
     * 簇头选举（FR-14）：综合考虑电量（>50%）、链路质量（EXCELLENT/GOOD）、位置居中。
     *
     * @param clusterId 簇 ID
     * @return 选出的簇头 sysid；若无合格候选则返回簇内第一个成员
     */
    public int electClusterHead(int clusterId) {
        Cluster cluster = clusters.get(clusterId);
        if (cluster == null) {
            throw new IllegalArgumentException("Cluster not found: " + clusterId);
        }
        int head = electClusterHeadInternal(clusterId, cluster.members);
        Cluster updated = cluster.withClusterHead(head);
        clusters.put(clusterId, updated);
        return head;
    }

    /**
     * 簇头选举内部实现。
     * <p>
     * 选举标准（加权评分）：
     * <ul>
     *   <li>电量评分：电量百分比（0-100），需 >50% 才合格</li>
     *   <li>链路评分：EXCELLENT=4, GOOD=3, FAIR=2, POOR=1</li>
     *   <li>位置评分：距簇内平均距离越小越好</li>
     * </ul>
     * 综合评分 = 电量×0.4 + 链路×0.3 + 位置×0.3
     */
    private int electClusterHeadInternal(int clusterId, Set<Integer> members) {
        NodeInfo best = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (int sysid : members) {
            NodeInfo info = nodeInfos.get(sysid);
            if (info == null) {
                continue;
            }
            // 电量需 >50% 才合格
            if (info.batteryPercent() < CLUSTER_HEAD_BATTERY_MIN) {
                continue;
            }
            // 链路质量需 GOOD 以上
            if (info.linkQuality() != LinkQuality.EXCELLENT
                    && info.linkQuality() != LinkQuality.GOOD) {
                continue;
            }

            // 综合评分
            double batteryScore = info.batteryPercent() / 100.0;
            double linkScore = (4 - info.linkQuality().code()) / 4.0;
            double positionScore = 1.0 - Math.min(info.avgDistanceToPeers() / 5000.0, 1.0);
            double totalScore = batteryScore * 0.4 + linkScore * 0.3 + positionScore * 0.3;

            if (totalScore > bestScore) {
                bestScore = totalScore;
                best = info;
            }
        }

        // 无合格候选：返回簇内第一个成员（降级处理）
        if (best == null) {
            return members.iterator().next();
        }
        return best.sysid();
    }

    /**
     * 簇头轮换（FR-15）：簇头电量<30% 或链路 POOR 时触发。
     *
     * @param clusterId 簇 ID
     * @return 新簇头 sysid；若无需轮换返回原簇头
     */
    public int rotateClusterHead(int clusterId) {
        Cluster cluster = clusters.get(clusterId);
        if (cluster == null) {
            throw new IllegalArgumentException("Cluster not found: " + clusterId);
        }

        NodeInfo headInfo = nodeInfos.get(cluster.clusterHead);
        if (headInfo == null) {
            // 簇头信息缺失，直接重新选举
            return electClusterHead(clusterId);
        }

        // 检查是否需要轮换：电量<30% 或链路 POOR
        boolean needRotate = headInfo.batteryPercent() < CLUSTER_HEAD_ROTATE_BATTERY
                || headInfo.linkQuality() == LinkQuality.POOR;

        if (!needRotate) {
            return cluster.clusterHead;
        }

        // 重新选举新簇头（排除当前簇头）
        int newHead = electClusterHeadExcluding(clusterId, cluster.clusterHead);
        Cluster updated = cluster.withClusterHead(newHead);
        clusters.put(clusterId, updated);
        return newHead;
    }

    /**
     * 簇头选举（排除指定节点）。
     */
    private int electClusterHeadExcluding(int clusterId, int excludeSysid) {
        Cluster cluster = clusters.get(clusterId);
        Set<Integer> candidates = new HashSet<>(cluster.members);
        candidates.remove(excludeSysid);

        if (candidates.isEmpty()) {
            return cluster.clusterHead; // 无其他候选，保持原簇头
        }

        return electClusterHeadInternal(clusterId, candidates);
    }

    /**
     * 查询节点所属簇（FR-13）。
     *
     * @param sysid 节点 sysid
     * @return 簇 ID；未分簇时返回 -1
     */
    public int getCluster(int sysid) {
        Integer cid = nodeToCluster.get(sysid);
        return cid != null ? cid : -1;
    }

    /**
     * 获取指定簇信息。
     */
    public Cluster getClusterInfo(int clusterId) {
        return clusters.get(clusterId);
    }

    /**
     * 更新节点状态信息（供簇头选举用）。
     */
    public void updateNodeInfo(NodeInfo info) {
        nodeInfos.put(info.sysid(), info);
    }

    /**
     * 获取所有簇的快照。
     */
    public List<Cluster> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(clusters.values()));
    }

    /**
     * 是否已启用分簇。
     */
    public boolean isClusteringEnabled() {
        return clusteringEnabled;
    }

    /**
     * 簇数量。
     */
    public int clusterCount() {
        return clusters.size();
    }

    /**
     * 获取簇头 sysid。
     *
     * @param clusterId 簇 ID
     * @return 簇头 sysid；簇不存在返回 -1
     */
    public int getClusterHead(int clusterId) {
        Cluster cluster = clusters.get(clusterId);
        return cluster != null ? cluster.clusterHead : -1;
    }

    /**
     * 获取簇成员集合。
     *
     * @param clusterId 簇 ID
     * @return 成员集合的不可变副本；簇不存在返回空集合
     */
    public Set<Integer> getClusterMembers(int clusterId) {
        Cluster cluster = clusters.get(clusterId);
        return cluster != null ? cluster.members : Collections.emptySet();
    }
}
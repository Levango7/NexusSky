package io.aerofleet.sim.mesh;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多路径冗余路由（灾害应急通讯组网，FR-17）。
 * <p>
 * 维护主路径 + 最多 2 条备用路径（节点不相交）。
 * 主路径断裂时无缝切换到备用路径，无需重新 RREQ。
 * <p>
 * 线程安全：内部 {@link ConcurrentHashMap}。
 */
public final class MultiPathRouter {

    /** 每目标最多备用路径数。 */
    public static final int MAX_BACKUP_ROUTES = 2;

    /** 路径状态枚举。 */
    public enum PathStatus {
        /** 主路径，当前活跃。 */
        PRIMARY,
        /** 备用路径，待切换。 */
        BACKUP,
        /** 路径已断裂。 */
        BROKEN
    }

    /** 路径条目：路由 + 状态 + 路径节点集合（用于不相交验证）。 */
    public record PathEntry(
            RouteEntry route,
            PathStatus status,
            Set<Integer> intermediateNodes
    ) {
        /**
         * 是否与另一路径节点不相交（除源/目标外）。
         *
         * @param other 另一条路径
         * @return true 若两路径中间节点不相交
         */
        public boolean isNodeDisjoint(PathEntry other) {
            Set<Integer> intersection = new HashSet<>(this.intermediateNodes);
            intersection.retainAll(other.intermediateNodes);
            return intersection.isEmpty();
        }
    }

    /** 目标 → 路径列表（主 + 备份）。 */
    private final ConcurrentHashMap<Integer, List<PathEntry>> pathTable = new ConcurrentHashMap<>();

    /**
     * 添加主路径（FR-17）。
     * <p>
     * 若已有主路径，旧主降为备份（若节点不相交）。
     *
     * @param target 目标 sysid
     * @param route  路由表项
     */
    public void addPrimaryRoute(int target, RouteEntry route) {
        addPrimaryRoute(target, route, extractIntermediateNodes(route));
    }

    /**
     * 添加主路径（带中间节点集合）。
     */
    public void addPrimaryRoute(int target, RouteEntry route, Set<Integer> intermediateNodes) {
        PathEntry newPrimary = new PathEntry(route, PathStatus.PRIMARY, intermediateNodes);
        pathTable.compute(target, (k, existing) -> {
            if (existing == null || existing.isEmpty()) {
                List<PathEntry> list = new ArrayList<>();
                list.add(newPrimary);
                return list;
            }
            List<PathEntry> list = new ArrayList<>(existing.size() + 1);
            // 旧主降为备份
            for (PathEntry pe : existing) {
                if (pe.status() == PathStatus.PRIMARY) {
                    list.add(new PathEntry(pe.route(), PathStatus.BACKUP, pe.intermediateNodes()));
                } else if (pe.status() == PathStatus.BACKUP) {
                    list.add(pe);
                }
            }
            list.add(newPrimary);
            // 限制备用路径数量
            enforceBackupLimit(list);
            return list;
        });
    }

    /**
     * 添加备用路径（FR-17）。
     * <p>
     * 备用路径必须与主路径节点不相交（除源/目标外）。
     *
     * @param target 目标 sysid
     * @param backup 备用路由表项
     * @return true 若添加成功（节点不相交且未超过上限）
     */
    public boolean addBackupRoute(int target, RouteEntry backup) {
        return addBackupRoute(target, backup, extractIntermediateNodes(backup));
    }

    /**
     * 添加备用路径（带中间节点集合）。
     */
    public boolean addBackupRoute(int target, RouteEntry backup, Set<Integer> intermediateNodes) {
        PathEntry newBackup = new PathEntry(backup, PathStatus.BACKUP, intermediateNodes);
        final boolean[] added = {false};
        pathTable.compute(target, (k, existing) -> {
            if (existing == null || existing.isEmpty()) {
                // 无主路径时，备用路径直接成为主路径
                List<PathEntry> list = new ArrayList<>();
                list.add(new PathEntry(backup, PathStatus.PRIMARY, intermediateNodes));
                added[0] = true;
                return list;
            }
            // 检查与所有现有路径的节点不相交性
            for (PathEntry pe : existing) {
                if (!newBackup.isNodeDisjoint(pe)) {
                    return existing; // 不相交检查失败，不添加
                }
            }
            // 检查备用路径数量上限
            long backupCount = existing.stream()
                    .filter(pe -> pe.status() == PathStatus.BACKUP)
                    .count();
            if (backupCount >= MAX_BACKUP_ROUTES) {
                return existing; // 已达上限
            }
            List<PathEntry> list = new ArrayList<>(existing);
            list.add(newBackup);
            added[0] = true;
            return list;
        });
        return added[0];
    }

    /**
     * 主路径断裂时无缝切换到备用路径（FR-17）。
     * <p>
     * 将主路径标记为 BROKEN，将最优备用路径提升为主路径。
     *
     * @param target 目标 sysid
     * @return 新主路径的 RouteEntry；无备用路径时返回 null
     */
    public RouteEntry switchToBackup(int target) {
        final RouteEntry[] result = {null};
        pathTable.computeIfPresent(target, (k, existing) -> {
            List<PathEntry> list = new ArrayList<>(existing.size());
            PathEntry bestBackup = null;
            // 标记主路径为 BROKEN，寻找最优备用
            for (PathEntry pe : existing) {
                if (pe.status() == PathStatus.PRIMARY) {
                    list.add(new PathEntry(pe.route(), PathStatus.BROKEN, pe.intermediateNodes()));
                } else if (pe.status() == PathStatus.BACKUP) {
                    if (bestBackup == null
                            || pe.route().metric < bestBackup.route().metric) {
                        bestBackup = pe;
                    }
                    list.add(pe);
                } else {
                    list.add(pe);
                }
            }
            if (bestBackup != null) {
                // 提升最优备用为主路径
                for (int i = 0; i < list.size(); i++) {
                    if (list.get(i) == bestBackup) {
                        list.set(i, new PathEntry(
                                bestBackup.route().withPrimary(true),
                                PathStatus.PRIMARY,
                                bestBackup.intermediateNodes()));
                        result[0] = bestBackup.route().withPrimary(true);
                        break;
                    }
                }
            }
            return list;
        });
        return result[0];
    }

    /**
     * 获取主路径。
     *
     * @param target 目标 sysid
     * @return 主路径 RouteEntry；无主路径返回 null
     */
    public RouteEntry getPrimaryRoute(int target) {
        List<PathEntry> paths = pathTable.get(target);
        if (paths == null) {
            return null;
        }
        for (PathEntry pe : paths) {
            if (pe.status() == PathStatus.PRIMARY) {
                return pe.route();
            }
        }
        return null;
    }

    /**
     * 获取所有备用路径。
     *
     * @param target 目标 sysid
     * @return 备用路径列表（可能为空）
     */
    public List<RouteEntry> getBackupRoutes(int target) {
        List<PathEntry> paths = pathTable.get(target);
        if (paths == null) {
            return Collections.emptyList();
        }
        List<RouteEntry> backups = new ArrayList<>();
        for (PathEntry pe : paths) {
            if (pe.status() == PathStatus.BACKUP) {
                backups.add(pe.route());
            }
        }
        return backups;
    }

    /**
     * 获取到指定目标的所有路径（主 + 备份）。
     */
    public List<PathEntry> getAllPaths(int target) {
        List<PathEntry> paths = pathTable.get(target);
        if (paths == null) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(paths));
    }

    /**
     * 备用路径数量。
     */
    public int backupCount(int target) {
        List<PathEntry> paths = pathTable.get(target);
        if (paths == null) {
            return 0;
        }
        int count = 0;
        for (PathEntry pe : paths) {
            if (pe.status() == PathStatus.BACKUP) {
                count++;
            }
        }
        return count;
    }

    /**
     * 移除指定目标的所有路径。
     */
    public void removeTarget(int target) {
        pathTable.remove(target);
    }

    /**
     * 移除所有以指定节点为下一跳的路径。
     */
    public void removeByNextHop(int nextHop) {
        for (var e : pathTable.entrySet()) {
            pathTable.computeIfPresent(e.getKey(), (k, existing) -> {
                List<PathEntry> kept = new ArrayList<>();
                for (PathEntry pe : existing) {
                    if (pe.route().nextHop != nextHop) {
                        kept.add(pe);
                    }
                }
                return kept.isEmpty() ? null : kept;
            });
        }
    }

    /**
     * 路径总数（所有目标的主+备份）。
     */
    public int size() {
        int total = 0;
        for (List<PathEntry> paths : pathTable.values()) {
            total += paths.size();
        }
        return total;
    }

    /**
     * 清空所有路径。
     */
    public void clear() {
        pathTable.clear();
    }

    /**
     * 从 RouteEntry 提取中间节点集合（简化：仅含 nextHop）。
     * <p>
     * 实际场景中应包含完整路径上的所有中间节点，此处简化为 nextHop。
     */
    private static Set<Integer> extractIntermediateNodes(RouteEntry route) {
        Set<Integer> nodes = new HashSet<>();
        if (route.nextHop != route.targetSysId) {
            nodes.add(route.nextHop);
        }
        return nodes;
    }

    /**
     * 限制备用路径数量：保留 metric 最小的。
     */
    private static void enforceBackupLimit(List<PathEntry> list) {
        long backupCount = list.stream()
                .filter(pe -> pe.status() == PathStatus.BACKUP)
                .count();
        if (backupCount > MAX_BACKUP_ROUTES) {
            // 按 metric 排序备份路径，保留最优的 MAX_BACKUP_ROUTES 条
            List<PathEntry> backups = list.stream()
                    .filter(pe -> pe.status() == PathStatus.BACKUP)
                    .sorted((a, b) -> Double.compare(a.route().metric, b.route().metric))
                    .toList();
            List<PathEntry> keptBackups = backups.subList(0, MAX_BACKUP_ROUTES);
            list.removeIf(pe -> pe.status() == PathStatus.BACKUP
                    && !keptBackups.contains(pe));
        }
    }
}
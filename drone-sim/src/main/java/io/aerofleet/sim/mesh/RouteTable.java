package io.aerofleet.sim.mesh;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 路由表容器（M5 应急 mesh，FR-17/18/21）。
 * <p>
 * 内部 {@link ConcurrentHashMap}，线程安全。key 为 targetSysId，value 为到该目标的主+备份路径列表（至多 2 条）。
 * <p>
 * 支持路由建立/查找/删除/过期清理/主备切换/快照。
 */
public final class RouteTable {

    /** 每目标至多保留的路由数（主 + 备份）。 */
    public static final int MAX_ROUTES_PER_TARGET = 2;

    private final ConcurrentHashMap<Integer, List<RouteEntry>> table = new ConcurrentHashMap<>();
    private final long defaultLifetimeMs;

    public RouteTable(long defaultLifetimeMs) {
        this.defaultLifetimeMs = defaultLifetimeMs;
    }

    /**
     * 查找主路径（FR-17）：返回 isPrimary=true 且未过期的路由；无则 null。
     */
    public RouteEntry lookup(int targetSysId) {
        List<RouteEntry> routes = table.get(targetSysId);
        if (routes == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        for (RouteEntry r : routes) {
            if (r.isPrimary && !r.isExpired(now)) {
                return r;
            }
        }
        return null;
    }

    /**
     * 查找任意有效项：优先主路径，其次备份；无则 null。
     */
    public RouteEntry lookupAny(int targetSysId) {
        RouteEntry primary = lookup(targetSysId);
        if (primary != null) {
            return primary;
        }
        List<RouteEntry> routes = table.get(targetSysId);
        if (routes == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        for (RouteEntry r : routes) {
            if (!r.isExpired(now)) {
                return r;
            }
        }
        return null;
    }

    /**
     * 新增或更新路由项（FR-17/18）。
     * <p>
     * 若到目标已有主路径：
     * <ul>
     *   <li>新路径 metric 更小 → 替换为主，旧主降为备份</li>
     *   <li>否则若不相交（nextHop 不同）→ 作为备份</li>
     * </ul>
     * 到同一目标至多保留 {@link #MAX_ROUTES_PER_TARGET} 条路径。
     */
    public void upsert(int targetSysId, int nextHop, int hopCount, double metric,
                       long nowMs, boolean isPrimary) {
        RouteEntry newEntry = new RouteEntry(targetSysId, nextHop, hopCount, metric,
                nowMs, nowMs + defaultLifetimeMs, isPrimary);
        table.compute(targetSysId, (k, existing) -> {
            if (existing == null || existing.isEmpty()) {
                List<RouteEntry> list = new ArrayList<>();
                list.add(newEntry);
                return list;
            }
            // 查找同 nextHop 的现有项：替换（刷新）
            for (int i = 0; i < existing.size(); i++) {
                RouteEntry r = existing.get(i);
                if (r.nextHop == nextHop) {
                    List<RouteEntry> list = new ArrayList<>(existing);
                    // 新项继承主备标志：若 isPrimary 或原项是 primary
                    boolean primary = isPrimary || r.isPrimary;
                    list.set(i, newEntry.withPrimary(primary));
                    // 若新 metric 更小且 primary，确保降级其他项
                    if (primary && newEntry.metric < r.metric) {
                        for (int j = 0; j < list.size(); j++) {
                            if (j != i && list.get(j).isPrimary) {
                                list.set(j, list.get(j).withPrimary(false));
                            }
                        }
                    }
                    return list;
                }
            }
            // 新 nextHop：若 isPrimary 且已有主路径，比较 metric
            List<RouteEntry> list = new ArrayList<>(existing);
            if (isPrimary) {
                RouteEntry currentPrimary = null;
                for (RouteEntry r : list) {
                    if (r.isPrimary) {
                        currentPrimary = r;
                        break;
                    }
                }
                if (currentPrimary != null) {
                    if (newEntry.metric < currentPrimary.metric) {
                        // 新路径更优 → 新为主，旧主降为备份
                        for (int i = 0; i < list.size(); i++) {
                            if (list.get(i).isPrimary) {
                                list.set(i, list.get(i).withPrimary(false));
                            }
                        }
                        list.add(newEntry);
                    } else {
                        // 旧主更优 → 新作为备份
                        list.add(newEntry.withPrimary(false));
                    }
                } else {
                    list.add(newEntry);
                }
            } else {
                list.add(newEntry);
            }
            // 限制至多 MAX_ROUTES_PER_TARGET 条：保留 metric 最小的
            if (list.size() > MAX_ROUTES_PER_TARGET) {
                list.sort((a, b) -> Double.compare(a.metric, b.metric));
                list = new ArrayList<>(list.subList(0, MAX_ROUTES_PER_TARGET));
            }
            return list;
        });
    }

    /** 删除所有到 target 的项。 */
    public void remove(int targetSysId) {
        table.remove(targetSysId);
    }

    /**
     * 删除所有以 nextHop 为下一跳的项（FR-13）。
     * <p>
     * P1-fix(Critical 2): 使用 {@code table.compute} 原子替换整个 list，
     * 创建新 list 过滤后替换，而非直接修改原 list，避免并发遍历时
     * 抛 ConcurrentModificationException。
     */
    public void removeByNextHop(int nextHop) {
        for (var e : table.entrySet()) {
            table.compute(e.getKey(), (k, existing) -> {
                if (existing == null || existing.isEmpty()) {
                    return null;
                }
                List<RouteEntry> kept = new ArrayList<>();
                for (RouteEntry r : existing) {
                    if (r.nextHop != nextHop) {
                        kept.add(r);
                    }
                }
                return kept.isEmpty() ? null : kept;
            });
        }
    }

    /**
     * 返回以 nextHop 为下一跳的所有 target（FR-13）。
     */
    public List<Integer> findAffectedTargets(int nextHop) {
        List<Integer> targets = new ArrayList<>();
        for (var e : table.entrySet()) {
            for (RouteEntry r : e.getValue()) {
                if (r.nextHop == nextHop) {
                    targets.add(e.getKey());
                    break;
                }
            }
        }
        return targets;
    }

    /**
     * 备份路径提升为主（FR-16）。
     * <p>
     * 若到 target 有备份路径，将 metric 最小的备份提升为主，原主（若有）降为备份。
     *
     * @return true 若提升成功
     */
    public boolean promoteBackup(int targetSysId) {
        List<RouteEntry> routes = table.get(targetSysId);
        if (routes == null || routes.isEmpty()) {
            return false;
        }
        // 查找备份（非 primary 且未过期）
        long now = System.currentTimeMillis();
        RouteEntry bestBackup = null;
        for (RouteEntry r : routes) {
            if (!r.isPrimary && !r.isExpired(now)) {
                if (bestBackup == null || r.metric < bestBackup.metric) {
                    bestBackup = r;
                }
            }
        }
        if (bestBackup == null) {
            return false;
        }
        // 原子替换：原主降为备份，最佳备份提升为主
        final RouteEntry backup = bestBackup;
        table.compute(targetSysId, (k, existing) -> {
            List<RouteEntry> list = new ArrayList<>();
            for (RouteEntry r : existing) {
                if (r.nextHop == backup.nextHop) {
                    list.add(r.withPrimary(true));
                } else if (r.isPrimary) {
                    list.add(r.withPrimary(false));
                } else {
                    list.add(r);
                }
            }
            return list;
        });
        return true;
    }

    /**
     * 清理过期项（FR-21），返回被清理列表。
     */
    public List<RouteEntry> cleanupExpired(long nowMs) {
        List<RouteEntry> removed = new ArrayList<>();
        for (var e : table.entrySet()) {
            List<RouteEntry> kept = new ArrayList<>();
            for (RouteEntry r : e.getValue()) {
                if (r.isExpired(nowMs)) {
                    removed.add(r);
                } else {
                    kept.add(r);
                }
            }
            if (kept.isEmpty()) {
                table.remove(e.getKey());
            } else if (kept.size() != e.getValue().size()) {
                table.put(e.getKey(), kept);
            }
        }
        return removed;
    }

    /**
     * 不可变快照（供 REST/拓扑上报用）。
     */
    public List<RouteEntry> snapshot() {
        List<RouteEntry> all = new ArrayList<>();
        for (List<RouteEntry> routes : table.values()) {
            all.addAll(routes);
        }
        return Collections.unmodifiableList(all);
    }

    /** 当前路由表项总数（含主备）。 */
    public int size() {
        int n = 0;
        for (List<RouteEntry> routes : table.values()) {
            n += routes.size();
        }
        return n;
    }

    /** 清空路由表。 */
    public void clear() {
        table.clear();
    }
}
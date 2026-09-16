package io.aerofleet.sim.mesh;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RREQ 去重缓存（M5 应急 mesh，FR-05）。
 * <p>
 * 内部固定容量 256 的环形缓冲（LRU 淘汰最旧条目）。key 为 (sourceSysid, broadcastId) 复合键。
 * <p>
 * 使用范式：{@code MeshRouter.onRouteRequest} 收到 RREQ 时调 {@link #seenAndRecord}，
 * 已见过返回 true 丢弃，首次返回 false 并记录。
 */
public final class RreqCache {

    /** 固定容量。 */
    public static final int CAPACITY = 256;

    /** LRU 缓存：访问顺序，容量满时淘汰最旧。 */
    private final LinkedHashMap<Long, Boolean> cache =
            new LinkedHashMap<>(CAPACITY, 0.75f, true);

    /**
     * 复合 key：高 16 位 sourceSysid，低 16 位 broadcastId。
     */
    private static long key(int sourceSysid, int broadcastId) {
        return ((long) (sourceSysid & 0xFFFF) << 16) | (broadcastId & 0xFFFFL);
    }

    /**
     * 检查是否已见过，并记录（若首次）。
     *
     * @param sourceSysid RREQ 源 sysid
     * @param broadcastId RREQ 广播 ID
     * @return true 若已见过（丢弃）；false 若首次（并记录）
     */
    public synchronized boolean seenAndRecord(int sourceSysid, int broadcastId) {
        long k = key(sourceSysid, broadcastId);
        if (cache.containsKey(k)) {
            return true;
        }
        // 容量满时淘汰最旧（LRU）
        if (cache.size() >= CAPACITY) {
            Map.Entry<Long, Boolean> eldest = cache.entrySet().iterator().next();
            cache.remove(eldest.getKey());
        }
        cache.put(k, Boolean.TRUE);
        return false;
    }

    /** 清空缓存。 */
    public synchronized void clear() {
        cache.clear();
    }

    /** 当前缓存条目数。 */
    public synchronized int size() {
        return cache.size();
    }
}
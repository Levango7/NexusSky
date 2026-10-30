package io.aerofleet.cloud.api.pusher;

import java.util.HashMap;
import java.util.Map;

/**
 * 事件去重窗口：同一 key 在窗口期内只放行一次，并按窗口裁剪已过期 key。
 *
 * <p>为什么不是调用方各存一个裸 {@code Map}：key 是「事件类型 + 源 sysid + 目标 sysid」，
 * 机队 N 台时 key 空间是 O(N²)。只 put 不删的 {@code HashMap} 会随**历史上见过的事件组合**
 * 单调增长——与「只保留最近 1 秒」的 debounce 语义毫无关系。{@link #prune(long)} 把窗口外的
 * key 清掉，缓存大小只与窗口内事件数相关。
 *
 * <p>非线程安全：两个 pusher 都只在各自的 {@code @Scheduled} 单线程回调里使用。
 */
final class DebounceWindow {

    private final long ttlMs;
    private final Map<String, Long> lastSeen = new HashMap<>();

    DebounceWindow(long ttlMs) {
        if (ttlMs <= 0) {
            throw new IllegalArgumentException("ttlMs must be positive");
        }
        this.ttlMs = ttlMs;
    }

    /**
     * 窗口内已放行过同名 key ⇒ {@code false}；否则记录本次时间并放行。
     * 语义与原有内联判据 {@code lastSeen == null || now - lastSeen > DEBOUNCE_MS} 一致：
     * 恰好等于窗口边界时仍视为窗口内（抑制）。
     */
    boolean allow(String key, long now) {
        Long previous = lastSeen.get(key);
        if (previous != null && now - previous <= ttlMs) {
            return false;
        }
        lastSeen.put(key, now);
        return true;
    }

    /** 丢弃窗口外的 key，保证缓存不随历史事件组合增长。 */
    void prune(long now) {
        lastSeen.entrySet().removeIf(entry -> now - entry.getValue() > ttlMs);
    }

    /** 窗口内 key 数（测试可观测性，用于断言缓存有界）。 */
    int size() {
        return lastSeen.size();
    }
}

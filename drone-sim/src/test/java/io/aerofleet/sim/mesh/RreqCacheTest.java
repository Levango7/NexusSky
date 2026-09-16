package io.aerofleet.sim.mesh;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RreqCache RREQ 去重缓存单测（M5 应急 mesh，FR-05）。
 * <p>
 * 覆盖首次/重复判定、不同 broadcastId、LRU 淘汰、clear/size。
 */
@DisplayName("RreqCache 去重缓存 (FR-05)")
class RreqCacheTest {

    @Test
    @DisplayName("首次 seenAndRecord 返回 false 并记录")
    void firstSeenReturnsFalse() {
        RreqCache cache = new RreqCache();
        assertThat(cache.seenAndRecord(1, 100)).isFalse();
        assertThat(cache.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("重复 (source, broadcastId) seenAndRecord 返回 true")
    void secondSeenReturnsTrue() {
        RreqCache cache = new RreqCache();
        cache.seenAndRecord(1, 100);
        assertThat(cache.seenAndRecord(1, 100)).isTrue();
        assertThat(cache.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("不同 broadcastId 不视为重复")
    void differentBroadcastIdNotDuplicate() {
        RreqCache cache = new RreqCache();
        cache.seenAndRecord(1, 100);
        assertThat(cache.seenAndRecord(1, 101)).isFalse();
        assertThat(cache.seenAndRecord(2, 100)).isFalse();
        assertThat(cache.size()).isEqualTo(3);
    }

    @Test
    @DisplayName("容量满 256 时 LRU 淘汰最旧条目")
    void lruEvictionAtCapacity() {
        RreqCache cache = new RreqCache();
        // 填满 256 条
        for (int i = 0; i < RreqCache.CAPACITY; i++) {
            assertThat(cache.seenAndRecord(1, i)).isFalse();
        }
        assertThat(cache.size()).isEqualTo(RreqCache.CAPACITY);

        // 插入第 257 条 → 淘汰最旧 (1,0)
        assertThat(cache.seenAndRecord(1, 256)).isFalse();
        assertThat(cache.size()).isEqualTo(RreqCache.CAPACITY);

        // (1,0) 已被淘汰 → 再次插入应返回 false（视为首次）
        assertThat(cache.seenAndRecord(1, 0)).isFalse();
    }

    @Test
    @DisplayName("clear 清空缓存，size 归零")
    void clearAndSize() {
        RreqCache cache = new RreqCache();
        cache.seenAndRecord(1, 10);
        cache.seenAndRecord(2, 20);
        assertThat(cache.size()).isEqualTo(2);

        cache.clear();
        assertThat(cache.size()).isZero();
        // clear 后再次插入应视为首次
        assertThat(cache.seenAndRecord(1, 10)).isFalse();
    }

    @Test
    @DisplayName("CAPACITY 常量为 256")
    void capacityConstant() {
        assertThat(RreqCache.CAPACITY).isEqualTo(256);
    }
}
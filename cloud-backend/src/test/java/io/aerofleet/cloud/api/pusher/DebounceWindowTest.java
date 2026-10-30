package io.aerofleet.cloud.api.pusher;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link DebounceWindow} 单测：窗口抑制、边界、按窗口裁剪（缓存有界）。
 *
 * <p>背景：两个拓扑 pusher 原来各持一张只 put 不删的 {@code HashMap}，key 是
 * 「事件类型 + 源 sysid + 目标 sysid」，机队 N 台时 key 空间 O(N²)，随历史事件单调增长。
 * 这里用合成时间戳验证裁剪逻辑本身（不依赖真实时钟）。
 */
@DisplayName("DebounceWindow 事件去重窗口")
class DebounceWindowTest {

    @Test
    @DisplayName("窗口内同名 key 只放行一次；超过窗口后再次放行")
    void allowsOnlyOncePerWindow() {
        DebounceWindow window = new DebounceWindow(1000);

        assertThat(window.allow("A", 10_000)).isTrue();
        assertThat(window.allow("A", 10_500)).isFalse();
        assertThat(window.allow("A", 11_001)).isTrue();
    }

    @Test
    @DisplayName("恰好落在窗口边界上仍视为窗口内（与原判据 now-last > TTL 一致）")
    void boundaryExactlyAtTtlIsStillSuppressed() {
        DebounceWindow window = new DebounceWindow(1000);

        assertThat(window.allow("A", 0)).isTrue();
        // 原逻辑：now - lastSeen > 1000 才放行；等于 1000 仍抑制
        assertThat(window.allow("A", 1000)).isFalse();
        assertThat(window.allow("A", 1001)).isTrue();
    }

    @Test
    @DisplayName("prune 丢弃窗口外 key，保留窗口内 key")
    void pruneDropsOnlyExpiredKeys() {
        DebounceWindow window = new DebounceWindow(1000);
        window.allow("old", 1000);
        window.allow("fresh", 1500);

        window.prune(1600);
        assertThat(window.size()).isEqualTo(2);

        window.prune(2001);
        assertThat(window.size()).isEqualTo(1);
        // 仍留在窗口内的 key 继续被抑制，不会因为 prune 而重复放行
        assertThat(window.allow("fresh", 2001)).isFalse();
    }

    @Test
    @DisplayName("多轮大量不同 key：prune 后缓存大小不随历史轮次增长")
    void sizeStaysBoundedAcrossRounds() {
        DebounceWindow window = new DebounceWindow(1000);
        int perRound = 100;
        int sizeAfterTenRounds = -1;

        for (int round = 0; round < 100; round++) {
            long now = round * 1000L;
            window.prune(now);
            for (int i = 0; i < perRound; i++) {
                window.allow("event:" + round + ":" + i, now);
            }
            if (round == 9) {
                sizeAfterTenRounds = window.size();
            }
            // 窗口只覆盖当前轮与上一轮（边界 1000ms 仍算窗口内）
            assertThat(window.size()).isLessThanOrEqualTo(2 * perRound);
        }

        assertThat(window.size()).isEqualTo(sizeAfterTenRounds);
    }

    @Test
    @DisplayName("非法 TTL 直接拒绝，避免静默退化成永不过期")
    void rejectsNonPositiveTtl() {
        assertThatThrownBy(() -> new DebounceWindow(0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

package io.aerofleet.mavlink.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 签名重放防护测试（官方规则）。
 * <p>
 * 本类同时是两处旧实现缺陷的回归守卫：
 * ① 旧版用 {@code timestamp >= last} 放行**相等**时间戳，等于允许原帧重放一次；
 * ② 旧版只按 linkId 分桶，两台源设备共用同一 linkId 时会互相把对方的时间戳基准顶高，
 *    正常流量被误判为重放；
 * ③ 旧版回退阈值 500 tick 且时间戳单位口径（10ms）与官方（10µs）不符。
 * <p>
 * 本地"当前时间"通过构造器注入，60 秒新流窗口因此可确定性验证。
 */
@DisplayName("MAVLink 签名时间戳重放防护")
class TimestampTrackerTest {

    private static final int LINK_ID = 3;
    private static final int SYSID = 1;
    private static final int COMPID = 0;

    /** 固定"现在"=1_000_000 tick，便于把窗口边界算成整数。 */
    private static final long NOW = 1_000_000L;

    private TimestampTracker trackerAt(long now) {
        return new TimestampTracker(() -> now);
    }

    // ==================== 严格递增 ====================

    @Test
    @DisplayName("首次见到的流在 60 秒窗口内被接受")
    void newStreamWithinWindowAccepted() {
        TimestampTracker tracker = trackerAt(NOW);

        assertThat(tracker.check(LINK_ID, SYSID, COMPID, NOW - 100)).isTrue();
        assertThat(tracker.trackedStreams()).isEqualTo(1);
    }

    @Test
    @DisplayName("时间戳前进被接受并更新基准")
    void increasingTimestampAccepted() {
        TimestampTracker tracker = trackerAt(NOW);
        assertThat(tracker.check(LINK_ID, SYSID, COMPID, 500)).isTrue();

        assertThat(tracker.check(LINK_ID, SYSID, COMPID, 501)).isTrue();
        assertThat(tracker.lastKnown(new TimestampTracker.StreamKey(LINK_ID, SYSID, COMPID)))
                .isEqualTo(501);
    }

    @Test
    @DisplayName("时间戳相等必须被拒（旧实现用 >= 放行，是原帧重放）")
    void equalTimestampRejected() {
        TimestampTracker tracker = trackerAt(NOW);
        assertThat(tracker.check(LINK_ID, SYSID, COMPID, 700)).isTrue();

        assertThat(tracker.check(LINK_ID, SYSID, COMPID, 700)).isFalse();
        // 被拒后基准不应被改写
        assertThat(tracker.lastKnown(new TimestampTracker.StreamKey(LINK_ID, SYSID, COMPID)))
                .isEqualTo(700);
    }

    @Test
    @DisplayName("已见过的流即使只差 1 tick 回退也拒绝（不因窗口宽而放宽）")
    void rollbackRejectedForKnownStream() {
        TimestampTracker tracker = trackerAt(NOW);
        assertThat(tracker.check(LINK_ID, SYSID, COMPID, 900)).isTrue();

        assertThat(tracker.check(LINK_ID, SYSID, COMPID, 899)).isFalse();
    }

    // ==================== 新流 60 秒窗口 ====================

    @Test
    @DisplayName("新流落后正好 60 秒（6,000,000 tick）仍接受")
    void newStreamExactlyAtWindowAccepted() {
        TimestampTracker tracker = trackerAt(NOW + MavlinkSigner.REPLAY_WINDOW_TICKS);
        long behind = NOW;

        assertThat(behind + MavlinkSigner.REPLAY_WINDOW_TICKS < NOW + MavlinkSigner.REPLAY_WINDOW_TICKS)
                .isFalse();
        assertThat(tracker.check(LINK_ID, SYSID, COMPID, behind)).isTrue();
    }

    @Test
    @DisplayName("新流落后超过 60 秒被拒（旧实现阈值只有 500 tick 且单位不对）")
    void newStreamTooFarBehindRejected() {
        TimestampTracker tracker = trackerAt(NOW + MavlinkSigner.REPLAY_WINDOW_TICKS + 1);

        assertThat(tracker.check(LINK_ID, SYSID, COMPID, NOW)).isFalse();
        assertThat(tracker.trackedStreams()).isZero();   // 被拒的流不留基准
    }

    @Test
    @DisplayName("60 秒窗口就是 6,000,000 个 10 微秒 tick = 1 分钟")
    void replayWindowIsOneMinuteInOfficialTicks() {
        assertThat(MavlinkSigner.REPLAY_WINDOW_TICKS).isEqualTo(6_000_000L);
        assertThat(MavlinkSigner.REPLAY_WINDOW_TICKS * MavlinkSigner.TICK_MICROSECONDS / 1_000_000L)
                .isEqualTo(60);
    }

    // ==================== 分流键 ====================

    @Test
    @DisplayName("同 linkId 不同 sysid/compid 是独立的流，互不顶高基准")
    void streamsAreScopedByLinkSystemAndComponent() {
        TimestampTracker tracker = trackerAt(NOW);
        assertThat(tracker.check(LINK_ID, 1, 0, 800)).isTrue();

        // 另一台设备（sysid=2）时间戳更小，也应作为新流被接受
        assertThat(tracker.check(LINK_ID, 2, 0, 100)).isTrue();
        // 但第一台设备自己的回退仍被拒
        assertThat(tracker.check(LINK_ID, 1, 0, 100)).isFalse();
        assertThat(tracker.trackedStreams()).isEqualTo(2);
    }

    @Test
    @DisplayName("同流不同 linkId 也互相独立")
    void differentLinkIdsAreSeparateStreams() {
        TimestampTracker tracker = trackerAt(NOW);
        assertThat(tracker.check(1, SYSID, COMPID, 500)).isTrue();

        assertThat(tracker.check(2, SYSID, COMPID, 400)).isTrue();
        assertThat(tracker.check(1, SYSID, COMPID, 400)).isFalse();
    }

    // ==================== 边界与维护 ====================

    @Test
    @DisplayName("48 位范围外的时间戳被拒")
    void outOfRangeTimestampRejected() {
        TimestampTracker tracker = trackerAt(NOW);

        assertThat(tracker.check(LINK_ID, SYSID, COMPID, -1)).isFalse();
        assertThat(tracker.check(LINK_ID, SYSID, COMPID, MavlinkSigner.MAX_TIMESTAMP_48 + 1))
                .isFalse();
        assertThat(tracker.check(LINK_ID, SYSID, COMPID, MavlinkSigner.MAX_TIMESTAMP_48)).isTrue();
    }

    @Test
    @DisplayName("reset 清空所有流基准；未知流的 lastKnown 返回 null")
    void resetClearsStreams() {
        TimestampTracker tracker = trackerAt(NOW);
        TimestampTracker.StreamKey key = new TimestampTracker.StreamKey(LINK_ID, SYSID, COMPID);
        assertThat(tracker.check(LINK_ID, SYSID, COMPID, 500)).isTrue();
        assertThat(tracker.lastKnown(key)).isEqualTo(500);

        tracker.reset();

        assertThat(tracker.trackedStreams()).isZero();
        assertThat(tracker.lastKnown(key)).isNull();
        // 清空后同一下降时间戳作为"新流"重新被接受
        assertThat(tracker.check(LINK_ID, SYSID, COMPID, 400)).isTrue();
    }

    @Test
    @DisplayName("默认构造器用系统时钟换算的官方 tick，可正常接受递增值")
    void defaultConstructorUsesWallClock() {
        TimestampTracker tracker = new TimestampTracker();
        long now = MavlinkSigner.currentSigningTimestamp();

        assertThat(tracker.check(LINK_ID, SYSID, COMPID, now)).isTrue();
        assertThat(tracker.check(LINK_ID, SYSID, COMPID, now)).isFalse();
        assertThat(tracker.check(LINK_ID, SYSID, COMPID, now + 1)).isTrue();
    }
}

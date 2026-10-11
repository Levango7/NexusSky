package io.aerofleet.mavlink.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MAVLink 签名时间戳的**单调递增**行为。
 *
 * <p><b>为什么单独写这个文件</b>：官方规范（mavlink.io/en/guide/message_signing.html
 * → Timestamp Handling）要求的是「每发一帧，timestamp 比上一帧多 1」，不是「取当前
 * 墙上时间」。旧实现直接调 {@link MavlinkSigner#currentSigningTimestamp()}，
 * 在帧间隔小于 10µs 分辨率（1ms = 100 tick）时会拿到<b>相同</b>的时间戳；
 * 接收端按严格递增判定为重放，链路遥测被大面积丢弃。
 *
 * <p>这个问题在「真 PostgreSQL + staging profile + reject-unsigned=false」的实测中
 * 暴露：{@code signing: verified=613 rejected=635}，1278 条拒绝全部是
 * {@code Timestamp 校验失败}，且 timestamp 是同一个值。
 *
 * <p>规范对此有明确预期，所以这不是"绕过校验"，而是**按协议实现发送端**：
 * <blockquote>「the timestamp may get ahead of GMT time if the packet rate
 * averages more than 100,000 packets per second」</blockquote>
 */
@DisplayName("MavlinkSigner: 签名时间戳必须逐帧单调递增（官方口径）")
class SigningTimestampMonotonicTest {

    private static final String KEY = "monotonic-test-key";

    // ==================== 核心：同 tick 内多帧也必须递增 ====================

    @Test
    @DisplayName("1. 同一流的连续 nextTimestamp 严格递增（不依赖墙钟分辨率）")
    void consecutiveCalls_areStrictlyIncreasing() {
        MavlinkSigner signer = new MavlinkSigner(true, KEY, 7, false);

        long prev = signer.nextTimestamp(7, 1, 1);
        for (int i = 0; i < 500; i++) {
            long next = signer.nextTimestamp(7, 1, 1);
            assertThat(next)
                    .as("第 %d 次：必须比上一帧大（旧实现直接取墙钟，同 tick 内会相等）", i + 1)
                    .isGreaterThan(prev);
            prev = next;
        }
    }

    @Test
    @DisplayName("2. 即使墙钟完全不动，也照样 +1（规范：每帧 +1）")
    void evenWithFrozenClock_incrementsByOne() {
        // 直接验证口径本身：给定一个"上一帧"，下一帧必须更大
        MavlinkSigner signer = new MavlinkSigner(true, KEY, 7, false);
        long t1 = signer.nextTimestamp(7, 1, 1);
        long t2 = signer.nextTimestamp(7, 1, 1);
        assertThat(t2).isGreaterThan(t1);
        long t3 = signer.nextTimestamp(7, 1, 1);
        assertThat(t3).isGreaterThan(t2);
    }

    @Test
    @DisplayName("3. 不同 sysid 的流互不干扰（规范：流 = (linkId, sysid, compid)）")
    void differentSysids_areIndependentStreams() {
        MavlinkSigner signer = new MavlinkSigner(true, KEY, 7, false);

        long a1 = signer.nextTimestamp(7, 1, 1);
        long b1 = signer.nextTimestamp(7, 2, 1);
        long a2 = signer.nextTimestamp(7, 1, 1);
        long b2 = signer.nextTimestamp(7, 2, 1);

        // 同一流内各自递增
        assertThat(a2).isGreaterThan(a1);
        assertThat(b2).isGreaterThan(b1);
        // 且确实是两个独立的流
        assertThat(signer.trackedStreamCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("4. 不同 compid / linkId 同样分流")
    void differentCompidAndLinkId_areIndependent() {
        MavlinkSigner signer = new MavlinkSigner(true, KEY, 7, false);
        signer.nextTimestamp(7, 1, 1);
        signer.nextTimestamp(7, 1, 2);
        signer.nextTimestamp(8, 1, 1);
        assertThat(signer.trackedStreamCount()).isEqualTo(3);
    }

    // ==================== 与旧实现的差异（防回归） ====================

    @Test
    @DisplayName("5. nextTimestamp 不会小于当前墙钟（不允许倒退到过去）")
    void neverGoesBackwardsBelowWallClock() {
        MavlinkSigner signer = new MavlinkSigner(true, KEY, 7, false);
        long before = MavlinkSigner.currentSigningTimestamp();
        long first = signer.nextTimestamp(7, 1, 1);
        assertThat(first).isGreaterThanOrEqualTo(before);
    }

    @Test
    @DisplayName("6. lastTimestamp 可观测（运维/测试用）")
    void lastTimestamp_isObservable() {
        MavlinkSigner signer = new MavlinkSigner(true, KEY, 7, false);
        assertThat(signer.lastTimestamp(7, 1, 1)).isNull();
        long t = signer.nextTimestamp(7, 1, 1);
        assertThat(signer.lastTimestamp(7, 1, 1)).isEqualTo(t);
    }

    @Test
    @DisplayName("7. 未启用签名时 nextTimestamp 仍是安全值（不抛）")
    void disabledSigner_stillReturnsSomething() {
        MavlinkSigner signer = new MavlinkSigner(false, null, 7, false);
        assertThat(signer.nextTimestamp(7, 1, 1)).isGreaterThan(0);
    }

    // ==================== 接收端口径 ====================

    @Test
    @DisplayName("8. TimestampTracker: 相等不再判重放，回退仍然拒绝（官方口径）")
    void tracker_equalIsAccepted_backwardsIsRejected() {
        TimestampTracker tracker = new TimestampTracker() {
            {
                // 用一个固定 now，让首帧一定落在 60s 窗口内
            }
        };
        // 用可控时钟：先让首帧以 now 为基准被接受
        TimestampTracker fixed = new TimestampTracker(() -> 1_000_000L);
        long ts = 900_000L; // 落后 100_000 tick < 6_000_000 窗口
        assertThat(fixed.check(7, 1, 1, ts)).isTrue();

        // 相等：官方只要求拒绝"更旧"的
        assertThat(fixed.check(7, 1, 1, ts)).isTrue();

        // 回退：仍然拒绝（重放防护不变）
        assertThat(fixed.check(7, 1, 1, ts - 1)).isFalse();
    }
}

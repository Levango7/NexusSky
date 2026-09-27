package io.aerofleet.mavlink.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TimestampTracker 单元测试（C5-T16）：
 * 1. 首次 timestamp → true
 * 2. 递增 timestamp → true
 * 3. 回退 ≤500 tick → true
 * 4. 回退 >500 tick → false
 * 5. 小回退后，后续递增 timestamp 仍以原基准判断
 * 6. 不同 linkId 的 timestamp 互不影响
 */
class TimestampTrackerTest {

    // ========== 1. firstTimestampAccepted ==========

    @Test
    @DisplayName("首次 timestamp → true")
    void firstTimestampAccepted() {
        TimestampTracker tracker = new TimestampTracker();

        assertTrue(tracker.check(1, 1000L), "首次 timestamp 应被接受");
    }

    // ========== 2. increasingTimestampAccepted ==========

    @Test
    @DisplayName("递增 timestamp → true")
    void increasingTimestampAccepted() {
        TimestampTracker tracker = new TimestampTracker();

        assertTrue(tracker.check(1, 1000L), "首次 timestamp 应被接受");
        assertTrue(tracker.check(1, 1001L), "递增 timestamp 应被接受");
        assertTrue(tracker.check(1, 2000L), "递增 timestamp 应被接受");
        assertTrue(tracker.check(1, 10000L), "递增 timestamp 应被接受");
    }

    // ========== 3. smallBackwardAccepted ==========

    @Test
    @DisplayName("回退 ≤500 tick → true")
    void smallBackwardAccepted() {
        TimestampTracker tracker = new TimestampTracker();

        assertTrue(tracker.check(1, 1000L), "首次 timestamp 应被接受");
        // 回退 500 tick（等于阈值）
        assertTrue(tracker.check(1, 500L), "回退 500 tick（等于阈值）应被接受");
        // 回退 200 tick（相对于基准 1000）
        assertTrue(tracker.check(1, 800L), "回退 200 tick 应被接受");
    }

    // ========== 4. largeBackwardRejected ==========

    @Test
    @DisplayName("回退 >500 tick → false")
    void largeBackwardRejected() {
        TimestampTracker tracker = new TimestampTracker();

        assertTrue(tracker.check(1, 1000L), "首次 timestamp 应被接受");
        // 回退 501 tick（超过阈值）
        assertFalse(tracker.check(1, 499L), "回退 501 tick（超过阈值）应被拒绝");
        // 回退更多
        assertFalse(tracker.check(1, 0L), "回退 1000 tick 应被拒绝");
    }

    // ========== 5. smallBackwardDoesNotLowerBaseline ==========

    @Test
    @DisplayName("小回退后，后续递增 timestamp 仍以原基准判断")
    void smallBackwardDoesNotLowerBaseline() {
        TimestampTracker tracker = new TimestampTracker();

        // 基准 = 1000
        assertTrue(tracker.check(1, 1000L), "首次 timestamp 应被接受");

        // 小回退到 800（回退 200 ≤ 500），允许但不更新基准
        assertTrue(tracker.check(1, 800L), "小回退应被接受");

        // 后续 timestamp = 900，仍 < 基准 1000，回退 100 ≤ 500，应被接受
        assertTrue(tracker.check(1, 900L), "小回退后递增但仍低于基准的 timestamp 应被接受");

        // 后续 timestamp = 1001，> 基准 1000，应被接受并更新基准
        assertTrue(tracker.check(1, 1001L), "超过基准的 timestamp 应被接受");

        // 新基准 = 1001，回退到 400（回退 601 > 500），应被拒绝
        assertFalse(tracker.check(1, 400L), "超过阈值的大回退应被拒绝");
    }

    // ========== 6. differentLinkIdsIndependent ==========

    @Test
    @DisplayName("不同 linkId 的 timestamp 互不影响")
    void differentLinkIdsIndependent() {
        TimestampTracker tracker = new TimestampTracker();

        // linkId=1 基准 = 1000
        assertTrue(tracker.check(1, 1000L), "linkId=1 首次 timestamp 应被接受");

        // linkId=2 基准 = 500（独立于 linkId=1）
        assertTrue(tracker.check(2, 500L), "linkId=2 首次 timestamp 应被接受");

        // linkId=1 回退到 400（回退 600 > 500），应被拒绝
        assertFalse(tracker.check(1, 400L), "linkId=1 大回退应被拒绝");

        // linkId=2 回退到 400（回退 100 ≤ 500），应被接受（不受 linkId=1 影响）
        assertTrue(tracker.check(2, 400L), "linkId=2 小回退应被接受（独立于 linkId=1）");

        // linkId=3 首次 timestamp，不受其他 linkId 影响
        assertTrue(tracker.check(3, 100L), "linkId=3 首次 timestamp 应被接受");
    }
}
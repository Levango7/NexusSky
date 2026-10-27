package io.aerofleet.cloud.surveillance.onvif;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link OnvifEventPoller} 的决策契约（同步、无线程）。
 *
 * <p>这些断言对应四件真实会发生的事：通知要全部交付、成功一次就把失败计数清零、
 * 连续失败到上限必须放弃（不能无限重试打爆设备）、坏回调不能打死整条订阅。
 */
@DisplayName("OnvifEventPoller 轮询决策")
class OnvifEventPollerTest {

    private final List<String> delivered = new ArrayList<>();

    @Test
    @DisplayName("成功一轮：全部通知按序交付，失败计数归零")
    void pollOnce_deliversAllAndResetsFailures() {
        OnvifEventPoller poller = new OnvifEventPoller("h1",
                () -> List.of("<a/>", "<b/>"), delivered::add);

        assertThat(poller.pollOnce()).isTrue();

        assertThat(delivered).containsExactly("<a/>", "<b/>");
        assertThat(poller.consecutiveFailures()).isZero();
    }

    @Test
    @DisplayName("失败一次后成功：计数清零（不能把历史失败累计到下一次断链）")
    void pollOnce_successAfterFailure_resetsCounter() {
        AtomicInteger calls = new AtomicInteger();
        OnvifEventPoller poller = new OnvifEventPoller("h2", () -> {
            if (calls.incrementAndGet() == 1) {
                throw new OnvifException("boom");
            }
            return List.of("<ok/>");
        }, delivered::add);

        assertThat(poller.pollOnce()).isTrue();          // 第 1 次失败，但还没到上限
        assertThat(poller.consecutiveFailures()).isEqualTo(1);
        assertThat(poller.pollOnce()).isTrue();          // 第 2 次成功
        assertThat(poller.consecutiveFailures()).isZero();
        assertThat(delivered).containsExactly("<ok/>");
    }

    @Test
    @DisplayName("连续失败到上限：返回 false（放弃订阅），恰好第 3 次触发")
    void pollOnce_givesUpAtFailureLimit() {
        OnvifEventPoller poller = new OnvifEventPoller("h3", () -> {
            throw new OnvifException("device gone");
        }, delivered::add);

        assertThat(poller.pollOnce()).isTrue();   // 1
        assertThat(poller.pollOnce()).isTrue();   // 2
        assertThat(poller.pollOnce()).isFalse();  // 3 -> 放弃
        assertThat(poller.consecutiveFailures()).isEqualTo(OnvifEventPoller.MAX_PULL_FAILURES);
    }

    @Test
    @DisplayName("回调抛异常：轮询继续，后续通知仍交付")
    void pollOnce_callbackFailure_doesNotKillPolling() {
        OnvifEventPoller poller = new OnvifEventPoller("h4",
                () -> List.of("<bad/>", "<good/>"), payload -> {
                    if ("<bad/>".equals(payload)) {
                        throw new IllegalStateException("consumer bug");
                    }
                    delivered.add(payload);
                });

        assertThat(poller.pollOnce()).isTrue();

        assertThat(delivered).containsExactly("<good/>");
        assertThat(poller.consecutiveFailures()).isZero();
    }

    @Test
    @DisplayName("重试等待被中断：立即返回 false 并保留中断标志（不再继续轮询）")
    void pollOnce_interruptedDuringRetry_stops() {
        OnvifEventPoller poller = new OnvifEventPoller("h5", () -> {
            throw new OnvifException("boom");
        }, delivered::add);

        Thread.currentThread().interrupt();
        try {
            assertThat(poller.pollOnce()).isFalse();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted(); // 清掉标志，别污染后续用例
        }
    }
}

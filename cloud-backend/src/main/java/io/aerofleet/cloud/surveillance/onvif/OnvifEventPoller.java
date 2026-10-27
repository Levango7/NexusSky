package io.aerofleet.cloud.surveillance.onvif;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Consumer;

/**
 * 单个 PullPoint 订阅的轮询决策（一次拉取的成败判定）。
 *
 * <p>为什么把这段从线程里拆出来：轮询的**决策**（成功清零、失败计数、第 N 次放弃、
 * 回调异常不打死轮询）全部是纯逻辑，塞在 {@code while (!stopped)} + 线程里之后
 * 只能靠"起线程 + sleep + 观察副作用"验证——那类测试既慢又不可靠，变异测试里
 * 大量变异体只能落成 TIMED_OUT。拆出来后每次 {@link #pollOnce()} 都能直接断言。
 *
 * <p>线程与停止语义仍在 {@code OnvifLiveBackend}：本类只回答"这一轮之后还要不要继续"。
 */
final class OnvifEventPoller {

    private static final Logger log = LoggerFactory.getLogger(OnvifEventPoller.class);

    /** 连续拉取失败多少次就放弃订阅（不静默无限重试，也不悄悄重建）。 */
    static final int MAX_PULL_FAILURES = 3;
    /** 拉取失败后的重试间隔（毫秒）。 */
    static final long RETRY_DELAY_MS = 500L;

    /** 一次拉取（返回序列化后的通知；设备无事件时为空列表）。 */
    interface Pull {
        List<String> pull() throws OnvifException;
    }

    private final Pull pull;
    private final Consumer<String> callback;
    private final String handle;
    private int consecutiveFailures;

    OnvifEventPoller(String handle, Pull pull, Consumer<String> callback) {
        this.handle = handle;
        this.pull = pull;
        this.callback = callback;
    }

    /**
     * 执行一轮拉取并交付通知。
     *
     * @return {@code true} 继续轮询；{@code false} 放弃（连续失败到上限，或被中断）
     */
    boolean pollOnce() {
        try {
            for (String event : pull.pull()) {
                deliver(event);
            }
            consecutiveFailures = 0;
            return true;
        } catch (OnvifException e) {
            consecutiveFailures++;
            if (consecutiveFailures >= MAX_PULL_FAILURES) {
                log.error("Giving up event subscription {} after {} consecutive failures: {}",
                        handle, consecutiveFailures, e.getMessage());
                return false;
            }
            log.warn("PullMessages failed for {} (attempt {}/{}): {}",
                    handle, consecutiveFailures, MAX_PULL_FAILURES, e.getMessage());
            return sleepBeforeRetry();
        }
    }

    /** 连续失败次数（成功一次即清零）。 */
    int consecutiveFailures() {
        return consecutiveFailures;
    }

    /** 回调异常不能打死轮询（一个坏消费者不该让整条订阅消失）。 */
    private void deliver(String event) {
        try {
            callback.accept(event);
        } catch (RuntimeException e) {
            log.warn("Event callback failed for {}: {}", handle, e.getMessage());
        }
    }

    private boolean sleepBeforeRetry() {
        try {
            Thread.sleep(RETRY_DELAY_MS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}

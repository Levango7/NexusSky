package io.aerofleet.cloud.geofence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link InterceptLogStore} 的容量与"丢弃可见性"单测。
 * <p>
 * 这块存的是"平台拒绝过多少次越权命令"的证据。审计发现两件事：
 * ① 上限以前是硬编码常量，而配置文件里同一件事有一个**没人读的键**（看起来可调，其实不可调）；
 * ② 超限丢最旧时不留任何可事后核对的数——只知道"现在有几条"，不知道"曾经丢了几条"。
 */
@DisplayName("InterceptLogStore 容量上限与丢弃计数")
class InterceptLogStoreTest {

    private static InterceptLog entry(int sysid, InterceptVerdict.Verdict verdict) {
        return new InterceptLog(sysid, "NAV_TAKEOFF", 39.9, 116.3,
                verdict, null, null, 1_000_000L + sysid);
    }

    @Test
    @DisplayName("上限真由构造参数决定（配置键 aerofleet.geofence.intercept.log-max-size 生效）")
    void capIsConfigurableNotHardcoded() {
        InterceptLogStore store = new InterceptLogStore(3);
        assertThat(store.maxLogs()).isEqualTo(3);

        for (int i = 1; i <= 5; i++) {
            store.record(entry(i, InterceptVerdict.Verdict.ALLOW));
        }

        assertThat(store.getLogs()).hasSize(3);
        // 留下的是最新的三条，不是最早的三条
        assertThat(store.getLogs()).extracting(InterceptLog::getSysid).containsExactly(3, 4, 5);
    }

    @Test
    @DisplayName("非正数的上限回退默认值 1000，而不是把日志全丢掉")
    void nonPositiveCapFallsBackToDefault() {
        assertThat(new InterceptLogStore(0).maxLogs()).isEqualTo(InterceptLogStore.DEFAULT_MAX_LOGS);
        assertThat(new InterceptLogStore(-5).maxLogs()).isEqualTo(InterceptLogStore.DEFAULT_MAX_LOGS);
        assertThat(new InterceptLogStore().maxLogs()).isEqualTo(InterceptLogStore.DEFAULT_MAX_LOGS);
    }

    @Test
    @DisplayName("丢弃是**可核对**的：evictedCount 累计，且没丢时恒为 0")
    void evictionsAreCountable() {
        InterceptLogStore fresh = new InterceptLogStore(10);
        fresh.record(entry(1, InterceptVerdict.Verdict.ALLOW));
        assertThat(fresh.evictedCount()).isZero();

        InterceptLogStore overflowing = new InterceptLogStore(2);
        for (int i = 1; i <= 6; i++) {
            overflowing.record(entry(i, InterceptVerdict.Verdict.DENY));
        }
        // 6 条进、留 2 条 ⇒ 丢 4 条。这个数是"证据少了多少"的唯一答案。
        assertThat(overflowing.getLogs()).hasSize(2);
        assertThat(overflowing.evictedCount()).isEqualTo(4L);
    }
}

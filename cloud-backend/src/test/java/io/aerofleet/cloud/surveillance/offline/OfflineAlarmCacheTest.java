package io.aerofleet.cloud.surveillance.offline;

import io.aerofleet.cloud.alarm.AlarmEvent;
import io.aerofleet.cloud.alarm.AlarmEventStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link OfflineAlarmCache} 单元测试（该子包此前**零测试**）。
 *
 * <p>这块是"断网期间的报警到底会不会丢"的实物：容量驱逐、上传成功清空、上传失败重试、
 * 依赖缺失降级。任何一条错了，用户看到的就是"灾后报警少了几条"或"重复上报"。
 */
@DisplayName("OfflineAlarmCache 离线报警缓存")
class OfflineAlarmCacheTest {

    private static OfflineAlarmCache.OfflineAlarmEvent event(String deviceId, long timestampMs) {
        return new OfflineAlarmCache.OfflineAlarmEvent(
                deviceId, "设备-" + deviceId, "INTRUSION", "WARN", "desc-" + deviceId,
                30.0, 120.0, 0.0, timestampMs, timestampMs);
    }

    // ===== 容量与快照 =====

    @Test
    @DisplayName("容量必须为正数")
    void constructorRejectsNonPositiveCapacity() {
        assertThatThrownBy(() -> new OfflineAlarmCache(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OfflineAlarmCache(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("cacheAlarm(null) 是空操作")
    void nullAlarmIsIgnored() {
        OfflineAlarmCache cache = new OfflineAlarmCache(4);
        cache.cacheAlarm(null);
        assertThat(cache.pendingCount()).isZero();
    }

    @Test
    @DisplayName("超容量时驱逐最旧事件（FIFO），不是丢最新")
    void overCapacityEvictsOldest() {
        OfflineAlarmCache cache = new OfflineAlarmCache(2);
        cache.cacheAlarm(event("A", 1));
        cache.cacheAlarm(event("B", 2));
        cache.cacheAlarm(event("C", 3));

        assertThat(cache.pendingCount()).isEqualTo(2);
        List<OfflineAlarmCache.OfflineAlarmEvent> pending = cache.getPendingAlarms();
        assertThat(pending).extracting(OfflineAlarmCache.OfflineAlarmEvent::getDeviceId)
                .containsExactly("B", "C");
    }

    @Test
    @DisplayName("驱逐是**可量化**的：evictedCount 与 getCacheStats 都给出条数")
    void evictionIsCountable() {
        // 上一条用例只钉住"谁被丢了"，没钉住"丢了几条能被事后核对"。
        // 只有 WARN 日志的话，运维要回答"这次断网丢了几条报警"得去翻日志数行数；
        // 而这个数一旦不可采，类注释里的容量取舍就永远无法被证伪。
        OfflineAlarmCache cache = new OfflineAlarmCache(2);
        assertThat(cache.evictedCount()).isZero();

        cache.cacheAlarm(event("A", 1));   // [A]
        cache.cacheAlarm(event("B", 2));   // [A,B]
        cache.cacheAlarm(event("C", 3));   // 驱逐 A
        cache.cacheAlarm(event("D", 4));   // 驱逐 B
        assertThat(cache.evictedCount()).isEqualTo(2L);
        assertThat(cache.pendingCount()).isEqualTo(2);

        Map<String, Object> stats = cache.getCacheStats();
        assertThat(stats).containsEntry("evictedCount", 2L);
        // 被驱逐的事件确实不在待上传队列里（否则这个计数是虚报）
        assertThat(cache.getPendingAlarms()).extracting(
                        OfflineAlarmCache.OfflineAlarmEvent::getDeviceId)
                .containsExactly("C", "D");
    }

    @Test
    @DisplayName("clear 清队列但**不抹掉**历史驱逐数（它是累计账，不是当前态）")
    void clearKeepsCumulativeEvictedCount() {
        OfflineAlarmCache cache = new OfflineAlarmCache(1);
        cache.cacheAlarm(event("A", 1));
        cache.cacheAlarm(event("B", 2));   // 驱逐 A
        assertThat(cache.evictedCount()).isEqualTo(1L);

        cache.clear();
        assertThat(cache.pendingCount()).isZero();
        assertThat(cache.evictedCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("getPendingAlarms 返回快照：改返回值不影响队列")
    void pendingIsSnapshot() {
        OfflineAlarmCache cache = new OfflineAlarmCache(4);
        cache.cacheAlarm(event("A", 1));

        cache.getPendingAlarms().clear();

        assertThat(cache.pendingCount()).isEqualTo(1);
    }

    // ===== flush 上传 =====

    @Test
    @DisplayName("没有 AlarmEventStore 时返回 0，事件留在队列里（不能假装上传成功）")
    void flushWithoutStoreKeepsEvents() {
        OfflineAlarmCache cache = new OfflineAlarmCache(4);
        cache.cacheAlarm(event("A", 1));

        assertThat(cache.flushToStore(null)).isZero();
        assertThat(cache.pendingCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("上传成功：转换字段正确、返回成功数、清空队列")
    void flushUploadsAndClears() {
        OfflineAlarmCache cache = new OfflineAlarmCache(4);
        cache.cacheAlarm(new OfflineAlarmCache.OfflineAlarmEvent(
                "cam-7", "北门球机", "FIRE", "CRITICAL", "仓库冒烟",
                31.5, 121.5, 12.0, 1760000000000L, 1760000001000L));

        AlarmEventStore store = Mockito.mock(AlarmEventStore.class);
        assertThat(cache.flushToStore(store)).isEqualTo(1);
        assertThat(cache.pendingCount()).isZero();

        ArgumentCaptor<AlarmEvent> captor = ArgumentCaptor.forClass(AlarmEvent.class);
        Mockito.verify(store).store(captor.capture());
        AlarmEvent uploaded = captor.getValue();
        assertThat(uploaded.getSourceDeviceId()).isEqualTo("cam-7");
        assertThat(uploaded.getSourceDeviceName()).isEqualTo("北门球机");
        assertThat(uploaded.getEventType()).isEqualTo(AlarmEvent.EventType.FIRE);
        assertThat(uploaded.getSeverity()).isEqualTo(AlarmEvent.Severity.CRITICAL);
        assertThat(uploaded.getDescription()).isEqualTo("仓库冒烟");
        assertThat(uploaded.getLat()).isEqualTo(31.5);
        assertThat(uploaded.getLon()).isEqualTo(121.5);
        assertThat(uploaded.getAlt()).isEqualTo(12.0);
        assertThat(uploaded.getTimestampMs()).isEqualTo(1760000000000L);
        assertThat(uploaded.isAcknowledged()).isFalse();
        assertThat(uploaded.getId()).isNotBlank();
    }

    @Test
    @DisplayName("未知的事件类型/严重度被容错成 CUSTOM/INFO，而不是抛异常丢事件")
    void flushToleratesUnknownEventTypeAndSeverity() {
        OfflineAlarmCache cache = new OfflineAlarmCache(4);
        cache.cacheAlarm(new OfflineAlarmCache.OfflineAlarmEvent(
                "cam-8", "x", "SOMETHING_NEW", "WHATEVER", "d", 0, 0, 0, 1L, 1L));

        AlarmEventStore store = Mockito.mock(AlarmEventStore.class);
        assertThat(cache.flushToStore(store)).isEqualTo(1);

        ArgumentCaptor<AlarmEvent> captor = ArgumentCaptor.forClass(AlarmEvent.class);
        Mockito.verify(store).store(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo(AlarmEvent.EventType.CUSTOM);
        assertThat(captor.getValue().getSeverity()).isEqualTo(AlarmEvent.Severity.INFO);
    }

    @Test
    @DisplayName("上传失败：事件放回队列等待下次重试，不能丢")
    void flushKeepsFailedEvents() {
        OfflineAlarmCache cache = new OfflineAlarmCache(4);
        cache.cacheAlarm(event("A", 1));

        AlarmEventStore store = Mockito.mock(AlarmEventStore.class);
        Mockito.doThrow(new RuntimeException("store down")).when(store).store(Mockito.any());

        assertThat(cache.flushToStore(store)).isZero();
        assertThat(cache.pendingCount()).isEqualTo(1);
        assertThat(cache.getPendingAlarms().get(0).getDeviceId()).isEqualTo("A");
    }

    @Test
    @DisplayName("连续失败达到上限就停手：12 条全失败时不能无限重试")
    void flushStopsAfterConsecutiveFailures() {
        OfflineAlarmCache cache = new OfflineAlarmCache(16);
        for (int i = 0; i < 12; i++) {
            cache.cacheAlarm(event("D" + i, i));
        }

        AlarmEventStore store = Mockito.mock(AlarmEventStore.class);
        Mockito.doThrow(new RuntimeException("store down")).when(store).store(Mockito.any());

        assertThat(cache.flushToStore(store)).isZero();
        assertThat(cache.pendingCount()).isEqualTo(12);
        // 恰好 10 次：少一次 = 提前放弃，多一次 = 上限没生效
        Mockito.verify(store, Mockito.times(10)).store(Mockito.any());
    }

    @Test
    @DisplayName("部分失败：成功的被消费、失败的留下")
    void flushKeepsOnlyFailedOnes() {
        OfflineAlarmCache cache = new OfflineAlarmCache(4);
        cache.cacheAlarm(event("BAD", 1));
        cache.cacheAlarm(event("GOOD", 2));

        AlarmEventStore store = Mockito.mock(AlarmEventStore.class);
        // 只对 BAD 失败，GOOD 正常入库（用 doAnswer 按设备区分，避免"第一次失败、之后都成功"的歧义）
        Mockito.doAnswer(inv -> {
            AlarmEvent e = inv.getArgument(0);
            if ("BAD".equals(e.getSourceDeviceId())) {
                throw new RuntimeException("boom");
            }
            return null;
        }).when(store).store(Mockito.any());

        assertThat(cache.flushToStore(store)).isEqualTo(1);
        assertThat(cache.pendingCount()).isEqualTo(1);
        assertThat(cache.getPendingAlarms().get(0).getDeviceId()).isEqualTo("BAD");
    }

    // ===== 统计与清空 =====

    @Test
    @DisplayName("空缓存的统计：计数 0、最旧/最新时间戳都是 0（不是 Long.MAX_VALUE）")
    void emptyStatsAreZero() {
        Map<String, Object> stats = new OfflineAlarmCache(4).getCacheStats();
        assertThat(stats).containsEntry("totalCount", 0)
                .containsEntry("maxCacheSize", 4)
                .containsEntry("evictedCount", 0L)
                .containsEntry("oldestTimestampMs", 0L)
                .containsEntry("newestTimestampMs", 0L);
    }

    @Test
    @DisplayName("非空缓存的统计：最旧/最新来自事件时间戳")
    void statsReportOldestAndNewest() {
        OfflineAlarmCache cache = new OfflineAlarmCache(4);
        cache.cacheAlarm(event("A", 100));
        cache.cacheAlarm(event("B", 300));
        cache.cacheAlarm(event("C", 200));

        Map<String, Object> stats = cache.getCacheStats();
        assertThat(stats).containsEntry("totalCount", 3)
                .containsEntry("oldestTimestampMs", 100L)
                .containsEntry("newestTimestampMs", 300L);
    }

    @Test
    @DisplayName("clear 清空队列")
    void clearEmptiesQueue() {
        OfflineAlarmCache cache = new OfflineAlarmCache(4);
        cache.cacheAlarm(event("A", 1));
        cache.clear();
        assertThat(cache.pendingCount()).isZero();
    }
}

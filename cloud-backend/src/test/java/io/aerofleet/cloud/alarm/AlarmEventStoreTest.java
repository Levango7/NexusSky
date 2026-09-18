package io.aerofleet.cloud.alarm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AlarmEventStore} 单元测试（M10 报警联动编排，FR-31）。
 * <p>
 * 覆盖存储、查询、分页、确认、容量限制、筛选等。
 */
@DisplayName("AlarmEventStore 事件存储 (FR-31)")
class AlarmEventStoreTest {

    private static AlarmEvent event(String id, AlarmEvent.EventType type, AlarmEvent.Severity severity,
                                    long ts) {
        return new AlarmEvent(id, "dev", "name",
                type, severity, "desc", 39.9, 116.3, 0, ts, false);
    }

    @Test
    @DisplayName("store 存储事件后可通过 getById 查询")
    void storeAndGetById() {
        AlarmEventStore store = new AlarmEventStore();
        AlarmEvent e = event("e1", AlarmEvent.EventType.FIRE, AlarmEvent.Severity.WARN, 100L);
        store.store(e);

        assertThat(store.size()).isEqualTo(1);
        assertThat(store.getById("e1")).isSameAs(e);
    }

    @Test
    @DisplayName("getById 不存在返回 null")
    void getByIdNonExistentReturnsNull() {
        AlarmEventStore store = new AlarmEventStore();
        assertThat(store.getById("nonexistent")).isNull();
    }

    @Test
    @DisplayName("acknowledge 确认事件返回 true")
    void acknowledgeReturnsTrue() {
        AlarmEventStore store = new AlarmEventStore();
        store.store(event("e1", AlarmEvent.EventType.FIRE, AlarmEvent.Severity.WARN, 100L));

        assertThat(store.acknowledge("e1")).isTrue();
        assertThat(store.getById("e1").isAcknowledged()).isTrue();
    }

    @Test
    @DisplayName("acknowledge 不存在返回 false")
    void acknowledgeNonExistentReturnsFalse() {
        AlarmEventStore store = new AlarmEventStore();
        assertThat(store.acknowledge("nonexistent")).isFalse();
    }

    @Test
    @DisplayName("query 分页返回正确结果")
    void queryPaginated() {
        AlarmEventStore store = new AlarmEventStore();
        for (int i = 0; i < 10; i++) {
            store.store(event("e" + i, AlarmEvent.EventType.MOTION, AlarmEvent.Severity.WARN, 100L + i));
        }

        AlarmEventStore.PageResult page1 = store.query(0, 3, null, null);
        assertThat(page1.getTotal()).isEqualTo(10);
        assertThat(page1.getItems()).hasSize(3);
        assertThat(page1.getPage()).isEqualTo(0);
        assertThat(page1.getSize()).isEqualTo(3);

        AlarmEventStore.PageResult page2 = store.query(1, 3, null, null);
        assertThat(page2.getItems()).hasSize(3);

        AlarmEventStore.PageResult page4 = store.query(3, 3, null, null);
        assertThat(page4.getItems()).hasSize(1);
    }

    @Test
    @DisplayName("query 按 severity 筛选")
    void queryFilterBySeverity() {
        AlarmEventStore store = new AlarmEventStore();
        store.store(event("e1", AlarmEvent.EventType.FIRE, AlarmEvent.Severity.WARN, 100L));
        store.store(event("e2", AlarmEvent.EventType.FIRE, AlarmEvent.Severity.CRITICAL, 200L));
        store.store(event("e3", AlarmEvent.EventType.MOTION, AlarmEvent.Severity.INFO, 300L));

        AlarmEventStore.PageResult result = store.query(0, 10, "CRITICAL", null);
        assertThat(result.getTotal()).isEqualTo(1);
        assertThat(result.getItems().get(0).getId()).isEqualTo("e2");
    }

    @Test
    @DisplayName("query 按 type 筛选")
    void queryFilterByType() {
        AlarmEventStore store = new AlarmEventStore();
        store.store(event("e1", AlarmEvent.EventType.FIRE, AlarmEvent.Severity.WARN, 100L));
        store.store(event("e2", AlarmEvent.EventType.MOTION, AlarmEvent.Severity.WARN, 200L));

        AlarmEventStore.PageResult result = store.query(0, 10, null, "FIRE");
        assertThat(result.getTotal()).isEqualTo(1);
        assertThat(result.getItems().get(0).getId()).isEqualTo("e1");
    }

    @Test
    @DisplayName("query 同时按 severity 和 type 筛选")
    void queryFilterBySeverityAndType() {
        AlarmEventStore store = new AlarmEventStore();
        store.store(event("e1", AlarmEvent.EventType.FIRE, AlarmEvent.Severity.WARN, 100L));
        store.store(event("e2", AlarmEvent.EventType.FIRE, AlarmEvent.Severity.CRITICAL, 200L));
        store.store(event("e3", AlarmEvent.EventType.MOTION, AlarmEvent.Severity.CRITICAL, 300L));

        AlarmEventStore.PageResult result = store.query(0, 10, "CRITICAL", "FIRE");
        assertThat(result.getTotal()).isEqualTo(1);
        assertThat(result.getItems().get(0).getId()).isEqualTo("e2");
    }

    @Test
    @DisplayName("query 结果按时间戳倒序（最新在前）")
    void queryOrderedByTimestampDesc() {
        AlarmEventStore store = new AlarmEventStore();
        store.store(event("old", AlarmEvent.EventType.MOTION, AlarmEvent.Severity.WARN, 100L));
        store.store(event("new", AlarmEvent.EventType.MOTION, AlarmEvent.Severity.WARN, 200L));

        AlarmEventStore.PageResult result = store.query(0, 10, null, null);
        assertThat(result.getItems().get(0).getId()).isEqualTo("new");
        assertThat(result.getItems().get(1).getId()).isEqualTo("old");
    }

    @Test
    @DisplayName("容量限制：超过容量时驱逐最旧事件")
    void capacityEvictsOldest() {
        AlarmEventStore store = new AlarmEventStore(3);
        store.store(event("e1", AlarmEvent.EventType.MOTION, AlarmEvent.Severity.WARN, 100L));
        store.store(event("e2", AlarmEvent.EventType.MOTION, AlarmEvent.Severity.WARN, 200L));
        store.store(event("e3", AlarmEvent.EventType.MOTION, AlarmEvent.Severity.WARN, 300L));
        assertThat(store.size()).isEqualTo(3);

        // 插入第 4 条，驱逐最旧的 e1
        store.store(event("e4", AlarmEvent.EventType.MOTION, AlarmEvent.Severity.WARN, 400L));
        assertThat(store.size()).isEqualTo(3);
        assertThat(store.getById("e1")).isNull();
        assertThat(store.getById("e4")).isNotNull();
    }

    @Test
    @DisplayName("容量限制：驱逐后索引同步更新")
    void capacityEvictionUpdatesIndex() {
        AlarmEventStore store = new AlarmEventStore(2);
        store.store(event("e1", AlarmEvent.EventType.MOTION, AlarmEvent.Severity.WARN, 100L));
        store.store(event("e2", AlarmEvent.EventType.MOTION, AlarmEvent.Severity.WARN, 200L));
        store.store(event("e3", AlarmEvent.EventType.MOTION, AlarmEvent.Severity.WARN, 300L));

        // e1 被驱逐
        assertThat(store.getById("e1")).isNull();
        assertThat(store.getById("e2")).isNotNull();
        assertThat(store.getById("e3")).isNotNull();
        assertThat(store.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("非法容量抛 IllegalArgumentException")
    void invalidCapacityThrows() {
        assertThatThrownBy(() -> new AlarmEventStore(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AlarmEventStore(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("query 页码超出范围返回空列表")
    void queryPageOutOfRangeReturnsEmpty() {
        AlarmEventStore store = new AlarmEventStore();
        store.store(event("e1", AlarmEvent.EventType.MOTION, AlarmEvent.Severity.WARN, 100L));

        AlarmEventStore.PageResult result = store.query(99, 10, null, null);
        assertThat(result.getTotal()).isEqualTo(1);
        assertThat(result.getItems()).isEmpty();
    }

    @Test
    @DisplayName("并发安全：多线程同时存储不丢失事件（在容量内）")
    void concurrentStore() throws InterruptedException {
        AlarmEventStore store = new AlarmEventStore(10000);
        int threads = 10;
        int perThread = 100;
        List<Thread> ts = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int tid = t;
            Thread th = new Thread(() -> {
                for (int i = 0; i < perThread; i++) {
                    store.store(event("e-" + tid + "-" + i,
                            AlarmEvent.EventType.MOTION, AlarmEvent.Severity.WARN, 100L + i));
                }
            });
            ts.add(th);
            th.start();
        }
        for (Thread th : ts) {
            th.join();
        }
        assertThat(store.size()).isEqualTo(threads * perThread);
    }
}
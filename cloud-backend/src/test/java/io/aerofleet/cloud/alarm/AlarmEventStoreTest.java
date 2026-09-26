package io.aerofleet.cloud.alarm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

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

    /**
     * 创建 AlarmEventStore 并注入 mock AlarmEventRepository，模拟内存存储行为。
     */
    private AlarmEventStore createStore() {
        return createStore(AlarmEventStore.DEFAULT_CAPACITY);
    }

    /**
     * 创建指定容量的 AlarmEventStore 并注入 mock AlarmEventRepository，模拟内存存储行为。
     */
    private AlarmEventStore createStore(int capacity) {
        AlarmEventStore store = new AlarmEventStore(capacity);
        AlarmEventRepository mockRepo = Mockito.mock(AlarmEventRepository.class);
        Map<String, AlarmEvent> eventMap = new ConcurrentHashMap<>();

        // 配置 save：保存并返回传入对象
        Mockito.when(mockRepo.save(Mockito.any(AlarmEvent.class))).thenAnswer(inv -> {
            AlarmEvent e = inv.getArgument(0);
            eventMap.put(e.getId(), e);
            return e;
        });
        // 配置 findById
        Mockito.when(mockRepo.findById(Mockito.anyString()))
                .thenAnswer(inv -> Optional.ofNullable(eventMap.get(inv.getArgument(0))));
        // 配置 findAll（无参版本）
        Mockito.when(mockRepo.findAll())
                .thenAnswer(inv -> new ArrayList<>(eventMap.values()));
        // 配置 findAll(Pageable)：按排序方向返回分页结果
        Mockito.when(mockRepo.findAll(Mockito.any(Pageable.class))).thenAnswer(inv -> {
            Pageable pageable = inv.getArgument(0);
            List<AlarmEvent> all = new ArrayList<>(eventMap.values());
            Sort sort = pageable.getSort();
            if (sort != null && sort.isSorted()) {
                for (Sort.Order order : sort) {
                    if ("timestampMs".equals(order.getProperty())) {
                        Comparator<AlarmEvent> cmp = Comparator.comparingLong(AlarmEvent::getTimestampMs);
                        if (order.isDescending()) {
                            cmp = cmp.reversed();
                        }
                        all.sort(cmp);
                    }
                }
            }
            int start = (int) pageable.getOffset();
            int end = Math.min(start + pageable.getPageSize(), all.size());
            List<AlarmEvent> subList = start < all.size() ? new ArrayList<>(all.subList(start, end)) : new ArrayList<>();
            return new PageImpl<>(subList, pageable, all.size());
        });
        // 配置 count
        Mockito.when(mockRepo.count()).thenAnswer(inv -> (long) eventMap.size());
        // 配置 delete
        Mockito.doAnswer(inv -> {
            AlarmEvent e = inv.getArgument(0);
            eventMap.remove(e.getId());
            return null;
        }).when(mockRepo).delete(Mockito.any(AlarmEvent.class));

        // 用反射注入到 repository 字段
        try {
            java.lang.reflect.Field f = AlarmEventStore.class.getDeclaredField("repository");
            f.setAccessible(true);
            f.set(store, mockRepo);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException("注入 mock repository 失败", e);
        }

        return store;
    }

    @Test
    @DisplayName("store 存储事件后可通过 getById 查询")
    void storeAndGetById() {
        AlarmEventStore store = createStore();
        AlarmEvent e = event("e1", AlarmEvent.EventType.FIRE, AlarmEvent.Severity.WARN, 100L);
        store.store(e);

        assertThat(store.size()).isEqualTo(1);
        assertThat(store.getById("e1")).isSameAs(e);
    }

    @Test
    @DisplayName("getById 不存在返回 null")
    void getByIdNonExistentReturnsNull() {
        AlarmEventStore store = createStore();
        assertThat(store.getById("nonexistent")).isNull();
    }

    @Test
    @DisplayName("acknowledge 确认事件返回 true")
    void acknowledgeReturnsTrue() {
        AlarmEventStore store = createStore();
        store.store(event("e1", AlarmEvent.EventType.FIRE, AlarmEvent.Severity.WARN, 100L));

        assertThat(store.acknowledge("e1")).isTrue();
        assertThat(store.getById("e1").isAcknowledged()).isTrue();
    }

    @Test
    @DisplayName("acknowledge 不存在返回 false")
    void acknowledgeNonExistentReturnsFalse() {
        AlarmEventStore store = createStore();
        assertThat(store.acknowledge("nonexistent")).isFalse();
    }

    @Test
    @DisplayName("query 分页返回正确结果")
    void queryPaginated() {
        AlarmEventStore store = createStore();
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
        AlarmEventStore store = createStore();
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
        AlarmEventStore store = createStore();
        store.store(event("e1", AlarmEvent.EventType.FIRE, AlarmEvent.Severity.WARN, 100L));
        store.store(event("e2", AlarmEvent.EventType.MOTION, AlarmEvent.Severity.WARN, 200L));

        AlarmEventStore.PageResult result = store.query(0, 10, null, "FIRE");
        assertThat(result.getTotal()).isEqualTo(1);
        assertThat(result.getItems().get(0).getId()).isEqualTo("e1");
    }

    @Test
    @DisplayName("query 同时按 severity 和 type 筛选")
    void queryFilterBySeverityAndType() {
        AlarmEventStore store = createStore();
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
        AlarmEventStore store = createStore();
        store.store(event("old", AlarmEvent.EventType.MOTION, AlarmEvent.Severity.WARN, 100L));
        store.store(event("new", AlarmEvent.EventType.MOTION, AlarmEvent.Severity.WARN, 200L));

        AlarmEventStore.PageResult result = store.query(0, 10, null, null);
        assertThat(result.getItems().get(0).getId()).isEqualTo("new");
        assertThat(result.getItems().get(1).getId()).isEqualTo("old");
    }

    @Test
    @DisplayName("容量限制：超过容量时驱逐最旧事件")
    void capacityEvictsOldest() {
        AlarmEventStore store = createStore(3);
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
        AlarmEventStore store = createStore(2);
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
        AlarmEventStore store = createStore();
        store.store(event("e1", AlarmEvent.EventType.MOTION, AlarmEvent.Severity.WARN, 100L));

        AlarmEventStore.PageResult result = store.query(99, 10, null, null);
        assertThat(result.getTotal()).isEqualTo(1);
        assertThat(result.getItems()).isEmpty();
    }

    @Test
    @DisplayName("并发安全：多线程同时存储不丢失事件（在容量内）")
    void concurrentStore() throws InterruptedException {
        AlarmEventStore store = createStore(10000);
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
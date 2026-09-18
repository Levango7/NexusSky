package io.aerofleet.cloud.alarm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 报警事件内存存储（M10 报警联动编排，FR-31）。
 * <p>
 * 线程安全的有界存储：使用 {@link ConcurrentLinkedDeque} 维护最近 N 条事件，
 * {@link ConcurrentHashMap} 维护 id → event 索引以支持 O(1) 查询。
 * <p>
 * 容量达到上限时，新事件会驱逐最旧事件（FIFO 驱逐策略）。
 * 适用于开发/演示环境；生产环境应替换为持久化存储。
 *
 * @see AlarmEvent
 * @see AlarmController
 */
@Component
public class AlarmEventStore {

    private static final Logger log = LoggerFactory.getLogger(AlarmEventStore.class);

    /** 默认容量（最近 1000 条事件）。 */
    public static final int DEFAULT_CAPACITY = 1000;

    private final int capacity;
    /** 按时间顺序保存的事件（最新在队首）。 */
    private final ConcurrentLinkedDeque<AlarmEvent> events;
    /** id → event 索引，支持 O(1) 按 ID 查询。 */
    private final ConcurrentHashMap<String, AlarmEvent> index;

    public AlarmEventStore() {
        this(DEFAULT_CAPACITY);
    }

    public AlarmEventStore(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.capacity = capacity;
        this.events = new ConcurrentLinkedDeque<>();
        this.index = new ConcurrentHashMap<>();
    }

    /**
     * 存储报警事件。
     * <p>
     * 容量达到上限时驱逐最旧事件（队尾）。索引同步更新。
     *
     * @param event 报警事件
     */
    public synchronized void store(AlarmEvent event) {
        events.addFirst(event);
        index.put(event.getId(), event);
        // 驱逐超容量事件
        while (events.size() > capacity) {
            AlarmEvent evicted = events.pollLast();
            if (evicted != null) {
                index.remove(evicted.getId());
            }
        }
        log.debug("alarm event stored: id={} type={} total={}",
                event.getId(), event.getEventType(), events.size());
    }

    /**
     * 按 ID 查询事件。
     *
     * @param id 事件 ID
     * @return 事件，不存在返回 null
     */
    public AlarmEvent getById(String id) {
        return index.get(id);
    }

    /**
     * 确认报警事件。
     *
     * @param id 事件 ID
     * @return true 若事件存在并已确认
     */
    public boolean acknowledge(String id) {
        AlarmEvent event = index.get(id);
        if (event == null) {
            return false;
        }
        event.acknowledge();
        log.info("alarm event acknowledged: id={}", id);
        return true;
    }

    /**
     * 分页查询事件（支持按严重程度与事件类型筛选）。
     * <p>
     * 返回结果按时间戳倒序（最新在前）。{@code severityFilter} 与
     * {@code typeFilter} 为 null 或空字符串时不参与筛选。
     *
     * @param page           页码（0-based）
     * @param size           每页大小
     * @param severityFilter 严重程度过滤（INFO/WARN/CRITICAL），null/空表示不过滤
     * @param typeFilter     事件类型过滤（MOTION/INTRUSION/FIRE/DOOR/CUSTOM），null/空表示不过滤
     * @return 分页结果（含 items/total/page/size）
     */
    public PageResult query(int page, int size, String severityFilter, String typeFilter) {
        if (page < 0) {
            page = 0;
        }
        if (size <= 0) {
            size = 20;
        }
        // 筛选 + 排序（最新在前）
        List<AlarmEvent> filtered = new ArrayList<>();
        for (AlarmEvent e : events) {
            if (!matchesFilter(e, severityFilter, typeFilter)) {
                continue;
            }
            filtered.add(e);
        }
        filtered.sort(Comparator.comparingLong(AlarmEvent::getTimestampMs).reversed());

        int total = filtered.size();
        int from = Math.min(page * size, total);
        int to = Math.min(from + size, total);
        List<AlarmEvent> pageItems = new ArrayList<>(filtered.subList(from, to));
        return new PageResult(pageItems, total, page, size);
    }

    /** 当前存储事件总数。 */
    public int size() {
        return events.size();
    }

    /** 容量上限。 */
    public int capacity() {
        return capacity;
    }

    private static boolean matchesFilter(AlarmEvent e, String severityFilter, String typeFilter) {
        if (severityFilter != null && !severityFilter.isEmpty()) {
            if (!e.getSeverity().name().equalsIgnoreCase(severityFilter)) {
                return false;
            }
        }
        if (typeFilter != null && !typeFilter.isEmpty()) {
            if (!e.getEventType().name().equalsIgnoreCase(typeFilter)) {
                return false;
            }
        }
        return true;
    }

    /** 分页查询结果。 */
    public static class PageResult {
        private final List<AlarmEvent> items;
        private final int total;
        private final int page;
        private final int size;

        public PageResult(List<AlarmEvent> items, int total, int page, int size) {
            this.items = items;
            this.total = total;
            this.page = page;
            this.size = size;
        }

        public List<AlarmEvent> getItems() {
            return items;
        }

        public int getTotal() {
            return total;
        }

        public int getPage() {
            return page;
        }

        public int getSize() {
            return size;
        }
    }
}
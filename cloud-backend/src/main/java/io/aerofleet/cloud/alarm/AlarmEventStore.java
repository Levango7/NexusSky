package io.aerofleet.cloud.alarm;

import io.aerofleet.cloud.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 报警事件持久化存储（M10 报警联动编排，FR-31）。
 * <p>
 * 基于 JPA Repository 的有界存储：事件持久化到数据库，重启不丢失。
 * 容量达到上限时，新事件会驱逐最旧事件（FIFO 驱逐策略）。
 *
 * @see AlarmEvent
 * @see AlarmEventRepository
 * @see AlarmController
 */
@Component
public class AlarmEventStore {

    private static final Logger log = LoggerFactory.getLogger(AlarmEventStore.class);

    /** 默认容量（最近 1000 条事件）。 */
    public static final int DEFAULT_CAPACITY = 1000;

    private final int capacity;

    /**
     * 事件总数高水位缓存计数器。
     * <p>
     * 初始值为 -1 表示「未初始化」；首次写入时通过懒初始化从数据库
     * {@code count()} 同步一次真实总数，此后每次 store 仅做 O(1) 递增/递减，
     * 避免每次写入都执行全表 COUNT（O(N) 全表扫描）。
     */
    private final AtomicInteger cachedCount = new AtomicInteger(-1);

    @Autowired
    private AlarmEventRepository repository;

    public AlarmEventStore() {
        this(DEFAULT_CAPACITY);
    }

    public AlarmEventStore(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.capacity = capacity;
    }

    /**
     * 懒初始化高水位计数器。
     * <p>
     * 仅当计数器尚未初始化（值为负数）时，才执行一次数据库 {@code count()} 同步真实总数。
     * 使用双重检查 + {@code synchronized(this)} 与 {@link #store(AlarmEvent)} 的锁保持一致，
     * 保证并发/多线程下初始化只发生一次。
     * <p>
     * 采用懒初始化而非 {@code @PostConstruct} 的原因：测试中会手动 {@code new AlarmEventStore()}
     * 并通过反射注入 mock repository，不会触发 Spring 生命周期回调，故必须在首次写入时兜底初始化。
     */
    private void ensureCountInit() {
        if (cachedCount.get() < 0) {
            synchronized (this) {
                if (cachedCount.get() < 0) {
                    cachedCount.set((int) repository.count());
                }
            }
        }
    }

    /**
     * 存储报警事件。
     * <p>
     * 容量达到上限时驱逐最旧事件。持久化到数据库。
     *
     * @param event 报警事件
     */
    @Transactional
    public synchronized void store(AlarmEvent event) {
        ensureCountInit();
        // 写入侧落租户：REST 摄取路径带上当前请求的租户，否则读侧过滤后本租户永远查不到
        // 自己的事件。调用方已显式设置（设备归属）时不覆盖。
        if (event.getTenantId() == null) {
            event.setTenantId(TenantContext.getWritableTenantId());
        }
        repository.save(event);
        cachedCount.incrementAndGet();
        // 驱逐超容量事件：按时间戳正序（最旧在前），删除超出容量的部分
        if (cachedCount.get() > capacity) {
            int excess = cachedCount.get() - capacity;
            PageRequest oldestPage = PageRequest.of(0, excess, Sort.by("timestampMs").ascending());
            List<AlarmEvent> oldest = repository.findAll(oldestPage).getContent();
            for (AlarmEvent evicted : oldest) {
                repository.delete(evicted);
                cachedCount.decrementAndGet();
            }
            log.debug("evicted {} oldest alarm events to maintain capacity {}", excess, capacity);
        }
        log.debug("alarm event stored: id={} type={} total={}",
                event.getId(), event.getEventType(), cachedCount.get());
    }

    /**
     * 按 ID 查询事件。
     * <p>
     * 租户隔离：他租户事件视为不存在（返回 null，由调用方转 404），
     * 口径与 {@code DeviceRegistry.get()} 一致——不按 ID 直取暴露资源归属。
     *
     * @param id 事件 ID
     * @return 事件，不存在或不属于当前租户返回 null
     */
    public AlarmEvent getById(String id) {
        AlarmEvent event = repository.findById(id).orElse(null);
        return isVisibleTo(event, TenantContext.getEffectiveTenantId()) ? event : null;
    }

    /**
     * 确认报警事件。
     *
     * @param id 事件 ID
     * @return true 若事件存在并已确认
     */
    @Transactional
    public boolean acknowledge(String id) {
        Optional<AlarmEvent> opt = repository.findById(id);
        if (opt.isEmpty()) {
            return false;
        }
        AlarmEvent event = opt.get();
        event.acknowledge();
        repository.save(event);
        log.info("alarm event acknowledged: id={}", id);
        return true;
    }

    /**
     * 分页查询事件（支持按严重程度与事件类型筛选）。
     * <p>
     * 返回结果按时间戳倒序（最新在前）。{@code severityFilter} 与
     * {@code typeFilter} 为 null 或空字符串时不参与筛选。
     * <p>
     * 租户隔离：有租户上下文时只返回本租户事件；有效租户为 null（全局管理员 /
     * dev-mode / 无请求上下文的后台线程）时返回全部。
     *
     * @param page           页码（0-based）
     * @param size           每页大小
     * @param severityFilter 严重程度过滤（INFO/WARN/CRITICAL），null/空表示不过滤
     * @param typeFilter     事件类型过滤（MOTION/INTRUSION/FIRE/DOOR/CUSTOM），null/空表示不过滤
     * @return 分页结果（含 items/total/page/size）
     */
    public PageResult query(int page, int size, String severityFilter, String typeFilter) {
        return query(page, size, severityFilter, typeFilter, TenantContext.getEffectiveTenantId());
    }

    /**
     * 分页查询事件（显式租户域版本）。
     * <p>
     * 给跑在非请求线程上的调用方用（典型：{@code AlarmController} 的 SSE 轮询
     * 任务跑在调度线程上，ThreadLocal 不可用，必须在订阅时于请求线程捕获
     * 租户域传进来）。租户域语义与 ThreadLocal 版本一致：null=全局管理员 /
     * 租户 ID=本租户 / {@link TenantContext#NO_ACCESS}=什么都看不到。
     * 传 null 不代表「不过滤」，而是「全局管理员视角」——与既有口径一致，
     * 调用方应原样转发 {@code TenantContext.getEffectiveTenantId()} 的捕获值。
     */
    public PageResult query(int page, int size, String severityFilter, String typeFilter,
                            Integer tenantScope) {
        if (page < 0) {
            page = 0;
        }
        if (size <= 0) {
            size = 20;
        }
        // 从数据库加载所有事件，筛选 + 排序（最新在前）
        List<AlarmEvent> all = repository.findAll(
                PageRequest.of(0, capacity, Sort.by("timestampMs").descending())
        ).getContent();

        List<AlarmEvent> filtered = new ArrayList<>();
        for (AlarmEvent e : all) {
            if (!isVisibleTo(e, tenantScope)) {
                continue;
            }
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
        return (int) repository.count();
    }

    /**
     * 指定租户域可见的事件数。
     * <p>
     * 与 {@link #query(int, int, String, String, Integer)} 基于同一容量窗口
     * （前 {@code capacity} 条）统计，保证「计数差值 → 取页」的 SSE 增量推送
     * 两侧口径一致；{@link #size()} 是全表 COUNT，两者在表超过容量窗口时
     * 会出现差额，增量逻辑应只使用本方法。
     */
    public int count(Integer tenantScope) {
        List<AlarmEvent> all = repository.findAll(
                PageRequest.of(0, capacity, Sort.by("timestampMs").descending())
        ).getContent();
        int count = 0;
        for (AlarmEvent e : all) {
            if (isVisibleTo(e, tenantScope)) {
                count++;
            }
        }
        return count;
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

    /**
     * 租户可见性判定，口径与 {@code DeviceRegistry.isVisibleTo()} 一致：
     * <ul>
     *   <li>有效租户为 null —— 全局管理员（含 dev-mode，无 TenantFilter 设置上下文），看全部；</li>
     *   <li>否则仅本租户；事件 tenantId 为 null 视为「未归属」，任何具体租户都看不到。</li>
     * </ul>
     * 显式租户域参数供非请求线程（SSE 轮询任务）使用——它们必须把请求线程捕获的
     * {@code TenantContext.getEffectiveTenantId()} 传进来；不传而依赖 ThreadLocal
     * 会取到 null（后台线程），被当作全局管理员，等于跨租户全量可见。
     *
     * @param event     事件，可为 null
     * @param tenantId  有效租户域（null=全局管理员 / 租户 ID / NO_ACCESS）
     * @return 指定租户域可见返回 true；事件为 null 或他租户返回 false
     */
    private static boolean isVisibleTo(AlarmEvent event, Integer tenantId) {
        if (event == null) {
            return false;
        }
        return tenantId == null || tenantId.equals(event.getTenantId());
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
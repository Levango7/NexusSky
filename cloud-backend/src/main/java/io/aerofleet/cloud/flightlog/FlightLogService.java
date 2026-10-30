package io.aerofleet.cloud.flightlog;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aerofleet.cloud.gateway.AlertEntry;
import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import io.aerofleet.cloud.gateway.TrackPoint;
import io.aerofleet.cloud.security.TenantContext;
import io.aerofleet.cloud.write.BatchedWriteQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Flight-log persistence: JSON Lines files under a configurable directory,
 * one file per local day ({@code LocalDate.now()} in the JVM's default zone),
 * one line per event.
 *
 *   { "t": "...", "type": "telemetry", "sysid": 1, "alt": 42.3, ... }
 *   { "t": "...", "type": "alert",     "sysid": 1, "severity": 6, "text": "..." }
 *   { "t": "...", "type": "mission",  "sysid": 1, "text": "uploaded 4 items" }
 *
 * The write path is fire-and-forget on the calling thread (single JSON line,
 * small); a failed append logs and drops rather than disturbing the caller.
 * Query path: REST reads today's (or a given day's) file back, newest-first
 * optional. This is the scaffold-honest persistence: no DB dependency, files
 * are grep-able, swapping to SQLite/Postgres later is a package change.
 * <p>
 * {@code aerofleet.flightlog.persist-to-db=true} 时写入与查询优先走
 * {@code flight_log} 表（{@link FlightLogRepository}），DB 异常自动回退 JSONL，
 * 两条路径的返回格式与租户过滤口径保持一致。
 */
@Component
public class FlightLogService {

    private static final Logger log = LoggerFactory.getLogger(FlightLogService.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final ObjectMapper mapper;
    private final Path dir;
    /** Track points are written at most this often per drone (ms). */
    private final long trackMinIntervalMs;

    /** 是否将飞行日志持久化到数据库（默认 false，保持 JSONL 行为不变）。 */
    @Value("${aerofleet.flightlog.persist-to-db:false}")
    private boolean persistToDb;

    /** JPA Repository（可选注入，数据库不可用时不影响 JSONL 路径）。 */
    @Autowired(required = false)
    private FlightLogRepository flightLogRepository;

    /** 批量落库用（可选注入）；缺失时退回 {@code saveAll} 逐行写。 */
    @Autowired(required = false)
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    /** 插入语句缓存，见 {@link #insertSql()}。 */
    private volatile String insertSql;

    /** 批量写队列（延迟创建，见 {@link #dbWriteQueue()}）。 */
    private volatile io.aerofleet.cloud.write.BatchedWriteQueue<PendingLog> dbWriteQueue;

    /** 队列容量：满即就地走 JSONL 兜底，不背压。50 机 × 1 行/秒 ≈ 400 秒缓冲。 */
    @Value("${aerofleet.flightlog.write-queue-capacity:20000}")
    private int writeQueueCapacity;

    /** 单批最多落库条数；应与 hibernate.jdbc.batch_size 同量级。 */
    @Value("${aerofleet.flightlog.write-batch-size:50}")
    private int writeBatchSize;

    /** writer 线程空闲轮询间隔（毫秒），也决定关闭时的最长排空延迟。 */
    @Value("${aerofleet.flightlog.write-flush-ms:200}")
    private long writeFlushIntervalMs;

    /**
     * 设备注册表（可选注入）：写入侧据此取设备归属租户。
     * 用字段注入而非构造注入，避免改变构造器签名（现有单测直接 new FlightLogService）。
     */
    @Autowired(required = false)
    private DeviceRegistry deviceRegistry;

    /** sysid -> last track-point write, for the telemetry throttle.
     *  CHM：telemetry() 可能被并发调用，避免 HashMap 并发写损坏内部结构；节流判断本身允许良性竞态。 */
    private final Map<Integer, Long> lastTrackWrite = new ConcurrentHashMap<>();

    public FlightLogService(
            @Value("${aerofleet.flightlog.dir:./flight-logs}") String dir,
            @Value("${aerofleet.flightlog.track-interval-ms:1000}") long trackMinIntervalMs,
            ObjectMapper objectMapper) {
        this.dir = Path.of(dir);
        this.trackMinIntervalMs = trackMinIntervalMs;
        this.mapper = objectMapper;
        try {
            Files.createDirectories(this.dir);
            log.info("flight log directory: {}", this.dir.toAbsolutePath());
        } catch (IOException e) {
            log.error("flight log directory unusable ({}): {}", dir, e.getMessage());
            throw new UncheckedIOException(e);
        }
    }

    /** File for a given day (local date, same zone as {@link #now()}). */
    private Path fileFor(LocalDate day) {
        return dir.resolve("flight-" + day + ".jsonl");
    }

    private void append(Map<String, Object> event) {
        // 数据库路径：入队，绝不在调用线程上写库。
        // 这条链的起点可能是 MAVLink UDP 接收线程（告警经 AlertBus 同步派发）或那个
        // 单线程调度池（每秒遥测快照），同步 JPA 写一慢就会拖住收包或所有 @Scheduled 作业。
        if (persistToDb && flightLogRepository != null) {
            BatchedWriteQueue<PendingLog> queue = dbWriteQueue();
            if (queue != null && queue.offer(new PendingLog(FlightLogEntity.from(event), event))) {
                return;
            }
            // 队列满或 writer 已停：就地走 JSONL 兜底，不阻塞、不丢账
        }
        writeJsonl(event);
    }

    /** JSONL 追加——默认路径，也是 DB 不可用/队列满时的兜底路径。 */
    private void writeJsonl(Map<String, Object> event) {
        try {
            String line = mapper.writeValueAsString(event) + "\n";
            Files.writeString(fileFor(LocalDate.now()), line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.debug("flight log append failed: {}", e.getMessage());
        }
    }

    /**
     * 延迟创建批量写队列。之所以不在构造器/字段初始化里建：
     * ① {@code flightLogRepository} 是可选注入，构造期可能还是 null；
     * ② 现有单测用反射在构造之后才塞 repository（FlightLogPersistenceTest:320），
     *    提前捕获引用会拿到过期 sink。故 flush 时才读字段当前值。
     *
     * @return 队列；repository 尚未就绪时返回 null（调用方走兜底）
     */
    private BatchedWriteQueue<PendingLog> dbWriteQueue() {
        BatchedWriteQueue<PendingLog> existing = this.dbWriteQueue;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (this.dbWriteQueue == null) {
                if (flightLogRepository == null) {
                    return null;
                }
                this.dbWriteQueue = new BatchedWriteQueue<>(
                        "flight-log", writeQueueCapacity, writeBatchSize, writeFlushIntervalMs,
                        this::writeBatch,
                        (batch, err) -> {
                            for (PendingLog p : batch) {
                                writeJsonl(p.jsonl());
                            }
                        });
            }
            return this.dbWriteQueue;
        }
    }

    /**
     * 一批落库：优先走显式 JDBC 多行批量插入，没有 JdbcTemplate 时退回 {@code saveAll}。
     * <p>
     * 为什么不靠 Hibernate 批：{@code flight_log.id} 是 IDENTITY 主键，Hibernate 为取回生成键
     * 必须逐行执行，{@code hibernate.jdbc.batch_size} 对它无效；而改序列主键会让 prod 的
     * {@code ddl-auto=validate} 在真 PostgreSQL 上报 missing sequence（实测）。列与值都从
     * 实体注解同源派生，不存在手写清单漂移。
     */
    void writeBatch(List<PendingLog> batch) {
        List<FlightLogEntity> entities = batch.stream().map(PendingLog::entity).toList();
        if (jdbcTemplate == null) {
            flightLogRepository.saveAll(entities);
            return;
        }
        String sql = insertSql();
        int[] types = FlightLogEntity.insertSqlTypes();
        List<Object[]> rows = entities.stream().map(FlightLogEntity::insertValues).toList();
        // 第三个参数是"每个 JDBC 批多少**行**"，不是列数——写错不会报错，只会让批大小悄悄变成列数
        jdbcTemplate.batchUpdate(sql, rows, writeBatchSize,
                (org.springframework.jdbc.core.ParameterizedPreparedStatementSetter<Object[]>) (ps, row) -> {
                    for (int i = 0; i < row.length; i++) {
                        if (row[i] == null) {
                            ps.setNull(i + 1, types[i]);
                        } else {
                            // 非 null 也必须带目标类型：漏掉时驱动会自己挑一个渲染方式，
                            // 而它给 java.sql.Timestamp 挑的正是"按 JVM 默认时区摊成无时区字面量"，
                            // 于是 timestamptz 之前那版在 +08 机器上写进了本地墙钟。
                            ps.setObject(i + 1, row[i], types[i]);
                        }
                    }
                });
    }

    /** 插入语句（列清单与占位符由实体元数据生成，进程内只算一次）。 */
    private String insertSql() {
        String cached = this.insertSql;
        if (cached != null) {
            return cached;
        }
        String[] columns = FlightLogEntity.insertColumnNames();
        StringBuilder sb = new StringBuilder("insert into flight_log (");
        sb.append(String.join(", ", columns)).append(") values (");
        for (int i = 0; i < columns.length; i++) {
            sb.append(i == 0 ? "?" : ",?");
        }
        cached = sb.append(')').toString();
        this.insertSql = cached;
        return cached;
    }

    /**
     * 等待已入队的写全部落地。写改成异步批量之后，"写完立刻读"不再必然看得见——
     * 这个入口给测试与运维确认"这批已入库"，生产路径不依赖它。
     *
     * @return true 已排空；false 超时或队列还没建（此时确实没有任何在途写）
     */
    public boolean awaitPendingWrites(long timeoutMs) throws InterruptedException {
        BatchedWriteQueue<PendingLog> queue = this.dbWriteQueue;
        return queue == null || queue.awaitIdle(timeoutMs);
    }

    /** 关闭时把队列里剩余的写完；由 Spring 在上下文停止时调用。 */
    @jakarta.annotation.PreDestroy
    public void shutdownWriteQueue() {
        BatchedWriteQueue<PendingLog> queue = this.dbWriteQueue;
        if (queue != null) {
            log.info("flight log 写队列关闭：{}", queue.statsLine());
            queue.close();
        }
    }

    /** 待落库条目：实体给 DB 路径，原始 map 给 JSONL 兜底路径（同一条账的两份表示）。 */
    record PendingLog(FlightLogEntity entity, Map<String, Object> jsonl) {
    }

    private static String now() {
        return LocalDateTime.now().format(TS);
    }

    // ---- writers ----

    /** Throttled telemetry snapshot (1 per drone per interval). */
    public void telemetry(DroneSnapshot s) {
        Long last = lastTrackWrite.get(s.sysid);
        long now = System.currentTimeMillis();
        if (last != null && now - last < trackMinIntervalMs) {
            return;
        }
        lastTrackWrite.put(s.sysid, now);
        Map<String, Object> e = base("telemetry", s.sysid);
        e.put("lat", nanToNull(s.lat));
        e.put("lon", nanToNull(s.lon));
        e.put("relativeAlt", nanToNull(s.relativeAlt));
        e.put("groundspeed", nanToNull(s.groundspeed));
        e.put("battery", s.battery);
        e.put("voltage", s.voltage);
        e.put("mode", s.mode);
        e.put("armed", s.armed);
        e.put("online", s.online);
        append(e);
    }

    /** Alert event: always written immediately. */
    public void alert(int sysid, AlertEntry entry) {
        Map<String, Object> e = base("alert", sysid);
        e.put("severity", entry.severity);
        e.put("text", entry.text);
        append(e);
    }

    /** Mission lifecycle event: upload/ack/commands worth remembering. */
    public void mission(int sysid, String text) {
        Map<String, Object> e = base("mission", sysid);
        e.put("text", text);
        append(e);
    }

    /** Online/offline transition. */
    public void connectivity(int sysid, boolean online) {
        Map<String, Object> e = base("connectivity", sysid);
        e.put("online", online);
        append(e);
    }

    private Map<String, Object> base(String type, int sysid) {
        Map<String, Object> e = new HashMap<>();
        e.put("t", now());
        e.put("type", type);
        e.put("sysid", sysid);
        // 写入侧落租户：日志按设备归档，归属取设备注册表（DeviceRegistry.tenantOf，
        // 与 WS 投递同源）；设备未归属时退回当前请求上下文（REST 触发的写入）。
        Integer tenantId = tenantForWrite(sysid);
        if (tenantId != null) {
            e.put("tenantId", tenantId);
        }
        return e;
    }

    /**
     * 写入侧的租户归属：设备归属优先，无归属设备退回请求上下文。
     * UDP 接收线程没有请求上下文，因此不能只用 {@link TenantContext}。
     *
     * @param sysid 无人机 systemId
     * @return 租户 ID；设备未知/未归属且无上下文时返回 null
     */
    private Integer tenantForWrite(int sysid) {
        if (deviceRegistry != null) {
            Integer deviceTenant = deviceRegistry.tenantOf(sysid);
            if (deviceTenant != null) {
                return deviceTenant;
            }
        }
        return TenantContext.getWritableTenantId();
    }

    private static Object nanToNull(double v) {
        return Double.isNaN(v) ? null : v;
    }

    /**
     * 租户可见性判定，口径与 {@code DeviceRegistry.isVisibleTo()} 一致：
     * 有效租户为 null（全局管理员 / dev-mode / 无请求上下文）时看全部，否则仅本租户；
     * 记录自身租户为 null 视为「未归属」，任何具体租户都看不到。
     *
     * @param recordTenant 记录上的租户 ID，可为 null
     * @param effectiveTenant 当前上下文的有效租户 ID，可为 null
     * @return 可见返回 true
     */
    private static boolean isVisibleTo(Integer recordTenant, Integer effectiveTenant) {
        return effectiveTenant == null || effectiveTenant.equals(recordTenant);
    }

    /** 从 JSONL 反序列化出的 Map 中取租户 ID（历史行无该字段时为 null）。 */
    private static Integer tenantOfMap(Map<String, Object> m) {
        return m.get("tenantId") instanceof Number n ? n.intValue() : null;
    }

    // ---- readers (REST) ----

    /**
     * Read events of one type (or all) for a day, optionally filtered by
     * sysid, limited to the most recent {@code limit} entries.
     * <p>
     * {@code limit > 0} 时"最新 N 条"由数据库截取（LIMIT + 租户条件一起下推）；
     * {@code limit <= 0} 表示"当天全部"，仍走全量查询——HTTP 入口
     * （{@code FlightLogController} / {@code EnvAlertController}）都会把 limit
     * 收口到 [1, 5000]，所以"全部"只对内部调用可见。
     */
    public List<Map<String, Object>> query(LocalDate day, String type, Integer sysid, int limit) {
        // DB 查询路径：persistToDb && repository 可用时优先从数据库查询
        if (persistToDb && flightLogRepository != null) {
            try {
                Instant start = day.atStartOfDay(ZoneId.systemDefault()).toInstant();
                Instant end = day.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
                Integer tenantId = TenantContext.getEffectiveTenantId();

                if (limit > 0) {
                    // 最新 N 条下推 SQL：1 机 1 天 1Hz ≈ 8.6 万行，先全量取回再 subList
                    // 等于把整天数据拖进堆（JSONL 路径已用环形缓冲解决，此处对齐）。
                    // 租户条件必须一起下推：否则"全局最新 N 行"被租户过滤后可能不够 N 条。
                    List<FlightLogEntity> newest = new ArrayList<>(flightLogRepository.findNewestFirst(
                            start, end, tenantId, type, sysid,
                            org.springframework.data.domain.PageRequest.of(0, limit)));
                    Collections.reverse(newest);   // 查询是"最新在前"，对外契约是旧→新（末尾=最新）
                    return newest.stream()
                            .filter(e -> isVisibleTo(e.getTenantId(), tenantId))
                            .map(FlightLogEntity::toMap)
                            .collect(Collectors.toList());
                }

                List<FlightLogEntity> entities;
                if (type != null && sysid != null) {
                    entities = flightLogRepository
                            .findByTypeAndSysidAndTimestampBetweenOrderByTimestampAscIdAsc(type, sysid, start, end);
                } else if (type != null) {
                    entities = flightLogRepository.findByTypeAndTimestampBetweenOrderByTimestampAscIdAsc(type, start, end);
                } else if (sysid != null) {
                    entities = flightLogRepository.findBySysidAndTimestampBetweenOrderByTimestampAscIdAsc(sysid, start, end);
                } else {
                    entities = flightLogRepository.findByTimestampBetweenOrderByTimestampAscIdAsc(start, end);
                }
                return entities.stream()
                        .filter(e -> isVisibleTo(e.getTenantId(), tenantId))
                        .map(FlightLogEntity::toMap)
                        .collect(Collectors.toList());
            } catch (Exception e) {
                log.warn("flight log DB query failed, falling back to JSONL: {}", e.getMessage());
                // 回退到 JSONL 路径
            }
        }
        // JSONL 文件查询路径（默认或 DB 失败回退）
        Path f = fileFor(day);
        if (!Files.isReadable(f)) {
            return List.of();
        }
        Integer jsonlTenant = TenantContext.getEffectiveTenantId();
        // limit<=0 表示"全天"，仍可流式全收；limit>0 只需末尾 N 条。
        // 用定长环形缓冲而不是全量 readAllLines：1 机 1 天 1Hz 就是 8.6 万行，
        // 全量读+过滤会把整天文件拖进堆，N 机 N 天线性恶化。
        int cap = limit > 0 ? limit : Integer.MAX_VALUE;
        List<Map<String, Object>> ring = new ArrayList<>(Math.min(cap, 1024));
        try (var lines = Files.lines(f, StandardCharsets.UTF_8)) {
            var it = lines.iterator();
            while (it.hasNext()) {
                String line = it.next();
                if (line.isBlank()) {
                    continue;
                }
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = mapper.readValue(line, Map.class);
                    if (!isVisibleTo(tenantOfMap(m), jsonlTenant)) {
                        continue;
                    }
                    if (type != null && !type.equals(m.get("type"))) {
                        continue;
                    }
                    if (sysid != null && sysid.intValue() != ((Number) m.get("sysid")).intValue()) {
                        continue;
                    }
                    if (cap == Integer.MAX_VALUE) {
                        ring.add(m);
                    } else {
                        if (ring.size() == cap) {
                            ring.remove(0);   // 丢最旧，保住"最新 N 条"
                        }
                        ring.add(m);
                    }
                } catch (IOException badLine) {
                    // skip malformed line
                }
            }
        } catch (IOException e) {
            log.warn("flight log read failed: {}", e.getMessage());
            return List.of();
        }
        return ring;
    }

    /**
     * 最近 ≤limit 条轨迹点（时间升序），跨「DB / JSONL」两路与 {@link #trackFor} 同款；
     * 供启动时 {@code FlightTrackStore} 重建内存轨迹历史（C4，ROADMAP:337）。
     *
     * <p>与 {@code trackFor} 的三点差异：①不带 day 边界——从**当下往回**取，JSONL 路径
     * 当日不足时逐日回看（至多 3 天）；②**无租户过滤**——调用点是启动钩子，没有请求
     * 上下文（{@code TenantContext} 为 null ⇒ 本就全可见），内存 deque 的租户过滤在
     * 读取侧由既有路径负责；③DB 路径用 Pageable 只取末尾 N 条（id 倒序再反转），
     * 不为 3600 点拉整天（1 机 1 天 1Hz = 8.6 万行）。
     *
     * @param sysid 目标机
     * @param limit 最大点数；≤0 返回空
     */
    public List<TrackPoint> recentTrack(int sysid, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        // DB 路径：id 倒序取末尾 limit 条，反转成时间升序
        if (persistToDb && flightLogRepository != null) {
            try {
                List<FlightLogEntity> desc = flightLogRepository.findByTypeAndSysid("telemetry", sysid,
                        org.springframework.data.domain.PageRequest.of(0, limit,
                                org.springframework.data.domain.Sort.by(
                                        org.springframework.data.domain.Sort.Direction.DESC, "id")));
                List<TrackPoint> pts = new ArrayList<>(desc.size());
                for (int i = desc.size() - 1; i >= 0; i--) {
                    FlightLogEntity e = desc.get(i);
                    if (e.getLat() != null && e.getLon() != null) {
                        double alt = e.getRelativeAlt() != null ? e.getRelativeAlt() : 0;
                        pts.add(new TrackPoint(e.getLat(), e.getLon(), alt, tsOf(e.getTimestamp())));
                    }
                }
                return pts;
            } catch (Exception e) {
                log.warn("flight log DB recent-track query failed, falling back to JSONL: {}", e.getMessage());
                // 回退到 JSONL 路径
            }
        }
        // JSONL 路径：逐日回看（今天 → 前天），环形缓冲每日只留末尾 limit 条，最后整体截尾
        List<TrackPoint> out = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (int back = 2; back >= 0; back--) {
            if (out.size() >= limit) {
                break;
            }
            for (Map<String, Object> m : query(today.minusDays(back), "telemetry", sysid, limit)) {
                Object lat = m.get("lat");
                Object lon = m.get("lon");
                if (lat == null || lon == null) {
                    continue;
                }
                double alt = m.get("relativeAlt") instanceof Number n ? n.doubleValue() : 0;
                out.add(new TrackPoint(((Number) lat).doubleValue(), ((Number) lon).doubleValue(),
                        alt, tsOf(m.get("t"))));
            }
        }
        return out.size() > limit ? new ArrayList<>(out.subList(out.size() - limit, out.size())) : out;
    }

    /** Track points for a drone on a day, reconstructed from telemetry lines. */
    public List<TrackPoint> trackFor(LocalDate day, int sysid) {
        return trackFor(day, sysid, 0);
    }

    /**
     * Track points for a drone on a day, reconstructed from telemetry lines.
     *
     * <p>{@code limit <= 0} keeps the <b>whole day</b> (forensics / lost-drone search
     * needs the complete trail; this is the historical default). {@code limit > 0}
     * keeps the <b>newest N</b> points and still returns them oldest→newest, so the
     * caller's polyline direction is unchanged.
     *
     * <p>Why the option exists: one drone at 1 Hz is ≈ 86k rows/day, while the live
     * map only ever renders the latest 1500 ({@code mergeTrack}). Pulling the whole
     * day just to drop 98% of it is pure waste, but capping unconditionally would
     * remove the complete-trail guarantee from the endpoint's forensic use case.
     *
     * <p>On the DB path the tenant filter and the {@code LIMIT} are pushed down
     * together via {@link FlightLogRepository#findNewestFirst}; filtering tenants in
     * memory after a global "newest N" query would under-fill when the newest rows
     * belong to another tenant.
     *
     * @param day   local day
     * @param sysid drone id
     * @param limit max points; {@code <= 0} = whole day
     */
    public List<TrackPoint> trackFor(LocalDate day, int sysid, int limit) {
        // DB 查询路径：persistToDb && repository 可用时优先从数据库查询
        if (persistToDb && flightLogRepository != null) {
            try {
                Instant start = day.atStartOfDay(ZoneId.systemDefault()).toInstant();
                Instant end = day.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
                Integer tenantId = TenantContext.getEffectiveTenantId();
                List<FlightLogEntity> entities;
                if (limit > 0) {
                    // 最新 N 条下推 SQL（租户条件一起下推），再反转成对外契约的旧→新
                    entities = new ArrayList<>(flightLogRepository.findNewestFirst(
                            start, end, tenantId, "telemetry", sysid,
                            org.springframework.data.domain.PageRequest.of(0, limit)));
                    Collections.reverse(entities);
                } else {
                    entities = flightLogRepository
                            .findByTypeAndSysidAndTimestampBetweenOrderByTimestampAscIdAsc(
                                    "telemetry", sysid, start, end);
                }
                List<TrackPoint> pts = new ArrayList<>(entities.size());
                for (FlightLogEntity e : entities) {
                    if (!isVisibleTo(e.getTenantId(), tenantId)) {
                        continue;
                    }
                    if (e.getLat() != null && e.getLon() != null) {
                        double alt = e.getRelativeAlt() != null ? e.getRelativeAlt() : 0;
                        pts.add(new TrackPoint(e.getLat(), e.getLon(), alt, tsOf(e.getTimestamp())));
                    }
                }
                return pts;
            } catch (Exception e) {
                log.warn("flight log DB track query failed, falling back to JSONL: {}", e.getMessage());
                // 回退到 JSONL 路径
            }
        }
        // JSONL 文件查询路径（默认或 DB 失败回退）。
        // limit 直接交给 query()：它用定长环形缓冲只保留最新 N 行，不会先读整天再截尾。
        List<TrackPoint> pts = new ArrayList<>();
        for (Map<String, Object> m : query(day, "telemetry", sysid, limit)) {
            Object lat = m.get("lat");
            Object lon = m.get("lon");
            if (lat == null || lon == null) {
                continue;
            }
            double alt = m.get("relativeAlt") instanceof Number n ? n.doubleValue() : 0;
            pts.add(new TrackPoint(((Number) lat).doubleValue(), ((Number) lon).doubleValue(),
                    alt, tsOf(m.get("t"))));
        }
        return pts;
    }

    /**
     * 轨迹点的 {@code ts}：**epoch 毫秒**（与实时/内存路径的 {@code System.currentTimeMillis()} 同口径）。
     *
     * <p><b>为什么这里以前写死 0 是 bug</b>：前端 {@code mergeTrack()} 用 {@code ts} 当
     * **合并键**（同一 ts 只留一个点）并按它排序；{@code useDrones.js} 也用 {@code prev[last].ts
     * === point.ts} 去重。持久化路径把 {@code ts} 写成常量 0 ⇒ <b>当天的历史轨迹被去重成
     * 一个点</b>，且因为 0 最小还会排到折线最前面。API 明明声明了 {@code ts} 字段，
     * 实际永远不带时间戳 —— 典型的「声明≠行为」，而单元测试喂的是合成的非零 ts，测不出来。
     *
     * <p>DB 路径给的是 {@link Instant}（绝对时刻，直接 {@code toEpochMilli}）；
     * JSONL 路径给的是写入侧 {@code now()} 的 **本地墙钟** ISO 字符串
     * （{@code DateTimeFormatter.ISO_LOCAL_DATE_TIME}），按系统时区还原成绝对时刻。
     */
    private static long tsOf(Instant instant) {
        return instant == null ? 0L : instant.toEpochMilli();
    }

    /** JSONL 里的 {@code "t"}（本地墙钟 ISO 字符串）→ epoch 毫秒；认不出来时退回 0。 */
    private static long tsOf(Object raw) {
        if (raw == null) {
            return 0L;
        }
        if (raw instanceof Number n) {
            return n.longValue();
        }
        if (raw instanceof Instant i) {
            return i.toEpochMilli();
        }
        try {
            return LocalDateTime.parse(String.valueOf(raw))
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (RuntimeException e) {
            return 0L;
        }
    }
}

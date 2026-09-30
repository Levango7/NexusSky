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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    /** sysid -> last track-point write, for the telemetry throttle. */
    private final Map<Integer, Long> lastTrackWrite = new HashMap<>();

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
                            ps.setObject(i + 1, row[i]);
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
     */
    public List<Map<String, Object>> query(LocalDate day, String type, Integer sysid, int limit) {
        // DB 查询路径：persistToDb && repository 可用时优先从数据库查询
        if (persistToDb && flightLogRepository != null) {
            try {
                Instant start = day.atStartOfDay(ZoneId.systemDefault()).toInstant();
                Instant end = day.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
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
                Integer tenantId = TenantContext.getEffectiveTenantId();
                List<Map<String, Object>> out = entities.stream()
                        .filter(e -> isVisibleTo(e.getTenantId(), tenantId))
                        .map(FlightLogEntity::toMap)
                        .collect(Collectors.toList());
                // 取末尾 = 最新 N 条：JSONL 路径按追加顺序天然如此，DB 路径靠
                // 查询的 ORDER BY timestamp, id 保证同一口径。
                if (limit > 0 && out.size() > limit) {
                    return out.subList(out.size() - limit, out.size());
                }
                return out;
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
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
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
                    out.add(m);
                } catch (IOException badLine) {
                    // skip malformed line
                }
            }
        } catch (IOException e) {
            log.warn("flight log read failed: {}", e.getMessage());
            return List.of();
        }
        if (limit > 0 && out.size() > limit) {
            return out.subList(out.size() - limit, out.size());
        }
        return out;
    }

    /** Track points for a drone on a day, reconstructed from telemetry lines. */
    public List<TrackPoint> trackFor(LocalDate day, int sysid) {
        // DB 查询路径：persistToDb && repository 可用时优先从数据库查询
        if (persistToDb && flightLogRepository != null) {
            try {
                Instant start = day.atStartOfDay(ZoneId.systemDefault()).toInstant();
                Instant end = day.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
                List<FlightLogEntity> entities = flightLogRepository
                        .findByTypeAndSysidAndTimestampBetweenOrderByTimestampAscIdAsc("telemetry", sysid, start, end);
                Integer tenantId = TenantContext.getEffectiveTenantId();
                List<TrackPoint> pts = new ArrayList<>();
                for (FlightLogEntity e : entities) {
                    if (!isVisibleTo(e.getTenantId(), tenantId)) {
                        continue;
                    }
                    if (e.getLat() != null && e.getLon() != null) {
                        double alt = e.getRelativeAlt() != null ? e.getRelativeAlt() : 0;
                        pts.add(new TrackPoint(e.getLat(), e.getLon(), alt, 0));
                    }
                }
                return pts;
            } catch (Exception e) {
                log.warn("flight log DB track query failed, falling back to JSONL: {}", e.getMessage());
                // 回退到 JSONL 路径
            }
        }
        // JSONL 文件查询路径（默认或 DB 失败回退）
        List<TrackPoint> pts = new ArrayList<>();
        for (Map<String, Object> m : query(day, "telemetry", sysid, 0)) {
            Object lat = m.get("lat");
            Object lon = m.get("lon");
            if (lat == null || lon == null) {
                continue;
            }
            double alt = m.get("relativeAlt") instanceof Number n ? n.doubleValue() : 0;
            pts.add(new TrackPoint(((Number) lat).doubleValue(), ((Number) lon).doubleValue(),
                    alt, 0));
        }
        return pts;
    }
}

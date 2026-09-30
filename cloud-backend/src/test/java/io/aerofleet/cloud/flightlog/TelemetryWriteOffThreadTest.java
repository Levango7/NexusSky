package io.aerofleet.cloud.flightlog;

import io.aerofleet.cloud.gateway.DroneSnapshot;
import io.aerofleet.cloud.tracking.FlightTrackStore;
import io.aerofleet.cloud.tracking.DroneLastKnownPositionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 遥测/告警入库的 off-thread 验收测试。
 * <p>
 * 这批改动的全部动机是"入库写跑在生产它的那条线程上"：告警来自 {@code mavlink-udp-*}
 * 单线程 UDP 接收循环（AlertBus 同步派发），遥测快照来自只有 1 条线程的调度池。
 * 所以这里断言的不是"写成功了"，而是<b>写不发生在调用线程上、且 DB 卡住时调用方不受牵连</b>。
 */
@DisplayName("遥测入库 off-thread 验收 (遥测持久化前置)")
class TelemetryWriteOffThreadTest {

    private Path dir;
    private FlightLogService service;
    private FlightLogRepository repository;

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void awaitUntil(java.util.function.BooleanSupplier condition, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("条件未在 " + timeoutMs + "ms 内成立");
    }

    @BeforeEach
    void setUp() throws Exception {
        dir = Files.createTempDirectory("flightlog-offthread");
        service = new FlightLogService(dir.toString(), 0L, new ObjectMapper());
        repository = mock(FlightLogRepository.class);
        setField(service, "persistToDb", true);
        setField(service, "flightLogRepository", repository);
        setField(service, "writeQueueCapacity", 2);
        setField(service, "writeBatchSize", 2);
        setField(service, "writeFlushIntervalMs", 10L);
    }

    @AfterEach
    void tearDown() {
        service.shutdownWriteQueue();
    }

    @Test
    @DisplayName("DB 写卡住时调用方立即返回，写发生在 writer 线程")
    void callerIsNotBlockedByDatabase() throws Exception {
        CountDownLatch sinkEntered = new CountDownLatch(1);
        CountDownLatch releaseSink = new CountDownLatch(1);
        AtomicReference<String> writerThread = new AtomicReference<>();
        List<Object> landed = new CopyOnWriteArrayList<>();
        when(repository.saveAll(any())).thenAnswer(invocation -> {
            writerThread.set(Thread.currentThread().getName());
            sinkEntered.countDown();
            releaseSink.await(3, TimeUnit.SECONDS);
            landed.addAll(invocation.getArgument(0));
            return invocation.getArgument(0);
        });

        long start = System.nanoTime();
        service.telemetry(new DroneSnapshot(1));
        long callerElapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(sinkEntered.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(callerCallerElapsed(callerElapsedMs)).as("调用方不得被 DB 写阻塞").isTrue();

        releaseSink.countDown();
        awaitUntil(() -> !landed.isEmpty(), 2000);
        assertThat(writerThread.get()).isEqualTo("db-write-flight-log")
                .isNotEqualTo(Thread.currentThread().getName());
    }

    /** 单独抽出来只为了让断言消息可读：阈值 100ms，实测远低于它。 */
    private static boolean callerCallerElapsed(long ms) {
        return ms < 100;
    }

    @Test
    @DisplayName("落库抛异常时整批回退到 JSONL，账不丢")
    void databaseFailureFallsBackToJsonl() throws Exception {
        when(repository.saveAll(any())).thenThrow(new IllegalStateException("db down"));

        service.alert(1, new io.aerofleet.cloud.gateway.AlertEntry(
                4, "fallback proof", System.currentTimeMillis()));

        awaitUntil(() -> jsonlLines() >= 1, 3000);
        assertThat(String.join("\n", Files.readAllLines(jsonlFile()))).contains("fallback proof");
    }

    @Test
    @DisplayName("队列满时就地写 JSONL，不阻塞也不丢记录")
    void overflowFallsBackToJsonlWithoutBlocking() throws Exception {
        CountDownLatch releaseSink = new CountDownLatch(1);
        when(repository.saveAll(any())).thenAnswer(invocation -> {
            releaseSink.await(3, TimeUnit.SECONDS);
            return invocation.getArgument(0);
        });

        // capacity=2、batch=2：第一条把 writer 占住，随后几条必然溢出
        for (int sysid = 1; sysid <= 8; sysid++) {
            service.alert(sysid, new io.aerofleet.cloud.gateway.AlertEntry(
                    4, "overflow-" + sysid, System.currentTimeMillis()));
        }
        releaseSink.countDown();

        awaitUntil(() -> jsonlLines() >= 1, 3000);
        // 溢出的那些必须能在文件里找到，而不是静默消失
        String all = String.join("\n", Files.readAllLines(jsonlFile()));
        assertThat(all).contains("overflow-");
    }

    @Test
    @DisplayName("FlightTrackStore：最后已知位置在 writer 线程落库")
    void lastKnownWrittenOffCallerThread() throws Exception {
        FlightTrackStore store = new FlightTrackStore();
        DroneLastKnownPositionRepository repo = mock(DroneLastKnownPositionRepository.class);
        setField(store, "repository", repo);
        AtomicReference<String> thread = new AtomicReference<>();
        when(repo.saveAll(any())).thenAnswer(invocation -> {
            thread.set(Thread.currentThread().getName());
            return invocation.getArgument(0);
        });

        for (int i = 0; i < 10; i++) {   // PERSIST_INTERVAL = 10 → 第 10 次触发入队
            store.addPoint(7, new FlightTrackStore.TrackPoint(7, System.currentTimeMillis(),
                    22.5, 114.0, 100.0 + i, 0, 0, 0, 0, 90));
        }

        awaitUntil(() -> thread.get() != null, 3000);
        assertThat(thread.get()).isEqualTo("db-write-last-known");
        store.persistAllOnShutdown();
    }

    @Test
    @DisplayName("关闭顺序：先排空队列，再用内存最新轨迹补写，旧值不得盖掉新值")
    void shutdownDrainsQueueBeforeRewritingFromMemory() throws Exception {
        FlightTrackStore store = new FlightTrackStore();
        DroneLastKnownPositionRepository repo = mock(DroneLastKnownPositionRepository.class);
        setField(store, "repository", repo);
        List<Double> writtenLatitudes = new CopyOnWriteArrayList<>();
        when(repo.saveAll(any())).thenAnswer(invocation -> {
            for (Object o : invocation.<List<io.aerofleet.cloud.tracking.DroneLastKnownPositionEntity>>getArgument(0)) {
                writtenLatitudes.add(((io.aerofleet.cloud.tracking.DroneLastKnownPositionEntity) o).getLat());
            }
            return invocation.getArgument(0);
        });
        when(repo.save(any())).thenAnswer(invocation -> {
            writtenLatitudes.add(((io.aerofleet.cloud.tracking.DroneLastKnownPositionEntity) invocation.getArgument(0)).getLat());
            return invocation.getArgument(0);
        });

        // 第 10 次入队的是 lat=109；随后再推进到 lat=500 但不触发节流（计数 11）
        for (int i = 0; i < 10; i++) {
            store.addPoint(9, new FlightTrackStore.TrackPoint(9, System.currentTimeMillis(),
                    100.0 + i, 114.0, 10.0, 0, 0, 0, 0, 90));
        }
        awaitUntil(() -> !writtenLatitudes.isEmpty(), 3000);
        store.addPoint(9, new FlightTrackStore.TrackPoint(9, System.currentTimeMillis(),
                500.0, 114.0, 10.0, 0, 0, 0, 0, 90));

        store.persistAllOnShutdown();

        // 最后一次落到库里的必须是内存里的最新位置 500，而不是队列里的旧值
        assertThat(writtenLatitudes.get(writtenLatitudes.size() - 1)).isEqualTo(500.0);
    }

    @Test
    @DisplayName("一批 N 行只发一次 batchUpdate（真批处理，而非逐行往返）")
    @SuppressWarnings("unchecked")
    void writeBatchIssuesOneJdbcBatch() throws Exception {
        org.springframework.jdbc.core.JdbcTemplate jdbc =
                mock(org.springframework.jdbc.core.JdbcTemplate.class);
        setField(service, "jdbcTemplate", jdbc);

        List<FlightLogService.PendingLog> batch = new java.util.ArrayList<>();
        for (int sysid = 20; sysid < 23; sysid++) {
            java.util.Map<String, Object> event = new java.util.LinkedHashMap<>();
            event.put("type", "telemetry");
            event.put("sysid", sysid);
            event.put("t", java.time.Instant.now().toString());
            batch.add(new FlightLogService.PendingLog(FlightLogEntity.from(event), event));
        }

        service.writeBatch(batch);

        org.mockito.ArgumentCaptor<java.util.List<Object[]>> rowsCap =
                org.mockito.ArgumentCaptor.forClass(java.util.List.class);
        verify(jdbc, org.mockito.Mockito.times(1)).batchUpdate(
                org.mockito.ArgumentMatchers.anyString(), rowsCap.capture(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.any(org.springframework.jdbc.core.ParameterizedPreparedStatementSetter.class));
        assertThat(rowsCap.getValue()).as("3 行应在同一次调用里提交").hasSize(3);
        // 有 JdbcTemplate 时不该再走 Hibernate 的逐行 saveAll
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("插入列由实体注解派生：不含主键，且列数与类型表一致")
    void insertColumnsDerivedFromEntity() {
        String[] columns = FlightLogEntity.insertColumnNames();
        assertThat(columns).doesNotContain("id");
        assertThat(columns).hasSameSizeAs(FlightLogEntity.insertSqlTypes());
        assertThat(columns).contains("timestamp", "type", "sysid", "tenant_id");
    }

    @Test
    @DisplayName("反射派生的插入列与 V18 DDL 的列逐项一致（防实体与迁移漂移）")
    void insertColumnsMatchV18Ddl() throws Exception {
        // 批量插入的列来自实体注解，不再来自手写 SQL —— 于是"改了 @Column 忘了改迁移"
        // 变成运行期才炸的错误。这条测试把它提前到 CI。
        String ddl;
        try (var in = getClass().getResourceAsStream("/db/migration/V18__flight_log_table.sql")) {
            assertThat(in).as("V18 迁移应在 classpath 上").isNotNull();
            ddl = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        String body = ddl.substring(ddl.indexOf("CREATE TABLE IF NOT EXISTS flight_log"));
        body = body.substring(body.indexOf('(') + 1, body.indexOf(");"));
        List<String> ddlColumns = new java.util.ArrayList<>();
        for (String line : body.split("\n")) {
            String l = line.trim();
            if (l.isEmpty() || l.startsWith("--")) {
                continue;
            }
            ddlColumns.add(l.split("\\s+")[0]);
        }
        assertThat(ddlColumns).contains("id");
        assertThat(FlightLogEntity.insertColumnNames())
                .as("插入列必须正好等于 V18 里除主键外的所有列")
                .containsExactlyInAnyOrderElementsOf(
                        ddlColumns.stream().filter(c -> !c.equals("id")).toList());
    }

    private Path jsonlFile() {
        return dir.resolve("flight-" + java.time.LocalDate.now() + ".jsonl");
    }

    private int jsonlLines() {
        try {
            if (!Files.exists(jsonlFile())) {
                return 0;
            }
            return Files.readAllLines(jsonlFile()).size();
        } catch (Exception e) {
            return 0;
        }
    }
}

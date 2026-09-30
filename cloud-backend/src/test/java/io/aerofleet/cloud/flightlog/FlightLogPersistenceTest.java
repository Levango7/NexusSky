package io.aerofleet.cloud.flightlog;

import io.aerofleet.cloud.gateway.AlertEntry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import io.aerofleet.cloud.gateway.TrackPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * FlightLogService 持久化模式集成测试（@SpringBootTest + H2 内存数据库）。
 * <p>
 * 验证 persistToDb=true 时 telemetry/alert/mission/connectivity 写入数据库、
 * query/trackFrom 从数据库查询、DB 不可用时回退 JSONL、persistToDb=false 时纯 JSONL 行为，
 * 以及 DB 模式下遥测节流逻辑仍然生效。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:flightlog-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "aerofleet.flightlog.persist-to-db=true",
        "aerofleet.flightlog.dir=./flight-logs-test",
        "aerofleet.flightlog.track-interval-ms=0",
        "aerofleet.device-registry.persist=false",
        "spring.cache.type=none",
        "aerofleet.udp-port=0",
        "aerofleet.drone-port=14549",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
})
@DisplayName("FlightLog 持久化模式集成测试")
class FlightLogPersistenceTest {

    @Autowired
    private FlightLogService flightLogService;

    @Autowired
    private FlightLogRepository flightLogRepository;

    @BeforeEach
    void cleanUp() throws Exception {
        flightLogRepository.deleteAll();
        setPersistToDb(true);
        // 确保注入的是真实 repository（可能被其他测试替换为 mock）
        setRepository(flightLogRepository);
        // 清空节流缓存
        clearLastTrackWrite();
    }

    // ===== DB 写入测试 =====

    @Test
    @DisplayName("persistToDb=true 时 telemetry 写入数据库")
    void telemetryWritesToDb() throws Exception {
        DroneSnapshot snapshot = new DroneSnapshot(1);
        snapshot.lat = 39.9042;
        snapshot.lon = 116.4074;
        snapshot.relativeAlt = 100.0;
        snapshot.groundspeed = 15.0;
        snapshot.battery = 80;
        snapshot.voltage = 12000;
        snapshot.mode = "AUTO";
        snapshot.armed = true;
        snapshot.online = true;

        flightLogService.telemetry(snapshot);

        List<FlightLogEntity> entities = awaitDbRows("telemetry", 1);
        assertThat(entities).hasSize(1);
        FlightLogEntity e = entities.get(0);
        assertThat(e.getType()).isEqualTo("telemetry");
        assertThat(e.getSysid()).isEqualTo(1);
        assertThat(e.getLat()).isEqualTo(39.9042);
        assertThat(e.getLon()).isEqualTo(116.4074);
        assertThat(e.getRelativeAlt()).isEqualTo(100.0);
        assertThat(e.getBattery()).isEqualTo(80);
        assertThat(e.getArmed()).isTrue();
    }

    @Test
    @DisplayName("persistToDb=true 时 alert 写入数据库")
    void alertWritesToDb() throws Exception {
        AlertEntry entry = new AlertEntry(6, "Low battery warning", System.currentTimeMillis());

        flightLogService.alert(2, entry);

        List<FlightLogEntity> entities = awaitDbRows("alert", 2);
        assertThat(entities).hasSize(1);
        FlightLogEntity e = entities.get(0);
        assertThat(e.getType()).isEqualTo("alert");
        assertThat(e.getSysid()).isEqualTo(2);
        assertThat(e.getSeverity()).isEqualTo(6);
        assertThat(e.getText()).isEqualTo("Low battery warning");
    }

    @Test
    @DisplayName("persistToDb=true 时 mission 写入数据库")
    void missionWritesToDb() throws Exception {
        flightLogService.mission(3, "uploaded 4 items");

        List<FlightLogEntity> entities = awaitDbRows("mission", 3);
        assertThat(entities).hasSize(1);
        FlightLogEntity e = entities.get(0);
        assertThat(e.getType()).isEqualTo("mission");
        assertThat(e.getSysid()).isEqualTo(3);
        assertThat(e.getText()).isEqualTo("uploaded 4 items");
    }

    @Test
    @DisplayName("persistToDb=true 时 connectivity 写入数据库")
    void connectivityWritesToDb() throws Exception {
        flightLogService.connectivity(4, true);

        List<FlightLogEntity> entities = awaitDbRows("connectivity", 4);
        assertThat(entities).hasSize(1);
        FlightLogEntity e = entities.get(0);
        assertThat(e.getType()).isEqualTo("connectivity");
        assertThat(e.getSysid()).isEqualTo(4);
        assertThat(e.getOnline()).isTrue();
    }

    // ===== DB 查询测试 =====

    @Test
    @DisplayName("query() 从数据库返回的格式与 JSONL 路径一致")
    void queryFromDbReturnsSameFormatAsJsonl() throws Exception {
        DroneSnapshot snapshot = new DroneSnapshot(5);
        snapshot.lat = 40.0;
        snapshot.lon = 116.0;
        snapshot.relativeAlt = 50.0;
        snapshot.groundspeed = 10.0;
        snapshot.battery = 90;
        snapshot.voltage = 11000;
        snapshot.mode = "GUIDED";
        snapshot.armed = false;
        snapshot.online = true;

        flightLogService.telemetry(snapshot);

        List<Map<String, Object>> results = awaitQueryRows(5);
        assertThat(results).hasSize(1);
        Map<String, Object> m = results.get(0);
        // 验证返回格式与 JSONL 路径一致（key 名相同）
        assertThat(m).containsKeys("t", "type", "sysid", "lat", "lon", "relativeAlt",
                "groundspeed", "battery", "voltage", "mode", "armed", "online");
        assertThat(m.get("type")).isEqualTo("telemetry");
        assertThat(m.get("sysid")).isEqualTo(5);
        assertThat(m.get("lat")).isEqualTo(40.0);
        assertThat(m.get("lon")).isEqualTo(116.0);
        assertThat(m.get("mode")).isEqualTo("GUIDED");
        assertThat(m.get("armed")).isEqualTo(false);
        assertThat(m.get("online")).isEqualTo(true);
    }

    @Test
    @DisplayName("trackFor() 从数据库返回 List<TrackPoint> 格式正确")
    void trackForFromDb() throws Exception {
        DroneSnapshot snapshot = new DroneSnapshot(6);
        snapshot.lat = 31.2304;
        snapshot.lon = 121.4737;
        snapshot.relativeAlt = 200.0;

        flightLogService.telemetry(snapshot);
        flightLogService.awaitPendingWrites(3000);

        List<TrackPoint> points = flightLogService.trackFor(LocalDate.now(), 6);
        assertThat(points).hasSize(1);
        TrackPoint p = points.get(0);
        assertThat(p.lat).isEqualTo(31.2304);
        assertThat(p.lon).isEqualTo(121.4737);
        assertThat(p.alt).isEqualTo(200.0);
    }

    @Test
    @DisplayName("query()/trackFor() 的时间顺序由 SQL ORDER BY 保证，limit 取的是最新 N 条")
    void dbReadPathIsChronologicallyOrdered() {
        // 三行故意按 +3h → +1h → +2h 的写入顺序落库：Repository 没有 ORDER BY 时
        // "列表末尾 = 最新"不成立，limit 会取到最旧的几条（换数据库/走索引即翻转）。
        LocalDate today = LocalDate.now();
        Instant dayStart = today.atStartOfDay(ZoneId.systemDefault()).toInstant();
        saveTelemetryRow(10, dayStart.plus(3, ChronoUnit.HOURS), 3.0);
        saveTelemetryRow(10, dayStart.plus(1, ChronoUnit.HOURS), 1.0);
        saveTelemetryRow(10, dayStart.plus(2, ChronoUnit.HOURS), 2.0);

        List<Map<String, Object>> recent = flightLogService.query(today, "telemetry", 10, 2);
        assertThat(recent).hasSize(2);
        assertThat(recent).extracting(m -> m.get("relativeAlt")).containsExactly(2.0, 3.0);

        List<TrackPoint> track = flightLogService.trackFor(today, 10);
        assertThat(track).extracting(p -> p.alt).containsExactly(1.0, 2.0, 3.0);
    }

    // ===== 降级测试 =====

    @Test
    @DisplayName("persistToDb=true 但 repository=null 时回退到 JSONL 文件写入")
    void jsonlFallbackWhenDbUnavailable() throws Exception {
        // 将 repository 设为 null，模拟 DB 不可用
        setRepository(null);

        DroneSnapshot snapshot = new DroneSnapshot(7);
        snapshot.lat = 35.0;
        snapshot.lon = 118.0;
        snapshot.relativeAlt = 80.0;
        snapshot.online = true;

        flightLogService.telemetry(snapshot);

        // DB 中不应有记录
        assertThat(flightLogRepository.findByTypeAndSysid("telemetry", 7)).isEmpty();

        // JSONL 文件中应有记录
        Path jsonlFile = Path.of("./flight-logs-test/flight-" + LocalDate.now() + ".jsonl");
        assertThat(Files.isReadable(jsonlFile)).isTrue();
        List<String> lines = Files.readAllLines(jsonlFile);
        assertThat(lines).anyMatch(line -> line.contains("\"type\":\"telemetry\"") && line.contains("\"sysid\":7"));
    }

    // ===== 纯 JSONL 模式测试 =====

    @Test
    @DisplayName("persistToDb=false 时仅写 JSONL，行为与当前完全一致")
    void persistToDbFalseKeepsJsonlBehavior() throws Exception {
        setPersistToDb(false);

        DroneSnapshot snapshot = new DroneSnapshot(8);
        snapshot.lat = 36.0;
        snapshot.lon = 119.0;
        snapshot.relativeAlt = 60.0;
        snapshot.online = true;

        flightLogService.telemetry(snapshot);

        // DB 中不应有记录
        assertThat(flightLogRepository.findByTypeAndSysid("telemetry", 8)).isEmpty();

        // JSONL 文件中应有记录
        Path jsonlFile = Path.of("./flight-logs-test/flight-" + LocalDate.now() + ".jsonl");
        assertThat(Files.isReadable(jsonlFile)).isTrue();
        List<String> lines = Files.readAllLines(jsonlFile);
        assertThat(lines).anyMatch(line -> line.contains("\"type\":\"telemetry\"") && line.contains("\"sysid\":8"));
    }

    // ===== 节流测试 =====

    @Test
    @DisplayName("persistToDb=true 时遥测节流逻辑仍然生效")
    void telemetryThrottleStillWorksInDbMode() throws Exception {
        // 设置节流间隔为 5000ms（足够长以触发节流）
        setTrackMinIntervalMs(5000);

        DroneSnapshot snapshot = new DroneSnapshot(9);
        snapshot.lat = 37.0;
        snapshot.lon = 120.0;
        snapshot.relativeAlt = 70.0;
        snapshot.online = true;

        // 第一次写入应该成功
        flightLogService.telemetry(snapshot);
        flightLogService.awaitPendingWrites(3000);
        assertThat(flightLogRepository.findByTypeAndSysid("telemetry", 9)).hasSize(1);

        // 第二次写入应被节流（在间隔内）
        flightLogService.telemetry(snapshot);
        flightLogService.awaitPendingWrites(3000);
        assertThat(flightLogRepository.findByTypeAndSysid("telemetry", 9)).hasSize(1);

        // 恢复节流间隔为 0（无节流）
        setTrackMinIntervalMs(0);
        clearLastTrackWrite();

        // 第三次写入应该成功（节流间隔为 0）
        flightLogService.telemetry(snapshot);
        flightLogService.awaitPendingWrites(3000);
        assertThat(flightLogRepository.findByTypeAndSysid("telemetry", 9)).hasSize(2);
    }

    // ===== 辅助方法 =====

    /** 直接落一行可控时间的遥测（写入侧节流与时刻口径都不参与排序测试）。 */
    private void saveTelemetryRow(int sysid, Instant ts, double relativeAlt) {
        FlightLogEntity e = new FlightLogEntity();
        e.setTimestamp(ts);
        e.setType("telemetry");
        e.setSysid(sysid);
        e.setLat(39.9);
        e.setLon(116.4);
        e.setRelativeAlt(relativeAlt);
        flightLogRepository.save(e);
    }

    /**
     * 入库改成异步批量写之后，断言前需要给 writer 线程一点时间。
     * <p>
     * 只放宽"多久能看到"，不放宽"必须看到"——超时仍返回空列表，让原有 hasSize(1) 断言失败。
     */
    private List<FlightLogEntity> awaitDbRows(String type, int sysid) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        List<FlightLogEntity> rows = List.of();
        while (System.currentTimeMillis() < deadline) {
            rows = flightLogRepository.findByTypeAndSysid(type, sysid);
            if (!rows.isEmpty()) {
                return rows;
            }
            Thread.sleep(20);
        }
        return rows;
    }

    /** 同上，走服务自己的读路径（DB 读 + 回退口径都经过它）。 */
    private List<Map<String, Object>> awaitQueryRows(int sysid) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        List<Map<String, Object>> rows = List.of();
        while (System.currentTimeMillis() < deadline) {
            rows = flightLogService.query(LocalDate.now(), "telemetry", sysid, 0);
            if (!rows.isEmpty()) {
                return rows;
            }
            Thread.sleep(20);
        }
        return rows;
    }

    /**
     * 通过反射设置 persistToDb 字段。
     */
    private void setPersistToDb(boolean value) throws Exception {
        Field field = FlightLogService.class.getDeclaredField("persistToDb");
        field.setAccessible(true);
        field.setBoolean(flightLogService, value);
    }

    /**
     * 通过反射设置 flightLogRepository 字段。
     */
    private void setRepository(FlightLogRepository repo) throws Exception {
        Field field = FlightLogService.class.getDeclaredField("flightLogRepository");
        field.setAccessible(true);
        field.set(flightLogService, repo);
    }

    /**
     * 通过反射设置 trackMinIntervalMs 字段。
     */
    private void setTrackMinIntervalMs(long value) throws Exception {
        Field field = FlightLogService.class.getDeclaredField("trackMinIntervalMs");
        field.setAccessible(true);
        field.setLong(flightLogService, value);
    }

    /**
     * 通过反射清空 lastTrackWrite 节流缓存。
     */
    @SuppressWarnings("unchecked")
    private void clearLastTrackWrite() throws Exception {
        Field field = FlightLogService.class.getDeclaredField("lastTrackWrite");
        field.setAccessible(true);
        Map<Integer, Long> lastTrackWrite = (Map<Integer, Long>) field.get(flightLogService);
        lastTrackWrite.clear();
    }
}
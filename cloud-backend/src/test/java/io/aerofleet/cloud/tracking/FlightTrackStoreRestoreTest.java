package io.aerofleet.cloud.tracking;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aerofleet.cloud.flightlog.FlightLogEntity;
import io.aerofleet.cloud.flightlog.FlightLogRepository;
import io.aerofleet.cloud.flightlog.FlightLogService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C4（ROADMAP:337）：启动时从 flight_log 重建内存轨迹历史。
 * <p>
 * 三层各钉一条：JSONL 全链（真文件、真 FlightLogService）、时间重叠过滤、
 * 容量截尾；DB 路径用 Mockito 假 repository 钉「id 倒序取末尾再反转」；
 * 最后一条钉「无 FlightLogService 时安全跳过」（纯内存模式不受影响）。
 * <p>边界：恢复对象是「repository 恢复出 lastKnown 的机」——真实主链路（C4=PostgreSQL）
 * 必然满足；纯 JSONL-only 模式（无 repository）启动时 tracks 为空、无人可恢复，
 * 此时历史由既有 trackFor/recentTrack API 按需提供（本 PR 不扩 sysid 枚举面）。
 */
class FlightTrackStoreRestoreTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tmp;

    /** 混合模式（真实主链路）：repository 给 lastKnown，flight_log 给历史。 */
    private io.aerofleet.cloud.tracking.DroneLastKnownPositionRepository repoWith(
            FlightTrackStore.TrackPoint lastKnown) {
        io.aerofleet.cloud.tracking.DroneLastKnownPositionRepository repo =
                Mockito.mock(io.aerofleet.cloud.tracking.DroneLastKnownPositionRepository.class);
        Mockito.when(repo.findAll()).thenReturn(List.of(
                io.aerofleet.cloud.tracking.DroneLastKnownPositionEntity.fromTrackPoint(lastKnown)));
        return repo;
    }

    private FlightTrackStore.TrackPoint point(int sysid, long ts, double lat, double lon) {
        return new FlightTrackStore.TrackPoint(sysid, ts, lat, lon, 100.0,
                5.0, 0.0, 0.0, 90.0, 80.0);
    }

    /** 当天某时刻（本地墙钟，与 JSONL "t" 字段同口径）的 epoch 毫秒。 */
    private static long epochMsOfToday(int hour, int minute, int second) {
        return LocalDateTime.of(LocalDate.now(), LocalDateTime.of(
                LocalDate.now(), java.time.LocalTime.of(hour, minute, second)).toLocalTime())
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    /** 往当日 JSONL 写一条遥测行（手工构造，"t" 可控——svc.telemetry 的 t 是 now()，测不出跨时间序列）。 */
    private void writeTelemetryLine(int sysid, int hour, int minute, int second,
                                    double lat, double lon) throws Exception {
        Path f = tmp.resolve("flight-" + LocalDate.now() + ".jsonl");
        String t = LocalDate.now().atTime(hour, minute, second)
                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        String line = MAPPER.writeValueAsString(java.util.Map.of(
                "type", "telemetry", "sysid", sysid, "t", t,
                "lat", lat, "lon", lon, "relativeAlt", 42.0));
        Files.writeString(f, line + "\n", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    }

    @Test
    @DisplayName("JSONL 全链：10 条历史 + lastKnown ⇒ deque 11 点升序、无重叠、含 lastKnown")
    void jsonlHistoryRestoredBeforeLastKnown() throws Exception {
        FlightLogService svc = new FlightLogService(tmp.toString(), 0, MAPPER);
        FlightTrackStore store = new FlightTrackStore();
        store.setMaxPoints(3600);
        store.flightLogService = svc;

        for (int i = 1; i <= 10; i++) {
            writeTelemetryLine(5, 10, 0, i, 22.50 + i * 0.001, 113.90);
        }
        long lastKnownTs = epochMsOfToday(10, 0, 11);
        ReflectionTestUtils.setField(store, "repository", repoWith(point(5, lastKnownTs, 22.511, 113.911)));
        store.loadLastKnownPositions();

        List<FlightTrackStore.TrackPoint> track = store.getTrack(5);
        assertThat(track).hasSize(11);
        for (int i = 1; i < track.size(); i++) {
            assertThat(track.get(i).timestampMs)
                    .as("点 %d 应不早于前一点", i)
                    .isGreaterThanOrEqualTo(track.get(i - 1).timestampMs);
        }
        assertThat(track.get(track.size() - 1).lat).isCloseTo(22.511, org.assertj.core.data.Offset.offset(1e-9));   // lastKnown 收尾
        assertThat(track.get(0).lat).isCloseTo(22.501, org.assertj.core.data.Offset.offset(1e-9));                  // 最老历史点打头
    }

    @Test
    @DisplayName("与 lastKnown 重叠（ts ≥）的历史点被跳过——lastKnown 是权威快照")
    void overlappingPointsAreSkipped() throws Exception {
        FlightLogService svc = new FlightLogService(tmp.toString(), 0, MAPPER);
        FlightTrackStore store = new FlightTrackStore();
        store.setMaxPoints(3600);
        store.flightLogService = svc;

        long lastKnownTs = epochMsOfToday(10, 0, 10);
        // 两条历史：一条更老（保留），一条 ts 与 lastKnown 相同（跳过）
        writeTelemetryLine(7, 9, 59, 50, 22.401, 113.801);
        writeTelemetryLine(7, 10, 0, 10, 22.402, 113.802);
        ReflectionTestUtils.setField(store, "repository", repoWith(point(7, lastKnownTs, 22.5, 113.9)));
        store.loadLastKnownPositions();

        List<FlightTrackStore.TrackPoint> track = store.getTrack(7);
        assertThat(track).hasSize(2);   // 老历史 + lastKnown；同 ts 那条被跳过
        assertThat(track.get(0).lat).isCloseTo(22.401, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(track.get(1).lat).isCloseTo(22.5, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    @DisplayName("容量截尾：maxPoints=5 时只保留最新 5 点，最老历史被裁掉")
    void capacityIsRespected() throws Exception {
        FlightLogService svc = new FlightLogService(tmp.toString(), 0, MAPPER);
        FlightTrackStore store = new FlightTrackStore();
        store.setMaxPoints(5);
        store.flightLogService = svc;

        for (int i = 1; i <= 10; i++) {
            writeTelemetryLine(9, 10, 0, i, 22.60 + i * 0.001, 113.95);
        }
        ReflectionTestUtils.setField(store, "repository",
                repoWith(point(9, epochMsOfToday(10, 0, 11), 22.611, 113.961)));
        store.loadLastKnownPositions();

        List<FlightTrackStore.TrackPoint> track = store.getTrack(9);
        assertThat(track).hasSize(5);
        assertThat(track.get(4).lat).isCloseTo(22.611, org.assertj.core.data.Offset.offset(1e-9));   // lastKnown 必在末位
        assertThat(track.get(3).lat).isCloseTo(22.610, org.assertj.core.data.Offset.offset(1e-9));   // 倒数第二新
        assertThat(track.get(0).lat).isCloseTo(22.607, org.assertj.core.data.Offset.offset(1e-9));   // 最老被裁到 10:00:07
    }

    @Test
    @DisplayName("DB 路径：id 倒序取末尾再反转；lat 缺失的行被过滤")
    void dbPathReversesDescQuery() {
        FlightLogService svc = new FlightLogService(tmp.toString(), 0, MAPPER);
        FlightLogRepository repo = Mockito.mock(FlightLogRepository.class);
        ReflectionTestUtils.setField(svc, "persistToDb", true);
        ReflectionTestUtils.setField(svc, "flightLogRepository", repo);

        FlightLogEntity newest = new FlightLogEntity();
        newest.setSysid(3);
        newest.setTimestamp(java.time.Instant.ofEpochMilli(3000));
        newest.setLat(22.3);
        newest.setLon(113.3);
        newest.setRelativeAlt(40.0);
        FlightLogEntity noFix = new FlightLogEntity();   // lat 缺失：还原时必须被滤掉
        noFix.setSysid(3);
        noFix.setTimestamp(java.time.Instant.ofEpochMilli(2000));
        FlightLogEntity oldest = new FlightLogEntity();
        oldest.setSysid(3);
        oldest.setTimestamp(java.time.Instant.ofEpochMilli(1000));
        oldest.setLat(22.1);
        oldest.setLon(113.1);
        Mockito.when(repo.findByTypeAndSysid("telemetry", 3,
                        PageRequest.of(0, 100, Sort.by(Sort.Direction.DESC, "id"))))
                .thenReturn(List.of(newest, noFix, oldest));

        List<io.aerofleet.cloud.gateway.TrackPoint> pts = svc.recentTrack(3, 100);
        assertThat(pts).hasSize(2);
        assertThat(pts.get(0).lat).isCloseTo(22.1, org.assertj.core.data.Offset.offset(1e-9));    // 反转后最老在前
        assertThat(pts.get(1).lat).isCloseTo(22.3, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(pts.get(0).ts).isEqualTo(1000);
    }

    @Test
    @DisplayName("无 FlightLogService：恢复安全跳过，纯内存模式行为不变")
    void nullServiceIsSafeNoop() {
        FlightTrackStore store = new FlightTrackStore();
        store.setMaxPoints(3600);
        store.loadLastKnownPositions();   // 无 repository、无 service：不应抛
        store.addPoint(1, point(1, 1000L, 22.5, 113.9));
        assertThat(store.getTrack(1)).hasSize(1);
    }
}

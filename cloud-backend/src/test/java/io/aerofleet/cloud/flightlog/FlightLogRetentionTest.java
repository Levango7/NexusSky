package io.aerofleet.cloud.flightlog;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 飞行日志保留清理集成测试（@SpringBootTest + H2 内存数据库）。
 * <p>
 * 覆盖 DB 行与 JSONL 文件两条存储路径各自的裁剪口径：DB 按精确时刻删，
 * 文件按文件名日期整天删（等于保留边界的那一天不删），不认识的文件名一律不动，
 * {@code retention-days<=0} 时两者都不动。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:flightlog-retention-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "aerofleet.flightlog.persist-to-db=true",
        "aerofleet.flightlog.dir=./flight-logs-retention-test",
        "aerofleet.flightlog.retention-days=30",
        "aerofleet.flightlog.track-interval-ms=0",
        "aerofleet.device-registry.persist=false",
        "spring.cache.type=none",
        "aerofleet.udp-port=0",
        "aerofleet.drone-port=14549",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
})
@DisplayName("FlightLog 保留清理")
class FlightLogRetentionTest {

    private static final String DIR = "./flight-logs-retention-test";

    @Autowired
    private FlightLogRepository flightLogRepository;

    @Autowired
    private FlightLogRetentionJob retentionJob;

    private Path dir;

    @BeforeEach
    void cleanUp() throws IOException {
        flightLogRepository.deleteAll();
        dir = Path.of(DIR);
        Files.createDirectories(dir);
        emptyDir();
        retentionJob.setRetentionDays(30);
    }

    @AfterEach
    void removeJsonl() throws IOException {
        emptyDir();
    }

    // ===== DB 行 =====

    @Test
    @DisplayName("retention-days=30：早于 30 天的 flight_log 行被删，新行保留")
    void dbRowsOlderThanRetentionAreDeleted() {
        saveTelemetry(1, Instant.now().minus(45, ChronoUnit.DAYS), 10.0);
        saveTelemetry(1, Instant.now().minus(30, ChronoUnit.DAYS).minusSeconds(1), 20.0);
        saveTelemetry(1, Instant.now().minus(1, ChronoUnit.HOURS), 30.0);

        retentionJob.purgeOldEntries();

        List<FlightLogEntity> rows = flightLogRepository.findByTypeAndSysid("telemetry", 1);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getRelativeAlt()).isEqualTo(30.0);
    }

    @Test
    @DisplayName("retention-days<=0：保留任务空转，DB 行与文件都不动")
    void purgeDisabledWhenRetentionDaysNotPositive() throws IOException {
        retentionJob.setRetentionDays(0);
        saveTelemetry(2, Instant.now().minus(400, ChronoUnit.DAYS), 10.0);
        writeJsonl(LocalDate.now().minusDays(400), "old line");

        retentionJob.purgeOldEntries();

        assertThat(flightLogRepository.findByTypeAndSysid("telemetry", 2)).hasSize(1);
        assertThat(Files.exists(jsonlFor(LocalDate.now().minusDays(400)))).isTrue();
    }

    // ===== JSONL 文件 =====

    @Test
    @DisplayName("JSONL 按文件名日期整天删：过期删、边界与当天留、不认识的文件名不动")
    void jsonlFilesDeletedByFileNameDate() throws IOException {
        LocalDate today = LocalDate.now();
        Path expired = writeJsonl(today.minusDays(45), "expired");
        Path boundary = writeJsonl(today.minusDays(30), "boundary"); // 正好等于保留边界：不删
        Path fresh = writeJsonl(today, "today");
        Path unrelated = Files.writeString(dir.resolve("notes.txt"), "keep me", StandardCharsets.UTF_8);
        Path malformed = Files.writeString(dir.resolve("flight-2026-13-40.jsonl"), "keep me", StandardCharsets.UTF_8);

        retentionJob.purgeOldEntries();

        assertThat(Files.exists(expired)).isFalse();
        assertThat(Files.exists(boundary)).isTrue();
        assertThat(Files.exists(fresh)).isTrue();
        assertThat(Files.exists(unrelated)).isTrue();
        assertThat(Files.exists(malformed)).isTrue();
    }

    // ===== 辅助方法 =====

    private void saveTelemetry(int sysid, Instant ts, double alt) {
        FlightLogEntity e = new FlightLogEntity();
        e.setTimestamp(ts);
        e.setType("telemetry");
        e.setSysid(sysid);
        e.setLat(39.9);
        e.setLon(116.4);
        e.setRelativeAlt(alt);
        flightLogRepository.save(e);
    }

    private Path jsonlFor(LocalDate day) {
        return dir.resolve("flight-" + day + ".jsonl");
    }

    private Path writeJsonl(LocalDate day, String content) throws IOException {
        return Files.writeString(jsonlFor(day), content + "\n", StandardCharsets.UTF_8);
    }

    private void emptyDir() throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var stream = Files.list(dir)) {
            for (Path p : stream.toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}

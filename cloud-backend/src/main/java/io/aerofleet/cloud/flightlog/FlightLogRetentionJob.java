package io.aerofleet.cloud.flightlog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 飞行日志保留清理：删除超过保留期的遥测行（DB 模式）与 JSONL 文件。
 * <p>
 * 两条存储路径都要裁：{@code persist-to-db=true} 时事件进 {@code flight_log} 表，
 * DB 写失败或 {@code false} 时进 {@code flight-logs/flight-YYYY-MM-DD.jsonl}——
 * 换过模式的部署会同时留着两套旧数据，所以文件与表各自按同一天数清理。
 * <p>
 * 口径差异（有意为之）：DB 行按精确时刻裁剪（{@code now - N 天}），JSONL 按文件名
 * 日期<b>整天</b>删除（一天的事件全在同一文件里，无法部分删除）。因此当天写入
 * DB 失败回退到文件的事件，最迟在文件日期跨过后第 N 天被删。
 * <p>
 * {@code aerofleet.flightlog.retention-days<=0} 时整个任务空转（数据永久保留）。
 */
@Component
public class FlightLogRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(FlightLogRetentionJob.class);

    /** {@link FlightLogService} 的 JSONL 文件名格式；不认识的文件一律不动。 */
    private static final Pattern JSONL_FILE = Pattern.compile("^flight-(\\d{4}-\\d{2}-\\d{2})\\.jsonl$");

    private final Path dir;

    /** 保留天数；<=0 关闭清理。 */
    @Value("${aerofleet.flightlog.retention-days:30}")
    private int retentionDays = 30;

    /** 可选注入：无 JPA 环境（纯 JSONL 部署）时只做文件清理。 */
    @Autowired(required = false)
    private FlightLogRepository repository;

    public FlightLogRetentionJob(@Value("${aerofleet.flightlog.dir:./flight-logs}") String dir) {
        this.dir = Path.of(dir);
    }

    /**
     * 每天 03:30（本地时区）执行。与审计保留任务（03:45）错开 15 分钟：
     * Boot 默认调度器是单线程（{@code spring.task.scheduling.pool.size=1}），
     * 两个清理任务不应在同一时刻排队，也不该与 1Hz 的遥测推送线程抢同一秒。
     */
    @Scheduled(cron = "0 30 3 * * *")
    public void purgeOldEntries() {
        if (retentionDays <= 0) {
            return;
        }
        int rows = purgeDb(Instant.now().minus(retentionDays, ChronoUnit.DAYS));
        int files = purgeFiles(LocalDate.now().minusDays(retentionDays));
        if (rows > 0 || files > 0) {
            log.info("飞行日志保留清理完成：数据库 {} 行、JSONL {} 个文件（保留 {} 天）",
                    rows, files, retentionDays);
        }
    }

    /** DB 路径清理；repository 缺失（无 JPA）或失败时返回 0，不影响文件清理。 */
    private int purgeDb(Instant cutoff) {
        if (repository == null) {
            return 0;
        }
        try {
            return repository.deleteOlderThan(cutoff);
        } catch (Exception e) {
            log.warn("飞行日志 DB 保留清理失败（继续清理 JSONL 文件）: {}", e.getMessage());
            return 0;
        }
    }

    /** JSONL 路径清理：删除文件名日期早于 cutoff 的日志文件。 */
    private int purgeFiles(LocalDate cutoffDay) {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        List<Path> files;
        try (Stream<Path> stream = Files.list(dir)) {
            files = stream.toList();
        } catch (IOException e) {
            log.warn("飞行日志目录不可读，跳过文件清理: {} ({})", dir, e.getMessage());
            return 0;
        }
        int deleted = 0;
        for (Path f : files) {
            Matcher m = JSONL_FILE.matcher(f.getFileName().toString());
            if (!m.matches()) {
                continue;
            }
            LocalDate day;
            try {
                day = LocalDate.parse(m.group(1));
            } catch (DateTimeParseException e) {
                continue;
            }
            if (!day.isBefore(cutoffDay)) {
                continue;
            }
            try {
                if (Files.deleteIfExists(f)) {
                    deleted++;
                }
            } catch (IOException e) {
                log.warn("删除过期飞行日志文件失败: {} ({})", f, e.getMessage());
            }
        }
        return deleted;
    }

    /** 设置保留天数（主要用于测试注入；<=0 关闭清理）。 */
    void setRetentionDays(int retentionDays) {
        this.retentionDays = retentionDays;
    }

    /** 当前保留天数（用于监控与测试断言）。 */
    int getRetentionDays() {
        return retentionDays;
    }
}

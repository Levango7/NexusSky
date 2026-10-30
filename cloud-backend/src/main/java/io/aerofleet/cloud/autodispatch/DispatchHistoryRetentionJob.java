package io.aerofleet.cloud.autodispatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 出警历史保留清理：删除 {@code trigger_time} 早于保留期的历史行。
 * <p>
 * {@code dispatch_history} 是只增表（V31 落库起只插入）。启动恢复已收敛为
 * 「最新 500 条」（与内存期 deque 裁剪口径一致），本条补上磁盘侧的按龄裁剪。
 * 判据用 {@code trigger_time}（NOT NULL，报警触发即落行）而非 {@code created_at}——
 * 两者在写入路径上同刻，但 trigger_time 有非空约束，不会因历史脏行漏裁。
 * <p>
 * {@code aerofleet.autodispatch.retention-days<=0}（默认）时整个任务空转——
 * 删数据是运维决策，不由代码默认替运维做。每天 02:15 执行；五个留存任务
 * 15 分钟一档错开（Boot 默认调度器单线程）。
 */
@Component
public class DispatchHistoryRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(DispatchHistoryRetentionJob.class);

    /** 保留天数；<=0 关闭清理。 */
    @Value("${aerofleet.autodispatch.retention-days:0}")
    private int retentionDays = 0;

    @Autowired(required = false)
    private DispatchHistoryRepository repository;

    @Scheduled(cron = "0 15 2 * * *")
    public void purgeOldEntries() {
        if (retentionDays <= 0 || repository == null) {
            return;
        }
        try {
            long cutoffMs = Instant.now().minus(retentionDays, ChronoUnit.DAYS).toEpochMilli();
            int deleted = repository.deleteOlderThan(cutoffMs);
            if (deleted > 0) {
                log.info("出警历史保留清理完成：删除 {} 行（保留 {} 天）", deleted, retentionDays);
            }
        } catch (Exception e) {
            log.warn("出警历史保留清理失败: {}", e.getMessage());
        }
    }

    /** 设置保留天数（主要用于测试注入；<=0 关闭清理）。 */
    void setRetentionDays(int retentionDays) {
        this.retentionDays = retentionDays;
    }
}

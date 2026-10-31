package io.aerofleet.cloud.delivery2;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 配送任务保留清理：删除时间基准早于保留期的历史行。
 * <p>
 * 判据 {@code coalesce(created_at, start_time)}：V38 起新行一律有 {@code created_at}
 * （{@code @PrePersist} 写入，覆盖 PENDING 与「未启动即中止」这些此前无时间基准的行）；
 * V38 之前的存量已启动行按 {@code start_time} 照常可裁，存量未启动行两者皆 NULL——
 * 无时间基准，不裁（不猜）。
 * <p>
 * {@code aerofleet.delivery.retention-days<=0}（默认）时整个任务空转。每天 02:30
 * 执行；五个留存任务 15 分钟一档错开（Boot 默认调度器单线程）。
 */
@Component
public class DeliveryTaskRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(DeliveryTaskRetentionJob.class);

    /** 保留天数；<=0 关闭清理。 */
    @Value("${aerofleet.delivery.retention-days:0}")
    private int retentionDays = 0;

    @Autowired(required = false)
    private DeliveryTask2Repository repository;

    @Scheduled(cron = "0 30 2 * * *")
    public void purgeOldEntries() {
        if (retentionDays <= 0 || repository == null) {
            return;
        }
        try {
            Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
            int deleted = repository.deleteOlderThan(cutoff);
            if (deleted > 0) {
                log.info("配送任务保留清理完成：删除 {} 行（保留 {} 天）", deleted, retentionDays);
            }
        } catch (Exception e) {
            log.warn("配送任务保留清理失败: {}", e.getMessage());
        }
    }

    /** 设置保留天数（主要用于测试注入；<=0 关闭清理）。 */
    void setRetentionDays(int retentionDays) {
        this.retentionDays = retentionDays;
    }
}

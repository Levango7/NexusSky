package io.aerofleet.cloud.health;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 维护记录保留清理：删除 {@code created_at} 早于保留期的历史行。
 * <p>
 * 判据用创建时刻（V30 起由应用写入；存量行可能为 NULL——无时间基准不裁）。
 * 维护记录带适航/合规属性（建议开启值从宽：3 年量级），因此本任务默认同样关闭
 * （{@code aerofleet.maintenance.retention-days<=0}）——什么时候裁、留多久，
 * 由运营/合规拍板，代码不替它决定。
 * <p>
 * 每天 03:00 执行；五个留存任务 15 分钟一档错开（Boot 默认调度器单线程），
 * 与 03:30/03:45 的飞行日志/审计保留也不重叠。
 */
@Component
public class MaintenanceRecordRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceRecordRetentionJob.class);

    /** 保留天数；<=0 关闭清理。 */
    @Value("${aerofleet.maintenance.retention-days:0}")
    private int retentionDays = 0;

    @Autowired(required = false)
    private MaintenanceRecordRepository repository;

    @Scheduled(cron = "0 0 3 * * *")
    public void purgeOldEntries() {
        if (retentionDays <= 0 || repository == null) {
            return;
        }
        try {
            Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
            int deleted = repository.deleteOlderThan(cutoff);
            if (deleted > 0) {
                log.info("维护记录保留清理完成：删除 {} 行（保留 {} 天）", deleted, retentionDays);
            }
        } catch (Exception e) {
            log.warn("维护记录保留清理失败: {}", e.getMessage());
        }
    }

    /** 设置保留天数（主要用于测试注入；<=0 关闭清理）。 */
    void setRetentionDays(int retentionDays) {
        this.retentionDays = retentionDays;
    }
}

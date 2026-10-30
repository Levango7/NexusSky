package io.aerofleet.cloud.geofence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 越界事件保留清理：删除 {@code timestamp_ms} 早于保留期的历史行。
 * <p>
 * {@code geofence_breach_event} 是只增表（只插入、全仓无既有清理路径），长期运行下
 * 十万/百万行是常态——启动加载已在 #209 收敛为「只取最新 1000 条」，本条补上磁盘侧
 * 的另一半：按龄裁剪。判据用事件自身时刻（{@code timestamp_ms}，非空），与存储侧
 * 分页排序键同源。
 * <p>
 * {@code aerofleet.geofence.breach-retention-days<=0}（默认）时整个任务空转——
 * 删数据是运维/合规决策，不由代码默认替运维做（与 audit 保留同口径）。
 * 每天 02:00 执行；与 03:30/03:45 的飞行日志/审计保留错开——Boot 默认调度器是
 * 单线程（{@code spring.task.scheduling.pool.size=1}），五个留存任务 15 分钟一档
 * 依次排布，不彼此排队。
 */
@Component
public class GeofenceBreachRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(GeofenceBreachRetentionJob.class);

    /** 保留天数；<=0 关闭清理。 */
    @Value("${aerofleet.geofence.breach-retention-days:0}")
    private int retentionDays = 0;

    @Autowired(required = false)
    private GeofenceBreachEventRepository repository;

    @Scheduled(cron = "0 0 2 * * *")
    public void purgeOldEntries() {
        if (retentionDays <= 0 || repository == null) {
            return;
        }
        try {
            long cutoffMs = Instant.now().minus(retentionDays, ChronoUnit.DAYS).toEpochMilli();
            int deleted = repository.deleteOlderThanMs(cutoffMs);
            if (deleted > 0) {
                log.info("越界事件保留清理完成：删除 {} 行（保留 {} 天）", deleted, retentionDays);
            }
        } catch (Exception e) {
            log.warn("越界事件保留清理失败: {}", e.getMessage());
        }
    }

    /** 设置保留天数（主要用于测试注入；<=0 关闭清理）。 */
    void setRetentionDays(int retentionDays) {
        this.retentionDays = retentionDays;
    }
}

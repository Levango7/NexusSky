package io.aerofleet.cloud.api.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 应急编排计划保留清理：删除 {@code updated_at} 早于保留期的历史行。
 * <p>
 * 判据用 {@code updated_at}（计划的最后触碰时刻）而非创建时刻：计划是活文档
 * （start/abort/上报回调都会写穿刷新），保留语义是「N 天未被触碰的计划才可裁」，
 * 正在推进的编排不会因「创建得早」被误删。
 * <p>
 * {@code aerofleet.emergency-orch.retention-days<=0}（默认）时整个任务空转。
 * 每天 02:45 执行；五个留存任务 15 分钟一档错开（Boot 默认调度器单线程）。
 */
@Component
public class EmergencyPlanRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(EmergencyPlanRetentionJob.class);

    /** 保留天数；<=0 关闭清理。 */
    @Value("${aerofleet.emergency-orch.retention-days:0}")
    private int retentionDays = 0;

    @Autowired(required = false)
    private EmergencyPlanRepository repository;

    @Scheduled(cron = "0 45 2 * * *")
    public void purgeOldEntries() {
        if (retentionDays <= 0 || repository == null) {
            return;
        }
        try {
            Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
            int deleted = repository.deleteOlderThan(cutoff);
            if (deleted > 0) {
                log.info("应急编排计划保留清理完成：删除 {} 行（保留 {} 天）", deleted, retentionDays);
            }
        } catch (Exception e) {
            log.warn("应急编排计划保留清理失败: {}", e.getMessage());
        }
    }

    /** 设置保留天数（主要用于测试注入；<=0 关闭清理）。 */
    void setRetentionDays(int retentionDays) {
        this.retentionDays = retentionDays;
    }
}

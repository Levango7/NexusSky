package io.aerofleet.cloud.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 审计日志保留清理：按 {@code aerofleet.audit.retention-days} 删除过期的历史行。
 * <p>
 * <b>默认关闭</b>（0=永久保留）。审计记录是防篡改取证对象，删与不删是合规决策，
 * 不由代码替运维决定；打开即接受"链的前缀不再可验"这一必然代价。
 * <p>
 * 与哈希链的关系：删除只会切掉链<b>前缀</b>（按时间从旧到新），链尾（最新行）不受影响，
 * 新记录照常续接。{@link AuditService#verifyChain()} 读同一配置键，据此把
 * "链首 prev_hash 不是创世哈希"判为预期截断（{@code truncated=true}）而非断链；
 * 保留关闭时同一形态仍判红。链校验从此保证的是"保留窗口内的链自洽"，
 * <b>前缀删除本身不可检测</b>——这是没有外部锚（签名检查点）时的固有边界，
 * 需要更强保证时应做归档导出而非删除。
 * <p>
 * 删除按 {@code timestamp} 而非 id：两者在正常写入下单调同向，但时间戳才是保留策略
 * 的口径；落库失败的行不进库，不影响判定。
 */
@Component
public class AuditRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(AuditRetentionJob.class);

    /** 保留天数；{@code <=0} 表示关闭清理（默认）。 */
    @Value("${aerofleet.audit.retention-days:0}")
    private int retentionDays = 0;

    /** 可选注入：纯内存模式（无 JPA）时无历史行可删。 */
    @Autowired(required = false)
    private AuditLogRepository repository;

    /**
     * 每天 03:45（本地时区）执行。与飞行日志保留（03:30）错开：Boot 默认调度器单线程
     * （{@code spring.task.scheduling.pool.size=1}），两个清理任务不排队在同一秒。
     */
    @Scheduled(cron = "0 45 3 * * *")
    public void purgeOldEntries() {
        if (retentionDays <= 0 || repository == null) {
            return;
        }
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        try {
            int deleted = repository.deleteOlderThan(cutoff);
            if (deleted > 0) {
                log.info("审计日志保留清理完成：删除 {} 条（保留 {} 天）；"
                        + "GET /api/v1/audit/verify 此后报 truncated=true（前缀不再可验）", deleted, retentionDays);
            }
        } catch (Exception e) {
            log.warn("审计日志保留清理失败（不影响新记录写入）: {}", e.getMessage());
        }
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

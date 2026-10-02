package io.aerofleet.cloud.security;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * API Key lastUsedAt 合并写：把「每请求一次同步 UPDATE」降为「每 Key 至多每
 * {@value #FLUSH_INTERVAL_SECONDS} 秒一次」。
 * <p>
 * 背景：{@link ApiKeyFilter} 过去在每个认证成功的请求里同步 {@code save(entity)}
 * 更新 lastUsedAt——遥测摄取高频路径上这是每包一次的数据库写。本组件在内存里
 * 记录每个 keyId 见到的最新时间，由单线程调度器周期性批量刷库（JPQL 定点 UPDATE，
 * 不走实体加载），进程退出前 flush 兜底。
 * <p>
 * 语义取舍（明示）：lastUsedAt 从「精确到请求」退化为「精确到刷写周期」——它的
 * 用途是运维观测（Key 最近是否在用），不是审计凭据；换取摄取路径零同步写。
 * 刷库失败只 WARN 丢本周期数据，绝不影响请求。
 */
@Component
public class ApiKeyLastUsedTracker {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyLastUsedTracker.class);

    /** 刷写周期（秒）。 */
    static final long FLUSH_INTERVAL_SECONDS = 30;

    private final ApiKeyRepository apiKeyRepository;
    private final ConcurrentHashMap<String, Instant> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;

    public ApiKeyLastUsedTracker(@Autowired(required = false) ApiKeyRepository apiKeyRepository) {
        this.apiKeyRepository = apiKeyRepository;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "api-key-lastused-flusher");
            thread.setDaemon(true);
            return thread;
        });
        this.scheduler.scheduleWithFixedDelay(this::safeFlush,
                FLUSH_INTERVAL_SECONDS, FLUSH_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    /** 记录一次 Key 使用（认证成功路径调用；O(1) 内存操作，无 IO）。 */
    public void record(String keyId) {
        if (keyId == null) {
            return;
        }
        Instant now = Instant.now();
        pending.merge(keyId, now, (oldVal, newVal) -> newVal.isAfter(oldVal) ? newVal : oldVal);
    }

    /** 周期刷库：定点 UPDATE 每个 pending keyId 的 lastUsedAt，逐条移除防丢并发新记录。 */
    @Transactional
    public void flush() {
        if (apiKeyRepository == null || pending.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<String, Instant>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Instant> entry = it.next();
            it.remove();
            try {
                apiKeyRepository.updateLastUsedAt(entry.getKey(), entry.getValue());
            } catch (Exception e) {
                log.warn("lastUsedAt 刷库失败（丢弃本条，不影响认证）: keyId={}, error={}",
                        entry.getKey(), e.getMessage());
            }
        }
    }

    private void safeFlush() {
        try {
            flush();
        } catch (Exception e) {
            // scheduleWithFixedDelay 的任务抛异常会终止后续调度，必须吞掉
            log.warn("lastUsedAt 周期刷写异常（下个周期重试）: {}", e.getMessage());
        }
    }

    /** 进程退出前把 pending 刷库（尽力而为）。 */
    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
        try {
            flush();
        } catch (Exception e) {
            log.warn("退出前 lastUsedAt 刷库失败: {}", e.getMessage());
        }
    }

    /** 测试辅助：当前待刷写条数。 */
    int pendingSize() {
        return pending.size();
    }
}

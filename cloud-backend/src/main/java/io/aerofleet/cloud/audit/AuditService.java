package io.aerofleet.cloud.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 审计日志服务：记录和查询操作审计日志。
 * <p>
 * 使用内存 {@link ConcurrentLinkedDeque} 存储（线程安全），默认保留最近 1000 条。
 * <p>
 * 当 {@code aerofleet.audit.enabled=false} 时，{@link #record} 方法直接返回，不记录日志。
 * <p>
 * 生产环境可替换为持久化存储（JPA / Elasticsearch / 文件），接口不变。
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    /** 默认内存日志上限，超出后丢弃最旧记录。 */
    private static final int DEFAULT_CAPACITY = 1000;

    private final boolean enabled;
    private final int capacity;
    private final ConcurrentLinkedDeque<AuditLog> logs = new ConcurrentLinkedDeque<>();

    public AuditService(@Value("${aerofleet.audit.enabled:true}") boolean enabled,
                        @Value("${aerofleet.audit.capacity:1000}") int capacity) {
        this.enabled = enabled;
        this.capacity = capacity > 0 ? capacity : DEFAULT_CAPACITY;
    }

    /**
     * 记录一条审计日志。
     *
     * @param userId 操作者标识
     * @param action HTTP 方法
     * @param target 请求路径
     * @param detail 操作详情（可为 null）
     * @param ip     客户端 IP
     */
    public void record(String userId, String action, String target, String detail, String ip) {
        if (!enabled) {
            return;
        }
        AuditLog entry = new AuditLog(Instant.now(), userId, action, target, detail, ip);
        logs.addLast(entry);
        // 超出容量时丢弃最旧记录
        while (logs.size() > capacity) {
            logs.pollFirst();
        }
        log.debug("审计日志: {}", entry);
    }

    /**
     * 查询全部审计日志（按时间倒序，最新的在前）。
     *
     * @return 不可修改的审计日志列表
     */
    public List<AuditLog> findAll() {
        List<AuditLog> snapshot = new ArrayList<>(logs);
        Collections.reverse(snapshot); // 最新的在前
        return Collections.unmodifiableList(snapshot);
    }

    /**
     * 查询最近 N 条审计日志。
     *
     * @param limit 最大返回条数
     * @return 不可修改的审计日志列表
     */
    public List<AuditLog> findRecent(int limit) {
        List<AuditLog> all = findAll();
        if (limit <= 0 || limit >= all.size()) {
            return all;
        }
        return all.subList(0, limit);
    }

    /**
     * 清空全部审计日志。
     */
    public void clear() {
        logs.clear();
    }

    /**
     * 返回当前审计日志条数。
     *
     * @return 日志条数
     */
    public int size() {
        return logs.size();
    }
}
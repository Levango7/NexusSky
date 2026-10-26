package io.aerofleet.cloud.geofence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 拦截日志存储：使用 {@link ConcurrentLinkedDeque} 存储拦截日志，支持并发追加与遍历。
 * <p>
 * 日志上限默认 {@value #DEFAULT_MAX_LOGS} 条（可经 {@code aerofleet.geofence.intercept.log-max-size} 覆盖），
 * 超过时丢弃最旧记录，丢弃条数由 {@link #evictedCount()} 累计。
 * <p>
 * 线程安全实现：所有操作通过 {@link ConcurrentLinkedDeque} 的原子方法保证。
 *
 * @see InterceptLog
 */
@Component
public class InterceptLogStore {

    private static final Logger log = LoggerFactory.getLogger(InterceptLogStore.class);

    /** 拦截日志最大保留条数的默认值（可由配置键覆盖，见构造函数）。 */
    static final int DEFAULT_MAX_LOGS = 1000;

    /**
     * 拦截日志最大保留条数。
     * <p>
     * 原来这是硬编码常量，而 {@code application.properties:252} 同时写着
     * {@code aerofleet.geofence.intercept.log-max-size=1000}——**没有一行代码读它**。
     * 现在把它接上：键值真生效，默认值仍与文档一致。
     */
    private final int maxLogs;

    /** 被容量丢弃的拦截日志累计数（进程生命周期内单调递增）。 */
    private final java.util.concurrent.atomic.AtomicLong evictedCount =
            new java.util.concurrent.atomic.AtomicLong(0L);

    /** 拦截日志（最新在队尾）。 */
    private final ConcurrentLinkedDeque<InterceptLog> logs = new ConcurrentLinkedDeque<>();

    public InterceptLogStore() {
        this(DEFAULT_MAX_LOGS);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public InterceptLogStore(
            @org.springframework.beans.factory.annotation.Value(
                    "${aerofleet.geofence.intercept.log-max-size:" + DEFAULT_MAX_LOGS + "}") int maxLogs) {
        this.maxLogs = maxLogs > 0 ? maxLogs : DEFAULT_MAX_LOGS;
    }

    /** 当前生效的保留上限（配置键被非正值覆盖时回退默认值）。 */
    public int maxLogs() {
        return maxLogs;
    }

    /**
     * 累计被容量丢掉的拦截日志条数。
     * <p>
     * 拦截记录是"平台拒绝过多少次越权命令"的证据；丢弃发生时如果只留一行 DEBUG，
     * 事后没人知道证据少了多少，所以这个数要能被端点与指标读出去。
     */
    public long evictedCount() {
        return evictedCount.get();
    }

    /**
     * 记录一条拦截日志；超过上限时丢弃最旧记录。
     *
     * @param logEntry 拦截日志
     */
    public void record(InterceptLog logEntry) {
        logs.addLast(logEntry);
        while (logs.size() > maxLogs) {
            if (logs.pollFirst() != null) {
                evictedCount.incrementAndGet();
            }
        }
        if (logEntry.getVerdict() == InterceptVerdict.Verdict.DENY) {
            log.warn("Intercept log: sysid={} command={} verdict=DENY reason={}",
                    logEntry.getSysid(), logEntry.getCommand(), logEntry.getReason());
        }
    }

    /**
     * 获取全部拦截日志（按时间升序，最新在末尾）。
     *
     * @return 拦截日志列表
     */
    public List<InterceptLog> getLogs() {
        return new ArrayList<>(logs);
    }

    /**
     * 获取指定无人机的拦截日志。
     *
     * @param sysid 无人机 systemId
     * @return 该无人机的拦截日志列表
     */
    public List<InterceptLog> getLogsForDrone(int sysid) {
        List<InterceptLog> result = new ArrayList<>();
        for (InterceptLog entry : logs) {
            if (entry.getSysid() == sysid) {
                result.add(entry);
            }
        }
        return result;
    }

    /**
     * 清空所有拦截日志（用于测试与管理）。
     */
    public void clear() {
        int n = logs.size();
        logs.clear();
        log.info("Intercept logs cleared: count={}", n);
    }

    /** 当前日志数量。 */
    public int logCount() {
        return logs.size();
    }
}
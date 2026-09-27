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
 * 日志上限 {@value #MAX_LOGS} 条，超过时丢弃最旧记录。
 * <p>
 * 线程安全实现：所有操作通过 {@link ConcurrentLinkedDeque} 的原子方法保证。
 *
 * @see InterceptLog
 */
@Component
public class InterceptLogStore {

    private static final Logger log = LoggerFactory.getLogger(InterceptLogStore.class);

    /** 拦截日志最大保留条数。 */
    static final int MAX_LOGS = 1000;

    /** 拦截日志（最新在队尾）。 */
    private final ConcurrentLinkedDeque<InterceptLog> logs = new ConcurrentLinkedDeque<>();

    /**
     * 记录一条拦截日志；超过上限时丢弃最旧记录。
     *
     * @param logEntry 拦截日志
     */
    public void record(InterceptLog logEntry) {
        logs.addLast(logEntry);
        while (logs.size() > MAX_LOGS) {
            logs.pollFirst();
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
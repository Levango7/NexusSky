package io.aerofleet.cloud.ai;

import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** M11 决策监控服务 */
@Service
public class DecisionMonitorService {
    /** 每台无人机保留的决策历史记录上限，超出后丢弃最旧记录。 */
    private static final int MAX_HISTORY = 1000;

    private final Map<Integer, List<Map<String, Object>>> decisionHistory = new ConcurrentHashMap<>();

    public void recordDecision(int sysid, String type, String reason, double confidence) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("type", type);
        entry.put("reason", reason);
        entry.put("confidence", confidence);
        entry.put("timestamp", System.currentTimeMillis());
        List<Map<String, Object>> history = decisionHistory.computeIfAbsent(sysid,
                k -> Collections.synchronizedList(new ArrayList<>()));
        // add + 修剪需在同一把锁内原子完成，避免并发下多线程同时突破上限
        synchronized (history) {
            history.add(entry);
            while (history.size() > MAX_HISTORY) {
                history.remove(0);
            }
        }
    }

    public List<Map<String, Object>> getDecisions(int sysid) {
        return decisionHistory.getOrDefault(sysid, Collections.emptyList());
    }

    public Map<Integer, List<Map<String, Object>>> getAllDecisions() {
        return Collections.unmodifiableMap(decisionHistory);
    }
}

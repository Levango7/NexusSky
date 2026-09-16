package io.aerofleet.cloud.ai;

import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** M11 决策监控服务 */
@Service
public class DecisionMonitorService {
    private final Map<Integer, List<Map<String, Object>>> decisionHistory = new ConcurrentHashMap<>();

    public void recordDecision(int sysid, String type, String reason, double confidence) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("type", type);
        entry.put("reason", reason);
        entry.put("confidence", confidence);
        entry.put("timestamp", System.currentTimeMillis());
        decisionHistory.computeIfAbsent(sysid, k -> Collections.synchronizedList(new ArrayList<>())).add(entry);
    }

    public List<Map<String, Object>> getDecisions(int sysid) {
        return decisionHistory.getOrDefault(sysid, Collections.emptyList());
    }

    public Map<Integer, List<Map<String, Object>>> getAllDecisions() {
        return Collections.unmodifiableMap(decisionHistory);
    }
}

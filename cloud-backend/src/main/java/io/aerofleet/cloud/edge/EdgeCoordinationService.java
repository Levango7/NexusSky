package io.aerofleet.cloud.edge;

import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** M12 边缘-云端协同服务 */
@Service
public class EdgeCoordinationService {
    /** 每台无人机保留的边缘结果记录上限，超出后丢弃最旧记录。 */
    private static final int MAX_HISTORY = 1000;

    private final Map<Integer, List<Map<String, Object>>> edgeResults = new ConcurrentHashMap<>();

    public void submitResult(int sysid, String taskId, String type, Map<String, Object> result) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("taskId", taskId);
        entry.put("type", type);
        entry.put("result", result);
        entry.put("timestamp", System.currentTimeMillis());
        List<Map<String, Object>> history = edgeResults.computeIfAbsent(sysid,
                k -> Collections.synchronizedList(new ArrayList<>()));
        // add + 修剪需在同一把锁内原子完成，避免并发下多线程同时突破上限
        synchronized (history) {
            history.add(entry);
            while (history.size() > MAX_HISTORY) {
                history.remove(0);
            }
        }
    }

    public List<Map<String, Object>> getResults(int sysid) {
        return edgeResults.getOrDefault(sysid, Collections.emptyList());
    }

    public Map<Integer, List<Map<String, Object>>> getAllResults() {
        return Collections.unmodifiableMap(edgeResults);
    }
}

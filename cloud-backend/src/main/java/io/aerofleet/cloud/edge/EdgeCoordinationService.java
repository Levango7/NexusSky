package io.aerofleet.cloud.edge;

import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** M12 边缘-云端协同服务 */
@Service
public class EdgeCoordinationService {
    private final Map<Integer, List<Map<String, Object>>> edgeResults = new ConcurrentHashMap<>();

    public void submitResult(int sysid, String taskId, String type, Map<String, Object> result) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("taskId", taskId);
        entry.put("type", type);
        entry.put("result", result);
        entry.put("timestamp", System.currentTimeMillis());
        edgeResults.computeIfAbsent(sysid, k -> Collections.synchronizedList(new ArrayList<>())).add(entry);
    }

    public List<Map<String, Object>> getResults(int sysid) {
        return edgeResults.getOrDefault(sysid, Collections.emptyList());
    }

    public Map<Integer, List<Map<String, Object>>> getAllResults() {
        return Collections.unmodifiableMap(edgeResults);
    }
}

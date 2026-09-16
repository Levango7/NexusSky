package io.aerofleet.cloud.twin;

import org.springframework.stereotype.Service;
import java.util.*;

/** M13 场景回放 */
@Service
public class ScenarioReplayService {
    private final Deque<Map<Integer, TwinState>> history = new ArrayDeque<>();
    private static final int MAX_HISTORY = 1800; // 30 min at 1Hz

    public void recordSnapshot(Map<Integer, TwinState> snapshot) {
        history.addLast(new LinkedHashMap<>(snapshot));
        while (history.size() > MAX_HISTORY) history.removeFirst();
    }

    public List<Map<Integer, TwinState>> getReplayData(long fromTs, long toTs) {
        return new ArrayList<>(history);
    }

    public int getHistorySize() { return history.size(); }
}

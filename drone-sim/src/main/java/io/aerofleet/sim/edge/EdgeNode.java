package io.aerofleet.sim.edge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/** M12 边缘计算节点 */
public class EdgeNode {
    private static final Logger log = LoggerFactory.getLogger(EdgeNode.class);
    private final int sysid;
    private final Map<String, EdgeTask> tasks = new ConcurrentHashMap<>();

    public EdgeNode(int sysid) { this.sysid = sysid; }

    public String submitTask(EdgeTask task) {
        tasks.put(task.taskId, task);
        log.info("[edge] sysid={} edge task submitted: {} type={}", sysid, task.taskId, task.type);
        return task.taskId;
    }

    public String getTaskStatus(String taskId) {
        EdgeTask t = tasks.get(taskId);
        return t != null ? "COMPLETED" : "UNKNOWN";
    }

    public int getTaskCount() { return tasks.size(); }
}

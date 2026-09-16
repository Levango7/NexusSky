package io.aerofleet.sim.edge;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/** M12 边缘计算节点 */
public class EdgeNode {
    private final int sysid;
    private final Map<String, EdgeTask> tasks = new ConcurrentHashMap<>();

    public EdgeNode(int sysid) { this.sysid = sysid; }

    public String submitTask(EdgeTask task) {
        tasks.put(task.taskId, task);
        System.out.println("[edge] sysid=" + sysid + " edge task submitted: " + task.taskId + " type=" + task.type);
        return task.taskId;
    }

    public String getTaskStatus(String taskId) {
        EdgeTask t = tasks.get(taskId);
        return t != null ? "COMPLETED" : "UNKNOWN";
    }

    public int getTaskCount() { return tasks.size(); }
}

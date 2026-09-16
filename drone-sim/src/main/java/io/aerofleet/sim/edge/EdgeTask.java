package io.aerofleet.sim.edge;

public class EdgeTask {
    public final String taskId;
    public final String type; // VIDEO_ANALYSIS / SENSOR_FUSION / OBJECT_DETECT
    public final long submittedAt;

    public EdgeTask(String taskId, String type) {
        this.taskId = taskId; this.type = type; this.submittedAt = System.currentTimeMillis();
    }
}

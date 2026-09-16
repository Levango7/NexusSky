package io.aerofleet.sim.edge;

import java.util.List;

public class AnalysisResult {
    public final String taskId;
    public final List<String> detectedObjects;
    public final double confidence;
    public final long processingTimeMs;

    public AnalysisResult(String taskId, List<String> objects, double conf, long ms) {
        this.taskId = taskId; this.detectedObjects = objects; this.confidence = conf; this.processingTimeMs = ms;
    }
}

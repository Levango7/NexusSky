package io.aerofleet.sim.edge;

import java.util.Arrays;
import java.util.List;

/** M12 视频流本地分析（模拟） */
public class VideoStreamAnalyzer {
    private long totalFrames = 0;
    private long totalDetections = 0;

    public AnalysisResult analyzeFrame(byte[] frame) {
        totalFrames++;
        long start = System.currentTimeMillis();
        // 模拟检测
        List<String> objects = Arrays.asList("vehicle", "person");
        totalDetections += objects.size();
        long elapsed = System.currentTimeMillis() - start;
        return new AnalysisResult("frame-" + totalFrames, objects, 0.85, elapsed);
    }

    public long getTotalFrames() { return totalFrames; }
    public long getTotalDetections() { return totalDetections; }
}

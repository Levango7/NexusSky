package io.aerofleet.sim.edge;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;

/**
 * M12 视频流本地分析（真实算法实现）。
 * <p>
 * 纯 Java 实现，不依赖 OpenCV，包含：
 * <ul>
 *   <li>帧差法运动检测（frame differencing + 二值化 + 连通域分析）</li>
 *   <li>质心跟踪（centroid tracker，基于欧氏距离匹配 + maxAge 老化删除）</li>
 *   <li>目标计数（累计/当前/按类别）</li>
 *   <li>场景变化检测（灰度直方图 BHATTACHARYYA 距离）</li>
 * </ul>
 * 输入帧按单字节灰度图处理（每像素 1 字节），宽高可配。
 */
public class VideoStreamAnalyzer {

    // ===== 配置 =====
    private final int width;
    private final int height;
    private int diffThreshold = 30;       // 帧差二值化阈值
    private double maxDistance = 50.0;    // 质心跟踪最大匹配距离（像素）
    private int maxAge = 5;               // 目标连续未匹配多少帧后删除
    private int minContourArea = 4;       // 最小连通域面积，过滤噪声

    // ===== 状态 =====
    private byte[] prevFrame = null;          // 前一帧灰度图（帧差法）
    private byte[] referenceFrame = null;     // 参考帧（场景变化检测）
    private final List<TrackedObjectImpl> trackedObjects = new ArrayList<>();
    private int nextTrackId = 1;

    // ===== 统计 =====
    private long totalFrames = 0;
    private long totalDetections = 0;
    private long uniqueObjectsTracked = 0;
    private long sceneChanges = 0;
    private final Map<String, Long> classCounts = new HashMap<>();

    // ===== 构造器 =====

    /** 默认构造器：64x64 灰度图，向后兼容。 */
    public VideoStreamAnalyzer() {
        this(64, 64);
    }

    /** 指定宽高的构造器。 */
    public VideoStreamAnalyzer(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("width/height 必须为正数, got " + width + "x" + height);
        }
        this.width = width;
        this.height = height;
    }

    // ===== 配置 setter（便于测试与调参） =====

    public void setDiffThreshold(int diffThreshold) {
        this.diffThreshold = diffThreshold;
    }

    public void setMaxDistance(double maxDistance) {
        this.maxDistance = maxDistance;
    }

    public void setMaxAge(int maxAge) {
        this.maxAge = maxAge;
    }

    public void setMinContourArea(int minContourArea) {
        this.minContourArea = minContourArea;
    }

    // ===== 向后兼容入口 =====

    /**
     * 分析单帧（向后兼容入口）。
     * <p>
     * 内部依次执行：运动检测 → 质心跟踪 → 统计更新，返回 {@link AnalysisResult}。
     * 检测对象列表为当前帧检测到的目标标签，置信度为当前帧检测的平均置信度。
     */
    public AnalysisResult analyzeFrame(byte[] frame) {
        totalFrames++;
        long start = System.currentTimeMillis();

        List<DetectedObject> detections = detectMotion(frame);
        updateTracking(detections);

        long elapsed = System.currentTimeMillis() - start;

        List<String> labels = new ArrayList<>(detections.size());
        double confSum = 0.0;
        for (DetectedObject d : detections) {
            labels.add(d.label);
            confSum += d.confidence;
        }
        double confidence = detections.isEmpty() ? 0.0 : confSum / detections.size();

        return new AnalysisResult("frame-" + totalFrames, labels, confidence, elapsed);
    }

    // ===== 运动检测（帧差法） =====

    /**
     * 帧差法运动检测。
     * <p>
     * 1. 当前帧与前帧逐像素求绝对差；<br>
     * 2. 差分图按 {@link #diffThreshold} 二值化；<br>
     * 3. 对二值图做 4-邻域 BFS 连通域分析，过滤面积小于 {@link #minContourArea} 的噪声；<br>
     * 4. 每个连通域输出一个 {@link DetectedObject}（边界框 + 类别 + 置信度）。
     * <p>
     * 首帧或帧尺寸变化时仅缓存前帧，返回空列表。
     */
    public List<DetectedObject> detectMotion(byte[] currentFrame) {
        if (currentFrame == null || currentFrame.length == 0) {
            return Collections.emptyList();
        }
        // 尺寸不一致或首帧：仅缓存，不检测
        if (prevFrame == null || prevFrame.length != currentFrame.length) {
            prevFrame = currentFrame.clone();
            return Collections.emptyList();
        }

        int n = currentFrame.length;
        byte[] binary = new byte[n];
        for (int i = 0; i < n; i++) {
            int d = Math.abs((currentFrame[i] & 0xFF) - (prevFrame[i] & 0xFF));
            binary[i] = d > diffThreshold ? (byte) 1 : (byte) 0;
        }

        // 推断宽高：若长度等于 width*height 用配置值，否则按 width=长度, height=1 处理
        int w, h;
        if (n == width * height) {
            w = width;
            h = height;
        } else {
            w = n;
            h = 1;
        }

        List<DetectedObject> detections = connectedComponents(binary, w, h);
        prevFrame = currentFrame.clone();
        totalDetections += detections.size();
        return detections;
    }

    /**
     * 4-邻域 BFS 连通域分析，返回每个连通域的边界框。
     */
    private List<DetectedObject> connectedComponents(byte[] binary, int w, int h) {
        List<DetectedObject> result = new ArrayList<>();
        boolean[] visited = new boolean[binary.length];

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int idx = y * w + x;
                if (binary[idx] != 1 || visited[idx]) {
                    continue;
                }
                // BFS 种子填充
                int minX = x, maxX = x, minY = y, maxY = y;
                int area = 0;
                Queue<Integer> queue = new ArrayDeque<>();
                queue.add(idx);
                visited[idx] = true;
                while (!queue.isEmpty()) {
                    int cur = queue.poll();
                    int cx = cur % w;
                    int cy = cur / w;
                    area++;
                    if (cx < minX) minX = cx;
                    if (cx > maxX) maxX = cx;
                    if (cy < minY) minY = cy;
                    if (cy > maxY) maxY = cy;
                    // 4 邻域
                    if (cx > 0) {
                        int nb = cur - 1;
                        if (binary[nb] == 1 && !visited[nb]) { visited[nb] = true; queue.add(nb); }
                    }
                    if (cx < w - 1) {
                        int nb = cur + 1;
                        if (binary[nb] == 1 && !visited[nb]) { visited[nb] = true; queue.add(nb); }
                    }
                    if (cy > 0) {
                        int nb = cur - w;
                        if (binary[nb] == 1 && !visited[nb]) { visited[nb] = true; queue.add(nb); }
                    }
                    if (cy < h - 1) {
                        int nb = cur + w;
                        if (binary[nb] == 1 && !visited[nb]) { visited[nb] = true; queue.add(nb); }
                    }
                }
                if (area >= minContourArea) {
                    int bw = maxX - minX + 1;
                    int bh = maxY - minY + 1;
                    String label = classify(bw, bh, area);
                    double conf = Math.min(1.0, 0.5 + area / 1000.0);
                    result.add(new DetectedObject(minX, minY, bw, bh, label, conf));
                }
            }
        }
        return result;
    }

    /**
     * 简单类别判定启发式：
     * <ul>
     *   <li>面积 ≥ 50 且长宽比 ≥ 1.5 → vehicle（车辆，大且横长）</li>
     *   <li>面积 ≥ 4 且长宽比 ≤ 2.0 → person（行人，中等且近方形）</li>
     *   <li>其他 → unknown</li>
     * </ul>
     */
    private String classify(int bw, int bh, int area) {
        double aspectRatio = (double) bw / (double) bh;
        if (area >= 50 && aspectRatio >= 1.5) {
            return "vehicle";
        }
        if (area >= 4 && aspectRatio <= 2.0) {
            return "person";
        }
        return "unknown";
    }

    // ===== 质心跟踪 =====

    /**
     * 质心跟踪更新。
     * <p>
     * 1. 对每个当前帧检测，在已跟踪目标中找欧氏距离最近且 &lt; {@link #maxDistance} 的目标匹配；<br>
     * 2. 匹配成功 → 更新位置与 lastSeen=0；<br>
     * 3. 未匹配的检测 → 分配新 ID，记入唯一计数与类别计数；<br>
     * 4. 未匹配的跟踪目标 → missedFrames++，连续超过 {@link #maxAge} 帧删除；<br>
     * 5. 返回当前活跃跟踪目标列表（{@link TrackedObject#lastSeen} 为连续未匹配帧数）。
     */
    public List<TrackedObject> updateTracking(List<DetectedObject> detections) {
        if (detections == null) {
            detections = Collections.emptyList();
        }
        int tSize = trackedObjects.size();
        int dSize = detections.size();
        boolean[] trackedMatched = new boolean[tSize];
        boolean[] detMatched = new boolean[dSize];

        // 贪心匹配：每个检测找最近未匹配跟踪目标
        for (int i = 0; i < dSize; i++) {
            DetectedObject det = detections.get(i);
            double detCx = det.x + det.width / 2.0;
            double detCy = det.y + det.height / 2.0;

            double bestDist = Double.MAX_VALUE;
            int bestIdx = -1;
            for (int j = 0; j < tSize; j++) {
                if (trackedMatched[j]) {
                    continue;
                }
                TrackedObjectImpl t = trackedObjects.get(j);
                double dx = detCx - t.centroidX;
                double dy = detCy - t.centroidY;
                double dist = Math.sqrt(dx * dx + dy * dy);
                if (dist < maxDistance && dist < bestDist) {
                    bestDist = dist;
                    bestIdx = j;
                }
            }

            if (bestIdx >= 0) {
                TrackedObjectImpl t = trackedObjects.get(bestIdx);
                t.centroidX = detCx;
                t.centroidY = detCy;
                t.x = det.x;
                t.y = det.y;
                t.boxWidth = det.width;
                t.boxHeight = det.height;
                t.missedFrames = 0;
                t.label = det.label;
                trackedMatched[bestIdx] = true;
                detMatched[i] = true;
            }
        }

        // 未匹配检测 → 新目标
        for (int i = 0; i < dSize; i++) {
            if (detMatched[i]) {
                continue;
            }
            DetectedObject det = detections.get(i);
            TrackedObjectImpl t = new TrackedObjectImpl();
            t.id = nextTrackId++;
            t.centroidX = det.x + det.width / 2.0;
            t.centroidY = det.y + det.height / 2.0;
            t.x = det.x;
            t.y = det.y;
            t.boxWidth = det.width;
            t.boxHeight = det.height;
            t.missedFrames = 0;
            t.label = det.label;
            trackedObjects.add(t);
            uniqueObjectsTracked++;
            classCounts.merge(det.label, 1L, Long::sum);
        }

        // 未匹配跟踪目标 → missedFrames++
        for (int j = 0; j < tSize; j++) {
            if (!trackedMatched[j]) {
                trackedObjects.get(j).missedFrames++;
            }
        }

        // 删除超过 maxAge 的目标
        Iterator<TrackedObjectImpl> it = trackedObjects.iterator();
        while (it.hasNext()) {
            if (it.next().missedFrames > maxAge) {
                it.remove();
            }
        }

        // 返回当前活跃目标快照
        List<TrackedObject> result = new ArrayList<>(trackedObjects.size());
        for (TrackedObjectImpl t : trackedObjects) {
            result.add(new TrackedObject(t.id, t.centroidX, t.centroidY, t.missedFrames));
        }
        return result;
    }

    // ===== 场景变化检测 =====

    /**
     * 场景变化检测。
     * <p>
     * 计算当前帧与参考帧的灰度直方图 BHATTACHARYYA 距离，大于阈值则判定为场景变化。
     * 首次调用或帧尺寸变化时仅缓存参考帧并返回 false。检测到变化后更新参考帧为当前帧。
     *
     * @param frame     当前帧灰度图
     * @param threshold 距离阈值（0~1，越大越敏感度低）
     * @return true 表示发生显著场景变化
     */
    public boolean detectSceneChange(byte[] frame, double threshold) {
        if (frame == null || frame.length == 0) {
            return false;
        }
        if (referenceFrame == null || referenceFrame.length != frame.length) {
            referenceFrame = frame.clone();
            return false;
        }
        double[] h1 = histogram(referenceFrame);
        double[] h2 = histogram(frame);
        double dist = bhattacharyyaDistance(h1, h2);
        boolean changed = dist > threshold;
        if (changed) {
            sceneChanges++;
            referenceFrame = frame.clone();
        }
        return changed;
    }

    /** 计算 256 bin 灰度直方图并归一化为概率分布。 */
    private double[] histogram(byte[] frame) {
        double[] hist = new double[256];
        for (byte b : frame) {
            hist[b & 0xFF]++;
        }
        double sum = frame.length;
        if (sum > 0) {
            for (int i = 0; i < 256; i++) {
                hist[i] /= sum;
            }
        }
        return hist;
    }

    /** BHATTACHARYYA 距离：D = sqrt(1 - sum(sqrt(p_i * q_i)))。 */
    private double bhattacharyyaDistance(double[] h1, double[] h2) {
        double bc = 0.0;
        for (int i = 0; i < 256; i++) {
            bc += Math.sqrt(h1[i] * h2[i]);
        }
        return Math.sqrt(Math.max(0.0, 1.0 - bc));
    }

    // ===== 统计 =====

    /** 返回综合统计快照。 */
    public VideoStats getStats() {
        return new VideoStats(totalFrames, totalDetections, uniqueObjectsTracked, sceneChanges);
    }

    /** 当前帧活跃跟踪目标数。 */
    public int getActiveTrackCount() {
        return trackedObjects.size();
    }

    /** 按类别返回累计计数（vehicle/person/unknown），不可变视图。 */
    public Map<String, Long> getClassCounts() {
        return Collections.unmodifiableMap(new HashMap<>(classCounts));
    }

    // ===== 向后兼容 getter =====

    public long getTotalFrames() {
        return totalFrames;
    }

    public long getTotalDetections() {
        return totalDetections;
    }

    // ===== 内部跟踪对象实现 =====

    /** 可变跟踪对象内部表示。 */
    private static class TrackedObjectImpl {
        int id;
        double centroidX;
        double centroidY;
        int x;
        int y;
        int boxWidth;
        int boxHeight;
        int missedFrames;
        String label;
    }

    // ===== 公开结果类型 =====

    /** 检测到的对象（边界框 + 类别 + 置信度）。 */
    public static final class DetectedObject {
        public final int x;
        public final int y;
        public final int width;
        public final int height;
        public final String label;
        public final double confidence;

        public DetectedObject(int x, int y, int width, int height, String label, double confidence) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.label = label;
            this.confidence = confidence;
        }

        @Override
        public String toString() {
            return "DetectedObject{x=" + x + ", y=" + y + ", w=" + width + ", h=" + height
                    + ", label=" + label + ", conf=" + confidence + "}";
        }
    }

    /** 跟踪对象（ID + 质心 + 连续未匹配帧数）。 */
    public static final class TrackedObject {
        public final int id;
        public final double centroidX;
        public final double centroidY;
        /** 距今连续多少帧未匹配到检测（0 表示本帧已匹配）。 */
        public final int lastSeen;

        public TrackedObject(int id, double centroidX, double centroidY, int lastSeen) {
            this.id = id;
            this.centroidX = centroidX;
            this.centroidY = centroidY;
            this.lastSeen = lastSeen;
        }

        @Override
        public String toString() {
            return "TrackedObject{id=" + id + ", cx=" + centroidX + ", cy=" + centroidY
                    + ", lastSeen=" + lastSeen + "}";
        }
    }

    /** 视频分析综合统计。 */
    public static final class VideoStats {
        public final long totalFrames;
        public final long totalDetections;
        public final long uniqueObjectsTracked;
        public final long sceneChanges;

        public VideoStats(long totalFrames, long totalDetections, long uniqueObjectsTracked, long sceneChanges) {
            this.totalFrames = totalFrames;
            this.totalDetections = totalDetections;
            this.uniqueObjectsTracked = uniqueObjectsTracked;
            this.sceneChanges = sceneChanges;
        }

        @Override
        public String toString() {
            return "VideoStats{frames=" + totalFrames + ", detections=" + totalDetections
                    + ", uniqueTracked=" + uniqueObjectsTracked + ", sceneChanges=" + sceneChanges + "}";
        }
    }
}

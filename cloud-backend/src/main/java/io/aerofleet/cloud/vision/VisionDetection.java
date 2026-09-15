package io.aerofleet.cloud.vision;

/**
 * 视觉检测结果（M3 感知成像增强，FR-02 数据契约）。
 * <p>
 * 不可变 record，紧凑构造校验：
 * <ul>
 *   <li>confidence ∈ [0.0, 1.0]</li>
 *   <li>kind 非空字符串</li>
 *   <li>trackId ≥ -1（-1 表示未关联跟踪）</li>
 * </ul>
 *
 * @param u          像素 u 坐标（0-based，相对图像原点）
 * @param v          像素 v 坐标（0-based）
 * @param kind       目标类别（非空字符串，如 "vehicle"/"person"/"blob"）
 * @param confidence 置信度（0.0-1.0）
 * @param trackId    跟踪 ID（≥0 表示已关联，-1 表示未关联）
 */
public record VisionDetection(double u, double v, String kind,
                              double confidence, int trackId) {
    public VisionDetection {
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException(
                    "confidence must be in [0.0, 1.0], got " + confidence);
        }
        if (kind == null || kind.isEmpty()) {
            throw new IllegalArgumentException("kind must be non-empty");
        }
        if (trackId < -1) {
            throw new IllegalArgumentException(
                    "trackId must be >= -1, got " + trackId);
        }
    }
}
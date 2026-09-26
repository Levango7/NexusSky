package io.aerofleet.cloud.inspection;

import java.time.Instant;

/**
 * 巡检异常（检测到的缺陷/故障）。
 * <p>
 * 由 {@link AnomalyDetectionService} 分析巡检照片后生成，按行业类型对应不同异常类型
 * （绝缘子破损/管道泄漏/轨道裂缝/面板裂纹/叶片损伤/结构锈蚀）。
 */
public final class Anomaly {

    /** 异常严重程度。 */
    public enum Severity {
        /** 高严重度 — 需立即处理。 */
        HIGH,
        /** 中严重度 — 需限期处理。 */
        MEDIUM,
        /** 低严重度 — 记录观察。 */
        LOW
    }

    private final String id;
    private final String type;
    private final Severity severity;
    private final double lat;
    private final double lon;
    private final String photoId;
    private final String description;
    private final Instant detectedAt;
    private final double confidencePct;

    public Anomaly(String id, String type, Severity severity,
                   double lat, double lon, String photoId,
                   String description, Instant detectedAt, double confidencePct) {
        this.id = id;
        this.type = type;
        this.severity = severity;
        this.lat = lat;
        this.lon = lon;
        this.photoId = photoId;
        this.description = description;
        this.detectedAt = detectedAt;
        this.confidencePct = confidencePct;
    }

    public String id() { return id; }
    public String type() { return type; }
    public Severity severity() { return severity; }
    public double lat() { return lat; }
    public double lon() { return lon; }
    public String photoId() { return photoId; }
    public String description() { return description; }
    public Instant detectedAt() { return detectedAt; }
    public double confidencePct() { return confidencePct; }
}
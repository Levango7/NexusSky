package io.aerofleet.cloud.inspection;

import java.time.Instant;

/**
 * 巡检照片（带 GPS 标注的航拍图像元数据）。
 * <p>
 * 由无人机在巡检航点拍摄，包含拍摄位置、航向、时间戳等元数据，
 * 供 {@link AnomalyDetectionService} 分析检测异常。
 */
public final class InspectionPhoto {

    private final String id;
    private final String taskId;
    private final int waypointSeq;
    private final double lat;
    private final double lon;
    private final double altM;
    private final double headingDeg;
    private final double cameraAngleDeg;
    private final Instant capturedAt;
    private final String imagePath;
    private final IndustryType industryType;

    public InspectionPhoto(String id, String taskId, int waypointSeq,
                           double lat, double lon, double altM,
                           double headingDeg, double cameraAngleDeg,
                           Instant capturedAt, String imagePath,
                           IndustryType industryType) {
        this.id = id;
        this.taskId = taskId;
        this.waypointSeq = waypointSeq;
        this.lat = lat;
        this.lon = lon;
        this.altM = altM;
        this.headingDeg = headingDeg;
        this.cameraAngleDeg = cameraAngleDeg;
        this.capturedAt = capturedAt;
        this.imagePath = imagePath;
        this.industryType = industryType;
    }

    public String id() { return id; }
    public String taskId() { return taskId; }
    public int waypointSeq() { return waypointSeq; }
    public double lat() { return lat; }
    public double lon() { return lon; }
    public double altM() { return altM; }
    public double headingDeg() { return headingDeg; }
    public double cameraAngleDeg() { return cameraAngleDeg; }
    public Instant capturedAt() { return capturedAt; }
    public String imagePath() { return imagePath; }
    public IndustryType industryType() { return industryType; }
}
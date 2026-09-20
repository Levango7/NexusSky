package io.aerofleet.cloud.mapping;

import java.time.Instant;

/**
 * 采集照片（无人机航拍测绘中采集的影像数据）。
 * <p>
 * 每张照片包含精确的 GPS 位置、姿态角（航向/俯仰/横滚）与时间戳，
 * 用于后续正射影像拼接、DEM 生成与三维建模。
 */
public final class CapturedPhoto {

    /** 照片 ID。 */
    private String id;
    /** 所属测绘任务 ID。 */
    private String taskId;
    /** 采集无人机 systemId。 */
    private int sysid;
    /** 拍照纬度（WGS84, degrees）。 */
    private double lat;
    /** 拍照经度（WGS84, degrees）。 */
    private double lon;
    /** 拍照海拔高度（m）。 */
    private double alt;
    /** 航向角（degrees, 0~360）。 */
    private double headingDeg;
    /** 俯仰角（degrees, 0=垂直向下）。 */
    private double pitchDeg;
    /** 横滚角（degrees）。 */
    private double rollDeg;
    /** 拍照时间戳。 */
    private Instant timestamp;
    /** 文件大小（bytes）。 */
    private long fileSize;
    /** 照片访问 URL。 */
    private String url;

    public CapturedPhoto() {
    }

    public CapturedPhoto(String id, String taskId, int sysid, double lat, double lon,
                         double alt, double headingDeg, double pitchDeg, double rollDeg,
                         Instant timestamp, long fileSize, String url) {
        this.id = id;
        this.taskId = taskId;
        this.sysid = sysid;
        this.lat = lat;
        this.lon = lon;
        this.alt = alt;
        this.headingDeg = headingDeg;
        this.pitchDeg = pitchDeg;
        this.rollDeg = rollDeg;
        this.timestamp = timestamp;
        this.fileSize = fileSize;
        this.url = url;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }

    public int getSysid() { return sysid; }
    public void setSysid(int sysid) { this.sysid = sysid; }

    public double getLat() { return lat; }
    public void setLat(double lat) { this.lat = lat; }

    public double getLon() { return lon; }
    public void setLon(double lon) { this.lon = lon; }

    public double getAlt() { return alt; }
    public void setAlt(double alt) { this.alt = alt; }

    public double getHeadingDeg() { return headingDeg; }
    public void setHeadingDeg(double headingDeg) { this.headingDeg = headingDeg; }

    public double getPitchDeg() { return pitchDeg; }
    public void setPitchDeg(double pitchDeg) { this.pitchDeg = pitchDeg; }

    public double getRollDeg() { return rollDeg; }
    public void setRollDeg(double rollDeg) { this.rollDeg = rollDeg; }

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }

    public long getFileSize() { return fileSize; }
    public void setFileSize(long fileSize) { this.fileSize = fileSize; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
}
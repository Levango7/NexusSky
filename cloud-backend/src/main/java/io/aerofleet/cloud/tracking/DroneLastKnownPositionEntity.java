package io.aerofleet.cloud.tracking;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 无人机最后已知位置持久化实体（JPA）。
 *
 * 每架无人机仅保存一条记录（sysid 为主键），用于重启后恢复最后已知位置。
 * 高频轨迹点仍保留在内存中（{@link FlightTrackStore}），此实体仅承载低频持久化快照。
 */
@Entity
@Table(name = "drone_last_known_position")
public class DroneLastKnownPositionEntity {

    @Id
    @Column(name = "sysid")
    private int sysid;

    @Column(name = "timestamp_ms")
    private long timestampMs;

    @Column(name = "lat")
    private double lat;

    @Column(name = "lon")
    private double lon;

    @Column(name = "alt")
    private double alt;

    @Column(name = "vx")
    private double vx;

    @Column(name = "vy")
    private double vy;

    @Column(name = "vz")
    private double vz;

    @Column(name = "heading")
    private double heading;

    @Column(name = "battery_pct")
    private double batteryPct;

    /** JPA 无参构造器（必需）。 */
    public DroneLastKnownPositionEntity() {
    }

    public int getSysid() {
        return sysid;
    }

    public void setSysid(int sysid) {
        this.sysid = sysid;
    }

    public long getTimestampMs() {
        return timestampMs;
    }

    public void setTimestampMs(long timestampMs) {
        this.timestampMs = timestampMs;
    }

    public double getLat() {
        return lat;
    }

    public void setLat(double lat) {
        this.lat = lat;
    }

    public double getLon() {
        return lon;
    }

    public void setLon(double lon) {
        this.lon = lon;
    }

    public double getAlt() {
        return alt;
    }

    public void setAlt(double alt) {
        this.alt = alt;
    }

    public double getVx() {
        return vx;
    }

    public void setVx(double vx) {
        this.vx = vx;
    }

    public double getVy() {
        return vy;
    }

    public void setVy(double vy) {
        this.vy = vy;
    }

    public double getVz() {
        return vz;
    }

    public void setVz(double vz) {
        this.vz = vz;
    }

    public double getHeading() {
        return heading;
    }

    public void setHeading(double heading) {
        this.heading = heading;
    }

    public double getBatteryPct() {
        return batteryPct;
    }

    public void setBatteryPct(double batteryPct) {
        this.batteryPct = batteryPct;
    }

    /**
     * 将此实体转换为 {@link FlightTrackStore.TrackPoint}。
     *
     * @return 对应的 TrackPoint 值对象
     */
    public FlightTrackStore.TrackPoint toTrackPoint() {
        return new FlightTrackStore.TrackPoint(
                sysid, timestampMs,
                lat, lon, alt,
                vx, vy, vz,
                heading, batteryPct
        );
    }

    /**
     * 从 {@link FlightTrackStore.TrackPoint} 创建持久化实体。
     *
     * @param point 轨迹点
     * @return 对应的 DroneLastKnownPositionEntity
     */
    public static DroneLastKnownPositionEntity fromTrackPoint(FlightTrackStore.TrackPoint point) {
        DroneLastKnownPositionEntity entity = new DroneLastKnownPositionEntity();
        entity.sysid = point.sysid;
        entity.timestampMs = point.timestampMs;
        entity.lat = point.lat;
        entity.lon = point.lon;
        entity.alt = point.alt;
        entity.vx = point.vx;
        entity.vy = point.vy;
        entity.vz = point.vz;
        entity.heading = point.heading;
        entity.batteryPct = point.batteryPct;
        return entity;
    }
}
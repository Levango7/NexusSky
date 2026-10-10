package io.aerofleet.cloud.scheduling;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 4D 空域预约的 JPA 实体（B5 内存预约表的持久化层）。
 * <p>
 * 与 {@link ConflictAvoidanceService.Reservation4D} 的关系：
 * <ul>
 *   <li>{@code Reservation4D} 是内存对象（字段 public final，无序时间语义）</li>
 *   <li>本实体是持久化对象，时间字段为 <b>epoch 毫秒</b>（绝对时间）</li>
 * </ul>
 * 重启后从库恢复未过期预约到内存表（{@code endMs > now}），过期条目随
 * 恢复一并清理——"占用"类数据不允许残留已失效窗口。
 */
@Entity
@Table(name = "airspace_reservations")
public class AirspaceReservationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "owner_sysid", nullable = false)
    private int ownerSysid;

    @Column(name = "lat", nullable = false)
    private double lat;

    @Column(name = "lon", nullable = false)
    private double lon;

    @Column(name = "alt", nullable = false)
    private double alt;

    /** 窗口起点（epoch 毫秒，绝对时间）。 */
    @Column(name = "start_ms", nullable = false)
    private double startMs;

    /** 窗口终点（epoch 毫秒，绝对时间；过期判定即 {@code endMs < now}）。 */
    @Column(name = "end_ms", nullable = false)
    private double endMs;

    /** 区块水平半径（米）。 */
    @Column(name = "radius_m", nullable = false)
    private double radiusM;

    @Column(name = "created_at", nullable = false)
    private long createdAt;

    protected AirspaceReservationEntity() {
    }

    public AirspaceReservationEntity(int ownerSysid, double lat, double lon, double alt,
                                     double startMs, double endMs, double radiusM,
                                     long createdAt) {
        this.ownerSysid = ownerSysid;
        this.lat = lat;
        this.lon = lon;
        this.alt = alt;
        this.startMs = startMs;
        this.endMs = endMs;
        this.radiusM = radiusM;
        this.createdAt = createdAt;
    }

    /** 内存预约 → 实体（落库口径）。 */
    public static AirspaceReservationEntity from(ConflictAvoidanceService.Reservation4D r,
                                                 int ownerSysid, long nowMs) {
        return new AirspaceReservationEntity(ownerSysid, r.lat, r.lon, r.alt,
                r.startTime, r.endTime, r.radius, nowMs);
    }

    /** 实体 → 内存预约（恢复口径）。 */
    public ConflictAvoidanceService.Reservation4D toReservation() {
        return new ConflictAvoidanceService.Reservation4D(lat, lon, alt, startMs, endMs, radiusM);
    }

    public Long getId() {
        return id;
    }

    public int getOwnerSysid() {
        return ownerSysid;
    }

    public double getEndMs() {
        return endMs;
    }
}

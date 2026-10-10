package io.aerofleet.cloud.scheduling;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 4D 空域预约仓库（B5 持久化）。
 * <p>
 * 仅保留三个查询：恢复（未过期全量）、过期清理（批量删）、按机主释放。
 * 内存表仍是唯一热路径——持久化是"重启不丢"的兜底，不承担查询职责。
 */
public interface AirspaceReservationRepository
        extends JpaRepository<AirspaceReservationEntity, Long> {

    /** 恢复用：终点晚于 now 的预约（未过期）。 */
    List<AirspaceReservationEntity> findByEndMsGreaterThan(double nowMs);

    /** 过期清理：终点早于 now 的批量删除（purge 与恢复时共用）。 */
    void deleteByEndMsLessThan(double nowMs);

    /** 按机主释放（释放语义的后续动作预留）。 */
    void deleteByOwnerSysid(int ownerSysid);
}

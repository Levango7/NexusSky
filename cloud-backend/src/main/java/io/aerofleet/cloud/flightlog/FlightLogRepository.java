package io.aerofleet.cloud.flightlog;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * FlightLog Spring Data JPA Repository。
 * <p>
 * 提供 {@link FlightLogEntity} 的 CRUD 操作和派生查询方法，
 * 用于飞行日志的数据库持久化路径。
 * 当 {@code aerofleet.flightlog.persist-to-db=true} 时由 {@link FlightLogService} 使用。
 */
@Repository
public interface FlightLogRepository extends JpaRepository<FlightLogEntity, Long> {

    /** 查找指定类型和 sysid 的所有日志记录。 */
    List<FlightLogEntity> findByTypeAndSysid(String type, int sysid);

    /** 查找指定时间范围内的所有日志记录。 */
    List<FlightLogEntity> findByTimestampBetween(Instant start, Instant end);

    /** 查找指定类型、sysid 和时间范围内的日志记录。 */
    List<FlightLogEntity> findByTypeAndSysidAndTimestampBetween(String type, int sysid, Instant start, Instant end);

    /** 查找指定类型和时间范围内的日志记录。 */
    List<FlightLogEntity> findByTypeAndTimestampBetween(String type, Instant start, Instant end);

    /** 查找指定 sysid 和时间范围内的日志记录。 */
    List<FlightLogEntity> findBySysidAndTimestampBetween(int sysid, Instant start, Instant end);
}
package io.aerofleet.cloud.flightlog;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

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

    // 下面四个按时间范围查询一律带 ORDER BY timestamp, id：FlightLogService 的
    // limit 语义是"取列表末尾（最新）N 条"，没有确定排序时到底返回哪 N 条由执行
    // 计划决定（换 PostgreSQL 或走索引就可能翻成最旧的 N 条）。id 作次级键，
    // 消掉同毫秒并列的不确定性（自增主键即写入顺序，与 JSONL 追加顺序同口径）。

    /** 查找指定时间范围内的所有日志记录（时间升序）。 */
    List<FlightLogEntity> findByTimestampBetweenOrderByTimestampAscIdAsc(Instant start, Instant end);

    /** 查找指定类型、sysid 和时间范围内的日志记录（时间升序）。 */
    List<FlightLogEntity> findByTypeAndSysidAndTimestampBetweenOrderByTimestampAscIdAsc(
            String type, int sysid, Instant start, Instant end);

    /** 查找指定类型和时间范围内的日志记录（时间升序）。 */
    List<FlightLogEntity> findByTypeAndTimestampBetweenOrderByTimestampAscIdAsc(String type, Instant start, Instant end);

    /** 查找指定 sysid 和时间范围内的日志记录（时间升序）。 */
    List<FlightLogEntity> findBySysidAndTimestampBetweenOrderByTimestampAscIdAsc(int sysid, Instant start, Instant end);

    /**
     * 保留策略用：删除 {@code cutoff} 之前的全部行（flight_log 无租户/类型条件，整表按时间裁剪）。
     * <p>
     * 单条 bulk DELETE，不把实体加载进持久化上下文；事务标在 Repository 方法上
     * （调用方是定时任务，没有请求事务）。
     *
     * @param cutoff 截止时间，早于此的记录被删除
     * @return 删除行数
     */
    @Modifying
    @Transactional
    @Query("delete from FlightLogEntity e where e.timestamp < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
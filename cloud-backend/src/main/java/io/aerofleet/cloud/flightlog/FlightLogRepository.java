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

    /**
     * 查找指定类型和 sysid 的**末尾 N 条**（id 倒序）——启动时重建内存轨迹历史用
     * （{@code FlightTrackStore.restoreTrackHistoryFromFlightLog}）。带 Pageable
     * 而不是全量再截尾：1 机 1 天 1Hz 就是 8.6 万行，重启路径不该为 3600 点拉整天。
     * 排序用 id（自增主键即写入顺序，与 JSONL 追加顺序同口径，见上方排序说明）。
     */
    List<FlightLogEntity> findByTypeAndSysid(String type, int sysid,
                                             org.springframework.data.domain.Pageable pageable);

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
     * 取时间范围内**最新的至多 N 条**，租户/类型/sysid 三个过滤条件均可为空（空=不过滤）。
     * <p>
     * 这是 {@link FlightLogService#query} 的唯一"取最新 N 条"入口：{@code flight_log}
     * 每个遥测点一行（1 机 1 天 1Hz ≈ 8.6 万行），先全量取回再 {@code subList} 截尾
     * 等于把整天数据拖进堆 —— JSONL 路径早已改用定长环形缓冲，DB 路径此前没有对齐。
     * <p>
     * <b>租户必须一起下推</b>：若先按全局取最新 N 行、再在内存里按租户过滤，
     * 当最新 N 行恰好都属于别的租户时，本租户会少返甚至空手而归（而它明明有数据）。
     * <p>
     * 排序用 {@code timestamp DESC, id DESC}（{@code id} 消掉同毫秒并列的不确定性），
     * 与既有四个升序方法的"末尾=最新"口径一致；调用方拿到的仍是**最新在前**，
     * 需要旧→新顺序时自行反转。
     *
     * @param start    起始时间（含）
     * @param end      截止时间（含）
     * @param tenantId 租户 ID；{@code null}=全局管理员（不过滤）
     * @param type     事件类型；{@code null}=不过滤
     * @param sysid    飞机编号；{@code null}=不过滤
     * @param pageable 必须只含分页（{@code PageRequest.of(0, limit)}），排序由 JPQL 固定
     * @return 最新在前的至多 {@code limit} 条
     */
    @Query("select e from FlightLogEntity e "
            + "where e.timestamp between :start and :end "
            + "and (:tenantId is null or e.tenantId = :tenantId) "
            + "and (:type is null or e.type = :type) "
            + "and (:sysid is null or e.sysid = :sysid) "
            + "order by e.timestamp desc, e.id desc")
    List<FlightLogEntity> findNewestFirst(@Param("start") Instant start,
                                          @Param("end") Instant end,
                                          @Param("tenantId") Integer tenantId,
                                          @Param("type") String type,
                                          @Param("sysid") Integer sysid,
                                          org.springframework.data.domain.Pageable pageable);

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
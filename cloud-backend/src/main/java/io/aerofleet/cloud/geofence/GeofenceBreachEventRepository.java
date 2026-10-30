package io.aerofleet.cloud.geofence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * {@link GeofenceBreachEventEntity} 的 JPA Repository。
 * <p>
 * 提供按无人机 sysid、围栏 zoneId、时间范围查询越界事件的方法。
 */
public interface GeofenceBreachEventRepository extends JpaRepository<GeofenceBreachEventEntity, Long> {

    /** 查询指定无人机的越界历史（按时间倒序）。 */
    List<GeofenceBreachEventEntity> findBySysidOrderByTimestampMsDesc(int sysid);

    /** 查询指定围栏的越界历史（按时间倒序）。 */
    List<GeofenceBreachEventEntity> findByZoneIdOrderByTimestampMsDesc(int zoneId);

    /** 查询指定租户的越界历史（按时间倒序）。 */
    List<GeofenceBreachEventEntity> findByTenantIdOrderByTimestampMsDesc(Integer tenantId);

    /** 查询指定租户和无人机的越界历史（按时间倒序）。 */
    List<GeofenceBreachEventEntity> findByTenantIdAndSysidOrderByTimestampMsDesc(Integer tenantId, int sysid);

    /** 查询指定租户和围栏的越界历史（按时间倒序）。 */
    List<GeofenceBreachEventEntity> findByTenantIdAndZoneIdOrderByTimestampMsDesc(Integer tenantId, int zoneId);

    /** 按时间范围查询越界历史（闭区间，按时间倒序）。 */
    @Query("SELECT e FROM GeofenceBreachEventEntity e WHERE e.timestampMs >= :fromMs AND e.timestampMs <= :toMs ORDER BY e.timestampMs DESC")
    List<GeofenceBreachEventEntity> findByTimeRange(@Param("fromMs") long fromMs, @Param("toMs") long toMs);

    /** 按租户和时间范围查询越界历史（闭区间，按时间倒序）。 */
    @Query("SELECT e FROM GeofenceBreachEventEntity e WHERE e.tenantId = :tenantId AND e.timestampMs >= :fromMs AND e.timestampMs <= :toMs ORDER BY e.timestampMs DESC")
    List<GeofenceBreachEventEntity> findByTenantIdAndTimeRange(@Param("tenantId") Integer tenantId,
                                                               @Param("fromMs") long fromMs,
                                                               @Param("toMs") long toMs);

    /**
     * 保留策略用：删除 {@code cutoffMs} 之前的全部行（越界事件无租户条件，整表按时间裁剪）。
     * <p>
     * 单条 bulk DELETE，不把实体加载进持久化上下文；事务标在 Repository 方法上
     * （调用方是定时任务，没有请求事务）。判据用事件自身时刻 {@code timestampMs}（非空），
     * 与存储侧分页排序键同源。
     *
     * @param cutoffMs 截止时刻（epoch 毫秒），早于此的记录被删除
     * @return 删除行数
     */
    @Modifying
    @Transactional
    @Query("delete from GeofenceBreachEventEntity e where e.timestampMs < :cutoffMs")
    int deleteOlderThanMs(@Param("cutoffMs") long cutoffMs);
}
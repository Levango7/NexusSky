package io.aerofleet.cloud.delivery2;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Repository
public interface DeliveryTask2Repository extends JpaRepository<DeliveryTask2, String> {

    List<DeliveryTask2> findByTenantId(Integer tenantId);

    /**
     * 保留策略用：删除时间基准早于 {@code cutoff} 的全部行。
     * <p>
     * 判据 {@code coalesce(created_at, start_time)}：V38 起新行一律有 created_at
     * （@PrePersist 写入）；V38 之前的存量已启动行按 start_time 照常可裁；
     * 存量未启动行两者皆 NULL——无时间基准，不裁（不猜）。
     * 单条 bulk DELETE；事务标在方法上（调用方是定时任务）。
     *
     * @param cutoff 截止时刻，早于此的记录被删除
     * @return 删除行数
     */
    @Modifying
    @Transactional
    @Query("delete from DeliveryTask2 e where coalesce(e.createdAt, e.startTime) < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
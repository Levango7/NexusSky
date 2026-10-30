package io.aerofleet.cloud.api.service;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** 应急编排计划仓库（V37）。 */
@Repository
public interface EmergencyPlanRepository extends JpaRepository<EmergencyPlanEntity, Long> {

    /**
     * 保留策略用：删除 {@code updated_at} 早于 {@code cutoff} 的全部行。
     * <p>
     * 判据用 {@code updated_at}（计划的最后触碰时刻）而非创建时刻：计划是
     * 活文档（start/abort/上报回调都会写穿刷新），保留语义是「N 天未被触碰的
     * 计划才可裁」，正在推进的编排不会因「创建得早」被误删。
     * 单条 bulk DELETE；事务标在方法上（调用方是定时任务）。
     *
     * @param cutoff 截止时刻，早于此的记录被删除
     * @return 删除行数
     */
    @Modifying
    @Transactional
    @Query("delete from EmergencyPlanEntity e where e.updatedAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}

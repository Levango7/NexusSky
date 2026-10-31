package io.aerofleet.cloud.autodispatch;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 出警历史持久化仓库（V31；审计 §2.1 第 4 条）。
 * <p>
 * 排序口径与内存 deque 一致：最新在前（trigger_time DESC，dispatch_id 作并列次级键）。
 */
@Repository
public interface DispatchHistoryRepository extends JpaRepository<DispatchRecordEntity, String> {

    List<DispatchRecordEntity> findAllByOrderByTriggerTimeDescDispatchIdDesc(Pageable pageable);

    List<DispatchRecordEntity> findAllByOrderByTriggerTimeAscDispatchIdAsc(Pageable pageable);

    /**
     * 保留策略用：删除 {@code cutoffMs} 之前的全部行（整表按时间裁剪）。
     * <p>
     * 判据用 {@code trigger_time}（NOT NULL，报警触发即落行）而非 {@code created_at}：
     * 两者在写入路径上同刻（派飞触发即建记录），但 trigger_time 有非空约束，
     * 不会因历史脏行漏裁。单条 bulk DELETE；事务标在方法上（调用方是定时任务）。
     *
     * @param cutoffMs 截止时刻（epoch 毫秒），早于此的记录被删除
     * @return 删除行数
     */
    @Modifying
    @Transactional
    @Query("delete from DispatchRecordEntity e where e.triggerTime < :cutoffMs")
    int deleteOlderThan(@Param("cutoffMs") long cutoffMs);
}

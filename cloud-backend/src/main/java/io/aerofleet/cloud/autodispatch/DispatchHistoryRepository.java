package io.aerofleet.cloud.autodispatch;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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
}

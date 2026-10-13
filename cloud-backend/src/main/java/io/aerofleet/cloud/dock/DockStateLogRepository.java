package io.aerofleet.cloud.dock;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DockStateLogRepository extends JpaRepository<DockStateLogEntity, Long> {
    List<DockStateLogEntity> findByDockIdOrderByTsDesc(Long dockId);
    List<DockStateLogEntity> findByDockIdAndTsBetweenOrderByTs(Long dockId, long from, long to);

    /**
     * 分页取某个机巢的最新 N 条状态流水。
     * <p>
     * recentLog 原先 findByDockIdOrderByTsDesc 全量取回再 subList——机巢状态
     * 变更频繁（秒级），一张表能到几十万行，每次都全表拉取。加 Pageable 让
     * LIMIT 下推到数据库。
     */
    List<DockStateLogEntity> findByDockIdOrderByTsDesc(Long dockId, Pageable pageable);
}

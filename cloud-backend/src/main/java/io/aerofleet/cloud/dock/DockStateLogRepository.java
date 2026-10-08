package io.aerofleet.cloud.dock;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DockStateLogRepository extends JpaRepository<DockStateLogEntity, Long> {
    List<DockStateLogEntity> findByDockIdOrderByTsDesc(Long dockId);
    List<DockStateLogEntity> findByDockIdAndTsBetweenOrderByTs(Long dockId, long from, long to);
}

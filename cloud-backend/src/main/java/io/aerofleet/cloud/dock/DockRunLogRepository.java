package io.aerofleet.cloud.dock;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DockRunLogRepository extends JpaRepository<DockRunLogEntity, Long> {
    List<DockRunLogEntity> findByDockIdAndStartedAtBetween(Long dockId, long from, long to);
    List<DockRunLogEntity> findByDockIdOrderByStartedAtDesc(Long dockId);
}

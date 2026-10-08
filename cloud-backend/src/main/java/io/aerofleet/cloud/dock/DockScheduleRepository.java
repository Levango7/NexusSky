package io.aerofleet.cloud.dock;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DockScheduleRepository extends JpaRepository<DockScheduleEntity, Long> {
    List<DockScheduleEntity> findByDockIdOrderById(Long dockId);
    List<DockScheduleEntity> findByEnabledTrue();
}

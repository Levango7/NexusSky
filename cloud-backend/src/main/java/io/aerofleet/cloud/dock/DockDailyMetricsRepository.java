package io.aerofleet.cloud.dock;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DockDailyMetricsRepository extends JpaRepository<DockDailyMetricsEntity, Long> {
    Optional<DockDailyMetricsEntity> findByDockIdAndDay(Long dockId, String day);
    List<DockDailyMetricsEntity> findByDockIdAndDayInOrderByDayDesc(Long dockId, List<String> days);
}

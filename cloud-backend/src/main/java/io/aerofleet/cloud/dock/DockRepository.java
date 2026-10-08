package io.aerofleet.cloud.dock;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DockRepository extends JpaRepository<DockEntity, Long> {
    Optional<DockEntity> findBySn(String sn);
    List<DockEntity> findByTenantIdOrderById(String tenantId);
}

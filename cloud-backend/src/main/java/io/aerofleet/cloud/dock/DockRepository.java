package io.aerofleet.cloud.dock;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DockRepository extends JpaRepository<DockEntity, Long> {
    Optional<DockEntity> findBySn(String sn);
    /** 托管机 sysid → 机巢（P1-10：DJI Cloud API 栈按 sysid 找网关机巢）。 */
    Optional<DockEntity> findByDroneSysid(Integer droneSysid);
    List<DockEntity> findByTenantIdOrderById(String tenantId);
}

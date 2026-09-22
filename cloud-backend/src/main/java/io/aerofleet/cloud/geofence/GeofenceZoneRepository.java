package io.aerofleet.cloud.geofence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * {@link GeofenceZoneEntity} 的 JPA Repository。
 * <p>
 * 围栏区域的主键为 {@code Integer}（zoneId），由业务层分配。
 */
public interface GeofenceZoneRepository extends JpaRepository<GeofenceZoneEntity, Integer> {

    /** 查找指定租户的所有围栏区域。 */
    List<GeofenceZoneEntity> findByTenantId(Integer tenantId);
}
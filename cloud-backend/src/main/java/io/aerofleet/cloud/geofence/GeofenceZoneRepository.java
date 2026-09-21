package io.aerofleet.cloud.geofence;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * {@link GeofenceZoneEntity} 的 JPA Repository。
 * <p>
 * 围栏区域的主键为 {@code Integer}（zoneId），由业务层分配。
 */
public interface GeofenceZoneRepository extends JpaRepository<GeofenceZoneEntity, Integer> {
}
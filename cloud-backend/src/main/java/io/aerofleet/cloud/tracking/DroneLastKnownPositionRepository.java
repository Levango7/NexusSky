package io.aerofleet.cloud.tracking;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 无人机最后已知位置 JPA Repository。
 *
 * 提供 {@link DroneLastKnownPositionEntity} 的持久化 CRUD 操作。
 * 每架无人机仅保留一条记录（sysid 为主键），save() 即 upsert。
 */
@Repository
public interface DroneLastKnownPositionRepository extends JpaRepository<DroneLastKnownPositionEntity, Integer> {
}
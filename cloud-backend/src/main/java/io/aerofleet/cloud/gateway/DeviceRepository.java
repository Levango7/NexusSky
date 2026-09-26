package io.aerofleet.cloud.gateway;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 设备注册 Spring Data JPA Repository。
 * <p>
 * 提供 {@link DeviceEntity} 的 CRUD 操作，用于可选的设备注册持久化。
 * 当 {@code aerofleet.device-registry.persist=true} 时由 {@link DeviceRegistry} 使用。
 */
@Repository
public interface DeviceRepository extends JpaRepository<DeviceEntity, Integer> {

    /** 查找所有在线设备。 */
    List<DeviceEntity> findByOnlineTrue();

    /** 查找指定租户的所有设备。 */
    List<DeviceEntity> findByTenantId(Integer tenantId);

    /** 查找指定租户的所有在线设备。 */
    List<DeviceEntity> findByTenantIdAndOnlineTrue(Integer tenantId);

    /** 检查设备是否存在。 */
    boolean existsBySysid(Integer sysid);
}
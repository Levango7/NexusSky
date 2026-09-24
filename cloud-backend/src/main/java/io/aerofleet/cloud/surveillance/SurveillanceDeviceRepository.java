package io.aerofleet.cloud.surveillance;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 安防设备 Spring Data JPA Repository。
 * <p>
 * 提供 {@link SurveillanceDeviceEntity} 的 CRUD 操作，
 * 由 {@link SurveillanceDeviceRegistry} 在混合模式（内存缓存 + JPA 持久化）下使用。
 */
@Repository
public interface SurveillanceDeviceRepository extends JpaRepository<SurveillanceDeviceEntity, String> {

    /**
     * 按租户 ID 查询安防设备。
     *
     * @param tenantId 租户 ID
     * @return 该租户下的所有安防设备列表
     */
    List<SurveillanceDeviceEntity> findByTenantId(Integer tenantId);
}
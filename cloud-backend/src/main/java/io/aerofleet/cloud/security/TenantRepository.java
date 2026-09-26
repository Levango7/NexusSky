package io.aerofleet.cloud.security;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * {@link TenantEntity} 的 JPA Repository。
 * <p>
 * 提供按租户编码查找、查找所有启用租户等查询方法。
 */
public interface TenantRepository extends JpaRepository<TenantEntity, Integer> {

    /**
     * 按租户编码查找租户。
     *
     * @param code 租户编码（用于 URL 和 API 标识）
     * @return 租户实体（可能为空）
     */
    Optional<TenantEntity> findByCode(String code);

    /**
     * 查找所有启用的租户。
     *
     * @return 启用状态为 true 的租户列表
     */
    List<TenantEntity> findByEnabledTrue();
}
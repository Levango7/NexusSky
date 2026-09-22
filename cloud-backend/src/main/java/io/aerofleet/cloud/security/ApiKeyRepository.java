package io.aerofleet.cloud.security;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * {@link ApiKeyEntity} 的 JPA Repository。
 * <p>
 * 提供 API Key 的查找、按租户查找、按 keyId 查找有效 Key 等查询方法。
 */
public interface ApiKeyRepository extends JpaRepository<ApiKeyEntity, String> {

    /**
     * 按 keyId 查找 API Key。
     *
     * @param keyId API Key ID
     * @return API Key 实体（可能为空）
     */
    Optional<ApiKeyEntity> findByKeyId(String keyId);

    /**
     * 查找指定租户下的所有 API Key。
     *
     * @param tenantId 租户 ID
     * @return API Key 实体列表
     */
    List<ApiKeyEntity> findByTenantId(Integer tenantId);

    /**
     * 按 keyId 查找未撤销的 API Key。
     *
     * @param keyId API Key ID
     * @return 未撤销的 API Key 实体（可能为空）
     */
    Optional<ApiKeyEntity> findByKeyIdAndRevokedFalse(String keyId);

    /**
     * 查找指定用户的所有 API Key。
     *
     * @param userId 用户 ID
     * @return API Key 实体列表
     */
    List<ApiKeyEntity> findByUserId(Integer userId);
}
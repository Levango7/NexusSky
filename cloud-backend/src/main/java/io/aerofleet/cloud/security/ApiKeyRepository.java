package io.aerofleet.cloud.security;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * {@link ApiKeyEntity} 的 JPA Repository。
 * <p>
 * 提供 API Key 的查找、按租户查找、按 keyHash 查找有效 Key 等查询方法。
 * 认证时通过 {@link #findByKeyHashAndRevokedFalse(String)} 查询，
 * 确保只存储和查询 SHA-256 哈希值，不涉及明文 Key。
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
     * 按 keyHash（SHA-256 哈希）查找未撤销的 API Key。
     * <p>
     * 认证过滤器使用此方法：从 X-API-Key Header 提取明文 Key，
     * 计算 SHA-256 哈希后查询数据库，避免存储明文 Key。
     *
     * @param keyHash API Key 的 SHA-256 哈希（Hex 编码）
     * @return 未撤销的 API Key 实体（可能为空）
     */
    Optional<ApiKeyEntity> findByKeyHashAndRevokedFalse(String keyHash);

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
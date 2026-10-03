package io.aerofleet.cloud.security;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * {@link ApiKeyEntity} 的 JPA Repository。
 * <p>
 * 提供按 keyId、keyHash、租户查找与 lastUsedAt 定点更新等查询方法。
 * 认证路径经 {@link ApiKeyCache} 走 {@link #findByKeyHash(String)}（取完整行，
 * 撤销/过期判定在缓存层统一做），库中只存 SHA-256 哈希，不涉及明文 Key。
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
     * 按 keyHash（SHA-256 哈希）查找 API Key，含已撤销的行。
     * <p>
     * 认证缓存使用此方法：撤销/过期判定统一在 {@link ApiKeyCache} 做，
     * 已撤销的行会被缓存为负条目，避免每请求重复查库。
     *
     * @param keyHash API Key 的 SHA-256 哈希（Hex 编码）
     * @return API Key 实体（可能为空）
     */
    Optional<ApiKeyEntity> findByKeyHash(String keyHash);

    /**
     * 按 keyHash（SHA-256 哈希）查找未撤销的 API Key。
     *
     * @param keyHash API Key 的 SHA-256 哈希（Hex 编码）
     * @return 未撤销的 API Key 实体（可能为空）
     */
    Optional<ApiKeyEntity> findByKeyHashAndRevokedFalse(String keyHash);

    /**
     * 定点更新 lastUsedAt（{@link ApiKeyLastUsedTracker} 周期批量调用）。
     * <p>
     * JPQL UPDATE 不加载实体、不动持久化上下文；需在事务内调用。
     *
     * @param keyId      API Key ID
     * @param lastUsedAt 本次使用的最新时间
     * @return 更新行数（0 = keyId 不存在）
     */
    @Modifying
    @Query("update ApiKeyEntity k set k.lastUsedAt = :lastUsedAt where k.keyId = :keyId")
    int updateLastUsedAt(@Param("keyId") String keyId, @Param("lastUsedAt") Instant lastUsedAt);

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
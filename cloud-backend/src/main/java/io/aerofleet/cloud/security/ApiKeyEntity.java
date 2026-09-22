package io.aerofleet.cloud.security;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * API Key 的 JPA 持久化实体。
 * <p>
 * 存储 API Key 的元数据和认证信息，支持与 JWT 并行的 API Key 认证机制。
 * keyId 为字符串主键，格式为 {@code ns-{tenantId}-{random32chars}}。
 * <p>
 * scopes 字段存储 JSON 数组字符串（如 {@code ["read","write"]}），
 * 用于细粒度权限控制。maskedKey 存储脱敏后的 Key（仅保留前4后4字符），
 * 供列表展示使用，完整 Key 仅在创建时返回一次。
 */
@Entity
@Table(name = "api_keys")
public class ApiKeyEntity {

    @Id
    @Column(name = "key_id", length = 80)
    private String keyId;

    @Column(name = "tenant_id")
    private Integer tenantId;

    @Column(name = "user_id")
    private Integer userId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /** JSON 数组字符串，如 ["read","write"]。 */
    @Column(name = "scopes", length = 500)
    private String scopes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "revoked", nullable = false)
    private boolean revoked = false;

    /** 脱敏 Key，格式如 ns-1-xxxx****yyyy，供列表展示。 */
    @Column(name = "masked_key", length = 80)
    private String maskedKey;

    /** JPA 要求的无参构造器。 */
    public ApiKeyEntity() {
    }

    /** 全参构造器。 */
    public ApiKeyEntity(String keyId, Integer tenantId, Integer userId, String name,
                        String scopes, Instant createdAt, Instant expiresAt,
                        Instant lastUsedAt, boolean revoked, String maskedKey) {
        this.keyId = keyId;
        this.tenantId = tenantId;
        this.userId = userId;
        this.name = name;
        this.scopes = scopes;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.lastUsedAt = lastUsedAt;
        this.revoked = revoked;
        this.maskedKey = maskedKey;
    }

    // --- getter / setter ---

    public String getKeyId() { return keyId; }
    public void setKeyId(String keyId) { this.keyId = keyId; }

    public Integer getTenantId() { return tenantId; }
    public void setTenantId(Integer tenantId) { this.tenantId = tenantId; }

    public Integer getUserId() { return userId; }
    public void setUserId(Integer userId) { this.userId = userId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getScopes() { return scopes; }
    public void setScopes(String scopes) { this.scopes = scopes; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

    public Instant getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(Instant lastUsedAt) { this.lastUsedAt = lastUsedAt; }

    public boolean isRevoked() { return revoked; }
    public void setRevoked(boolean revoked) { this.revoked = revoked; }

    public String getMaskedKey() { return maskedKey; }
    public void setMaskedKey(String maskedKey) { this.maskedKey = maskedKey; }

    @Override
    public String toString() {
        return "ApiKeyEntity{keyId='" + keyId + "', tenantId=" + tenantId
                + ", userId=" + userId + ", name='" + name + "', scopes='" + scopes
                + "', createdAt=" + createdAt + ", expiresAt=" + expiresAt
                + ", lastUsedAt=" + lastUsedAt + ", revoked=" + revoked
                + ", maskedKey='" + maskedKey + "'}";
    }
}
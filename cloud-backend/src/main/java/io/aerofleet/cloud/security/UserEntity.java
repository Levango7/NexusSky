package io.aerofleet.cloud.security;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 用户的 JPA 持久化实体。
 * <p>
 * 存储用户认证信息（BCrypt 哈希密码）和授权信息（角色、租户归属）。
 * tenant_id 为 null 表示全局管理员，不属于任何特定租户。
 * <p>
 * 与 {@link User} 值对象分离：Entity 负责持久化，值对象负责运行时传递。
 *
 * @see User
 */
@Entity
@Table(name = "app_user")
public class UserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "username", nullable = false, unique = true)
    private String username;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "role", nullable = false)
    private String role;

    @Column(name = "tenant_id")
    private Integer tenantId;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** JPA 要求的无参构造器。 */
    public UserEntity() {
    }

    /** 全参构造器。 */
    public UserEntity(Integer id, String username, String passwordHash, String role,
                      Integer tenantId, boolean enabled, Instant createdAt) {
        this.id = id;
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
        this.tenantId = tenantId;
        this.enabled = enabled;
        this.createdAt = createdAt;
    }

    // --- getter / setter ---

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public Integer getTenantId() { return tenantId; }
    public void setTenantId(Integer tenantId) { this.tenantId = tenantId; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    // --- 与值对象的转换 ---

    /**
     * 转换为不可变值对象 {@link User}。
     * <p>
     * 仅携带运行时所需字段（username、role、tenantId），
     * 不包含 passwordHash 等敏感信息。
     */
    public User toUser() {
        Role roleEnum = Role.valueOf(role);
        return new User(username, roleEnum, tenantId);
    }

    @Override
    public String toString() {
        return "UserEntity{id=" + id + ", username='" + username + "', role=" + role
                + ", tenantId=" + tenantId + ", enabled=" + enabled + ", createdAt=" + createdAt + "}";
    }
}
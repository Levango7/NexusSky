package io.aerofleet.cloud.security;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 租户的 JPA 持久化实体。
 * <p>
 * 租户用于多租户隔离，每个租户有唯一的 code（用于 URL 和 API 标识）。
 * tenant_id 为 null 的用户为全局管理员，不属于任何租户。
 */
@Entity
@Table(name = "tenant")
public class TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "code", nullable = false, unique = true)
    private String code;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** JPA 要求的无参构造器。 */
    public TenantEntity() {
    }

    /** 全参构造器。 */
    public TenantEntity(Integer id, String name, String code, boolean enabled, Instant createdAt) {
        this.id = id;
        this.name = name;
        this.code = code;
        this.enabled = enabled;
        this.createdAt = createdAt;
    }

    // --- getter / setter ---

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    @Override
    public String toString() {
        return "TenantEntity{id=" + id + ", name='" + name + "', code='" + code
                + "', enabled=" + enabled + ", createdAt=" + createdAt + "}";
    }
}
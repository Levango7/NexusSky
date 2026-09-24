package io.aerofleet.cloud.webhook;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Webhook 注册 JPA 实体，用于持久化客户注册的回调 URL 和事件订阅。
 * <p>
 * 客户通过 {@link WebhookController} 注册 webhook，指定回调 URL、HMAC 签名密钥
 * 和订阅的事件类型列表。系统在事件发生时通过 {@link WebhookService#trigger}
 * 主动推送通知到回调 URL。
 * <p>
 * 租户隔离：每个 webhook 绑定注册时的 tenantId，查询和推送时自动过滤。
 */
@Entity
@Table(name = "webhooks")
public class WebhookEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 回调 URL，系统在事件发生时向此 URL 发送 POST 请求。 */
    @Column(name = "url", nullable = false, length = 500)
    private String url;

    /** HMAC-SHA256 签名密钥，用于推送时对 payload 签名，客户可验证请求来源。 */
    @Column(name = "secret", length = 200)
    private String secret;

    /** 订阅的事件类型列表，逗号分隔（如 "device.online,device.offline"）。 */
    @Column(name = "events", length = 500)
    private String events;

    /** 租户 ID（数据隔离）。 */
    @Column(name = "tenant_id")
    private Integer tenantId;

    /** 是否启用，禁用的 webhook 不会被触发推送。 */
    @Column(name = "enabled")
    private Boolean enabled;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public WebhookEntity() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public String getEvents() {
        return events;
    }

    public void setEvents(String events) {
        this.events = events;
    }

    public Integer getTenantId() {
        return tenantId;
    }

    public void setTenantId(Integer tenantId) {
        this.tenantId = tenantId;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
package io.aerofleet.cloud.gateway;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 设备注册 JPA 实体，用于持久化已知设备列表。
 * <p>
 * 仅保存设备标识与基本状态，完整遥测数据仍在内存 {@link DroneSnapshot} 中。
 * 用于重启后恢复已知设备列表和设备 provisioning/auth。
 */
@Entity
@Table(name = "devices")
public class DeviceEntity {

    @Id
    @Column(name = "sysid")
    private Integer sysid;

    @Column(name = "online")
    private Boolean online;

    @Column(name = "last_heartbeat_ms")
    private Long lastHeartbeatMs;

    @Column(name = "first_seen")
    private Instant firstSeen;

    @Column(name = "last_seen")
    private Instant lastSeen;

    /** 设备名称（provisioning 时设置）。 */
    @Column(name = "name")
    private String name;

    /** 设备认证 token（provisioning/auth 时使用）。 */
    @Column(name = "device_token")
    private String deviceToken;

    /** 租户 ID（数据隔离）。 */
    @Column(name = "tenant_id")
    private Integer tenantId;

    public DeviceEntity() {
    }

    public DeviceEntity(Integer sysid) {
        this.sysid = sysid;
        this.firstSeen = Instant.now();
        this.lastSeen = Instant.now();
    }

    public Integer getSysid() {
        return sysid;
    }

    public void setSysid(Integer sysid) {
        this.sysid = sysid;
    }

    public Boolean getOnline() {
        return online;
    }

    public void setOnline(Boolean online) {
        this.online = online;
    }

    public Long getLastHeartbeatMs() {
        return lastHeartbeatMs;
    }

    public void setLastHeartbeatMs(Long lastHeartbeatMs) {
        this.lastHeartbeatMs = lastHeartbeatMs;
    }

    public Instant getFirstSeen() {
        return firstSeen;
    }

    public void setFirstSeen(Instant firstSeen) {
        this.firstSeen = firstSeen;
    }

    public Instant getLastSeen() {
        return lastSeen;
    }

    public void setLastSeen(Instant lastSeen) {
        this.lastSeen = lastSeen;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDeviceToken() {
        return deviceToken;
    }

    public void setDeviceToken(String deviceToken) {
        this.deviceToken = deviceToken;
    }

    public Integer getTenantId() {
        return tenantId;
    }

    public void setTenantId(Integer tenantId) {
        this.tenantId = tenantId;
    }
}
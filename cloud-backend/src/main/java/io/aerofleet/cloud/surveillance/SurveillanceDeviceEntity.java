package io.aerofleet.cloud.surveillance;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 安防设备 JPA 实体，用于持久化 {@link SurveillanceDevice} 配置。
 * <p>
 * 与 {@link SurveillanceDevice} 的关系：
 * <ul>
 *   <li>{@code SurveillanceDevice} 是运行时可变快照对象（含 volatile 字段、final id/vendor）</li>
 *   <li>{@code SurveillanceDeviceEntity} 是 JPA 持久化层对象，通过 {@link #toDevice()} 和
 *       {@link #fromDevice(SurveillanceDevice)} 互相转换</li>
 * </ul>
 * <p>
 * 重启后从数据库恢复设备列表，避免设备配置丢失。
 */
@Entity
@Table(name = "surveillance_device")
public class SurveillanceDeviceEntity {

    @Id
    @Column(name = "id")
    private String id;

    @Column(name = "name")
    private String name;

    @Column(name = "vendor")
    private String vendor;

    @Column(name = "ip")
    private String ip;

    @Column(name = "port")
    private Integer port;

    @Column(name = "username")
    private String username;

    @Convert(converter = PasswordConverter.class)
    @Column(name = "password")
    private String password;

    @Column(name = "status")
    private String status;

    @Convert(converter = StringSetConverter.class)
    @Column(name = "capabilities")
    private Set<String> capabilities = Collections.emptySet();

    @Column(name = "rtsp_url")
    private String rtspUrl;

    @Column(name = "last_heartbeat_ms")
    private Long lastHeartbeatMs;

    @Column(name = "tenant_id")
    private Integer tenantId;

    /** JPA 无参构造器（必需）。 */
    public SurveillanceDeviceEntity() {
    }

    /**
     * 从 {@link SurveillanceDevice} 创建 JPA 实体。
     *
     * @param device 安防设备快照对象
     * @return 对应的 JPA 实体
     */
    public static SurveillanceDeviceEntity fromDevice(SurveillanceDevice device) {
        SurveillanceDeviceEntity entity = new SurveillanceDeviceEntity();
        entity.id = device.id;
        entity.name = device.name;
        entity.vendor = device.vendor.name();
        entity.ip = device.ip;
        entity.port = device.port;
        entity.username = device.username;
        entity.password = device.password;
        entity.status = device.status.name();
        entity.capabilities = device.getCapabilities() == null
                ? Collections.emptySet()
                : new LinkedHashSet<>(device.getCapabilities());
        entity.rtspUrl = device.rtspUrl;
        entity.lastHeartbeatMs = device.lastHeartbeatMs;
        entity.tenantId = device.tenantId;
        return entity;
    }

    /**
     * 转换为 {@link SurveillanceDevice} 运行时快照对象。
     *
     * @return 对应的安防设备对象
     */
    public SurveillanceDevice toDevice() {
        SurveillanceDevice device = new SurveillanceDevice(
                this.id, this.name,
                SurveillanceDevice.Vendor.valueOf(this.vendor),
                this.ip, this.port,
                this.username, this.password);
        device.status = SurveillanceDevice.Status.valueOf(this.status);
        device.setCapabilities(this.capabilities);
        device.rtspUrl = this.rtspUrl;
        device.lastHeartbeatMs = this.lastHeartbeatMs != null ? this.lastHeartbeatMs : 0L;
        device.tenantId = this.tenantId;
        return device;
    }

    // ===== getter / setter =====

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getVendor() {
        return vendor;
    }

    public void setVendor(String vendor) {
        this.vendor = vendor;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Set<String> getCapabilities() {
        return capabilities;
    }

    public void setCapabilities(Set<String> capabilities) {
        this.capabilities = capabilities;
    }

    public String getRtspUrl() {
        return rtspUrl;
    }

    public void setRtspUrl(String rtspUrl) {
        this.rtspUrl = rtspUrl;
    }

    public Long getLastHeartbeatMs() {
        return lastHeartbeatMs;
    }

    public void setLastHeartbeatMs(Long lastHeartbeatMs) {
        this.lastHeartbeatMs = lastHeartbeatMs;
    }

    public Integer getTenantId() {
        return tenantId;
    }

    public void setTenantId(Integer tenantId) {
        this.tenantId = tenantId;
    }
}
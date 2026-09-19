package io.aerofleet.cloud.surveillance;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 安防设备数据模型。
 * <p>
 * 怀业信息科技（应急救援公司）产品兼容海康威视、大华、宇视三大安防厂商协议。
 * ONVIF 是这三家都支持的标准协议，用于设备发现/视频流获取/PTZ控制/事件订阅。
 * <p>
 * 该类为可变快照（心跳/状态/能力随时间变化），但 {@code id} 与 {@code vendor}
 * 在构造后不可变，便于在注册表中作为稳定键使用。
 */
public class SurveillanceDevice {

    /** 安防设备厂商枚举。 */
    public enum Vendor {
        /** 海康威视。 */
        HIKVISION,
        /** 大华。 */
        DAHUA,
        /** 宇视。 */
        UNIVIEW
    }

    /** 设备在线状态。 */
    public enum Status {
        ONLINE,
        OFFLINE
    }

    /** 设备唯一标识（外部传入，注册表以此为键）。 */
    public final String id;
    /** 设备名称（人类可读）。 */
    public volatile String name;
    /** 厂商。 */
    public final Vendor vendor;
    /** 设备 IP 地址。 */
    public volatile String ip;
    /** ONVIF 服务端口（默认 80）。 */
    public volatile int port;
    /** 登录用户名。 */
    public volatile String username;
    /** 登录密码（明文，由调用方负责加密传输）。 */
    public volatile String password;
    /** 在线状态。 */
    public volatile Status status;
    /** 设备能力集合（如 "Media"、"PTZ"、"Events"、"Device"）。 */
    private volatile Set<String> capabilities = Collections.emptySet();
    /** RTSP 流地址（由 OnvifClient 解析得到）。 */
    public volatile String rtspUrl;
    /** 最近一次心跳时间戳（System.currentTimeMillis()）。 */
    public volatile long lastHeartbeatMs;

    public SurveillanceDevice(String id, String name, Vendor vendor, String ip, int port,
                              String username, String password) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("device id must not be blank");
        }
        if (vendor == null) {
            throw new IllegalArgumentException("vendor must not be null");
        }
        if (ip == null || ip.isBlank()) {
            throw new IllegalArgumentException("ip must not be blank");
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("port must be in [1, 65535]");
        }
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username must not be blank");
        }
        this.id = id;
        this.name = name;
        this.vendor = vendor;
        this.ip = ip;
        this.port = port;
        this.username = username;
        this.password = password;
        this.status = Status.ONLINE;
        this.lastHeartbeatMs = System.currentTimeMillis();
    }

    /** 获取设备能力集合（不可变视图）。 */
    public Set<String> getCapabilities() {
        return capabilities;
    }

    /** 设置设备能力集合（防御性拷贝）。 */
    public void setCapabilities(Set<String> capabilities) {
        this.capabilities = capabilities == null
                ? Collections.emptySet()
                : Collections.unmodifiableSet(new LinkedHashSet<>(capabilities));
    }

    /** 触发一次心跳：更新 lastHeartbeatMs 并标记 ONLINE。 */
    public synchronized void heartbeat() {
        this.lastHeartbeatMs = System.currentTimeMillis();
        this.status = Status.ONLINE;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SurveillanceDevice)) return false;
        SurveillanceDevice that = (SurveillanceDevice) o;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "SurveillanceDevice{id=" + id
                + ", name=" + name
                + ", vendor=" + vendor
                + ", ip=" + ip + ":" + port
                + ", status=" + status + "}";
    }
}
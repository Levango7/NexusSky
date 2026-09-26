package io.aerofleet.cloud.surveillance;

import java.util.Objects;

/**
 * 设备信息 DTO（厂商适配层通用数据结构）。
 * <p>
 * 由 {@link VendorAdapter#discover} 返回，作为厂商无关的设备描述对象。
 * 与 {@link SurveillanceDevice} 的区别：DeviceInfo 是适配器层的轻量数据传输对象，
 * 不包含心跳/状态等运行时信息，仅描述设备发现时的静态属性。
 * <p>
 * 可通过 {@link #toSurveillanceDevice()} 转换为注册表使用的 {@link SurveillanceDevice}。
 */
public class DeviceInfo {

    /** 设备唯一标识。 */
    public final String deviceId;
    /** 设备名称（人类可读）。 */
    public final String name;
    /** 厂商类型。 */
    public final Vendor vendor;
    /** 设备 IP 地址。 */
    public final String ip;
    /** 设备服务端口。 */
    public final int port;
    /** 登录用户名。 */
    public final String username;
    /** 登录密码。 */
    public final String password;

    public DeviceInfo(String deviceId, String name, Vendor vendor, String ip, int port,
                      String username, String password) {
        if (deviceId == null || deviceId.isBlank()) {
            throw new IllegalArgumentException("deviceId must not be blank");
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
        this.deviceId = deviceId;
        this.name = name == null ? "" : name;
        this.vendor = vendor;
        this.ip = ip;
        this.port = port;
        this.username = username == null ? "" : username;
        this.password = password == null ? "" : password;
    }

    /**
     * 转换为 {@link SurveillanceDevice}（用于注册到 {@link SurveillanceDeviceRegistry}）。
     *
     * @return 等价的 SurveillanceDevice 实例
     */
    public SurveillanceDevice toSurveillanceDevice() {
        SurveillanceDevice.Vendor deviceVendor = switch (vendor) {
            case HIKVISION -> SurveillanceDevice.Vendor.HIKVISION;
            case DAHUA -> SurveillanceDevice.Vendor.DAHUA;
            case UNIVIEW -> SurveillanceDevice.Vendor.UNIVIEW;
            case ONVIF, GENERIC -> SurveillanceDevice.Vendor.ONVIF; // ONVIF 标准协议 fallback
        };
        return new SurveillanceDevice(deviceId, name, deviceVendor, ip, port, username, password);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DeviceInfo)) return false;
        DeviceInfo that = (DeviceInfo) o;
        return deviceId.equals(that.deviceId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(deviceId);
    }

    @Override
    public String toString() {
        return "DeviceInfo{deviceId=" + deviceId
                + ", name=" + name
                + ", vendor=" + vendor
                + ", ip=" + ip + ":" + port + "}";
    }
}
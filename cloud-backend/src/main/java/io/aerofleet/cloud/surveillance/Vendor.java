package io.aerofleet.cloud.surveillance;

/**
 * 安防设备厂商枚举（协议适配层）。
 * <p>
 * 用于 {@link VendorAdapterRegistry} 按厂商路由到对应适配器。
 * 与 {@link SurveillanceDevice.Vendor} 的区别：本枚举额外包含 {@code ONVIF}，
 * 作为标准协议 fallback，当设备厂商未知或不支持私有协议时使用。
 */
public enum Vendor {
    /** 海康威视（ISAPI + SDK 私有协议）。 */
    HIKVISION,
    /** 大华（DHSDK + ONVIF 私有协议）。 */
    DAHUA,
    /** 宇视（UNIVIEW SDK + ONVIF 私有协议）。 */
    UNIVIEW,
    /** ONVIF 标准协议（fallback，适用于所有支持 ONVIF 的设备）。 */
    ONVIF,
    /** 通用/未知厂商（当设备厂商无法识别时使用，自动降级到 ONVIF fallback）。 */
    GENERIC
}
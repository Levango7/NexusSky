package io.aerofleet.cloud.surveillance;

import io.aerofleet.cloud.surveillance.onvif.OnvifSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 厂商适配器注册与路由中心。
 * <p>
 * 通过 Spring 自动注入所有 {@link VendorAdapter} 实现，按 {@link Vendor} 枚举路由到对应适配器。
 * 上层业务调用 {@link #getAdapter(Vendor)} 获取适配器后，通过统一接口操作设备。
 * <p>
 * 路由策略：
 * <ul>
 *   <li>HIKVISION -> {@link HikAdapter}</li>
 *   <li>DAHUA -> {@link DahuaAdapter}</li>
 *   <li>UNIVIEW -> {@link UniviewAdapter}</li>
 *   <li>ONVIF -> {@link OnvifVendorAdapter}（fallback）</li>
 * </ul>
 * <p>
 * 当请求的厂商适配器不存在时，自动降级到 ONVIF fallback 适配器。
 *
 * <h2>live ONVIF 下的路由</h2>
 *
 * <p>{@code aerofleet.onvif.mode=live} 时，海康/大华/宇视三家的<b>私有 SDK
 * 适配器仍是模拟实现</b>，把它们当成真实通道会给出"按厂商规范推导、从未
 * 向设备确认"的地址。此时统一路由到 ONVIF live 适配器（这三家的设备都支持
 * ONVIF），取流地址才会带 {@code deviceVerified=true}。
 * 模拟模式（默认）路由完全不变。
 */
@Component
public final class VendorAdapterRegistry {

    private static final Logger log = LoggerFactory.getLogger(VendorAdapterRegistry.class);

    /** 厂商 -> 适配器 映射表。 */
    private final Map<Vendor, VendorAdapter> adapterMap;

    /** live ONVIF 是否接管海康/大华/宇视的私有适配器路由。 */
    private final boolean onvifLive;

    /**
     * 模拟路由（既有单测与离线场景）。
     *
     * <p>Spring 容器使用带 {@link OnvifSettings} 的构造器（{@code @Autowired}）。
     */
    public VendorAdapterRegistry(List<VendorAdapter> adapters) {
        this(adapters, null);
    }

    /**
     * 通过 Spring 自动注入所有 {@link VendorAdapter} Bean。
     * <p>
     * 构造时按各适配器的 {@link VendorAdapter#getVendor()} 建立路由映射。
     *
     * @param adapters Spring 容器中所有 VendorAdapter 实现
     * @param settings ONVIF 配置（决定是否由 live 适配器接管私有厂商路由）
     */
    @Autowired
    public VendorAdapterRegistry(List<VendorAdapter> adapters, OnvifSettings settings) {
        Map<Vendor, VendorAdapter> map = new EnumMap<>(Vendor.class);
        if (adapters != null) {
            for (VendorAdapter adapter : adapters) {
                Vendor vendor = adapter.getVendor();
                map.put(vendor, adapter);
                log.info("注册厂商适配器: {} -> {}", vendor, adapter.getClass().getSimpleName());
            }
        }
        this.adapterMap = Collections.unmodifiableMap(map);
        this.onvifLive = settings != null && settings.isLive();
        log.info("ONVIF live 模式={}（live 时海康/大华/宇视将路由到 ONVIF 适配器，私有 SDK 适配器仍为模拟实现）",
                onvifLive);
        log.info("VendorAdapterRegistry 初始化完成, 已注册 {} 个适配器", adapterMap.size());
    }

    /**
     * 按厂商获取适配器。
     * <p>
     * 若请求的厂商适配器不存在，自动降级到 ONVIF fallback 适配器。
     *
     * @param vendor 厂商枚举
     * @return 对应的适配器实例；若 ONVIF fallback 也不存在则返回 null
     */
    public VendorAdapter getAdapter(Vendor vendor) {
        if (vendor == null) {
            throw new IllegalArgumentException("vendor must not be null");
        }
        // live ONVIF：私有 SDK 适配器仍是模拟实现，统一交给真实协议实现，
        // 否则真实摄像头会拿到"按规范推导、未向设备确认"的地址。
        if (onvifLive && (vendor == Vendor.HIKVISION || vendor == Vendor.DAHUA
                || vendor == Vendor.UNIVIEW)) {
            VendorAdapter onvif = adapterMap.get(Vendor.ONVIF);
            if (onvif != null) {
                return onvif;
            }
        }
        VendorAdapter adapter = adapterMap.get(vendor);
        if (adapter != null) {
            return adapter;
        }
        // 降级到 ONVIF fallback
        log.warn("厂商 {} 无注册适配器, 降级到 ONVIF fallback", vendor);
        return adapterMap.get(Vendor.ONVIF);
    }

    /**
     * 按设备获取适配器。
     * <p>
     * 根据设备的 {@link SurveillanceDevice.Vendor} 映射到 {@link Vendor} 枚举，
     * 再通过 {@link #getAdapter(Vendor)} 获取适配器。
     *
     * @param device 安防设备
     * @return 对应的适配器实例
     */
    public VendorAdapter getAdapter(SurveillanceDevice device) {
        if (device == null) {
            throw new IllegalArgumentException("device must not be null");
        }
        Vendor vendor = mapDeviceVendor(device.vendor);
        return getAdapter(vendor);
    }

    /**
     * 获取所有已注册的厂商适配器（不可变视图）。
     *
     * @return 厂商 -> 适配器 映射
     */
    public Map<Vendor, VendorAdapter> getAllAdapters() {
        return adapterMap;
    }

    /**
     * 检查指定厂商是否有注册适配器。
     *
     * @param vendor 厂商枚举
     * @return true 若存在注册适配器
     */
    public boolean hasAdapter(Vendor vendor) {
        return vendor != null && adapterMap.containsKey(vendor);
    }

    // ===== 内部工具方法 =====

    /**
     * 将 {@link SurveillanceDevice.Vendor} 映射到 {@link Vendor} 枚举。
     * <p>
     * SurveillanceDevice.Vendor 有 HIKVISION/DAHUA/UNIVIEW/ONVIF/GB28181 五种，
     * 映射到 Vendor 枚举后可通过 fallback 机制处理。
     */
    private static Vendor mapDeviceVendor(SurveillanceDevice.Vendor deviceVendor) {
        if (deviceVendor == null) {
            return Vendor.ONVIF;
        }
        return switch (deviceVendor) {
            case HIKVISION -> Vendor.HIKVISION;
            case DAHUA -> Vendor.DAHUA;
            case UNIVIEW -> Vendor.UNIVIEW;
            case ONVIF -> Vendor.ONVIF;
            case GB28181 -> Vendor.GB28181;
        };
    }
}
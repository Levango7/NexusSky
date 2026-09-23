package io.aerofleet.cloud.surveillance;

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
 */
@Component
public final class VendorAdapterRegistry {

    private static final Logger log = LoggerFactory.getLogger(VendorAdapterRegistry.class);

    /** 厂商 -> 适配器 映射表。 */
    private final Map<Vendor, VendorAdapter> adapterMap;

    /**
     * 通过 Spring 自动注入所有 {@link VendorAdapter} Bean。
     * <p>
     * 构造时按各适配器的 {@link VendorAdapter#getVendor()} 建立路由映射。
     *
     * @param adapters Spring 容器中所有 VendorAdapter 实现
     */
    @Autowired
    public VendorAdapterRegistry(List<VendorAdapter> adapters) {
        Map<Vendor, VendorAdapter> map = new EnumMap<>(Vendor.class);
        if (adapters != null) {
            for (VendorAdapter adapter : adapters) {
                Vendor vendor = adapter.getVendor();
                map.put(vendor, adapter);
                log.info("注册厂商适配器: {} -> {}", vendor, adapter.getClass().getSimpleName());
            }
        }
        this.adapterMap = Collections.unmodifiableMap(map);
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
     * SurveillanceDevice.Vendor 只有 HIKVISION/DAHUA/UNIVIEW 三种，
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
        };
    }
}
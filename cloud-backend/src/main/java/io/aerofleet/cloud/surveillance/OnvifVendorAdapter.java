package io.aerofleet.cloud.surveillance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * ONVIF 标准协议适配器（fallback）。
 * <p>
 * 包装现有 {@link OnvifClient} 作为 ONVIF 标准协议的 fallback 适配器。
 * 当设备厂商未知或不支持厂商私有协议时，通过 ONVIF 标准协议进行设备管理。
 * <p>
 * 不修改 {@link OnvifClient} 的任何代码，仅做适配层包装，
 * 将 {@link OnvifClient} 的方法签名适配到 {@link VendorAdapter} 接口。
 */
@Component
public final class OnvifVendorAdapter implements VendorAdapter {

    private static final Logger log = LoggerFactory.getLogger(OnvifVendorAdapter.class);

    private final OnvifClient onvifClient;

    @Autowired
    public OnvifVendorAdapter(OnvifClient onvifClient) {
        this.onvifClient = onvifClient;
    }

    @Override
    public Vendor getVendor() {
        return Vendor.ONVIF;
    }

    @Override
    public List<SurveillanceDevice> discover(String subnet) {
        log.info("ONVIF 设备发现 subnet={} (fallback)", subnet);
        return onvifClient.discoverDevices(subnet);
    }

    @Override
    public String getStreamUrl(SurveillanceDevice device, int channel) {
        validateDevice(device);
        log.info("ONVIF 获取流 URL device={} channel={} (fallback)", device.id, channel);
        return onvifClient.getRtspUrl(device.ip, device.port, device.username, device.password, channel);
    }

    @Override
    public String ptzControl(SurveillanceDevice device, String cmd) {
        validateDevice(device);
        log.info("ONVIF PTZ 控制 device={} cmd={} (fallback)", device.id, cmd);
        return onvifClient.ptzControl(device.ip, device.port, device.username, device.password, cmd);
    }

    @Override
    public String subscribeEvents(SurveillanceDevice device, Consumer<String> callback) {
        validateDevice(device);
        log.info("ONVIF 事件订阅 device={} (fallback)", device.id);
        return onvifClient.subscribeEvents(device.ip, device.port, device.username, device.password, callback);
    }

    @Override
    public void unsubscribeEvents(String handle) {
        log.info("ONVIF 取消事件订阅 handle={} (fallback)", handle);
        onvifClient.unsubscribeEvents(handle);
    }

    @Override
    public Set<String> getCapabilities(SurveillanceDevice device) {
        validateDevice(device);
        log.info("ONVIF 能力查询 device={} (fallback)", device.id);
        return onvifClient.getDeviceCapabilities(device.ip, device.port, device.username, device.password);
    }

    // ===== 内部工具方法 =====

    private static void validateDevice(SurveillanceDevice device) {
        if (device == null) {
            throw new IllegalArgumentException("device must not be null");
        }
        if (device.ip == null || device.ip.isBlank()) {
            throw new IllegalArgumentException("device ip must not be blank");
        }
    }
}
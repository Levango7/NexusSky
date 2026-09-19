package io.aerofleet.cloud.surveillance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 安防设备注册表（线程安全）。
 * <p>
 * 以设备 ID 为键，使用 {@link ConcurrentHashMap} 保证并发读写安全。
 * <p>
 * 与 {@code io.aerofleet.cloud.gateway.DeviceRegistry} 风格保持一致：
 * <ul>
 *   <li>纯内存实现，重启后状态丢失（与 DeviceRegistry 默认行为一致）</li>
 *   <li>支持心跳更新与超时清理（{@link #pruneStaleDevices(long)}）</li>
 *   <li>支持按厂商筛选（{@link #listDevicesByVendor(SurveillanceDevice.Vendor)}）</li>
 * </ul>
 */
@Component
public class SurveillanceDeviceRegistry {

    private static final Logger log = LoggerFactory.getLogger(SurveillanceDeviceRegistry.class);

    private final Map<String, SurveillanceDevice> devices = new ConcurrentHashMap<>();

    /**
     * 注册设备。若同 ID 设备已存在则覆盖。
     *
     * @param device 待注册设备（id 不可为空）
     * @return 被注册的设备
     */
    public SurveillanceDevice register(SurveillanceDevice device) {
        if (device == null) {
            throw new IllegalArgumentException("device must not be null");
        }
        devices.put(device.id, device);
        log.info("Surveillance device registered: id={} vendor={} ip={}:{}",
                device.id, device.vendor, device.ip, device.port);
        return device;
    }

    /**
     * 注销设备。
     *
     * @param deviceId 设备 ID
     * @return 被注销的设备；若不存在返回 null
     */
    public SurveillanceDevice unregister(String deviceId) {
        if (deviceId == null) return null;
        SurveillanceDevice removed = devices.remove(deviceId);
        if (removed != null) {
            log.info("Surveillance device unregistered: id={}", deviceId);
        }
        return removed;
    }

    /**
     * 获取设备。
     *
     * @param deviceId 设备 ID
     * @return 设备；若不存在返回 null
     */
    public SurveillanceDevice getDevice(String deviceId) {
        if (deviceId == null) return null;
        return devices.get(deviceId);
    }

    /**
     * 列出所有设备（按 id 字典序排序）。
     *
     * @return 设备列表（不可变副本）
     */
    public List<SurveillanceDevice> listDevices() {
        List<SurveillanceDevice> snapshot = devices.values().stream()
                .sorted(Comparator.comparing(d -> d.id))
                .collect(Collectors.toCollection(ArrayList::new));
        return Collections.unmodifiableList(snapshot);
    }

    /**
     * 按厂商筛选设备。
     *
     * @param vendor 厂商
     * @return 该厂商的所有设备列表
     */
    public List<SurveillanceDevice> listDevicesByVendor(SurveillanceDevice.Vendor vendor) {
        if (vendor == null) return new ArrayList<>();
        return devices.values().stream()
                .filter(d -> d.vendor == vendor)
                .sorted(Comparator.comparing(d -> d.id))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    /**
     * 更新设备心跳：刷新 lastHeartbeatMs 并标记 ONLINE。
     *
     * @param deviceId 设备 ID
     * @return true 若设备存在并已更新
     */
    public boolean updateHeartbeat(String deviceId) {
        SurveillanceDevice d = getDevice(deviceId);
        if (d == null) return false;
        d.heartbeat();
        return true;
    }

    /**
     * 清理超时设备：将 lastHeartbeatMs 距今超过 timeoutMs 的设备标记为 OFFLINE。
     * <p>
     * 注意：本方法不删除设备，仅将状态置为 OFFLINE，便于上层持续显示设备列表。
     *
     * @param timeoutMs 超时阈值（毫秒）
     * @return 被标记为 OFFLINE 的设备 ID 列表
     */
    public List<String> pruneStaleDevices(long timeoutMs) {
        if (timeoutMs < 0) {
            throw new IllegalArgumentException("timeoutMs must be >= 0");
        }
        long now = System.currentTimeMillis();
        List<String> stale = new ArrayList<>();
        for (SurveillanceDevice d : devices.values()) {
            devices.computeIfPresent(d.id, (k, dev) -> {
                if (dev.status == SurveillanceDevice.Status.ONLINE
                        && dev.lastHeartbeatMs > 0
                        && now - dev.lastHeartbeatMs > timeoutMs) {
                    dev.status = SurveillanceDevice.Status.OFFLINE;
                    stale.add(k);
                    log.warn("Surveillance device stale (offline): id={} lastHeartbeatMs={} ago={}ms",
                            k, dev.lastHeartbeatMs, now - dev.lastHeartbeatMs);
                }
                return dev;
            });
        }
        return stale;
    }

    /** 当前注册设备总数。 */
    public int size() {
        return devices.size();
    }
}
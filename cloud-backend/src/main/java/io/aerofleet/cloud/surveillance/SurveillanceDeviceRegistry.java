package io.aerofleet.cloud.surveillance;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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
 * 混合模式：内存缓存 + JPA 持久化。
 * <ul>
 *   <li>所有读操作从内存缓存读取，保证低延迟</li>
 *   <li>register/unregister/pruneStaleDevices 同时写内存和数据库</li>
 *   <li>心跳更新（{@link #updateHeartbeat(String)}）仅写内存，避免高频数据库写入</li>
 *   <li>启动时通过 {@link #loadFromDatabase()} 从数据库恢复设备列表</li>
 * </ul>
 * <p>
 * 当未配置 JPA（如单元测试直接实例化）时，自动降级为纯内存模式。
 */
@Component
public class SurveillanceDeviceRegistry {

    private static final Logger log = LoggerFactory.getLogger(SurveillanceDeviceRegistry.class);

    private SurveillanceDeviceRepository repository;

    private final Map<String, SurveillanceDevice> devices = new ConcurrentHashMap<>();

    public SurveillanceDeviceRegistry() {
        this.repository = null;
    }

    @Autowired
    public SurveillanceDeviceRegistry(@Autowired(required = false) SurveillanceDeviceRepository repository) {
        this.repository = repository;
    }

    /**
     * 启动时从数据库加载设备到内存缓存。
     * <p>
     * 若 repository 未注入（纯内存模式），则跳过。
     */
    @PostConstruct
    public void loadFromDatabase() {
        if (repository == null) {
            log.info("SurveillanceDeviceRegistry: 纯内存模式（无 JPA repository）");
            return;
        }
        try {
            List<SurveillanceDeviceEntity> entities = repository.findAll();
            for (SurveillanceDeviceEntity entity : entities) {
                SurveillanceDevice device = entity.toDevice();
                devices.put(device.id, device);
            }
            log.info("SurveillanceDeviceRegistry: 从数据库恢复 {} 台安防设备", entities.size());
        } catch (Exception e) {
            log.warn("SurveillanceDeviceRegistry: 从数据库加载设备失败: {}", e.getMessage());
        }
    }

    /**
     * 注册设备。若同 ID 设备已存在则覆盖。
     * <p>
     * 同时写入内存缓存和数据库。
     *
     * @param device 待注册设备（id 不可为空）
     * @return 被注册的设备
     */
    @Transactional
    public SurveillanceDevice register(SurveillanceDevice device) {
        if (device == null) {
            throw new IllegalArgumentException("device must not be null");
        }
        devices.put(device.id, device);
        persistDevice(device);
        log.info("Surveillance device registered: id={} vendor={} ip={}:{}",
                device.id, device.vendor, device.ip, device.port);
        return device;
    }

    /**
     * 注销设备。
     * <p>
     * 同时从内存缓存和数据库删除。
     *
     * @param deviceId 设备 ID
     * @return 被注销的设备；若不存在返回 null
     */
    @Transactional
    public SurveillanceDevice unregister(String deviceId) {
        if (deviceId == null) return null;
        SurveillanceDevice removed = devices.remove(deviceId);
        if (removed != null) {
            deleteDeviceFromDb(deviceId);
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
     * <p>
     * 仅更新内存缓存，不写数据库（心跳频率较高，避免高频 DB 写入）。
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
     * 同时更新内存缓存和数据库中的设备状态。
     * <p>
     * 注意：本方法不删除设备，仅将状态置为 OFFLINE，便于上层持续显示设备列表。
     *
     * @param timeoutMs 超时阈值（毫秒）
     * @return 被标记为 OFFLINE 的设备 ID 列表
     */
    @Transactional
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
        // 批量同步离线状态到数据库
        if (!stale.isEmpty()) {
            persistStaleStatus(stale);
        }
        return stale;
    }

    /** 当前注册设备总数。 */
    public int size() {
        return devices.size();
    }

    // ===== 内部持久化方法 =====

    /**
     * 将设备持久化到数据库。
     * <p>
     * 若 repository 未注入则跳过（纯内存模式兼容）。
     */
    private void persistDevice(SurveillanceDevice device) {
        if (repository == null) return;
        try {
            SurveillanceDeviceEntity entity = SurveillanceDeviceEntity.fromDevice(device);
            repository.save(entity);
        } catch (Exception e) {
            log.warn("安防设备持久化失败 id={}: {}", device.id, e.getMessage());
        }
    }

    /**
     * 从数据库删除设备。
     * <p>
     * 若 repository 未注入则跳过。
     */
    private void deleteDeviceFromDb(String deviceId) {
        if (repository == null) return;
        try {
            repository.deleteById(deviceId);
        } catch (Exception e) {
            log.warn("安防设备数据库删除失败 id={}: {}", deviceId, e.getMessage());
        }
    }

    /**
     * 批量将离线设备状态同步到数据库。
     * <p>
     * 若 repository 未注入则跳过。
     */
    private void persistStaleStatus(List<String> staleIds) {
        if (repository == null) return;
        try {
            for (String id : staleIds) {
                repository.findById(id).ifPresent(entity -> {
                    entity.setStatus(SurveillanceDevice.Status.OFFLINE.name());
                    repository.save(entity);
                });
            }
        } catch (Exception e) {
            log.warn("安防设备离线状态同步失败: {}", e.getMessage());
        }
    }
}

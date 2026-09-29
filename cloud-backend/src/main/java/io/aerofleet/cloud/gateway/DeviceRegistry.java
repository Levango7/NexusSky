package io.aerofleet.cloud.gateway;

import io.aerofleet.cloud.security.TenantContext;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 设备注册表，以 MAVLink systemId 为键。
 * <p>
 * 快照在离线期间保留（机队列表持续显示），可选 JPA 持久化：
 * <ul>
 *   <li>{@code aerofleet.device-registry.persist=false}（默认）：纯内存，向后兼容。</li>
 *   <li>{@code aerofleet.device-registry.persist=true}：同时持久化到 H2/JPA，
 *       重启后恢复已知设备列表，支持设备 provisioning/auth。</li>
 * </ul>
 */
@Component
public class DeviceRegistry {

    private static final Logger log = LoggerFactory.getLogger(DeviceRegistry.class);

    @Value("${aerofleet.heartbeat-timeout-seconds:10}")
    private int heartbeatTimeoutSeconds;

    @Value("${aerofleet.device-registry.persist:false}")
    private boolean persist;

    @Autowired(required = false)
    private DeviceRepository repository;

    private final Map<Integer, DroneSnapshot> drones = new ConcurrentHashMap<>();

    /**
     * 启动时从数据库恢复已知设备列表到内存缓存。
     * <p>
     * 所有恢复的设备初始状态为 offline，等待心跳确认后转为 online。
     * 当 persist=false 或 repository 不可用时直接 return，不影响现有行为。
     */
    @PostConstruct
    public void restoreFromRepository() {
        if (!persist || repository == null) {
            return;
        }
        try {
            List<DeviceEntity> entities = repository.findAll();
            int count = 0;
            for (DeviceEntity entity : entities) {
                DroneSnapshot snapshot = new DroneSnapshot(entity.getSysid());
                snapshot.online = false;
                snapshot.tenantId = entity.getTenantId();
                drones.put(entity.getSysid(), snapshot);
                count++;
            }
            log.info("Restored {} devices from repository (all offline, awaiting heartbeat)", count);
        } catch (Exception e) {
            log.warn("设备恢复失败，降级为空缓存: {}", e.getMessage());
        }
    }

    /** Get or create the snapshot for a systemId (called from the receive thread). */
    public DroneSnapshot registerIfAbsent(int sysid) {
        return drones.computeIfAbsent(sysid, id -> {
            DroneSnapshot s = new DroneSnapshot(id);
            s.online = true;
            s.tenantId = resolveTenantFor(id);
            log.info("Drone registered: sysid={} tenantId={}", id, s.tenantId);
            if (persist && repository != null) {
                try {
                    DeviceEntity entity = repository.findById(id)
                            .orElseGet(() -> new DeviceEntity(id));
                    entity.setOnline(true);
                    entity.setLastSeen(java.time.Instant.now());
                    if (s.tenantId != null) {
                        entity.setTenantId(s.tenantId);
                    }
                    repository.save(entity);
                } catch (Exception e) {
                    log.warn("设备持久化失败 sysid={}: {}", id, e.getMessage());
                }
            }
            return s;
        });
    }

    public DroneSnapshot get(int sysid) {
        DroneSnapshot snapshot = drones.get(sysid);
        if (snapshot == null) {
            return null;
        }
        return isVisibleTo(snapshot) ? snapshot : null;
    }

    /**
     * 设备的归属租户，供服务端投递路径（WS 广播、事件监听）使用——这些线程没有请求上下文，
     * 不能走 {@link #get(int)} 的可见性过滤。
     *
     * @return 归属租户 ID；设备未知或未归属时为 null
     */
    public Integer tenantOf(int sysid) {
        DroneSnapshot snapshot = drones.get(sysid);
        return snapshot == null ? null : snapshot.tenantId;
    }

    /**
     * 该 sysid 是否是本机队里真实存在的无人机。
     * <p>
     * 用于区分「设备但未归属租户」与「压根不是设备（基站/卫星/地面中继等基础设施）」：
     * 前者的数据不该出现在任何租户的界面上，后者本就没有租户归属，属公共态势。
     *
     * @param sysid 待判定的 system id
     * @return 已知设备返回 true
     */
    public boolean isKnownDevice(int sysid) {
        return drones.containsKey(sysid);
    }

    /** All known drones, sorted by sysid. Includes offline ones. */
    public List<DroneSnapshot> all() {
        return drones.values().stream()
                .filter(this::isVisibleTo)
                .sorted(Comparator.comparingInt(s -> s.sysid))
                .collect(Collectors.toList());
    }

    /**
     * Sysids with no tenant assigned (visible only to the global-admin context).
     * Used by provisioning to find devices that still need ownership binding.
     */
    public List<Integer> unassigned() {
        return drones.values().stream()
                .filter(s -> s.tenantId == null)
                .map(s -> s.sysid)
                .sorted()
                .collect(Collectors.toList());
    }

    /**
     * Bind a device to a tenant. Applies to the in-memory snapshot and, when
     * {@code aerofleet.device-registry.persist=true}, to the persisted row.
     *
     * @return false when the device is unknown (never seen and not persisted)
     */
    public boolean assignTenant(int sysid, Integer tenantId) {
        DroneSnapshot snapshot = drones.get(sysid);
        if (snapshot == null && !(persist && repository != null && repository.existsById(sysid))) {
            return false;
        }
        if (snapshot != null) {
            snapshot.tenantId = tenantId;
        }
        if (persist && repository != null) {
            try {
                DeviceEntity entity = repository.findById(sysid).orElseGet(() -> {
                    DeviceEntity created = new DeviceEntity(sysid);
                    created.setFirstSeen(java.time.Instant.now());
                    return created;
                });
                entity.setTenantId(tenantId);
                repository.save(entity);
            } catch (Exception e) {
                log.warn("设备租户归属持久化失败 sysid={}: {}", sysid, e.getMessage());
            }
        }
        log.info("Device tenant assigned: sysid={} tenantId={}", sysid, tenantId);
        return true;
    }

    /**
     * Tenant for a newly created snapshot: the request context when present
     * (REST-initiated registration), otherwise the persisted row's tenant so an
     * offline device that was already assigned does not lose its ownership on
     * the next heartbeat. The UDP receive thread has no request context, so it
     * takes the second branch.
     */
    private Integer resolveTenantFor(int sysid) {
        Integer contextTenant = TenantContext.getEffectiveTenantId();
        if (contextTenant != null) {
            return contextTenant;
        }
        if (persist && repository != null) {
            try {
                return repository.findById(sysid)
                        .map(DeviceEntity::getTenantId)
                        .orElse(null);
            } catch (Exception e) {
                log.warn("读取设备归属失败 sysid={}: {}", sysid, e.getMessage());
            }
        }
        return null;
    }

    /**
     * Tenant visibility. A null effective tenant means the global-admin context
     * (including dev-mode, where no TenantFilter runs) and sees everything.
     * A device with no tenant is <em>unassigned</em> and must not be visible or
     * controllable by any tenant — UDP-registered devices get their snapshot
     * created on the receive thread, where there is no request context.
     */
    private boolean isVisibleTo(DroneSnapshot snapshot) {
        Integer tenantId = TenantContext.getEffectiveTenantId();
        return tenantId == null || tenantId.equals(snapshot.tenantId);
    }

    /**
     * Sweep run by the background scanner: mark snapshots stale when no
     * HEARTBEAT arrived within the timeout window. Returns the sysids that
     * transitioned online -> offline on this pass.
     */
    public List<Integer> sweepOffline() {
        long cutoff = heartbeatTimeoutSeconds * 1000L;
        return drones.values().stream()
                .filter(s -> s.online && s.lastHeartbeatMs > 0
                        && System.currentTimeMillis() - s.lastHeartbeatMs > cutoff)
                .peek(s -> {
                    s.online = false;
                    log.warn("Drone offline (heartbeat timeout {}s): sysid={}",
                            heartbeatTimeoutSeconds, s.sysid);
                    if (persist && repository != null) {
                        try {
                            repository.findById(s.sysid).ifPresent(entity -> {
                                entity.setOnline(false);
                                repository.save(entity);
                            });
                        } catch (Exception e) {
                            log.warn("设备离线持久化失败 sysid={}: {}", s.sysid, e.getMessage());
                        }
                    }
                })
                .map(s -> s.sysid)
                .collect(Collectors.toList());
    }
}

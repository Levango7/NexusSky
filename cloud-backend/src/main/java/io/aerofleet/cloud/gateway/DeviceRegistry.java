package io.aerofleet.cloud.gateway;

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

    /** Get or create the snapshot for a systemId (called from the receive thread). */
    public DroneSnapshot registerIfAbsent(int sysid) {
        return drones.computeIfAbsent(sysid, id -> {
            DroneSnapshot s = new DroneSnapshot(id);
            s.online = true;
            log.info("Drone registered: sysid={}", id);
            if (persist && repository != null) {
                try {
                    DeviceEntity entity = repository.findById(id)
                            .orElseGet(() -> new DeviceEntity(id));
                    entity.setOnline(true);
                    entity.setLastSeen(java.time.Instant.now());
                    repository.save(entity);
                } catch (Exception e) {
                    log.warn("设备持久化失败 sysid={}: {}", id, e.getMessage());
                }
            }
            return s;
        });
    }

    public DroneSnapshot get(int sysid) {
        return drones.get(sysid);
    }

    /** All known drones, sorted by sysid. Includes offline ones. */
    public List<DroneSnapshot> all() {
        return drones.values().stream()
                .sorted(Comparator.comparingInt(s -> s.sysid))
                .collect(Collectors.toList());
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

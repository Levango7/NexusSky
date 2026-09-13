package io.aerofleet.cloud.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory device registry keyed by MAVLink systemId.
 * Snapshots survive offline periods (fleet list keeps showing them);
 * TODO: persist to a database + add device provisioning/auth when scaling out.
 */
@Component
public class DeviceRegistry {

    private static final Logger log = LoggerFactory.getLogger(DeviceRegistry.class);

    @Value("${aerofleet.heartbeat-timeout-seconds:10}")
    private int heartbeatTimeoutSeconds;

    private final Map<Integer, DroneSnapshot> drones = new ConcurrentHashMap<>();

    /** Get or create the snapshot for a systemId (called from the receive thread). */
    public DroneSnapshot registerIfAbsent(int sysid) {
        return drones.computeIfAbsent(sysid, id -> {
            DroneSnapshot s = new DroneSnapshot(id);
            s.online = true;
            log.info("Drone registered: sysid={}", id);
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
                })
                .map(s -> s.sysid)
                .collect(Collectors.toList());
    }
}

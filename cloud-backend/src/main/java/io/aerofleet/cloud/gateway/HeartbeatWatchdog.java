package io.aerofleet.cloud.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Background heartbeat watchdog: sweeps the registry once per second and
 * flags devices whose last HEARTBEAT is older than the configured timeout.
 * Online/offline transitions are persisted to the flight log.
 */
@Component
public class HeartbeatWatchdog {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatWatchdog.class);

    private final DeviceRegistry registry;
    private final io.aerofleet.cloud.flightlog.FlightLogService flightLog;
    /** sysids already known offline, so re-registrations can be logged too. */
    private final java.util.Set<Integer> knownOffline = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public HeartbeatWatchdog(DeviceRegistry registry,
                             io.aerofleet.cloud.flightlog.FlightLogService flightLog) {
        this.registry = registry;
        this.flightLog = flightLog;
    }

    @Scheduled(fixedDelay = 1000)
    public void sweep() {
        try {
            for (Integer sysid : registry.sweepOffline()) {
                knownOffline.add(sysid);
                flightLog.connectivity(sysid, false);
            }
            // Log recoveries: online drones that we remember being offline.
            for (var s : registry.all()) {
                if (s.online && knownOffline.remove(s.sysid)) {
                    flightLog.connectivity(s.sysid, true);
                }
            }
        } catch (RuntimeException e) {
            log.warn("Heartbeat sweep failed: {}", e.getMessage());
        }
    }
}

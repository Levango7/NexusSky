package io.aerofleet.cloud.twin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** M13 数字孪生管理核心 */
@Service
public class DigitalTwinService {
    private static final Logger log = LoggerFactory.getLogger(DigitalTwinService.class);
    private final Map<Integer, TwinState> twins = new ConcurrentHashMap<>();

    public void syncTwin(int sysid, double lat, double lon, double alt, double heading, double velocity, double battery) {
        TwinState old = twins.get(sysid);
        double drift = 0;
        if (old != null) {
            drift = Math.sqrt(Math.pow(lat - old.lat, 2) + Math.pow(lon - old.lon, 2)) * 111000;
        }
        twins.put(sysid, new TwinState(sysid, lat, lon, alt, heading, velocity, battery, System.currentTimeMillis(), drift));
        log.debug("Twin synced: sysid={} drift={}m", sysid, drift);
    }

    public TwinState getTwin(int sysid) { return twins.get(sysid); }
    public Collection<TwinState> getAllTwins() { return twins.values(); }
}

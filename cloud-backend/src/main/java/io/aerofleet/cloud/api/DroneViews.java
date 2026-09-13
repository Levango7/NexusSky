package io.aerofleet.cloud.api;

import io.aerofleet.cloud.gateway.AlertEntry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import io.aerofleet.cloud.gateway.TrackPoint;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST/WS view models (camelCase JSON). Built from {@link DroneSnapshot} so
 * volatile fields are read once per request/response and copied out.
 */
public final class DroneViews {

    private DroneViews() {
    }

    /** Row of GET /api/v1/drones. */
    public static Map<String, Object> summary(DroneSnapshot s) {
        Map<String, Object> m = new HashMap<>();
        m.put("sysid", s.sysid);
        m.put("callsign", "Drone-" + s.sysid);
        m.put("online", s.online);
        m.put("armed", s.armed);
        m.put("mode", s.mode);
        m.put("battery", s.battery);
        m.put("voltage", s.voltage);
        m.put("lat", clean(s.lat));
        m.put("lon", clean(s.lon));
        m.put("relativeAlt", clean(s.relativeAlt));
        m.put("lastHeartbeat", s.lastHeartbeatMs);
        return m;
    }

    /** Full snapshot: GET /api/v1/drones/{sysid}/telemetry, plus alerts in the detail view. */
    public static Map<String, Object> telemetry(DroneSnapshot s) {
        Map<String, Object> m = new HashMap<>();
        m.put("sysid", s.sysid);
        m.put("online", s.online);
        m.put("armed", s.armed);
        m.put("mode", s.mode);
        m.put("customMode", s.customMode);
        m.put("systemStatus", s.systemStatus);
        m.put("battery", s.battery);
        m.put("voltage", s.voltage);
        m.put("current", s.current);
        m.put("load", s.load);
        m.put("lat", clean(s.lat));
        m.put("lon", clean(s.lon));
        m.put("relativeAlt", clean(s.relativeAlt));
        m.put("amslAlt", clean(s.amslAlt));
        m.put("vx", clean(s.vx));
        m.put("vy", clean(s.vy));
        m.put("vz", clean(s.vz));
        m.put("roll", clean(s.roll));
        m.put("pitch", clean(s.pitch));
        m.put("yaw", clean(s.yaw));
        m.put("groundspeed", clean(s.groundspeed));
        m.put("airspeed", clean(s.airspeed));
        m.put("climb", clean(s.climb));
        m.put("heading", clean(s.heading));
        m.put("throttle", s.throttle);
        m.put("fixType", s.fixType);
        m.put("satellites", s.satellites);
        m.put("eph", s.eph);
        m.put("gpsHealthy", s.gpsHealthy);
        m.put("missionSeq", s.missionSeq);
        m.put("missionTotal", s.missionTotal);
        m.put("missionState", s.missionState);
        m.put("lastHeartbeat", s.lastHeartbeatMs);
        return m;
    }

    /** Detail view = telemetry + recent alerts. */
    public static Map<String, Object> detail(DroneSnapshot s) {
        Map<String, Object> m = telemetry(s);
        m.put("callsign", "Drone-" + s.sysid);
        m.put("alerts", alerts(s.alerts.toList()));
        return m;
    }

    /** WS "status" frame: small payload with the fields the fleet list needs. */
    public static Map<String, Object> status(DroneSnapshot s) {
        Map<String, Object> m = new HashMap<>();
        m.put("armed", s.armed);
        m.put("mode", s.mode);
        m.put("battery", s.battery);
        m.put("online", s.online);
        return m;
    }

    /** WS "alert" frame body. */
    public static Map<String, Object> alert(AlertEntry e) {
        Map<String, Object> m = new HashMap<>();
        m.put("severity", e.severity);
        m.put("text", e.text);
        m.put("ts", e.ts);
        return m;
    }

    public static List<Map<String, Object>> alerts(List<AlertEntry> entries) {
        return entries.stream().map(DroneViews::alert)
                .collect(java.util.stream.Collectors.toList());
    }

    public static List<Map<String, Object>> track(List<TrackPoint> points) {
        return points.stream().map(p -> {
            Map<String, Object> m = new HashMap<>();
            m.put("lat", p.lat);
            m.put("lon", p.lon);
            m.put("alt", p.alt);
            m.put("ts", p.ts);
            return m;
        }).collect(java.util.stream.Collectors.toList());
    }

    /** NaN -> null so the JSON shows "--" placeholders on the GCS instead of garbage. */
    private static Object clean(double v) {
        return Double.isNaN(v) ? null : v;
    }
}

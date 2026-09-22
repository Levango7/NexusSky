package io.aerofleet.cloud.mission.squad;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import io.aerofleet.cloud.vision.OrbitJobManager;
import io.aerofleet.cloud.vision.TargetTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Multi-target task assignment (batch F, inspired by "蜂群大脑 任务分解与
 * 冲突消解"). Pure rule-based, interpretable, no ML/ROS2.
 *
 * Input : the TargetTracker's ACTIVE tracks (per drone) + the online fleet
 *         (with battery from DeviceRegistry) + SquadRoleService roles.
 * Output: which drone should go track which target, with conflict resolution:
 *   - a target seen by several drones is assigned to the nearest drone only
 *     (avoids duplicate orbit missions around the same spot);
 *   - battery-critical drones are not burdened with orbit tasks;
 *   - unassigned targets and idle drones are reported.
 *
 * Execution: the assigned drone's orbit mission is dispatched via the existing
 * async OrbitJobManager (no blocking, no new flight-authority layer).
 * Dispatch is a SUGGESTION: the orbit service still enforces its own safety
 * bounds (radius/alt/photos) and its per-drone one-in-flight rule.
 */
@Component
public class SquadDispatcher {

    private static final Logger log = LoggerFactory.getLogger(SquadDispatcher.class);

    /** Meters: beyond this, a drone does not get assigned that target. */
    static final double ASSIGN_GATE_M = 500.0;
    static final double ORBIT_RADIUS_M = 25;
    static final double ORBIT_ALT_M = 60;
    static final int ORBIT_PHOTOS = 4;

    private final TargetTracker tracker;
    private final DeviceRegistry registry;
    private final SquadRoleService roles;
    private final OrbitJobManager orbitJobs;

    public SquadDispatcher(TargetTracker tracker, DeviceRegistry registry,
                           SquadRoleService roles, OrbitJobManager orbitJobs) {
        this.tracker = tracker;
        this.registry = registry;
        this.roles = roles;
        this.orbitJobs = orbitJobs;
    }

    /**
     * Compute the assignment from current tracks + fleet, then dispatch the
     * chosen drones' orbit missions. Returns the assignment map for the GCS.
     */
    public Map<String, Object> dispatch() {
        List<Map<String, Object>> plan = planAssignments();
        List<Map<String, Object>> assignments = new ArrayList<>();
        for (Map<String, Object> p : plan) {
            int sysid = (Integer) p.get("sysid");
            double tLat = (Double) p.get("targetLat");
            double tLon = (Double) p.get("targetLon");
            try {
                OrbitJobManager.OrbitJob job = orbitJobs.submit(
                        sysid, tLat, tLon, ORBIT_RADIUS_M, ORBIT_ALT_M, ORBIT_PHOTOS);
                Map<String, Object> a = new HashMap<>(p);
                a.put("jobId", job.id);
                assignments.add(a);
            } catch (Exception ex) {
                log.warn("dispatch orbit for sysid={} rejected: {}", sysid, ex.getMessage());
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", roles.version());
        result.put("leader", roles.leaderSysid());
        result.put("assignments", assignments);
        result.put("targetCount", plan.size());
        result.put("idleDrones", registry.all().stream()
                .filter(s -> s.online && assignments.stream()
                        .noneMatch(a -> (Integer) a.get("sysid") == s.sysid))
                .map(s -> s.sysid).toList());
        result.put("notes",
                "rule-based conflict-resolving dispatch; orbit is async and safety-bound");
        log.info("squad dispatch: {} target(s), {} drone(s) assigned",
                plan.size(), assignments.size());
        return result;
    }

    /**
     * PURE decision: which drone goes to which target. No side effects, no
     * orbit submission - extracted so the rule logic is unit-testable without
     * a running fleet. Returns plans of {sysid, targetLat, targetLon, trackId}.
     */
    List<Map<String, Object>> planAssignments() {
        // Candidate targets: ACTIVE tracks across online drones, each carrying
        // the drone's current position so we can score "who is nearest".
        List<Candidate> candidates = new ArrayList<>();
        for (DroneSnapshot s : registry.all()) {
            if (!s.online) {
                continue;
            }
            if (Double.isNaN(s.lat) || Double.isNaN(s.lon)) {
                continue; // drone has no fix yet
            }
            for (TargetTracker.Track t : tracker.tracksOf(s.sysid)) {
                if (t.state == TargetTracker.TrackState.ACTIVE && t.last != null) {
                    candidates.add(new Candidate(t, s.sysid, s.lat, s.lon));
                }
            }
        }

        // Conflict resolution: one target = one nearest drone. Same physical
        // spot (dedup by rounded position) is seen by several drones; keep the
        // drone closest to that spot.
        Map<String, Candidate> bestBySpot = new LinkedHashMap<>();
        for (Candidate c : candidates) {
            String spot = spotKey(c.trackLat, c.trackLon);
            Candidate prev = bestBySpot.get(spot);
            if (prev == null || c.droneToTargetM < prev.droneToTargetM) {
                bestBySpot.put(spot, c);
            }
        }

        List<Map<String, Object>> plan = new ArrayList<>();
        for (Candidate c : bestBySpot.values()) {
            DroneSnapshot d = registry.get(c.droneSysid);
            if (d == null || !d.online || roles.batteryCritical(d.sysid)) {
                continue; // battery-critical / offline never dispatched
            }
            if (c.droneToTargetM > ASSIGN_GATE_M) {
                continue; // too far to be worth a dedicated orbit
            }
            Map<String, Object> p = new HashMap<>();
            p.put("sysid", d.sysid);
            p.put("targetLat", c.trackLat);
            p.put("targetLon", c.trackLon);
            p.put("trackId", c.trackId);
            plan.add(p);
        }
        return plan;
    }

    /** A target seen by one drone, with that drone's distance to the target. */
    private static final class Candidate {
        final int trackId;
        final int droneSysid;
        final double trackLat;
        final double trackLon;
        final double droneToTargetM;

        Candidate(TargetTracker.Track t, int droneSysid,
                  double droneLat, double droneLon) {
            this.trackId = t.trackId;
            this.droneSysid = droneSysid;
            this.trackLat = t.last.lat;
            this.trackLon = t.last.lon;
            this.droneToTargetM = distanceM(droneLat, droneLon, this.trackLat, this.trackLon);
        }
    }

    /** Round the spot to ~10 m buckets so the same physical target dedups. */
    private static String spotKey(double lat, double lon) {
        double dlat = Math.round(lat * 1e4) / 1e4;
        double dlon = Math.round(lon * 1e4) / 1e4;
        return dlat + "," + dlon;
    }

    /** Flat-earth meter distance (same approx as TargetTracker). */
    static double distanceM(double lat1, double lon1, double lat2, double lon2) {
        double dn = (lat2 - lat1) * 111_320.0;
        double de = (lon2 - lon1) * 111_320.0 * Math.cos(Math.toRadians((lat1 + lat2) / 2));
        return Math.hypot(dn, de);
    }
}
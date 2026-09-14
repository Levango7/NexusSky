package io.aerofleet.cloud.mission;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Role state machine (batch F, inspired by "跨场景飞控" 的动态角色切换).
 *
 * Each drone holds a role: WORKER (执行者), RELAY (中继), LEADER (协调者).
 * Switching triggers are rule-based and interpretable (no ML, no ROS2):
 *   - LEADER: the coordinator; default = lowest online sysid, overridable.
 *   - RELAY : a node that should carry relay load. With the current
 *             single-GCS routing there is no per-drone forwarding table, so
 *             we conservatively infer none (returns WORKER) until E3's static
 *             relay supplies a topology. The decision surface is here;
 *             the backing transport is E3's static relay.
 *   - battery <= BATTERY_CRIT_PCT: demote to WORKER and never LEADER/RELAY
 *             (E4 battery model supplies the trigger signal).
 *
 * Pure read-only over existing services (DeviceRegistry + battery). No mutation
 * of routing/command layers. version() increments on every role change so the
 * GCS can poll for topology changes.
 */
@Component
public class SquadRoleService {

    public enum Role { WORKER, RELAY, LEADER }

    /** A battery at or below this never carries coordination/relay load. */
    static final int BATTERY_CRIT_PCT = 20;

    private final DeviceRegistry registry;
    private final Map<Integer, Role> roles = new ConcurrentHashMap<>();
    private final AtomicInteger version = new AtomicInteger();
    private volatile int leaderSysid = -1;

    public SquadRoleService(DeviceRegistry registry) {
        this.registry = registry;
    }

    /** Recompute roles from current fleet + battery. Idempotent; bumps version
     *  only when something changed. Call periodically from the scheduler. */
    public void refresh() {
        List<DroneSnapshot> fleet = registry.all();
        boolean changed = false;

        // 1. Decide per-drone role from battery (LEADER handled below).
        for (DroneSnapshot s : fleet) {
            if (!s.online) {
                continue;
            }
            Role next = decideRole(s);
            Role prev = roles.put(s.sysid, next);
            if (prev != next) {
                changed = true;
            }
        }

        // 2. Elect leader: lowest online, non-battery-critical sysid.
        int elected = -1;
        for (DroneSnapshot s : fleet) {
            if (s.online && !batteryCritical(s) && (elected == -1 || s.sysid < elected)) {
                elected = s.sysid;
            }
        }
        if (leaderSysid != elected) {
            leaderSysid = elected;
            changed = true;
        }
        if (elected != -1) {
            Role prev = roles.put(elected, Role.LEADER);
            if (prev != Role.LEADER) {
                changed = true;
            }
        }

        if (changed) {
            version.incrementAndGet();
        }
    }

    /** Rule: decide one drone's role (battery + leadership). */
    Role decideRole(DroneSnapshot s) {
        if (batteryCritical(s)) {
            return Role.WORKER;             // battery-critical: never coordinate/relay
        }
        if (s.sysid == leaderSysid) {
            return Role.LEADER;
        }
        if (isRelayCandidate(s.sysid)) {
            return Role.RELAY;
        }
        return Role.WORKER;
    }

    /** Route-table heuristic: a node forwarding for several others is a relay.
     *  Currently no per-drone forwarding topology exists -> none. E3's static
     *  relay will drive this when it lands. */
    private boolean isRelayCandidate(int sysid) {
        return false;
    }

    public Role roleOf(int sysid) {
        return roles.getOrDefault(sysid, Role.WORKER);
    }

    public int leaderSysid() {
        return leaderSysid;
    }

    public int version() {
        return version.get();
    }

    /** Snapshot: {version, leader, drones:[{sysid, role, battery, online}]}. */
    public Map<String, Object> rolesView() {
        Map<String, Object> out = new java.util.HashMap<>();
        out.put("version", version.get());
        out.put("leader", leaderSysid);
        java.util.List<Map<String, Object>> list = new java.util.ArrayList<>();
        for (DroneSnapshot s : registry.all()) {
            Map<String, Object> m = new java.util.HashMap<>();
            m.put("sysid", s.sysid);
            m.put("role", roleOf(s.sysid).name());
            m.put("battery", s.battery);
            m.put("online", s.online);
            list.add(m);
        }
        out.put("drones", list);
        return out;
    }

    /** Manual leader override. */
    public void assignLeader(int sysid) {
        if (registry.get(sysid) == null) {
            return;
        }
        leaderSysid = sysid;
        Role prev = roles.put(sysid, Role.LEADER);
        if (prev != Role.LEADER) {
            version.incrementAndGet();
        }
    }

    /** True when a node is battery-critical: a dispatch must not burden it. */
    public boolean batteryCritical(int sysid) {
        DroneSnapshot s = registry.get(sysid);
        return batteryCritical(s);
    }

    private boolean batteryCritical(DroneSnapshot s) {
        return s != null && s.battery > 0 && s.battery <= BATTERY_CRIT_PCT;
    }
}
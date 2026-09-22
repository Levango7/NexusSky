package io.aerofleet.cloud.mission.squad;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import io.aerofleet.cloud.vision.TargetTracker;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SquadDispatcher.planAssignments tests (batch F): conflict-resolving
 * multi-target assignment. Pure decision method tested with a hand-built
 * tracker + fleet; orbit submission is not exercised (pass null manager).
 */
class SquadDispatchTest {

    private static final double HOME_LAT = 22.5907;
    private static final double HOME_LON = 113.9345;

    private static DroneSnapshot online(DeviceRegistry reg, int sysid, int battery, double lat, double lon) {
        DroneSnapshot d = reg.registerIfAbsent(sysid);
        d.online = true;
        d.battery = battery;
        d.lat = lat;
        d.lon = lon;
        return d;
    }

    private static void feedTrack(TargetTracker tracker, int sysid, double lat, double lon) {
        tracker.ingest(sysid, List.of(new TargetTracker.Observation(
                1, System.currentTimeMillis(), lat, lon, "vehicle")));
    }

    private SquadDispatcher dispatcher(DeviceRegistry reg, TargetTracker tracker,
                                       SquadRoleService roles) {
        return new SquadDispatcher(tracker, reg, roles, null);
    }

    @Test
    void twoDronesTwoTargetsAssignsNearest() {
        DeviceRegistry reg = new DeviceRegistry();
        TargetTracker tracker = new TargetTracker();
        // drones
        online(reg, 7, 90, HOME_LAT, HOME_LON);
        online(reg, 8, 90, HOME_LAT + 0.01, HOME_LON);   // ~1.1km north
        // targets: one near drone7, one near drone8
        feedTrack(tracker, 7, HOME_LAT + 0.0002, HOME_LON);       // ~22m from d7
        feedTrack(tracker, 8, HOME_LAT + 0.0102, HOME_LON);       // ~22m from d8
        SquadRoleService roles = new SquadRoleService(reg);
        roles.refresh();

        List<Map<String, Object>> plan =
                new SquadDispatcher(tracker, reg, roles, null).planAssignments();
        assertEquals(2, plan.size(), "two distinct targets -> two assignments");
        // drone7 goes to the target it is closest to (first feed)
        assertEquals(7, plan.get(0).get("sysid"));
        assertEquals(8, plan.get(1).get("sysid"));
    }

    @Test
    void sameSpotSeenByTwoDronesAssignsNearestOnly() {
        DeviceRegistry reg = new DeviceRegistry();
        TargetTracker tracker = new TargetTracker();
        online(reg, 7, 90, HOME_LAT, HOME_LON);                // d7 at origin
        online(reg, 8, 90, HOME_LAT + 0.005, HOME_LON);        // d8 ~555m north
        // both drones track the SAME physical spot
        feedTrack(tracker, 7, HOME_LAT + 0.001, HOME_LON);
        feedTrack(tracker, 8, HOME_LAT + 0.001, HOME_LON);
        SquadRoleService roles = new SquadRoleService(reg);
        roles.refresh();

        List<Map<String, Object>> plan =
                new SquadDispatcher(tracker, reg, roles, null).planAssignments();
        assertEquals(1, plan.size(), "one physical spot -> one assignment (conflict resolved)");
        assertEquals(7, plan.get(0).get("sysid"), "nearest drone (d7) wins the spot");
    }

    @Test
    void batteryCriticalDroneNotAssigned() {
        DeviceRegistry reg = new DeviceRegistry();
        TargetTracker tracker = new TargetTracker();
        online(reg, 5, 15, HOME_LAT, HOME_LON);   // battery-critical
        online(reg, 6, 60, HOME_LAT + 0.01, HOME_LON);
        feedTrack(tracker, 5, HOME_LAT + 0.0001, HOME_LON);  // nearest but critical
        SquadRoleService roles = new SquadRoleService(reg);
        roles.refresh();

        List<Map<String, Object>> plan =
                new SquadDispatcher(tracker, reg, roles, null).planAssignments();
        assertTrue(plan.isEmpty(), "battery-critical drone must not be dispatched");
    }

    @Test
    void tooFarTargetNotAssigned() {
        DeviceRegistry reg = new DeviceRegistry();
        TargetTracker tracker = new TargetTracker();
        online(reg, 7, 90, HOME_LAT, HOME_LON);
        feedTrack(tracker, 7, HOME_LAT + 0.1, HOME_LON);   // ~11km away, beyond gate
        SquadRoleService roles = new SquadRoleService(reg);
        roles.refresh();

        List<Map<String, Object>> plan =
                new SquadDispatcher(tracker, reg, roles, null).planAssignments();
        assertTrue(plan.isEmpty(), "target beyond ASSIGN_GATE_M not dispatched");
    }

    @Test
    void emptyFleetEmptyPlan() {
        SquadDispatcher d = new SquadDispatcher(new TargetTracker(),
                new DeviceRegistry(), new SquadRoleService(new DeviceRegistry()), null);
        assertTrue(d.planAssignments().isEmpty(), "no fleet -> no assignments");
    }
}
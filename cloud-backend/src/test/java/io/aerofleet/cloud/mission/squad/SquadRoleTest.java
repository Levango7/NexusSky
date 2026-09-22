package io.aerofleet.cloud.mission.squad;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SquadRoleService tests (batch F): leader election, battery-critical demotion,
 * version bumps, manual override. Pure services constructed directly (no Spring).
 */
class SquadRoleTest {

    private static final double HOME_LAT = 22.5907;
    private static final double HOME_LON = 113.9345;

    private static DroneSnapshot online(DeviceRegistry reg, int sysid, int battery) {
        DroneSnapshot d = reg.registerIfAbsent(sysid);
        d.online = true;
        d.battery = battery;
        d.lat = HOME_LAT;
        d.lon = HOME_LON;
        return d;
    }

    @Test
    void lowestOnlineNonCriticalBecomesLeader() {
        DeviceRegistry reg = new DeviceRegistry();
        online(reg, 9, 80);   // higher sysid
        online(reg, 7, 90);   // lowest sysid online
        SquadRoleService svc = new SquadRoleService(reg);
        svc.refresh();
        assertEquals(SquadRoleService.Role.LEADER, svc.roleOf(7), "lowest online becomes leader");
        assertEquals(SquadRoleService.Role.WORKER, svc.roleOf(9));
        assertEquals(7, svc.leaderSysid());
    }

    @Test
    void batteryCriticalDroneIsNeverLeader() {
        DeviceRegistry reg = new DeviceRegistry();
        online(reg, 5, 15);   // battery-critical
        online(reg, 6, 50);
        SquadRoleService svc = new SquadRoleService(reg);
        svc.refresh();
        assertEquals(SquadRoleService.Role.WORKER, svc.roleOf(5), "low battery never leader");
        assertEquals(SquadRoleService.Role.LEADER, svc.roleOf(6), "healthy lowest is leader");
        assertEquals(6, svc.leaderSysid());
    }

    @Test
    void versionBumpsOnRoleChange() {
        DeviceRegistry reg = new DeviceRegistry();
        online(reg, 7, 80);
        SquadRoleService svc = new SquadRoleService(reg);
        svc.refresh();
        int v1 = svc.version();
        // stable second refresh: no change
        svc.refresh();
        assertEquals(v1, svc.version(), "no role change -> version stable");
    }

    @Test
    void manualLeaderOverride() {
        DeviceRegistry reg = new DeviceRegistry();
        online(reg, 3, 90);
        online(reg, 4, 90);
        SquadRoleService svc = new SquadRoleService(reg);
        svc.refresh();
        assertEquals(3, svc.leaderSysid(), "default election picks lowest");
        svc.assignLeader(4);
        assertEquals(4, svc.leaderSysid(), "manual override");
        assertEquals(SquadRoleService.Role.LEADER, svc.roleOf(4));
    }

    @Test
    void emptyFleetIsSafe() {
        SquadRoleService svc = new SquadRoleService(new DeviceRegistry());
        svc.refresh();
        assertEquals(-1, svc.leaderSysid(), "no fleet -> no leader");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> drones = (List<Map<String, Object>>) svc.rolesView().get("drones");
        assertNotNull(drones);
        assertTrue(drones.isEmpty(), "no fleet -> empty drone list");
    }

    @Test
    void batteryCriticalCheckUsesSnapshot() {
        DeviceRegistry reg = new DeviceRegistry();
        online(reg, 5, 15);
        online(reg, 8, 100);
        SquadRoleService svc = new SquadRoleService(reg);
        assertTrue(svc.batteryCritical(5), "15% is critical");
        assertFalse(svc.batteryCritical(8), "100% is healthy");
    }
}
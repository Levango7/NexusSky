package io.aerofleet.cloud.citytwin;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 实时态势叠加服务测试。
 */
class SituationOverlayServiceTest {

    private SituationOverlayService service;
    private DeviceRegistry deviceRegistry;

    @BeforeEach
    void setUp() {
        deviceRegistry = new DeviceRegistry();
        service = new SituationOverlayService(deviceRegistry);
    }

    @Test
    void getCurrentSituation_returnsValidSituation() {
        RealtimeSituation situation = service.getCurrentSituation();

        assertNotNull(situation);
        assertTrue(situation.getTimestamp() > 0);
        assertNotNull(situation.getDrones());
        assertNotNull(situation.getVehicles());
        assertNotNull(situation.getPersonnel());
        assertNotNull(situation.getAlerts());
    }

    @Test
    void getCurrentSituation_generatesVehicles() {
        RealtimeSituation situation = service.getCurrentSituation();

        assertFalse(situation.getVehicles().isEmpty());
        // 验证车辆有合理属性
        VehiclePosition vehicle = situation.getVehicles().get(0);
        assertNotNull(vehicle.getId());
        assertTrue(vehicle.getLat() != 0);
        assertTrue(vehicle.getLon() != 0);
        assertTrue(vehicle.getSpeed() > 0);
    }

    @Test
    void getCurrentSituation_generatesPersonnel() {
        RealtimeSituation situation = service.getCurrentSituation();

        assertFalse(situation.getPersonnel().isEmpty());
        PersonPosition person = situation.getPersonnel().get(0);
        assertNotNull(person.getId());
        assertNotNull(person.getRole());
        assertNotNull(person.getStatus());
    }

    @Test
    void getCurrentSituation_generatesAlerts() {
        RealtimeSituation situation = service.getCurrentSituation();

        assertFalse(situation.getAlerts().isEmpty());
        AlertMarker alert = situation.getAlerts().get(0);
        assertNotNull(alert.getId());
        assertNotNull(alert.getType());
        assertNotNull(alert.getSeverity());
        assertNotNull(alert.getDescription());
        assertTrue(alert.getTimestamp() > 0);
    }

    @Test
    void getSituationAt_returnsHistoricalSituation() {
        // 先生成当前态势以建立历史记录
        RealtimeSituation current = service.getCurrentSituation();
        long timestamp = current.getTimestamp();

        RealtimeSituation historical = service.getSituationAt(timestamp);
        assertNotNull(historical);
        assertEquals(timestamp, historical.getTimestamp());
    }

    @Test
    void getSituationAt_returnsEmptySituationForNoHistory() {
        RealtimeSituation situation = service.getSituationAt(System.currentTimeMillis() + 100000);
        assertNotNull(situation);
        assertTrue(situation.getDrones().isEmpty());
        assertTrue(situation.getVehicles().isEmpty());
    }

    @Test
    void getDronePositions_returnsEmptyWhenNoRegisteredDrones() {
        List<DronePosition> drones = service.getDronePositions();
        assertNotNull(drones);
        assertTrue(drones.isEmpty());
    }

    @Test
    void getDronePositions_returnsRegisteredDronePositions() {
        // 注册一架无人机
        deviceRegistry.registerIfAbsent(1);
        DroneSnapshot snapshot = deviceRegistry.get(1);
        snapshot.lat = 39.9042;
        snapshot.lon = 116.4074;
        snapshot.relativeAlt = 100;
        snapshot.heading = 45;
        snapshot.battery = 80;
        snapshot.mode = "AUTO";
        snapshot.online = true;

        List<DronePosition> drones = service.getDronePositions();
        assertEquals(1, drones.size());
        DronePosition drone = drones.get(0);
        assertEquals(1, drone.getSysid());
        assertEquals(39.9042, drone.getLat());
        assertEquals(116.4074, drone.getLon());
        assertEquals(100, drone.getAlt());
        assertEquals(45, drone.getHeading());
        assertEquals(80, drone.getBattery());
        assertEquals("AUTO", drone.getMode());
        assertEquals("ONLINE", drone.getStatus());
    }

    @Test
    void getAlertMarkers_returnsValidAlerts() {
        List<AlertMarker> alerts = service.getAlertMarkers();

        assertNotNull(alerts);
        assertFalse(alerts.isEmpty());
        for (AlertMarker alert : alerts) {
            assertNotNull(alert.getType());
            assertNotNull(alert.getSeverity());
        }
    }

    @Test
    void getSituationsBetween_returnsInRange() throws InterruptedException {
        long from = System.currentTimeMillis();
        service.getCurrentSituation();
        Thread.sleep(10);
        service.getCurrentSituation();
        long to = System.currentTimeMillis();

        List<RealtimeSituation> situations = service.getSituationsBetween(from, to);
        assertFalse(situations.isEmpty());
    }
}
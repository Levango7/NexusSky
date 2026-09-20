package io.aerofleet.cloud.citytwin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 灾害模拟推演服务测试。
 */
class DisasterSimulationServiceTest {

    private DisasterSimulationService service;

    @BeforeEach
    void setUp() {
        service = new DisasterSimulationService();
    }

    @Test
    void simulateFlood_generatesValidSimulation() {
        DisasterSimulation sim = service.simulateFlood(39.9, 116.4, 5.0, 2.5, 60);

        assertNotNull(sim.getId());
        assertEquals(DisasterSimulation.DisasterType.FLOOD, sim.getType());
        assertEquals(60, sim.getDurationMin());
        assertEquals(DisasterSimulation.SimStatus.COMPLETED, sim.getStatus());
        assertNotNull(sim.getArea());
        assertEquals(39.9, sim.getArea().getCenterLat());
        assertEquals(116.4, sim.getArea().getCenterLon());
        assertEquals(5.0, sim.getArea().getRadiusKm());
        assertNotNull(sim.getParameters());
        assertEquals(2.5, sim.getParameters().get("depthM"));
        assertNotNull(sim.getResult());
        assertTrue(sim.getResult().getAffectedAreaKm2() > 0);
        assertTrue(sim.getResult().getAffectedPopulation() > 0);
        assertFalse(sim.getResult().getTimeline().isEmpty());
        assertFalse(sim.getResult().getEvacuationRoutes().isEmpty());
    }

    @Test
    void simulateFlood_timelineFramesAreProgressive() {
        DisasterSimulation sim = service.simulateFlood(39.9, 116.4, 5.0, 1.0, 60);
        List<SimFrame> timeline = sim.getResult().getTimeline();

        assertFalse(timeline.isEmpty());
        // 第一帧面积应小于最后一帧面积（递增）
        assertTrue(timeline.get(0).getAffectedAreaKm2() <= timeline.get(timeline.size() - 1).getAffectedAreaKm2());
        // 时间戳应递增
        for (int i = 1; i < timeline.size(); i++) {
            assertTrue(timeline.get(i).getTimestampMin() >= timeline.get(i - 1).getTimestampMin());
        }
    }

    @Test
    void simulateFire_generatesValidSimulation() {
        DisasterSimulation sim = service.simulateFire(39.9, 116.4, 3.0, 15.0, 30);

        assertNotNull(sim.getId());
        assertEquals(DisasterSimulation.DisasterType.FIRE, sim.getType());
        assertEquals(30, sim.getDurationMin());
        assertNotNull(sim.getParameters());
        assertEquals(15.0, sim.getParameters().get("windSpeed"));
        assertNotNull(sim.getResult());
        assertFalse(sim.getResult().getTimeline().isEmpty());
        assertFalse(sim.getResult().getEvacuationRoutes().isEmpty());
    }

    @Test
    void simulateFire_highWindSpeedProducesCriticalSeverity() {
        DisasterSimulation sim = service.simulateFire(39.9, 116.4, 3.0, 20.0, 30);
        List<SimFrame> timeline = sim.getResult().getTimeline();

        // 风速 > 10 m/s 时，后半段应有 CRITICAL 级别
        boolean hasCritical = timeline.stream()
                .anyMatch(f -> "CRITICAL".equals(f.getSeverity()));
        assertTrue(hasCritical, "High wind speed should produce CRITICAL severity frames");
    }

    @Test
    void simulateEarthquake_generatesValidSimulation() {
        DisasterSimulation sim = service.simulateEarthquake(39.9, 116.4, 7.0, 30);

        assertNotNull(sim.getId());
        assertEquals(DisasterSimulation.DisasterType.EARTHQUAKE, sim.getType());
        assertNotNull(sim.getParameters());
        assertEquals(7.0, sim.getParameters().get("magnitude"));
        // 震级 7.0 应产生 70km 半径
        assertEquals(70.0, sim.getArea().getRadiusKm());
        assertNotNull(sim.getResult());
        assertFalse(sim.getResult().getTimeline().isEmpty());
    }

    @Test
    void simulateEarthquake_highMagnitudeProducesCriticalSeverity() {
        DisasterSimulation sim = service.simulateEarthquake(39.9, 116.4, 8.0, 30);
        List<SimFrame> timeline = sim.getResult().getTimeline();

        // 震级 >= 7 时，后半段应有 CRITICAL 级别
        boolean hasCritical = timeline.stream()
                .anyMatch(f -> "CRITICAL".equals(f.getSeverity()));
        assertTrue(hasCritical, "Magnitude >= 7 should produce CRITICAL severity frames");
    }

    @Test
    void simulateEvacuation_generatesValidSimulation() {
        DisasterSimulation sim = service.simulateEvacuation(39.9, 116.4, 5.0);

        assertNotNull(sim.getId());
        assertEquals(DisasterSimulation.DisasterType.EVACUATION, sim.getType());
        assertNotNull(sim.getResult());
        // 疏散模拟不产生直接损失
        assertEquals(0.0, sim.getResult().getEstimatedDamage());
        assertFalse(sim.getResult().getEvacuationRoutes().isEmpty());
        assertFalse(sim.getResult().getTimeline().isEmpty());
    }

    @Test
    void getSimulation_returnsStoredSimulation() {
        DisasterSimulation sim = service.simulateFlood(39.9, 116.4, 5.0, 2.0, 60);
        DisasterSimulation retrieved = service.getSimulation(sim.getId());

        assertEquals(sim.getId(), retrieved.getId());
        assertEquals(sim.getType(), retrieved.getType());
    }

    @Test
    void getSimulation_throwsForUnknownId() {
        assertThrows(IllegalArgumentException.class, () -> service.getSimulation("nonexistent"));
    }

    @Test
    void getSimulationHistory_returnsAllSimulations() {
        service.simulateFlood(39.9, 116.4, 5.0, 2.0, 60);
        service.simulateFire(39.9, 116.4, 3.0, 10.0, 30);
        service.simulateEarthquake(39.9, 116.4, 6.0, 30);

        List<DisasterSimulation> history = service.getSimulationHistory();
        assertEquals(3, history.size());
    }
}
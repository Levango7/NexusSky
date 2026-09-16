package io.aerofleet.sim.edge;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SensorFusionEngineTest {
    @Test
    void testFusionWithAllSensors() {
        SensorFusionEngine engine = new SensorFusionEngine();
        FusedState state = engine.fuse(30.0, 120.0, 100, 45, 10, 30.001, 120.001, 101, true, true, true, true);
        assertEquals(15, state.sensorMask); // all 4 bits
        assertTrue(state.accuracy > 0);
    }

    @Test
    void testFusionWithGpsOnly() {
        SensorFusionEngine engine = new SensorFusionEngine();
        FusedState state = engine.fuse(30.0, 120.0, 100, 0, 0, 0, 0, 0, true, false, false, false);
        assertEquals(1, state.sensorMask);
    }
}

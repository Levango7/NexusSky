package io.aerofleet.cloud.vision;

import io.aerofleet.cloud.mission.DroneCommandService;
import io.aerofleet.mavlink.messages.RotorTelemetryMsg;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * RotorController 单测（M4 硬件抽象，FR-26）。
 */
@ExtendWith(MockitoExtension.class)
class RotorControllerTest {

    @Mock
    private DroneCommandService commands;

    private RotorController controller;

    @BeforeEach
    void setUp() {
        controller = new RotorController(commands);
        lenient().when(commands.command(anyInt(), anyInt(), anyFloat(), anyFloat(), anyFloat(),
                anyFloat(), anyFloat(), anyFloat(), anyFloat())).thenReturn(0);
    }

    @Test
    void configureValidRotorConfig() {
        RotorController.RotorConfig cfg = new RotorController.RotorConfig(
                1, 4, 0.25, 5.0, 10000, 1.225);
        RotorController.RotorConfig result = controller.configure(cfg);
        assertSame(cfg, result);
        assertSame(cfg, controller.getConfig(1));
    }

    @Test
    void configureInvalidRpm() {
        assertThrows(IllegalArgumentException.class, () ->
                new RotorController.RotorConfig(1, 4, 0.25, 5.0, 30000, 1.225));
    }

    @Test
    void getTelemetryReturnsView() {
        // 注入遥测
        RotorTelemetryMsg msg = new RotorTelemetryMsg(0, 6000, 3.68f, 15.5f, 14.72f, 62.0f, 1);
        controller.onRotorTelemetry(msg);
        RotorController.RotorTelemetry tel = controller.getTelemetry(1);
        assertNotNull(tel);
        assertEquals(0, tel.rotorIndex);
        assertEquals(6000, tel.rpm, 0.01f);
        assertEquals(3.68f, tel.thrust, 0.01f);
        assertEquals(14.72f, tel.totalThrust, 0.01f);
        assertTrue(tel.lastUpdateTime > 0);
    }

    @Test
    void getTelemetryDefaultForUnknownSysid() {
        RotorController.RotorTelemetry tel = controller.getTelemetry(999);
        assertNotNull(tel);
        assertEquals(0, tel.rpm, 0.01f);
    }
}
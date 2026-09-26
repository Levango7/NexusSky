package io.aerofleet.cloud.vision;

import io.aerofleet.cloud.mission.common.DroneCommandService;
import io.aerofleet.mavlink.enums.ScanMode;
import io.aerofleet.mavlink.messages.RadarTargetMsg;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * RadarController 单测（M4 硬件抽象，FR-03/FR-24/FR-25）。
 */
@ExtendWith(MockitoExtension.class)
class RadarControllerTest {

    @Mock
    private DroneCommandService commands;

    private RadarController controller;

    @BeforeEach
    void setUp() {
        controller = new RadarController(commands);
        lenient().when(commands.command(anyInt(), anyInt(), anyFloat(), anyFloat(), anyFloat(),
                anyFloat(), anyFloat(), anyFloat(), anyFloat())).thenReturn(0);
    }

    @Test
    void configureValidRadarConfig() {
        RadarController.RadarScanConfig cfg = new RadarController.RadarScanConfig(
                1, ScanMode.STARE, 0, 60, 0, 10, 1000, 1000, true);
        RadarController.RadarScanConfig result = controller.configure(cfg);
        assertSame(cfg, result);
        assertSame(cfg, controller.getConfig(1));
    }

    @Test
    void configureInvalidRange() {
        assertThrows(IllegalArgumentException.class, () ->
                new RadarController.RadarScanConfig(
                        1, ScanMode.STARE, 0, 60, 0, 10, 5, 1000, true));
    }

    @Test
    void configureInvalidBeamWidth() {
        assertThrows(IllegalArgumentException.class, () ->
                new RadarController.RadarScanConfig(
                        1, ScanMode.STARE, 0, 60, 0, 0, 1000, 1000, true));
    }

    @Test
    void getTargetsReturnsList() {
        // 注入一个目标
        RadarTargetMsg msg = new RadarTargetMsg(1, 100, 0, 0, 0, 10, 0, 1, 0);
        controller.onRadarTarget(msg);
        var targets = controller.getTargets(1);
        assertEquals(1, targets.size());
        assertEquals(1, targets.get(0).targetId);
    }

    @Test
    void getTargetsEmptyForUnknownSysid() {
        var targets = controller.getTargets(999);
        assertTrue(targets.isEmpty());
    }

    @Test
    void getStatusReturnsDefault() {
        RadarController.RadarScanStatus status = controller.getStatus(1);
        assertNotNull(status);
        assertEquals(0, status.mode);
        assertEquals(0, status.targetCount);
    }

    @Test
    void onRadarScanUpdatesStatus() {
        io.aerofleet.mavlink.messages.RadarScanMsg msg =
                new io.aerofleet.mavlink.messages.RadarScanMsg(1, 45.0f, 10.0f, 1000, 3, 1, 12345L);
        controller.onRadarScan(msg);
        RadarController.RadarScanStatus status = controller.getStatus(1);
        assertEquals(1, status.mode);
        assertEquals(45.0f, status.beamAzim, 0.01f);
        assertEquals(3, status.targetCount);
        assertEquals(12345L, status.lastScanTime);
    }
}
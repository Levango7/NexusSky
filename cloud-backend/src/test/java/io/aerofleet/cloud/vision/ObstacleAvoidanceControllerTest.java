package io.aerofleet.cloud.vision;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.mission.DroneCommandService;
import io.aerofleet.mavlink.enums.AvoidanceMode;
import io.aerofleet.mavlink.enums.MavEnums;
import io.aerofleet.mavlink.enums.ThreatLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

/**
 * ObstacleAvoidanceController 单测（M3 感知成像增强，FR-15/16/17/18/29）。
 * <p>
 * 覆盖：
 * <ul>
 *   <li>FR-15 避障配置（有效存储 + 数据约束校验）</li>
 *   <li>FR-16 威胁→命令映射（CRITICAL→紧急悬停 / HIGH→航点偏移 / HIGH→速度限制 / MEDIUM→不下发）</li>
 *   <li>FR-17 威胁降级解除（HIGH→MEDIUM 恢复速度）</li>
 *   <li>FR-18 状态查询</li>
 *   <li>FR-29 紧急悬停手动解除</li>
 *   <li>未启用避障不下发命令</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ObstacleAvoidanceControllerTest {

    @Mock
    private DroneCommandService commands;
    @Mock
    private DeviceRegistry registry;

    private ObstacleAvoidanceController controller;

    @BeforeEach
    void setUp() {
        controller = new ObstacleAvoidanceController(commands, registry);
        // command() 默认返回 0（MAV_RESULT_ACCEPTED）；lenient 因部分测试不触发 command()
        lenient().when(commands.command(anyInt(), anyInt(), anyFloat(), anyFloat(), anyFloat(),
                anyFloat(), anyFloat(), anyFloat(), anyFloat())).thenReturn(0);
    }

    // ===== FR-15 避障配置 =====

    @Test
    void configureValidConfigStoredAndReturned() {
        ObstacleConfig cfg = new ObstacleConfig(1, 5.0, 2.0,
                AvoidanceMode.WAYPOINT_OFFSET, true, 10.0);
        ObstacleConfig result = controller.configure(cfg);
        assertSame(cfg, result, "configure should return the same config");
        assertSame(cfg, controller.getConfig(1), "config should be stored");
    }

    @Test
    void configureInvalidSafetyDistanceThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> new ObstacleConfig(1, 0.5, 0.2, AvoidanceMode.DISABLED, true, 5.0),
                "safetyDistanceM < 1.0 should reject");
    }

    @Test
    void configureEmergencyHoverNotLessThanSafetyThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> new ObstacleConfig(1, 5.0, 5.0, AvoidanceMode.DISABLED, true, 5.0),
                "emergencyHoverM >= safetyDistanceM should reject");
        assertThrows(IllegalArgumentException.class,
                () -> new ObstacleConfig(1, 5.0, 6.0, AvoidanceMode.DISABLED, true, 5.0),
                "emergencyHoverM > safetyDistanceM should reject");
    }

    @Test
    void configureNegativeMaxSpeedThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> new ObstacleConfig(1, 5.0, 2.0, AvoidanceMode.SPEED_LIMIT, true, -1.0),
                "maxSpeedMs < 0 should reject");
    }

    // ===== FR-16 威胁→命令映射 =====

    @Test
    void criticalThreatSendsEmergencyHoverCommand() {
        ObstacleConfig cfg = new ObstacleConfig(1, 5.0, 2.0,
                AvoidanceMode.WAYPOINT_OFFSET, true, 10.0);
        controller.configure(cfg);

        controller.onObstacleReport(1, 1.5, 0, ThreatLevel.CRITICAL);

        ArgumentCaptor<Integer> cmdCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(commands).command(eq(1), cmdCaptor.capture(), anyFloat(), anyFloat(),
                anyFloat(), anyFloat(), anyFloat(), anyFloat(), anyFloat());
        assertEquals(MavEnums.MAV_CMD_DO_SET_MODE, cmdCaptor.getValue(),
                "CRITICAL should send DO_SET_MODE");

        ObstacleStatus status = controller.getStatus(1);
        assertTrue(status.inEmergencyHover, "should be in emergency hover");
    }

    @Test
    void highThreatWaypointOffsetSendsRepositionCommand() {
        ObstacleConfig cfg = new ObstacleConfig(1, 5.0, 2.0,
                AvoidanceMode.WAYPOINT_OFFSET, true, 10.0);
        controller.configure(cfg);

        controller.onObstacleReport(1, 4.0, 90, ThreatLevel.HIGH);

        ArgumentCaptor<Integer> cmdCaptor = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Float> p4Captor = ArgumentCaptor.forClass(Float.class);
        verify(commands).command(eq(1), cmdCaptor.capture(), anyFloat(), anyFloat(),
                anyFloat(), p4Captor.capture(), anyFloat(), anyFloat(), anyFloat());
        assertEquals(MavEnums.MAV_CMD_DO_REPOSITION, cmdCaptor.getValue(),
                "HIGH + WAYPOINT_OFFSET should send DO_REPOSITION");
        // offsetDir = (90 + 180) % 360 = 270
        assertEquals(270.0f, p4Captor.getValue(), 0.001f,
                "offset direction should be opposite of obstacle direction");
    }

    @Test
    void highThreatSpeedLimitSendsChangeSpeedCommand() {
        ObstacleConfig cfg = new ObstacleConfig(1, 5.0, 2.0,
                AvoidanceMode.SPEED_LIMIT, true, 5.0);
        controller.configure(cfg);

        controller.onObstacleReport(1, 4.0, 0, ThreatLevel.HIGH);

        ArgumentCaptor<Integer> cmdCaptor = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Float> p2Captor = ArgumentCaptor.forClass(Float.class);
        verify(commands).command(eq(1), cmdCaptor.capture(), anyFloat(), p2Captor.capture(),
                anyFloat(), anyFloat(), anyFloat(), anyFloat(), anyFloat());
        assertEquals(MavEnums.MAV_CMD_DO_CHANGE_SPEED, cmdCaptor.getValue(),
                "HIGH + SPEED_LIMIT should send DO_CHANGE_SPEED");
        assertEquals(5.0f, p2Captor.getValue(), 0.001f,
                "speed should be limited to maxSpeedMs");
    }

    @Test
    void highThreatEmergencyHoverModeSendsSetModeCommand() {
        ObstacleConfig cfg = new ObstacleConfig(1, 5.0, 2.0,
                AvoidanceMode.EMERGENCY_HOVER, true, 10.0);
        controller.configure(cfg);

        controller.onObstacleReport(1, 4.0, 0, ThreatLevel.HIGH);

        ArgumentCaptor<Integer> cmdCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(commands).command(eq(1), cmdCaptor.capture(), anyFloat(), anyFloat(),
                anyFloat(), anyFloat(), anyFloat(), anyFloat(), anyFloat());
        assertEquals(MavEnums.MAV_CMD_DO_SET_MODE, cmdCaptor.getValue(),
                "HIGH + EMERGENCY_HOVER should send DO_SET_MODE");
    }

    @Test
    void mediumThreatSendsNoCommand() {
        ObstacleConfig cfg = new ObstacleConfig(1, 5.0, 2.0,
                AvoidanceMode.WAYPOINT_OFFSET, true, 10.0);
        controller.configure(cfg);

        controller.onObstacleReport(1, 8.0, 0, ThreatLevel.MEDIUM);

        verifyNoInteractions(commands);
    }

    @Test
    void lowThreatSendsNoCommand() {
        ObstacleConfig cfg = new ObstacleConfig(1, 5.0, 2.0,
                AvoidanceMode.WAYPOINT_OFFSET, true, 10.0);
        controller.configure(cfg);

        controller.onObstacleReport(1, 15.0, 0, ThreatLevel.LOW);

        verifyNoInteractions(commands);
    }

    @Test
    void notEnabledSendsNoCommand() {
        ObstacleConfig cfg = new ObstacleConfig(1, 5.0, 2.0,
                AvoidanceMode.WAYPOINT_OFFSET, false, 10.0);
        controller.configure(cfg);

        controller.onObstacleReport(1, 1.5, 0, ThreatLevel.CRITICAL);

        verifyNoInteractions(commands);
    }

    @Test
    void noConfigSendsNoCommand() {
        // 未配置避障的飞机
        controller.onObstacleReport(99, 1.5, 0, ThreatLevel.CRITICAL);
        verifyNoInteractions(commands);
    }

    @Test
    void disabledModeSendsNoCommand() {
        ObstacleConfig cfg = new ObstacleConfig(1, 5.0, 2.0,
                AvoidanceMode.DISABLED, true, 10.0);
        controller.configure(cfg);

        controller.onObstacleReport(1, 4.0, 0, ThreatLevel.HIGH);

        verifyNoInteractions(commands);
    }

    // ===== FR-17 威胁降级解除 =====

    @Test
    void threatDegradedFromHighToMediumRestoresSpeed() {
        ObstacleConfig cfg = new ObstacleConfig(1, 5.0, 2.0,
                AvoidanceMode.SPEED_LIMIT, true, 5.0);
        controller.configure(cfg);

        // HIGH → 限速
        controller.onObstacleReport(1, 4.0, 0, ThreatLevel.HIGH);
        // MEDIUM → 解除（恢复速度）
        controller.onObstacleReport(1, 8.0, 0, ThreatLevel.MEDIUM);

        // 应下发 2 次命令：限速 + 恢复
        verify(commands, times(2)).command(eq(1), anyInt(), anyFloat(), anyFloat(),
                anyFloat(), anyFloat(), anyFloat(), anyFloat(), anyFloat());

        // 第 2 次应为恢复速度：DO_CHANGE_SPEED p2=-1
        ArgumentCaptor<Integer> cmdCaptor = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Float> p2Captor = ArgumentCaptor.forClass(Float.class);
        verify(commands, times(2)).command(eq(1), cmdCaptor.capture(), anyFloat(),
                p2Captor.capture(), anyFloat(), anyFloat(), anyFloat(), anyFloat(), anyFloat());
        // captor 按调用顺序捕获所有值
        assertEquals(MavEnums.MAV_CMD_DO_CHANGE_SPEED, cmdCaptor.getAllValues().get(0));
        assertEquals(MavEnums.MAV_CMD_DO_CHANGE_SPEED, cmdCaptor.getAllValues().get(1));
        // 第 2 次的 p2 应为 -1（恢复默认速度）
        assertEquals(-1.0f, p2Captor.getAllValues().get(1), 0.001f,
                "threat degraded should restore speed (p2=-1)");
    }

    @Test
    void criticalNotAutoReleasedOnThreatDegrade() {
        ObstacleConfig cfg = new ObstacleConfig(1, 5.0, 2.0,
                AvoidanceMode.EMERGENCY_HOVER, true, 10.0);
        controller.configure(cfg);

        // CRITICAL → 紧急悬停
        controller.onObstacleReport(1, 1.0, 0, ThreatLevel.CRITICAL);
        assertTrue(controller.getStatus(1).inEmergencyHover);

        // MEDIUM → CRITICAL 不自动解除（FR-17）
        controller.onObstacleReport(1, 8.0, 0, ThreatLevel.MEDIUM);
        assertTrue(controller.getStatus(1).inEmergencyHover,
                "CRITICAL emergency hover should not auto-release on threat degrade");
    }

    // ===== FR-18 状态查询 =====

    @Test
    void getStatusReturnsInitializedStatus() {
        ObstacleStatus status = controller.getStatus(1);
        assertNotNull(status);
        assertEquals(ThreatLevel.NONE, status.currentThreat);
        assertEquals(Double.MAX_VALUE, status.nearestDistance, 0.001);
        assertFalse(status.inEmergencyHover);
    }

    @Test
    void getStatusReflectsLatestReport() {
        ObstacleConfig cfg = new ObstacleConfig(1, 5.0, 2.0,
                AvoidanceMode.WAYPOINT_OFFSET, true, 10.0);
        controller.configure(cfg);

        controller.onObstacleReport(1, 4.0, 45, ThreatLevel.HIGH);

        ObstacleStatus status = controller.getStatus(1);
        assertEquals(ThreatLevel.HIGH, status.currentThreat);
        assertEquals(4.0, status.nearestDistance, 0.001);
        assertEquals(45.0, status.nearestDirectionDeg, 0.001);
        assertTrue(status.lastReportTime > 0);
    }

    // ===== FR-25 配置查询 =====

    @Test
    void getConfigReturnsNullForUnknownSysid() {
        assertNull(controller.getConfig(999));
    }

    // ===== FR-29 紧急悬停手动解除 =====

    @Test
    void releaseClearsEmergencyHover() {
        ObstacleConfig cfg = new ObstacleConfig(1, 5.0, 2.0,
                AvoidanceMode.WAYPOINT_OFFSET, true, 10.0);
        controller.configure(cfg);

        controller.onObstacleReport(1, 1.0, 0, ThreatLevel.CRITICAL);
        assertTrue(controller.getStatus(1).inEmergencyHover);

        controller.release(1);
        assertFalse(controller.getStatus(1).inEmergencyHover,
                "release should clear emergency hover flag");
    }

    @Test
    void releaseNoOpIfNotInEmergencyHover() {
        controller.release(1); // 未曾进入紧急悬停，不应抛异常
        // 无异常即通过
    }

    // ===== 并发安全（DFX 4.2）=====

    @Test
    void commandFailureDoesNotPropagate() {
        ObstacleConfig cfg = new ObstacleConfig(1, 5.0, 2.0,
                AvoidanceMode.WAYPOINT_OFFSET, true, 10.0);
        controller.configure(cfg);

        // 模拟命令下发失败
        when(commands.command(anyInt(), eq(MavEnums.MAV_CMD_DO_SET_MODE), anyFloat(),
                anyFloat(), anyFloat(), anyFloat(), anyFloat(), anyFloat(), anyFloat()))
                .thenThrow(new RuntimeException("send failed"));

        // CRITICAL 时命令失败，不应抛异常（per-sysid try-catch 隔离）
        assertDoesNotThrow(() ->
                controller.onObstacleReport(1, 1.0, 0, ThreatLevel.CRITICAL));
    }
}
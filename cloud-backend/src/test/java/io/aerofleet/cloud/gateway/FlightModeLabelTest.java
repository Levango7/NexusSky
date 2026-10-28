package io.aerofleet.cloud.gateway;

import io.aerofleet.cloud.telemetry.AlertBus;
import io.aerofleet.mavlink.enums.MavEnums;
import io.aerofleet.mavlink.messages.Heartbeat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 飞行模式标签按固件口径分派的回归（SITL 首验实测）。
 *
 * <p>修复前 custom_mode 一律按 PX4 nav-state 表硬解：ArduCopter 进 GUIDED
 * （custom_mode=4）会被解成 "RTL"——地面站显示与实际模式完全错位。修复后按
 * HEARTBEAT.autopilot 分派：ArduPilotMega 用 ArduCopter 模式表，其余保持 PX4 口径。
 */
class FlightModeLabelTest {

    @Test
    @DisplayName("ArduCopter 表：GUIDED=4 显示 GUIDED（修复前误显示 RTL）")
    void arduCopterGuided_isNotRtl() {
        assertThat(TelemetrySnapshotListener.modeLabel(
                MavEnums.MAV_AUTOPILOT_ARDUPILOTMEGA, 4)).isEqualTo("GUIDED");
        assertThat(TelemetrySnapshotListener.arduCopterModeLabel(4)).isEqualTo("GUIDED");
    }

    @Test
    @DisplayName("ArduCopter 表覆盖常用模式（STABILIZE/ALT_HOLD/AUTO/LOITER/RTL/LAND/POSHOLD）")
    void arduCopterCommonModes() {
        assertThat(TelemetrySnapshotListener.arduCopterModeLabel(0)).isEqualTo("STABILIZE");
        assertThat(TelemetrySnapshotListener.arduCopterModeLabel(2)).isEqualTo("ALT_HOLD");
        assertThat(TelemetrySnapshotListener.arduCopterModeLabel(3)).isEqualTo("AUTO");
        assertThat(TelemetrySnapshotListener.arduCopterModeLabel(5)).isEqualTo("LOITER");
        assertThat(TelemetrySnapshotListener.arduCopterModeLabel(6)).isEqualTo("RTL");
        assertThat(TelemetrySnapshotListener.arduCopterModeLabel(9)).isEqualTo("LAND");
        assertThat(TelemetrySnapshotListener.arduCopterModeLabel(16)).isEqualTo("POSHOLD");
        assertThat(TelemetrySnapshotListener.arduCopterModeLabel(21)).isEqualTo("SMART_RTL");
        assertThat(TelemetrySnapshotListener.arduCopterModeLabel(99)).isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("PX4 与未知固件：保持既有 PX4 nav-state 口径")
    void px4AndUnknown_keepPx4Table() {
        assertThat(TelemetrySnapshotListener.modeLabel(MavEnums.MAV_AUTOPILOT_PX4, 3))
                .isEqualTo("MISSION");
        assertThat(TelemetrySnapshotListener.modeLabel(MavEnums.MAV_AUTOPILOT_PX4, 4))
                .isEqualTo("RTL");
        // -1（尚未收到心跳）走历史口径，不改变既有行为
        assertThat(TelemetrySnapshotListener.modeLabel(-1, 0)).isEqualTo("MANUAL");
    }

    @Test
    @DisplayName("onHeartbeat 落库：autopilot 存档 + AP GUIDED 心跳解出 GUIDED")
    void heartbeatStoresAutopilotAndMode() {
        DeviceRegistry registry = new DeviceRegistry();
        TelemetrySnapshotListener listener =
                new TelemetrySnapshotListener(registry, new AlertBus());

        listener.onHeartbeat(new MavlinkMessageEvent(listener, 1, Heartbeat.ID,
                new Heartbeat(4, MavEnums.MAV_TYPE_QUADROTOR,
                        MavEnums.MAV_AUTOPILOT_ARDUPILOTMEGA, 129, 4),
                System.currentTimeMillis()));

        DroneSnapshot s = registry.get(1);
        assertThat(s.autopilot).isEqualTo(MavEnums.MAV_AUTOPILOT_ARDUPILOTMEGA);
        assertThat(s.mode).isEqualTo("GUIDED");
        assertThat(s.customMode).isEqualTo(4);
    }
}

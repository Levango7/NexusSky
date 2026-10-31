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
    @DisplayName("PX4 打包字（SITL 实测编码）：地面待命=HOLD、任务=MISSION、返航=RTL")
    void px4PackedModeWord() {
        // 真 PX4 心跳 custom_mode = (main_mode << 16) | (sub_mode << 24)。
        // 0x03040000 = AUTO/LOITER：PX4 首验实测的「无 RC 地面待命」心跳值。
        assertThat(TelemetrySnapshotListener.modeLabel(MavEnums.MAV_AUTOPILOT_PX4, 0x03040000))
                .isEqualTo("HOLD");
        assertThat(TelemetrySnapshotListener.modeLabel(MavEnums.MAV_AUTOPILOT_PX4, 0x04040000))
                .isEqualTo("MISSION");
        assertThat(TelemetrySnapshotListener.modeLabel(MavEnums.MAV_AUTOPILOT_PX4, 0x05040000))
                .isEqualTo("RTL");
        assertThat(TelemetrySnapshotListener.modeLabel(MavEnums.MAV_AUTOPILOT_PX4, 0x06040000))
                .isEqualTo("LAND");
        assertThat(TelemetrySnapshotListener.modeLabel(MavEnums.MAV_AUTOPILOT_PX4, 0x02040000))
                .isEqualTo("TAKEOFF");
        assertThat(TelemetrySnapshotListener.modeLabel(MavEnums.MAV_AUTOPILOT_PX4, 0x00030000))
                .isEqualTo("POSITION");
        assertThat(TelemetrySnapshotListener.modeLabel(MavEnums.MAV_AUTOPILOT_PX4, 0x01030000))
                .isEqualTo("ORBIT");
        assertThat(TelemetrySnapshotListener.modeLabel(MavEnums.MAV_AUTOPILOT_PX4, 0x00010000))
                .isEqualTo("MANUAL");
    }

    @Test
    @DisplayName("PX4 裸值/零值不再猜模式：旧口径 3/4 与打包缺口 0 都如实 UNKNOWN")
    void px4NaiveAndZeroAreNotGuessed() {
        // 修复前 sim 口径的裸值 3/4 会被解成 MISSION/RTL 假标签；真 PX4 发打包字，
        // 裸值一个都命中不了——删除该口径后如实 UNKNOWN（sim 同批已对齐打包字）
        assertThat(TelemetrySnapshotListener.modeLabel(MavEnums.MAV_AUTOPILOT_PX4, 3))
                .isEqualTo("UNKNOWN");
        assertThat(TelemetrySnapshotListener.modeLabel(MavEnums.MAV_AUTOPILOT_PX4, 4))
                .isEqualTo("UNKNOWN");
        // custom_mode=0：v1.13 对 AUTO_READY 等 nav_state 的打包缺口，不猜成某个模式
        assertThat(TelemetrySnapshotListener.modeLabel(MavEnums.MAV_AUTOPILOT_PX4, 0))
                .isEqualTo("UNKNOWN");
        // -1（尚未收到心跳）同 PX4 打包字口径
        assertThat(TelemetrySnapshotListener.modeLabel(-1, 0)).isEqualTo("UNKNOWN");
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

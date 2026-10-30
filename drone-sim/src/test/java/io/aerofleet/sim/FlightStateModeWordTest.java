package io.aerofleet.sim;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 心跳 custom_mode 打包字回归（PX4 首验口径对齐）。
 *
 * <p>真 PX4 的 custom_mode = (main_mode &lt;&lt; 16) | (sub_mode &lt;&lt; 24)
 * （px4_custom_mode.h 联合体；SITL 实测地面待命 0x03040000 = AUTO/LOITER）。
 * sim 此前发裸 nav_state 值（历史口径），后端按 sim 表解码真机恒 UNKNOWN——
 * 现 sim 与真机同编码，这些数值是后端标签表的直接输入，钉死防漂移。
 */
@DisplayName("FlightState PX4 打包字（heartbeat custom_mode）")
class FlightStateModeWordTest {

    @Test
    @DisplayName("各状态打包字与真 PX4 一致：地面待命=0x03040000、任务=0x04040000、返航=0x05040000")
    void packedWordsMatchRealPx4() {
        assertThat(FlightState.STANDBY.px4NavState()).isEqualTo(0x03040000);  // AUTO/LOITER
        assertThat(FlightState.HOLD.px4NavState()).isEqualTo(0x03040000);     // AUTO/LOITER
        assertThat(FlightState.MISSION.px4NavState()).isEqualTo(0x04040000);  // AUTO/MISSION
        assertThat(FlightState.RTL.px4NavState()).isEqualTo(0x05040000);      // AUTO/RTL
        assertThat(FlightState.ARMED.px4NavState()).isEqualTo(0x00030000);    // POSCTL
        assertThat(FlightState.MANUAL.px4NavState()).isEqualTo(0x00010000);   // MANUAL
        assertThat(FlightState.INIT.px4NavState()).isEqualTo(0x00010000);
    }

    @Test
    @DisplayName("CRASHED 故意留在无效主模式：不伪装成任何可用 PX4 模式")
    void crashedIsNotARealMode() {
        int word = FlightState.CRASHED.px4NavState();
        // 主模式 0xFF 不在 PX4_CUSTOM_MAIN_MODE 枚举（1..9）里
        assertThat((word >>> 16) & 0xFF).isEqualTo(0xFF);
    }

    @Test
    @DisplayName("字节位域：main_mode 在 16-23 位、sub_mode 在 24-31 位")
    void bitLayout() {
        int mission = FlightState.MISSION.px4NavState();
        assertThat((mission >>> 16) & 0xFF).isEqualTo(4);   // AUTO
        assertThat((mission >>> 24) & 0xFF).isEqualTo(4);   // MISSION
    }
}

package io.aerofleet.sim.celltower;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GroundTerminal 领域对象单测（M6 移动基站载荷抽象，FR-TERM-01/02/06）。
 */
@DisplayName("GroundTerminal (FR-TERM-01/02/06)")
class GroundTerminalTest {

    @Test
    @DisplayName("构造器正确赋值所有字段")
    void constructorAssignsFields() {
        GroundTerminal t = new GroundTerminal(1001, TerminalType.PHONE,
                399000000, 1163000000, 1000L, 1000L, 1.4);
        assertThat(t.terminalId).isEqualTo(1001);
        assertThat(t.terminalType).isEqualTo(TerminalType.PHONE);
        assertThat(t.gpsLatE7).isEqualTo(399000000);
        assertThat(t.gpsLonE7).isEqualTo(1163000000);
        assertThat(t.connectedSysid).isZero();
        assertThat(t.registeredAtMs).isEqualTo(1000L);
        assertThat(t.lastSeenMs).isEqualTo(1000L);
        assertThat(t.moveSpeedMps).isEqualTo(1.4);
    }

    @Test
    @DisplayName("fromRegister 根据终端类型设置移动速度")
    void fromRegisterSetsSpeedByType() {
        GroundTerminal phone = GroundTerminal.fromRegister(1, TerminalType.PHONE, 100, 200, 5000L);
        assertThat(phone.moveSpeedMps).isEqualTo(1.4);

        GroundTerminal walkie = GroundTerminal.fromRegister(2, TerminalType.WALKIE_TALKIE, 100, 200, 5000L);
        assertThat(walkie.moveSpeedMps).isEqualTo(1.4);

        GroundTerminal sensor = GroundTerminal.fromRegister(3, TerminalType.SENSOR, 100, 200, 5000L);
        assertThat(sensor.moveSpeedMps).isEqualTo(0.0);
    }

    @Test
    @DisplayName("isGpsValid 校验合法坐标")
    void isGpsValid() {
        assertThat(GroundTerminal.isGpsValid(399000000, 1163000000)).isTrue();
        assertThat(GroundTerminal.isGpsValid(-900000000, -1800000000)).isTrue();
        assertThat(GroundTerminal.isGpsValid(900000000, 1800000000)).isTrue();
    }

    @Test
    @DisplayName("isGpsValid 拒绝未初始化坐标 (0,0)")
    void isGpsValidRejectsZero() {
        assertThat(GroundTerminal.isGpsValid(0, 0)).isFalse();
    }

    @Test
    @DisplayName("isGpsValid 拒绝越界坐标")
    void isGpsValidRejectsOutOfRange() {
        assertThat(GroundTerminal.isGpsValid(910000000, 0)).isFalse();
        assertThat(GroundTerminal.isGpsValid(0, 1810000000)).isFalse();
    }

    @Test
    @DisplayName("connect/disconnect 更新 connectedSysid")
    void connectDisconnect() {
        GroundTerminal t = GroundTerminal.fromRegister(1, TerminalType.PHONE, 100, 200, 1000L);
        assertThat(t.connectedSysid).isZero();

        t.connect(5);
        assertThat(t.connectedSysid).isEqualTo(5);

        t.disconnect();
        assertThat(t.connectedSysid).isZero();
    }

    @Test
    @DisplayName("updateLastSeen 更新最后心跳时间")
    void updateLastSeen() {
        GroundTerminal t = GroundTerminal.fromRegister(1, TerminalType.PHONE, 100, 200, 1000L);
        t.updateLastSeen(3000L);
        assertThat(t.lastSeenMs).isEqualTo(3000L);
    }
}
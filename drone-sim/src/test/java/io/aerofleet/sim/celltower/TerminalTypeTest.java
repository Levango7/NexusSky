package io.aerofleet.sim.celltower;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TerminalType 枚举单测（M6 移动基站载荷抽象，FR-TERM-01）。
 */
@DisplayName("TerminalType 枚举 (FR-TERM-01)")
class TerminalTypeTest {

    @Test
    @DisplayName("三种终端类型序数值正确")
    void ordinalCodes() {
        assertThat(TerminalType.PHONE.ordinalCode()).isEqualTo(0);
        assertThat(TerminalType.WALKIE_TALKIE.ordinalCode()).isEqualTo(1);
        assertThat(TerminalType.SENSOR.ordinalCode()).isEqualTo(2);
    }

    @Test
    @DisplayName("fromOrdinal 正向还原")
    void fromOrdinalValid() {
        assertThat(TerminalType.fromOrdinal(0)).isEqualTo(TerminalType.PHONE);
        assertThat(TerminalType.fromOrdinal(1)).isEqualTo(TerminalType.WALKIE_TALKIE);
        assertThat(TerminalType.fromOrdinal(2)).isEqualTo(TerminalType.SENSOR);
    }

    @Test
    @DisplayName("fromOrdinal 非法值抛出 IllegalArgumentException")
    void fromOrdinalInvalid() {
        assertThatThrownBy(() -> TerminalType.fromOrdinal(3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown terminalType");
    }
}
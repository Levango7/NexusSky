package io.aerofleet.sim.celltower;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HandoverReason 枚举单测（M6 移动基站载荷抽象，FR-HO-04）。
 */
@DisplayName("HandoverReason 枚举 (FR-HO-04)")
class HandoverReasonTest {

    @Test
    @DisplayName("三种切换原因序数值正确")
    void ordinalCodes() {
        assertThat(HandoverReason.SIGNAL_WEAK.ordinalCode()).isEqualTo(0);
        assertThat(HandoverReason.LOAD_BALANCE.ordinalCode()).isEqualTo(1);
        assertThat(HandoverReason.CELL_SHUTDOWN.ordinalCode()).isEqualTo(2);
    }

    @Test
    @DisplayName("fromOrdinal 正向还原")
    void fromOrdinalValid() {
        assertThat(HandoverReason.fromOrdinal(0)).isEqualTo(HandoverReason.SIGNAL_WEAK);
        assertThat(HandoverReason.fromOrdinal(1)).isEqualTo(HandoverReason.LOAD_BALANCE);
        assertThat(HandoverReason.fromOrdinal(2)).isEqualTo(HandoverReason.CELL_SHUTDOWN);
    }

    @Test
    @DisplayName("fromOrdinal 非法值默认返回 SIGNAL_WEAK（容忍解码）")
    void fromOrdinalInvalidDefaultsToSignalWeak() {
        assertThat(HandoverReason.fromOrdinal(99)).isEqualTo(HandoverReason.SIGNAL_WEAK);
        assertThat(HandoverReason.fromOrdinal(-1)).isEqualTo(HandoverReason.SIGNAL_WEAK);
    }
}
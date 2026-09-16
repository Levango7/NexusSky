package io.aerofleet.sim.celltower;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CellType 枚举单测（M6 移动基站载荷抽象，FR-CT-01）。
 */
@DisplayName("CellType 枚举 (FR-CT-01)")
class CellTypeTest {

    @Test
    @DisplayName("三种制式序数值正确")
    void ordinalCodes() {
        assertThat(CellType.LTE_MICRO_CELL.ordinalCode()).isEqualTo(0);
        assertThat(CellType.WIFI_MESH.ordinalCode()).isEqualTo(1);
        assertThat(CellType.LORA.ordinalCode()).isEqualTo(2);
    }

    @Test
    @DisplayName("fromOrdinal 正向还原")
    void fromOrdinalValid() {
        assertThat(CellType.fromOrdinal(0)).isEqualTo(CellType.LTE_MICRO_CELL);
        assertThat(CellType.fromOrdinal(1)).isEqualTo(CellType.WIFI_MESH);
        assertThat(CellType.fromOrdinal(2)).isEqualTo(CellType.LORA);
    }

    @Test
    @DisplayName("fromOrdinal 非法值抛出 IllegalArgumentException")
    void fromOrdinalInvalid() {
        assertThatThrownBy(() -> CellType.fromOrdinal(3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown cellType");
        assertThatThrownBy(() -> CellType.fromOrdinal(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("枚举值数量为 3")
    void enumCount() {
        assertThat(CellType.values()).hasSize(3);
    }
}
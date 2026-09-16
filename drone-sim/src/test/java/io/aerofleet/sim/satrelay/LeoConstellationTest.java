package io.aerofleet.sim.satrelay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * LeoConstellation LEO 星座单测（M7 星-空-地多层级中继，FR-5.1）。
 */
@DisplayName("LeoConstellation LEO 星座 (FR-5.1)")
class LeoConstellationTest {

    private List<SatelliteNode> makeSats(int n) {
        List<SatelliteNode> sats = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            sats.add(new SatelliteNode(i + 1, 550.0, 53.0,
                    360.0 * i / n, 360.0 * i / n));
        }
        return sats;
    }

    @Test
    @DisplayName("合法规模 10 颗构造成功")
    void validSize10() {
        LeoConstellation c = new LeoConstellation(makeSats(10));
        assertThat(c.size()).isEqualTo(10);
    }

    @Test
    @DisplayName("合法规模 100 颗构造成功")
    void validSize100() {
        LeoConstellation c = new LeoConstellation(makeSats(100));
        assertThat(c.size()).isEqualTo(100);
    }

    @Test
    @DisplayName("规模 <10 拒绝（FR-5.1.1.7）")
    void sizeTooSmall() {
        assertThatThrownBy(() -> new LeoConstellation(makeSats(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("constellation size");
    }

    @Test
    @DisplayName("规模 >100 拒绝（FR-5.1.1.7）")
    void sizeTooLarge() {
        assertThatThrownBy(() -> new LeoConstellation(makeSats(150)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("constellation size");
    }

    @Test
    @DisplayName("null 列表拒绝")
    void nullListRejected() {
        assertThatThrownBy(() -> new LeoConstellation(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("tick 更新所有卫星轨道位置")
    void tickUpdatesPositions() {
        LeoConstellation c = new LeoConstellation(makeSats(10));
        SatelliteNode sat = c.findSatellite(1);
        double m0 = sat.currentMeanAnomalyDeg();
        c.tick(60_000L);
        double m1 = sat.currentMeanAnomalyDeg();
        assertThat(m1).isGreaterThan(m0);
    }

    @Test
    @DisplayName("findSatellite 查找存在与不存在")
    void findSatellite() {
        LeoConstellation c = new LeoConstellation(makeSats(10));
        assertThat(c.findSatellite(1)).isNotNull();
        assertThat(c.findSatellite(10)).isNotNull();
        assertThat(c.findSatellite(11)).isNull();
    }

    @Test
    @DisplayName("walkerShell 工厂生成均匀分布星座")
    void walkerShellFactory() {
        LeoConstellation c = LeoConstellation.walkerShell(24, 550.0, 53.0);
        assertThat(c.size()).isEqualTo(24);
        assertThat(c.findSatellite(1)).isNotNull();
        assertThat(c.findSatellite(24)).isNotNull();
    }

    @Test
    @DisplayName("findVisible 返回可见卫星列表")
    void findVisible() {
        LeoConstellation c = LeoConstellation.walkerShell(24, 550.0, 53.0);
        c.tick(0L);
        List<SatelliteNode> visible = c.findVisible(22.0, 114.0, 10.0);
        // 可见卫星数 >= 0（取决于星座几何）
        assertThat(visible).isNotNull();
        for (SatelliteNode sat : visible) {
            assertThat(sat.isVisible()).isTrue();
        }
    }
}
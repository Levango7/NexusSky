package io.aerofleet.sim.sat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 真实卫星链路提供者占位实现单测（FR-20 真实卫星接入预留）。
 * <p>
 * 验证三个占位实现类的基本行为：
 * <ul>
 *   <li>getSatType() 返回正确的 SatType</li>
 *   <li>getSatId() 返回构造时传入的值</li>
 *   <li>isConnected() 返回 false</li>
 *   <li>connect() 抛出 UnsupportedOperationException</li>
 *   <li>getLinkQuality() 抛出 UnsupportedOperationException</li>
 * </ul>
 */
@DisplayName("真实卫星链路占位实现 (FR-20)")
class RealSatLinkProviderPlaceholderTest {

    // ===== 天通占位实现 =====

    @Test
    @DisplayName("天通占位：getSatType 返回 TIANTONG")
    void tiantongGetSatType() {
        TiantongSatLinkProvider provider = new TiantongSatLinkProvider("TT-1");

        assertThat(provider.getSatType()).isEqualTo(SatLinkProvider.SatType.TIANTONG);
    }

    @Test
    @DisplayName("天通占位：getSatId 返回构造传入值")
    void tiantongGetSatId() {
        TiantongSatLinkProvider provider = new TiantongSatLinkProvider("TT-1");

        assertThat(provider.getSatId()).isEqualTo("TT-1");
    }

    @Test
    @DisplayName("天通占位：isConnected 返回 false")
    void tiantongIsConnected() {
        TiantongSatLinkProvider provider = new TiantongSatLinkProvider("TT-1");

        assertThat(provider.isConnected()).isFalse();
    }

    @Test
    @DisplayName("天通占位：connect 抛出 UnsupportedOperationException")
    void tiantongConnectThrows() {
        TiantongSatLinkProvider provider = new TiantongSatLinkProvider("TT-1");

        assertThatThrownBy(provider::connect)
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("SimulatedSatLinkProvider");
    }

    @Test
    @DisplayName("天通占位：getLinkQuality 抛出 UnsupportedOperationException")
    void tiantongGetLinkQualityThrows() {
        TiantongSatLinkProvider provider = new TiantongSatLinkProvider("TT-1");

        assertThatThrownBy(provider::getLinkQuality)
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("SimulatedSatLinkProvider");
    }

    // ===== 铱星占位实现 =====

    @Test
    @DisplayName("铱星占位：getSatType 返回 IRIDIUM")
    void iridiumGetSatType() {
        IridiumSatLinkProvider provider = new IridiumSatLinkProvider("IR-1");

        assertThat(provider.getSatType()).isEqualTo(SatLinkProvider.SatType.IRIDIUM);
    }

    @Test
    @DisplayName("铱星占位：getSatId 返回构造传入值")
    void iridiumGetSatId() {
        IridiumSatLinkProvider provider = new IridiumSatLinkProvider("IR-1");

        assertThat(provider.getSatId()).isEqualTo("IR-1");
    }

    @Test
    @DisplayName("铱星占位：isConnected 返回 false")
    void iridiumIsConnected() {
        IridiumSatLinkProvider provider = new IridiumSatLinkProvider("IR-1");

        assertThat(provider.isConnected()).isFalse();
    }

    @Test
    @DisplayName("铱星占位：connect 抛出 UnsupportedOperationException")
    void iridiumConnectThrows() {
        IridiumSatLinkProvider provider = new IridiumSatLinkProvider("IR-1");

        assertThatThrownBy(provider::connect)
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("SimulatedSatLinkProvider");
    }

    @Test
    @DisplayName("铱星占位：getLinkQuality 抛出 UnsupportedOperationException")
    void iridiumGetLinkQualityThrows() {
        IridiumSatLinkProvider provider = new IridiumSatLinkProvider("IR-1");

        assertThatThrownBy(provider::getLinkQuality)
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("SimulatedSatLinkProvider");
    }

    // ===== 星链占位实现 =====

    @Test
    @DisplayName("星链占位：getSatType 返回 STARLINK")
    void starlinkGetSatType() {
        StarlinkSatLinkProvider provider = new StarlinkSatLinkProvider("SL-1");

        assertThat(provider.getSatType()).isEqualTo(SatLinkProvider.SatType.STARLINK);
    }

    @Test
    @DisplayName("星链占位：getSatId 返回构造传入值")
    void starlinkGetSatId() {
        StarlinkSatLinkProvider provider = new StarlinkSatLinkProvider("SL-1");

        assertThat(provider.getSatId()).isEqualTo("SL-1");
    }

    @Test
    @DisplayName("星链占位：isConnected 返回 false")
    void starlinkIsConnected() {
        StarlinkSatLinkProvider provider = new StarlinkSatLinkProvider("SL-1");

        assertThat(provider.isConnected()).isFalse();
    }

    @Test
    @DisplayName("星链占位：connect 抛出 UnsupportedOperationException")
    void starlinkConnectThrows() {
        StarlinkSatLinkProvider provider = new StarlinkSatLinkProvider("SL-1");

        assertThatThrownBy(provider::connect)
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("SimulatedSatLinkProvider");
    }

    @Test
    @DisplayName("星链占位：getLinkQuality 抛出 UnsupportedOperationException")
    void starlinkGetLinkQualityThrows() {
        StarlinkSatLinkProvider provider = new StarlinkSatLinkProvider("SL-1");

        assertThatThrownBy(provider::getLinkQuality)
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("SimulatedSatLinkProvider");
    }
}
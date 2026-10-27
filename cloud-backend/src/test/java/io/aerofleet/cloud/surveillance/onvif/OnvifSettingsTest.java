package io.aerofleet.cloud.surveillance.onvif;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link OnvifSettings} 的默认值契约。
 *
 * <p>为什么默认值值得单独立测：整套系统的"离线可跑"依赖这些默认值——
 * 模式必须是 {@code SIMULATED}（否则全新 clone 的机器一启动就向真实局域网发探针），
 * 超时必须是有限正值（否则一个没响应的设备能挂住一条请求线程）。
 * 一个 getter 被改成回 {@code null}/{@code 0}/{@code ""}，没有任何功能测试会失败，
 * 但演示环境会悄悄坏掉。
 */
@DisplayName("OnvifSettings 默认值契约")
class OnvifSettingsTest {

    @Test
    @DisplayName("默认 simulated：全新环境不触碰真实网络")
    void defaults_simulatedNotLive() {
        OnvifSettings settings = new OnvifSettings();

        assertThat(settings.getMode()).isEqualTo(OnvifSettings.Mode.SIMULATED);
        assertThat(settings.isLive()).isFalse();
    }

    @Test
    @DisplayName("默认超时与响应上限是有限正值")
    void defaults_timeoutsAndLimitsArePositive() {
        OnvifSettings settings = new OnvifSettings();

        assertThat(settings.getConnectTimeoutMs()).isEqualTo(3000);
        assertThat(settings.getReadTimeoutMs()).isEqualTo(5000);
        assertThat(settings.getDiscoveryTimeoutMs()).isEqualTo(1500);
        assertThat(settings.getMaxResponseBytes()).isEqualTo(2 * 1024 * 1024);
    }

    @Test
    @DisplayName("默认值与 ONVIF 规范对齐：设备服务路径、多播开、无单播目标、无发现凭据")
    void defaults_alignedWithOnvifSpec() {
        OnvifSettings settings = new OnvifSettings();

        assertThat(settings.getDeviceServicePath()).isEqualTo("/onvif/device_service");
        assertThat(settings.isDiscoveryMulticastEnabled()).isTrue();
        assertThat(settings.getDiscoveryUnicastTargets()).isEmpty();
        assertThat(settings.getDiscoveryUsername()).isEmpty();
        assertThat(settings.getDiscoveryPassword()).isEmpty();
    }

    @Test
    @DisplayName("setMode(null) 回到 simulated，不留下 null 模式")
    void setMode_nullFallsBackToSimulated() {
        OnvifSettings settings = new OnvifSettings();
        settings.setMode(OnvifSettings.Mode.LIVE);

        settings.setMode(null);

        assertThat(settings.getMode()).isEqualTo(OnvifSettings.Mode.SIMULATED);
        assertThat(settings.isLive()).isFalse();
    }

    @Test
    @DisplayName("setDiscoveryUnicastTargets(null) 归一成空列表，不留 null")
    void setUnicastTargets_nullBecomesEmptyList() {
        OnvifSettings settings = new OnvifSettings();

        settings.setDiscoveryUnicastTargets(null);

        assertThat(settings.getDiscoveryUnicastTargets()).isNotNull().isEmpty();
    }
}

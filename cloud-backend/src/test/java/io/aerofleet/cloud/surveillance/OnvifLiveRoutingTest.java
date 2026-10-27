package io.aerofleet.cloud.surveillance;

import io.aerofleet.cloud.surveillance.onvif.OnvifSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * live ONVIF 下的来源标注与路由。
 *
 * <p>两件事必须同时成立，否则"真实取流"只是配置里的一个词：
 * <ol>
 *   <li>适配器上报 {@code ONVIF_GETSTREAM_URI}（{@code deviceVerified=true}），
 *       而不是继续报"按规范推导"；</li>
 *   <li>海康/大华/宇视设备真的被路由到 ONVIF 适配器——否则私有 SDK 模拟适配器
 *       仍会给出未验证地址。</li>
 * </ol>
 */
@DisplayName("ONVIF live：来源标注与厂商路由")
class OnvifLiveRoutingTest {

    private static OnvifClient liveClient() {
        OnvifSettings settings = new OnvifSettings();
        settings.setMode(OnvifSettings.Mode.LIVE);
        return new OnvifClient(settings);
    }

    @Test
    @DisplayName("模拟适配器：来源仍是 VENDOR_STANDARD_FORMAT，事件订阅可用")
    void simulatedAdapter_keepsStandardFormatOrigin() {
        OnvifVendorAdapter adapter = new OnvifVendorAdapter(new OnvifClient());

        assertThat(adapter.streamUrlOrigin()).isEqualTo(StreamUrlOrigin.VENDOR_STANDARD_FORMAT);
        assertThat(adapter.supportsEventSubscription()).isTrue();
    }

    @Test
    @DisplayName("live 适配器：来源是 ONVIF_GETSTREAM_URI（deviceVerified=true），事件订阅声明为支持")
    void liveAdapter_reportsDeviceVerifiedOrigin() {
        OnvifVendorAdapter adapter = new OnvifVendorAdapter(liveClient());

        assertThat(adapter.streamUrlOrigin()).isEqualTo(StreamUrlOrigin.ONVIF_GETSTREAM_URI);
        assertThat(adapter.streamUrlOrigin().deviceVerified()).isTrue();
        // live 走真实 PullPoint：适配器层声明"支持"，设备是否有 Events 服务
        // 由 subscribeEvents 当场回答（失败会在建立 SSE 之前变成 5xx）
        assertThat(adapter.supportsEventSubscription()).isTrue();
    }

    @Test
    @DisplayName("live：海康/大华/宇视路由到 ONVIF 适配器（私有 SDK 仍是模拟实现）")
    void liveRouting_sendsPrivateVendorsToOnvif() {
        OnvifVendorAdapter onvif = new OnvifVendorAdapter(liveClient());
        VendorAdapterRegistry registry = new VendorAdapterRegistry(
                List.of(onvif, new HikAdapter(), new DahuaAdapter(), new UniviewAdapter()),
                liveSettings());

        assertThat(registry.getAdapter(Vendor.HIKVISION)).isSameAs(onvif);
        assertThat(registry.getAdapter(Vendor.DAHUA)).isSameAs(onvif);
        assertThat(registry.getAdapter(Vendor.UNIVIEW)).isSameAs(onvif);
    }

    @Test
    @DisplayName("模拟（默认）：路由完全不变——海康仍走海康适配器")
    void simulatedRouting_unchanged() {
        OnvifVendorAdapter onvif = new OnvifVendorAdapter(new OnvifClient());
        VendorAdapterRegistry registry = new VendorAdapterRegistry(
                List.of(onvif, new HikAdapter()));

        assertThat(registry.getAdapter(Vendor.HIKVISION))
                .isInstanceOf(HikAdapter.class);
    }

    private static OnvifSettings liveSettings() {
        OnvifSettings settings = new OnvifSettings();
        settings.setMode(OnvifSettings.Mode.LIVE);
        return settings;
    }
}

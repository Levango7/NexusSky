package io.aerofleet.cloud.surveillance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link RtmpAdapter} 单测（纯元数据/URL 构造，无网络依赖）。
 *
 * <p>判据：推流/拉流地址按标准格式构造、Vendor.RTMP 识别、能力/事件/PTZ 语义与
 * GB28181/ONVIF 对齐（不支持 → 抛异常/返回 false）。
 */
@DisplayName("RtmpAdapter：RTMP 推流/拉流适配器")
class RtmpAdapterTest {

    private static final String SERVER_HOST = "192.168.1.100";
    private static final int SERVER_PORT = 1935;
    private static final String APP_NAME = "live";

    private final RtmpAdapter adapter = new RtmpAdapter(SERVER_HOST, SERVER_PORT, APP_NAME);

    private SurveillanceDevice device() {
        return new SurveillanceDevice("rtmp-cam-1", "RTMP 推流摄像头",
                SurveillanceDevice.Vendor.RTMP, "192.168.1.50", 1935, "user", "pass");
    }

    // ===== 基础标识 =====

    @Test
    @DisplayName("getVendor() = Vendor.RTMP")
    void vendor() {
        assertThat(adapter.getVendor()).isEqualTo(Vendor.RTMP);
    }

    // ===== 发现（无标准协议）=====

    @Test
    @DisplayName("discover() 返回空列表（RTMP 无标准发现协议）")
    void discover_returnsEmptyList() {
        assertThat(adapter.discover("ignored")).isEmpty();
    }

    // ===== 拉流（播放）=====

    @Test
    @DisplayName("getStreamUrl：构造标准 rtmp:// 可播地址，带凭据、端口、应用名、streamKey")
    void getStreamUrl_standardRtmpUrl() {
        String url = adapter.getStreamUrl(device(), 1);

        // 默认端口 1935 不出现在 URL 中
        assertThat(url).isEqualTo("rtmp://user:pass@192.168.1.100/live/rtmp-cam-1_1");
    }

    @Test
    @DisplayName("getStreamUrl：streamKey 语义 = deviceId_channel")
    void getStreamUrl_streamKeyFormat() {
        String url = adapter.getStreamUrl(device(), 3);

        assertThat(url).contains("rtmp-cam-1_3");
    }

    @Test
    @DisplayName("getStreamUrl：默认端口 1935 不出现在 URL 中")
    void getStreamUrl_defaultPortOmitted() {
        RtmpAdapter adapterDefault = new RtmpAdapter("192.168.1.100", 1935, "live");
        String url = adapterDefault.getStreamUrl(device(), 1);

        assertThat(url).doesNotContain(":1935");
    }

    @Test
    @DisplayName("getStreamUrl：非标准端口出现在 URL 中")
    void getStreamUrl_nonStandardPortIncluded() {
        RtmpAdapter adapterNonStd = new RtmpAdapter("192.168.1.100", 1936, "live");
        String url = adapterNonStd.getStreamUrl(device(), 1);

        assertThat(url).contains(":1936");
    }

    @Test
    @DisplayName("getStreamUrl：自定义应用名出现在路径中")
    void getStreamUrl_customAppName() {
        RtmpAdapter adapterApp = new RtmpAdapter("192.168.1.100", 1935, "streaming");
        String url = adapterApp.getStreamUrl(device(), 1);

        assertThat(url).contains("/streaming/");
    }

    @Test
    @DisplayName("getPublishUrl：空 streamKey 抛异常")
    void getPublishUrl_emptyStreamKey_throws() {
        assertThatThrownBy(() -> adapter.getPublishUrl("user", "pass", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("streamKey");
    }

    @Test
    @DisplayName("getPublishUrl：空白 streamKey 抛异常")
    void getPublishUrl_blankStreamKey_throws() {
        assertThatThrownBy(() -> adapter.getPublishUrl("user", "pass", "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("streamKey");
    }

    @Test
    @DisplayName("getPublishUrl：null streamKey 抛异常")
    void getPublishUrl_nullStreamKey_throws() {
        assertThatThrownBy(() -> adapter.getPublishUrl("user", "pass", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("streamKey");
    }

    // ===== 推流 =====

    @Test
    @DisplayName("getPublishUrl：构造标准 rtmp:// 推流地址，同拉流格式")
    void getPublishUrl_standardRtmpUrl() {
        String url = adapter.getPublishUrl("user", "pass", "stream-key-123");

        // 默认端口 1935 不出现在 URL 中
        assertThat(url).isEqualTo("rtmp://user:pass@192.168.1.100/live/stream-key-123");
    }

    // ===== PTZ（RTMP 规范无定义）=====

    @Test
    @DisplayName("ptzControl：抛 UnsupportedOperationException（规范无 PTZ，fallback 到 ONVIF/私有）")
    void ptzControl_throwsUnsupported() {
        assertThatThrownBy(() -> adapter.ptzControl(device(), "up"))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("RTMP specification does not define PTZ");
    }

    @Test
    @DisplayName("ptzControl：所有命令均抛 UnsupportedOperationException")
    void ptzControl_allCommandsUnsupported() {
        for (String cmd : new String[]{"up", "down", "left", "right", "zoomIn", "zoomOut", "stop"}) {
            assertThatThrownBy(() -> adapter.ptzControl(device(), cmd))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    // ===== 事件订阅（不支持）=====

    @Test
    @DisplayName("supportsEventSubscription() = false")
    void supportsEventSubscription_false() {
        assertThat(adapter.supportsEventSubscription()).isFalse();
    }

    @Test
    @DisplayName("subscribeEvents：抛 UnsupportedOperationException")
    void subscribeEvents_throws() {
        assertThatThrownBy(() -> adapter.subscribeEvents(device(), p -> {}))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("event subscription");
    }

    @Test
    @DisplayName("unsubscribeEvents：幂等 no-op（记录 debug 日志）")
    void unsubscribeEvents_noOp() {
        adapter.unsubscribeEvents("any-handle");
        // 不抛异常即通过
    }

    // ===== 能力 =====

    @Test
    @DisplayName("getCapabilities：仅返回 STREAM")
    void getCapabilities_streamOnly() {
        Set<String> caps = adapter.getCapabilities(device());

        assertThat(caps).containsExactly("STREAM");
    }

    // ===== 流地址来源 =====

    @Test
    @DisplayName("streamUrlOrigin = VENDOR_STANDARD_FORMAT（可播、未验证设备端）")
    void streamUrlOrigin_vendorStandardFormat() {
        assertThat(adapter.streamUrlOrigin()).isEqualTo(StreamUrlOrigin.VENDOR_STANDARD_FORMAT);
    }

    // ===== 入参校验 =====

    @Test
    @DisplayName("null 设备被拦截")
    void validate_rejectsNull() {
        assertThatThrownBy(() -> adapter.getStreamUrl(null, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("device");
    }

    @Test
    @DisplayName("非 RTMP vendor 被拦截")
    void validate_rejectsWrongVendor() {
        SurveillanceDevice wrong = new SurveillanceDevice("onvif-1", "ONVIF 设备",
                SurveillanceDevice.Vendor.ONVIF, "192.168.1.50", 8899, "admin", "pass");

        assertThatThrownBy(() -> adapter.getStreamUrl(wrong, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RtmpAdapter only accepts RTMP devices");
    }

    @Test
    @DisplayName("空 id 设备被拦截（构造器校验优先）")
    void validate_rejectsBlankId() {
        assertThatThrownBy(() -> new SurveillanceDevice("  ", "空 ID",
                SurveillanceDevice.Vendor.RTMP, "192.168.1.50", 1935, "user", "pass"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("device id must not be blank");
    }
}
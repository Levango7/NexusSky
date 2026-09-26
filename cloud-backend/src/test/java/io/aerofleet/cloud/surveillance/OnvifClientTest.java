package io.aerofleet.cloud.surveillance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OnvifClient 单元测试。
 * <p>
 * 直接实例化（无 Spring 上下文），覆盖：设备发现/能力获取/RTSP URL/PTZ 控制/事件订阅/错误处理。
 */
@DisplayName("OnvifClient ONVIF 协议客户端")
class OnvifClientTest {

    private static final String IP = "192.168.1.100";
    private static final int PORT = 80;
    private static final String USER = "admin";
    private static final String PASS = "pass123";

    // ===== 设备发现 =====

    @Test
    @DisplayName("discoverDevices 返回 3 个预设设备（海康/大华/宇视各一）")
    void discoverDevices_returnsThreePresetDevices() {
        OnvifClient client = new OnvifClient();

        List<SurveillanceDevice> devices = client.discoverDevices("192.168.1.0/24");

        assertThat(devices).hasSize(3);
        // 三家厂商各一
        long hikCount = devices.stream()
                .filter(d -> d.vendor == SurveillanceDevice.Vendor.HIKVISION).count();
        long dahuaCount = devices.stream()
                .filter(d -> d.vendor == SurveillanceDevice.Vendor.DAHUA).count();
        long univiewCount = devices.stream()
                .filter(d -> d.vendor == SurveillanceDevice.Vendor.UNIVIEW).count();
        assertThat(hikCount).isEqualTo(1);
        assertThat(dahuaCount).isEqualTo(1);
        assertThat(univiewCount).isEqualTo(1);
    }

    @Test
    @DisplayName("discoverDevices 设备 IP 前缀匹配子网")
    void discoverDevices_ipPrefixMatchesSubnet() {
        OnvifClient client = new OnvifClient();

        List<SurveillanceDevice> devices = client.discoverDevices("10.0.0.0/24");

        assertThat(devices).isNotEmpty();
        assertThat(devices).allMatch(d -> d.ip.startsWith("10.0.0."));
    }

    @Test
    @DisplayName("discoverDevices null/blank subnet 抛 IllegalArgumentException")
    void discoverDevices_blankSubnet_throws() {
        OnvifClient client = new OnvifClient();
        assertThatThrownBy(() -> client.discoverDevices(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("subnet");
        assertThatThrownBy(() -> client.discoverDevices(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ===== 能力获取 =====

    @Test
    @DisplayName("getDeviceCapabilities 默认端口返回包含 Media/PTZ/Events 的能力集")
    void getDeviceCapabilities_defaultPort_includesMediaAndPtz() {
        OnvifClient client = new OnvifClient();

        Set<String> caps = client.getDeviceCapabilities(IP, PORT, USER, PASS);

        assertThat(caps).contains("Device", "Media", "Events", "PTZ", "Imaging");
    }

    @Test
    @DisplayName("getDeviceCapabilities 非默认端口不包含 PTZ")
    void getDeviceCapabilities_nonDefaultPort_excludesPtz() {
        OnvifClient client = new OnvifClient();

        Set<String> caps = client.getDeviceCapabilities(IP, 8080, USER, PASS);

        assertThat(caps).contains("Device", "Media", "Events");
        assertThat(caps).doesNotContain("PTZ");
    }

    @Test
    @DisplayName("getDeviceCapabilities 非法端口抛 IllegalArgumentException")
    void getDeviceCapabilities_invalidPort_throws() {
        OnvifClient client = new OnvifClient();
        assertThatThrownBy(() -> client.getDeviceCapabilities(IP, 0, USER, PASS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("port");
        assertThatThrownBy(() -> client.getDeviceCapabilities(IP, 70000, USER, PASS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ===== RTSP URL =====

    @Test
    @DisplayName("getRtspUrl 返回包含凭据与通道号的 rtsp:// URL")
    void getRtspUrl_returnsUrlWithCredentialsAndChannel() {
        OnvifClient client = new OnvifClient();

        String url = client.getRtspUrl(IP, PORT, USER, PASS, 1);

        assertThat(url).startsWith("rtsp://");
        assertThat(url).contains(USER);
        assertThat(url).contains(PASS);
        assertThat(url).contains(IP);
        assertThat(url).contains("Channels/1");
    }

    @Test
    @DisplayName("getRtspUrl 不同通道号生成不同 URL")
    void getRtspUrl_differentChannels_differentUrls() {
        OnvifClient client = new OnvifClient();

        String url1 = client.getRtspUrl(IP, PORT, USER, PASS, 1);
        String url2 = client.getRtspUrl(IP, PORT, USER, PASS, 2);

        assertThat(url1).isNotEqualTo(url2);
    }

    @Test
    @DisplayName("getRtspUrl 非法通道号抛 IllegalArgumentException")
    void getRtspUrl_invalidChannel_throws() {
        OnvifClient client = new OnvifClient();
        assertThatThrownBy(() -> client.getRtspUrl(IP, PORT, USER, PASS, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channel");
    }

    // ===== PTZ 控制 =====

    @Test
    @DisplayName("ptzControl 所有支持命令返回 ok")
    void ptzControl_allSupportedCommandsReturnOk() {
        OnvifClient client = new OnvifClient();
        for (String cmd : OnvifClient.PTZ_COMMANDS) {
            String result = client.ptzControl(IP, PORT, USER, PASS, cmd);
            assertThat(result).isEqualTo("ok");
        }
    }

    @Test
    @DisplayName("ptzControl 不支持的命令抛 IllegalArgumentException")
    void ptzControl_unsupportedCommand_throws() {
        OnvifClient client = new OnvifClient();
        assertThatThrownBy(() -> client.ptzControl(IP, PORT, USER, PASS, "panLeft"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported ptz command");
    }

    @Test
    @DisplayName("ptzControl null 命令抛 IllegalArgumentException")
    void ptzControl_nullCommand_throws() {
        OnvifClient client = new OnvifClient();
        assertThatThrownBy(() -> client.ptzControl(IP, PORT, USER, PASS, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ===== 事件订阅 =====

    @Test
    @DisplayName("subscribeEvents 返回非空句柄且 activeSubscriberCount 增加")
    void subscribeEvents_returnsHandleAndIncrementsCount() {
        OnvifClient client = new OnvifClient();
        int initial = client.activeSubscriberCount();

        String handle = client.subscribeEvents(IP, PORT, USER, PASS, payload -> {});

        assertThat(handle).isNotBlank();
        assertThat(client.activeSubscriberCount()).isEqualTo(initial + 1);
    }

    @Test
    @DisplayName("simulateEvent 触发已注册回调")
    void simulateEvent_triggersCallback() {
        OnvifClient client = new OnvifClient();
        AtomicReference<String> received = new AtomicReference<>();
        String handle = client.subscribeEvents(IP, PORT, USER, PASS, received::set);

        boolean ok = client.simulateEvent(handle, "<Event>Motion</Event>");

        assertThat(ok).isTrue();
        assertThat(received.get()).isEqualTo("<Event>Motion</Event>");
    }

    @Test
    @DisplayName("unsubscribeEvents 后 simulateEvent 返回 false")
    void unsubscribeEvents_thenSimulateReturnsFalse() {
        OnvifClient client = new OnvifClient();
        String handle = client.subscribeEvents(IP, PORT, USER, PASS, payload -> {});
        int countBefore = client.activeSubscriberCount();

        client.unsubscribeEvents(handle);

        assertThat(client.activeSubscriberCount()).isEqualTo(countBefore - 1);
        assertThat(client.simulateEvent(handle, "test")).isFalse();
    }

    @Test
    @DisplayName("subscribeEvents null callback 抛 IllegalArgumentException")
    void subscribeEvents_nullCallback_throws() {
        OnvifClient client = new OnvifClient();
        assertThatThrownBy(() -> client.subscribeEvents(IP, PORT, USER, PASS, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("callback");
    }

    // ===== 错误处理 =====

    @Test
    @DisplayName("getRtspUrl 空 IP 抛 IllegalArgumentException")
    void getRtspUrl_blankIp_throws() {
        OnvifClient client = new OnvifClient();
        assertThatThrownBy(() -> client.getRtspUrl("", PORT, USER, PASS, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ip");
    }

    @Test
    @DisplayName("getDeviceCapabilities 空用户名抛 IllegalArgumentException")
    void getDeviceCapabilities_blankUser_throws() {
        OnvifClient client = new OnvifClient();
        assertThatThrownBy(() -> client.getDeviceCapabilities(IP, PORT, "", PASS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("user");
    }

    @Test
    @DisplayName("ptzControl 空密码抛 IllegalArgumentException")
    void ptzControl_emptyPassword_throws() {
        OnvifClient client = new OnvifClient();
        assertThatThrownBy(() -> client.ptzControl(IP, PORT, USER, "", "up"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pass");
    }
}
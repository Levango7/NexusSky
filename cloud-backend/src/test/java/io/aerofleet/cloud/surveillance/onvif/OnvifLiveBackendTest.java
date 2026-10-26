package io.aerofleet.cloud.surveillance.onvif;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link OnvifLiveBackend} 对<b>真的 HTTP</b> ONVIF 设备（{@link HttpServer} 模拟固件）的行为。
 *
 * <p>服务端按 SOAP 操作名回标准响应；请求体也被捕获，用于断言"通道号真的落到了
 * 哪个 ProfileToken"和"PTZ 速度向量到底发了什么"——只看返回的 "ok" 证明不了这些。
 */
@DisplayName("OnvifLiveBackend 真实 SOAP：能力/取流/PTZ")
class OnvifLiveBackendTest {

    private static final String USER = "admin";
    private static final String PASS = "pass123";

    private HttpServer server;
    private int port;
    private String base;

    private volatile boolean mediaPresent = true;
    private volatile boolean ptzPresent = true;
    private volatile boolean imagingPresent = false;
    private volatile List<Profile> profiles = List.of(
            new Profile("profile_1", "Main"),
            new Profile("profile_2", "Sub"));
    private volatile String streamUri =
            "rtsp://127.0.0.1:8554/Streaming/Channels/101?transportmode=unicast";

    private final List<String> requests = new CopyOnWriteArrayList<>();

    private record Profile(String token, String name) {
    }

    private OnvifSettings settings;

    @BeforeEach
    void startFakeDevice() throws Exception {
        settings = new OnvifSettings();
        settings.setMode(OnvifSettings.Mode.LIVE);
        settings.setConnectTimeoutMs(2000);
        settings.setReadTimeoutMs(3000);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        base = "http://127.0.0.1:" + port;

        server.createContext("/onvif/device_service", ex -> handle(ex, "device"));
        server.createContext("/onvif/media", ex -> handle(ex, "media"));
        server.createContext("/onvif/ptz", ex -> handle(ex, "ptz"));
        server.start();
    }

    @AfterEach
    void stopFakeDevice() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ===== 能力 =====

    @Test
    @DisplayName("GetCapabilities：按 XAddr 如实映射 Media/PTZ/Events，没声明的 Imaging 不虚报")
    void capabilities_mappedFromXAddr() {
        Set<String> caps = backend().getDeviceCapabilities("127.0.0.1", port, USER, PASS);

        assertThat(caps).containsExactlyInAnyOrder("Device", "Media", "PTZ", "Events");
        assertThat(caps).doesNotContain("Imaging");
    }

    @Test
    @DisplayName("GetCapabilities：设备声明 Imaging 时才出现 Imaging")
    void capabilities_imagingOnlyWhenDeclared() {
        imagingPresent = true;

        assertThat(backend().getDeviceCapabilities("127.0.0.1", port, USER, PASS))
                .contains("Imaging");
    }

    // ===== 取流 =====

    @Test
    @DisplayName("GetStreamUri：地址来自设备，凭据按 RFC 3986 注入，端口/路径/查询串原样保留")
    void getRtspUrl_deviceVerifiedWithEncodedCredentials() {
        String url = backend().getRtspUrl("127.0.0.1", port, USER, "p@ss w", 1);

        assertThat(url).isEqualTo(
                "rtsp://admin:p%40ss%20w@127.0.0.1:8554/Streaming/Channels/101?transportmode=unicast");
        assertThat(requests).anyMatch(r -> r.contains("GetStreamUri"))
                .anyMatch(r -> r.contains("<trt:ProfileToken>profile_1</trt:ProfileToken>"));
    }

    @Test
    @DisplayName("通道号 2 映射到第二个 Profile，而不是永远回第一路")
    void getRtspUrl_channelTwoUsesSecondProfile() {
        backend().getRtspUrl("127.0.0.1", port, USER, PASS, 2);

        assertThat(requests).anyMatch(r -> r.contains("<trt:ProfileToken>profile_2</trt:ProfileToken>"));
    }

    @Test
    @DisplayName("通道号超过设备 profile 数：明确报错并说明实际有几路")
    void getRtspUrl_channelBeyondProfiles_throws() {
        assertThatThrownBy(() -> backend().getRtspUrl("127.0.0.1", port, USER, PASS, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reports 2 media profile(s)");
    }

    @Test
    @DisplayName("设备没有 Media 服务：报错，不回退成猜的 rtsp 地址")
    void getRtspUrl_noMediaService_throws() {
        mediaPresent = false;

        assertThatThrownBy(() -> backend().getRtspUrl("127.0.0.1", port, USER, PASS, 1))
                .isInstanceOf(OnvifException.class)
                .hasMessageContaining("Media service");
    }

    @Test
    @DisplayName("设备返回非 RTSP 地址：不能当可播流，明确报错")
    void getRtspUrl_nonRtsp_throws() {
        streamUri = "http://127.0.0.1:8080/snapshot.jpg";

        assertThatThrownBy(() -> backend().getRtspUrl("127.0.0.1", port, USER, PASS, 1))
                .isInstanceOf(OnvifException.class)
                .hasMessageContaining("non-RTSP");
    }

    @Test
    @DisplayName("GetProfiles 为空：报错，不拿空 token 去请求")
    void getRtspUrl_emptyProfiles_throws() {
        profiles = List.of();

        assertThatThrownBy(() -> backend().getRtspUrl("127.0.0.1", port, USER, PASS, 1))
                .isInstanceOf(OnvifException.class)
                .hasMessageContaining("no media profiles");
        assertThat(requests).noneMatch(r -> r.contains("GetStreamUri"));
    }

    // ===== PTZ =====

    @Test
    @DisplayName("PTZ right：发 ContinuousMove，PanTilt x=0.5、Zoom=0")
    void ptz_right_sendsContinuousMoveVector() {
        assertThat(backend().ptzControl("127.0.0.1", port, USER, PASS, "right")).isEqualTo("ok");

        String ptzRequest = lastRequest("ptz");
        assertThat(ptzRequest)
                .contains("<tptz:ContinuousMove>")
                .contains("<tt:PanTilt x=\"0.5\" y=\"0.0\"/>")
                .contains("<tt:Zoom x=\"0.0\"/>")
                .contains("<tptz:ProfileToken>profile_1</tptz:ProfileToken>");
    }

    @Test
    @DisplayName("PTZ zoomIn / up：速度向量落到对应分量，不是只回一个 ok")
    void ptz_zoomAndTilt_useCorrectAxis() {
        backend().ptzControl("127.0.0.1", port, USER, PASS, "zoomIn");
        assertThat(lastRequest("ptz")).contains("<tt:Zoom x=\"0.5\"/>");

        backend().ptzControl("127.0.0.1", port, USER, PASS, "up");
        assertThat(lastRequest("ptz")).contains("<tt:PanTilt x=\"0.0\" y=\"0.5\"/>");
    }

    @Test
    @DisplayName("PTZ stop：发 Stop 且 PanTilt/Zoom 都置 true")
    void ptz_stop_sendsStopFlags() {
        backend().ptzControl("127.0.0.1", port, USER, PASS, "stop");

        assertThat(lastRequest("ptz"))
                .contains("<tptz:Stop>")
                .contains("<tptz:PanTilt>true</tptz:PanTilt>")
                .contains("<tptz:Zoom>true</tptz:Zoom>");
    }

    @Test
    @DisplayName("设备没有 PTZ 服务：报错，不发请求")
    void ptz_noPtzService_throws() {
        ptzPresent = false;

        assertThatThrownBy(() -> backend().ptzControl("127.0.0.1", port, USER, PASS, "up"))
                .isInstanceOf(OnvifException.class)
                .hasMessageContaining("PTZ service");
    }

    @Test
    @DisplayName("参数校验与模拟后端同一套措辞（live 不另写一套）")
    void validation_messagesSharedWithSimulated() {
        OnvifLiveBackend live = backend();

        assertThatThrownBy(() -> live.getRtspUrl("", port, USER, PASS, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ip must not be blank");
        assertThatThrownBy(() -> live.ptzControl("127.0.0.1", port, USER, "", "up"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pass must not be empty");
        assertThatThrownBy(() -> live.ptzControl("127.0.0.1", port, USER, PASS, "panLeft"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported ptz command");
    }

    @Test
    @DisplayName("通道 0/负数在校验阶段就拒绝，不拿它去问设备")
    void getRtspUrl_invalidChannel_rejectedBeforeNetwork() {
        assertThatThrownBy(() -> backend().getRtspUrl("127.0.0.1", port, USER, PASS, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channel");

        assertThat(requests).isEmpty();
    }

    @Test
    @DisplayName("设备给不带端口或 0 端口的 RTSP 地址：按 554 补齐（0 不是可播端口）")
    void getRtspUrl_portZeroOrMissing_defaultsTo554() {
        streamUri = "rtsp://10.9.8.7:0/Streaming/Channels/101";
        assertThat(backend().getRtspUrl("127.0.0.1", port, USER, PASS, 1))
                .isEqualTo("rtsp://admin:pass123@10.9.8.7:554/Streaming/Channels/101");

        streamUri = "rtsp://10.9.8.7/Streaming/Channels/101";
        assertThat(backend().getRtspUrl("127.0.0.1", port, USER, PASS, 1))
                .isEqualTo("rtsp://admin:pass123@10.9.8.7:554/Streaming/Channels/101");
    }

    @Test
    @DisplayName("device-service-path 为空：回退到 ONVIF 规范默认路径（不能拼出 http://ip:port 这种裸地址）")
    void capabilities_blankDeviceServicePath_fallsBackToDefault() {
        settings.setDeviceServicePath("");

        assertThat(backend().getDeviceCapabilities("127.0.0.1", port, USER, PASS))
                .containsExactlyInAnyOrder("Device", "Media", "PTZ", "Events");
        assertThat(requests).anyMatch(r -> r.startsWith("device "));
    }

    @Test
    @DisplayName("device-service-path 没带斜杠：归一成绝对路径再拼 URL")
    void capabilities_pathWithoutLeadingSlash_normalized() {
        settings.setDeviceServicePath("onvif/device_service");

        assertThat(backend().getDeviceCapabilities("127.0.0.1", port, USER, PASS))
                .containsExactlyInAnyOrder("Device", "Media", "PTZ", "Events");
    }

    @Test
    @DisplayName("device-service-path 为 null：回退到规范默认路径（不留空路径拼出裸地址）")
    void capabilities_nullDeviceServicePath_fallsBackToDefault() {
        settings.setDeviceServicePath(null);

        assertThat(backend().getDeviceCapabilities("127.0.0.1", port, USER, PASS))
                .containsExactlyInAnyOrder("Device", "Media", "PTZ", "Events");
    }

    // ===== 设备端实现 =====

    private OnvifLiveBackend backend() {
        return new OnvifLiveBackend(settings);
    }

    private String lastRequest(String service) {
        List<String> matching = new ArrayList<>();
        for (String request : requests) {
            if (request.startsWith(service + " ")) {
                matching.add(request);
            }
        }
        assertThat(matching).as("requests to " + service).isNotEmpty();
        return matching.get(matching.size() - 1);
    }

    private void handle(HttpExchange exchange, String service) throws java.io.IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(service + " " + body);
        String response;
        if ("device".equals(service)) {
            response = capabilitiesResponse();
        } else if ("media".equals(service)) {
            response = body.contains("GetProfiles") ? profilesResponse() : streamUriResponse();
        } else {
            if (body.contains("<tptz:Stop>")) {
                response = "<tptz:StopResponse/>";
            } else {
                response = "<tptz:ContinuousMoveResponse/>";
            }
        }
        send(exchange, envelope(response));
    }

    private static void send(HttpExchange exchange, String xml) throws java.io.IOException {
        byte[] payload = xml.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/soap+xml; charset=utf-8");
        exchange.sendResponseHeaders(200, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
        exchange.close();
    }

    private String capabilitiesResponse() {
        StringBuilder xml = new StringBuilder("<tds:GetCapabilitiesResponse><tds:Capabilities>");
        xml.append("<tt:Device><tt:XAddr>").append(base)
                .append("/onvif/device_service</tt:XAddr></tt:Device>");
        if (mediaPresent) {
            xml.append("<tt:Media><tt:XAddr>").append(base).append("/onvif/media</tt:XAddr></tt:Media>");
        }
        if (ptzPresent) {
            xml.append("<tt:PTZ><tt:XAddr>").append(base).append("/onvif/ptz</tt:XAddr></tt:PTZ>");
        }
        xml.append("<tt:Events><tt:XAddr>").append(base).append("/onvif/events</tt:XAddr></tt:Events>");
        if (imagingPresent) {
            xml.append("<tt:Imaging><tt:XAddr>").append(base).append("/onvif/imaging</tt:XAddr></tt:Imaging>");
        }
        return xml.append("</tds:Capabilities></tds:GetCapabilitiesResponse>").toString();
    }

    private String profilesResponse() {
        StringBuilder xml = new StringBuilder("<trt:GetProfilesResponse>");
        for (Profile profile : profiles) {
            xml.append("<trt:Profiles token=\"").append(profile.token).append("\" fixed=\"true\">")
                    .append("<tt:Name>").append(profile.name).append("</tt:Name>")
                    .append("</trt:Profiles>");
        }
        return xml.append("</trt:GetProfilesResponse>").toString();
    }

    private String streamUriResponse() {
        return "<trt:GetStreamUriResponse><trt:MediaUri><tt:Uri>" + streamUri
                + "</tt:Uri></trt:MediaUri></trt:GetStreamUriResponse>";
    }

    private static String envelope(String body) {
        return "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\""
                + " xmlns:tds=\"http://www.onvif.org/ver10/device/wsdl\""
                + " xmlns:trt=\"http://www.onvif.org/ver10/media/wsdl\""
                + " xmlns:tptz=\"http://www.onvif.org/ver20/ptz/wsdl\""
                + " xmlns:tt=\"http://www.onvif.org/ver10/schema\">"
                + "<s:Body>" + body + "</s:Body></s:Envelope>";
    }
}

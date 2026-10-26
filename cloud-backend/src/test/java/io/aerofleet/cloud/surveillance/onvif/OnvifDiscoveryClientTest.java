package io.aerofleet.cloud.surveillance.onvif;

import io.aerofleet.cloud.surveillance.SurveillanceDevice;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link OnvifDiscoveryClient} 测试：真的发 UDP 探针，真的收 ProbeMatch。
 *
 * <p>被测的不是"解析一段固定 XML"，而是"探针能不能到、应答能不能回来、
 * 回的东西能不能被解成设备"。所以这里起一个真的 {@link DatagramSocket}
 * 当设备端，客户端走真实 socket 路径（只把多播关掉，改用单播目标，
 * 避免测试向真实局域网发探针）。
 */
@DisplayName("OnvifDiscoveryClient WS-Discovery")
class OnvifDiscoveryClientTest {

    private DatagramSocket responder;
    private Thread responderThread;
    private final AtomicReference<String> receivedProbe = new AtomicReference<>();
    private final AtomicReference<Throwable> responderFailure = new AtomicReference<>();
    /** 收到的探针数：用于断言"非法目标没有发探针"。 */
    private final AtomicInteger probeCount = new AtomicInteger();

    /** 子类测试可先设置一个自定义 ProbeMatch；默认用 HIKVISION 那台。 */
    private volatile String probeMatch;
    /** 关掉应答以测超时。 */
    private volatile boolean replyEnabled = true;
    /** 非空时这组应答按序全部发出（用于"一包 64 台 + 第二包 1 台"这类截断场景）。 */
    private volatile List<String> replyPayloads;

    private OnvifSettings settings;

    @BeforeEach
    void startResponder() throws Exception {
        settings = new OnvifSettings();
        settings.setMode(OnvifSettings.Mode.LIVE);
        settings.setDiscoveryTimeoutMs(1500);
        settings.setDiscoveryMulticastEnabled(false);
        settings.setDiscoveryUsername("admin");
        settings.setDiscoveryPassword("secret");

        responder = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
        settings.setDiscoveryUnicastTargets(List.of("127.0.0.1:" + responder.getLocalPort()));

        responderThread = new Thread(this::serveOneProbe, "onvif-discovery-responder");
        responderThread.setDaemon(true);
        responderThread.start();
    }

    @AfterEach
    void stopResponder() throws Exception {
        if (responder != null) {
            responder.close();
        }
        if (responderThread != null) {
            responderThread.join(2000);
        }
        assertThat(responderFailure.get()).as("responder failure").isNull();
    }

    @Test
    @DisplayName("单播 Probe → ProbeMatch：解析出 IP/端口/厂商/名称/凭据")
    void discover_parsesProbeMatch() {
        List<SurveillanceDevice> devices = new OnvifDiscoveryClient(settings)
                .discover("10.1.2.0/24");

        assertThat(receivedProbe.get())
                .contains("Probe")
                .contains("NetworkVideoTransmitter")
                .contains("urn:schemas-xmlsoap-org:ws:2005:04:discovery");
        assertThat(devices).hasSize(1);
        SurveillanceDevice device = devices.get(0);
        assertThat(device.ip).isEqualTo("10.1.2.3");
        assertThat(device.port).isEqualTo(80);
        assertThat(device.vendor).isEqualTo(SurveillanceDevice.Vendor.HIKVISION);
        assertThat(device.name).isEqualTo("HIKVISION");
        assertThat(device.id).isEqualTo("onvif-2c5f4d1e");
        assertThat(device.username).isEqualTo("admin");
        assertThat(device.password).isEqualTo("secret");
    }

    @Test
    @DisplayName("ProbeMatch 里的名称按 RFC 3986 解码（%20 是空格，不是 '+' 语义）")
    void discover_decodesNameScope() {
        probeMatch = matchWithScopes(
                "onvif://www.onvif.org/type/video_encoder "
                        + "onvif://www.onvif.org/name/Front%20Gate%2B1");

        List<SurveillanceDevice> devices = new OnvifDiscoveryClient(settings)
                .discover("10.1.2.0/24");

        assertThat(devices).hasSize(1);
        assertThat(devices.get(0).name).isEqualTo("Front Gate+1");
        assertThat(devices.get(0).vendor).isEqualTo(SurveillanceDevice.Vendor.ONVIF);
    }

    @Test
    @DisplayName("subnet 过滤：网段外的应答被丢弃（live 的 subnet 只做过滤，不指导探针）")
    void discover_subnetFilterDropsOutOfRange() {
        List<SurveillanceDevice> devices = new OnvifDiscoveryClient(settings)
                .discover("192.168.9.0/24");

        assertThat(devices).isEmpty();
    }

    @Test
    @DisplayName("没有配置发现凭据：明确报错（ProbeMatch 里没有账号，不能拿占位账号登记）")
    void discover_withoutCredentials_throws() {
        settings.setDiscoveryUsername("");
        settings.setDiscoveryPassword("");

        assertThatThrownBy(() -> new OnvifDiscoveryClient(settings).discover("10.1.2.0/24"))
                .isInstanceOf(OnvifException.class)
                .hasMessageContaining("discovery-username");
    }

    @Test
    @DisplayName("无应答：超时后返回空列表，不抛异常")
    void discover_noResponse_returnsEmpty() {
        replyEnabled = false;
        settings.setDiscoveryTimeoutMs(200);

        assertThat(new OnvifDiscoveryClient(settings).discover("10.1.2.0/24")).isEmpty();
    }

    @Test
    @DisplayName("空白 subnet：校验阶段拒绝（live 下 subnet 只做结果过滤，但参数不能是空白）")
    void discover_blankSubnet_throws() {
        assertThatThrownBy(() -> new OnvifDiscoveryClient(settings).discover(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("subnet");
    }

    @Test
    @DisplayName("多播关闭且没有可用的单播目标：明确报错，不假装扫过了")
    void discover_nothingSent_throws() {
        settings.setDiscoveryUnicastTargets(List.of()); // fixture 已禁用多播

        assertThatThrownBy(() -> new OnvifDiscoveryClient(settings).discover("10.1.2.0/24"))
                .isInstanceOf(OnvifException.class)
                .hasMessageContaining("could not send")
                .hasMessageContaining("disabled"); // 诊断要如实：是多播被禁用，不是"发了没发出去"
    }

    @Test
    @DisplayName("空主机单播目标（\":port\"）被跳过：探针只发给合法目标，不偷偷发到本机")
    void discover_colonPrefixedTarget_skippedNotLocalhost() {
        settings.setDiscoveryUnicastTargets(List.of(
                ":" + responder.getLocalPort(),
                "127.0.0.1:" + responder.getLocalPort()));

        List<SurveillanceDevice> devices = new OnvifDiscoveryClient(settings).discover("10.1.2.0/24");

        assertThat(devices).hasSize(1);
        assertThat(probeCount.get()).as("只有合法目标应收到探针").isEqualTo(1);
    }

    @Test
    @DisplayName("端口 65535（上限本身）是合法目标：越界判定不能把边界端口丢掉")
    void discover_portUpperBound65535_accepted() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(canBind65535(), "本机 65535 被占用时跳过");

        try (DatagramSocket fixed = new DatagramSocket(65535, InetAddress.getByName("127.0.0.1"))) {
            fixed.setSoTimeout(3000);
            Thread fixedResponder = new Thread(() -> {
                try {
                    byte[] buffer = new byte[65535];
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    fixed.receive(packet);
                    byte[] payload = defaultProbeMatch().getBytes(StandardCharsets.UTF_8);
                    fixed.send(new DatagramPacket(payload, payload.length,
                            packet.getAddress(), packet.getPort()));
                } catch (Exception ignored) {
                    // 没收到探针（比如边界被当成越界）时让断言失败，而不是线程挂死
                }
            });
            fixedResponder.setDaemon(true);
            fixedResponder.start();

            settings.setDiscoveryUnicastTargets(List.of("127.0.0.1:65535"));
            assertThat(new OnvifDiscoveryClient(settings).discover("10.1.2.0/24")).hasSize(1);
            fixedResponder.join(2000);
        }
    }

    private static boolean canBind65535() {
        try (DatagramSocket probe = new DatagramSocket(65535, InetAddress.getByName("127.0.0.1"))) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    @DisplayName("XAddr 显式端口 0：按 80 处理（0 不是可连端口）")
    void discover_xAddrPortZero_defaultsTo80() {
        probeMatch = matchWithScopes(DEFAULT_SCOPES, "http://10.1.2.4:0/onvif/device_service");

        List<SurveillanceDevice> devices = new OnvifDiscoveryClient(settings)
                .discover("10.0.0.0/8");

        assertThat(devices).hasSize(1);
        assertThat(devices.get(0).port).isEqualTo(80);
    }

    @Test
    @DisplayName("XAddr 是主机名时不按 IPv4 CIDR 过滤（过滤只认点分四段，别的不过滤）")
    void discover_hostnameXAddr_notCidrFiltered() {
        probeMatch = matchWithScopes(DEFAULT_SCOPES, "http://camera.example/onvif/device_service");

        List<SurveillanceDevice> devices = new OnvifDiscoveryClient(settings)
                .discover("192.168.9.0/24"); // 网段和主机名八竿子打不着，但主机名不参与过滤

        assertThat(devices).hasSize(1);
        assertThat(devices.get(0).ip).isEqualTo("camera.example");
    }

    @Test
    @DisplayName("ProbeMatch 没有 Scopes：名称兜底为主机名，厂商判为 ONVIF，不 NPE")
    void discover_withoutScopes_fallsBackToHostBasedName() {
        probeMatch = probeMatchXml("urn:uuid:9a8b7c6d", null, "http://10.1.2.9/onvif/device_service");

        List<SurveillanceDevice> devices = new OnvifDiscoveryClient(settings)
                .discover("10.1.2.0/24");

        assertThat(devices).hasSize(1);
        assertThat(devices.get(0).name).isEqualTo("ONVIF 设备 10.1.2.9");
        assertThat(devices.get(0).vendor).isEqualTo(SurveillanceDevice.Vendor.ONVIF);
    }

    @Test
    @DisplayName("没有 name scope 时用 hardware scope 兜底设备名")
    void discover_hardwareScopeAsNameFallback() {
        probeMatch = matchWithScopes(
                "onvif://www.onvif.org/type/video_encoder onvif://www.onvif.org/hardware/DS-2CD");

        List<SurveillanceDevice> devices = new OnvifDiscoveryClient(settings)
                .discover("10.1.2.0/24");

        assertThat(devices).hasSize(1);
        assertThat(devices.get(0).name).isEqualTo("DS-2CD");
    }

    @Test
    @DisplayName("达到 64 台后停止继续收包（MAX_DEVICES 是收包循环的条件，不是对最后一个包的截断）")
    void discover_stopsReceivingAtMaxDevices() {
        // 两包不同网段，且 subnet 过滤放宽到 10.0.0.0/8——
        // 若收包上限失效（< 变成 <=），第二包的 64 台会混进结果。
        replyPayloads = List.of(multiMatchPayload(64, 2), multiMatchPayload(64, 3));

        List<SurveillanceDevice> devices = new OnvifDiscoveryClient(settings)
                .discover("10.0.0.0/8");

        assertThat(devices).hasSize(64);
    }

    @Test
    @DisplayName("EndpointReference 地址元素为空：id 退回 onvif-<host>，不因为畸形应答丢设备")
    void discover_emptyEndpointAddress_fallsBackToHostId() {
        probeMatch = "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\""
                + " xmlns:w=\"http://schemas.xmlsoap.org/ws/2004/08/addressing\""
                + " xmlns:d=\"http://schemas.xmlsoap.org/ws/2005/04/discovery\">"
                + "<s:Body><d:ProbeMatches><d:ProbeMatch>"
                + "<w:EndpointReference><w:Address></w:Address></w:EndpointReference>"
                + "<d:Types>dn:NetworkVideoTransmitter</d:Types>"
                + "<d:Scopes>" + DEFAULT_SCOPES + "</d:Scopes>"
                + "<d:XAddrs>http://10.1.2.3/onvif/device_service</d:XAddrs>"
                + "</d:ProbeMatch></d:ProbeMatches></s:Body></s:Envelope>";

        List<SurveillanceDevice> devices = new OnvifDiscoveryClient(settings)
                .discover("10.1.2.0/24");

        assertThat(devices).hasSize(1);
        assertThat(devices.get(0).id).isEqualTo("onvif-10.1.2.3");
    }

    @Test
    @DisplayName("厂商判断看 scopes 全文（不止截出来的名字段）：OEM 地址带厂商词也算数")
    void discover_vendorDetectedFromFullScopesText() {
        // 厂商词出现在 scopes 的非 name/hardware 段里——这不会进设备名，
        // 但厂商推断必须用 scopes 全文（名字兜底里挖不出厂商词时尤其如此）。
        probeMatch = matchWithScopes(
                "onvif://www.onvif.org/type/video_encoder http://oem.example/hikvision-series");

        List<SurveillanceDevice> devices = new OnvifDiscoveryClient(settings)
                .discover("10.1.2.0/24");

        assertThat(devices).hasSize(1);
        assertThat(devices.get(0).name).isEqualTo("ONVIF 设备 10.1.2.3");
        assertThat(devices.get(0).vendor).isEqualTo(SurveillanceDevice.Vendor.HIKVISION);
    }

    // ===== 设备端 =====

    private void serveOneProbe() {
        try {
            byte[] buffer = new byte[65535];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            responder.receive(packet);
            probeCount.incrementAndGet();
            receivedProbe.set(new String(packet.getData(), packet.getOffset(), packet.getLength(),
                    StandardCharsets.UTF_8));
            if (!replyEnabled) {
                Thread.sleep(1000);
                return;
            }
            List<String> payloads = replyPayloads != null ? replyPayloads
                    : List.of(probeMatch == null ? defaultProbeMatch() : probeMatch);
            for (String reply : payloads) {
                byte[] payload = reply.getBytes(StandardCharsets.UTF_8);
                responder.send(new DatagramPacket(payload, payload.length,
                        packet.getAddress(), packet.getPort()));
            }
        } catch (Exception e) {
            if (!responder.isClosed()) {
                responderFailure.set(e);
            }
        }
    }

    private static final String DEFAULT_SCOPES = "onvif://www.onvif.org/type/video_encoder "
            + "onvif://www.onvif.org/name/HIKVISION "
            + "onvif://www.onvif.org/hardware/DS-2CD";

    private static String matchWithScopes(String scopes) {
        return matchWithScopes(scopes, "http://10.1.2.3/onvif/device_service");
    }

    private static String matchWithScopes(String scopes, String xaddrs) {
        return probeMatchXml("urn:uuid:2c5f4d1e", scopes, xaddrs);
    }

    private static String defaultProbeMatch() {
        return matchWithScopes(DEFAULT_SCOPES);
    }

    private static String probeMatchXml(String endpoint, String scopes, String xaddrs) {
        return "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\""
                + " xmlns:w=\"http://schemas.xmlsoap.org/ws/2004/08/addressing\""
                + " xmlns:d=\"http://schemas.xmlsoap.org/ws/2005/04/discovery\">"
                + "<s:Body><d:ProbeMatches><d:ProbeMatch>"
                + "<w:EndpointReference><w:Address>" + endpoint + "</w:Address></w:EndpointReference>"
                + "<d:Types>dn:NetworkVideoTransmitter</d:Types>"
                + (scopes == null ? "" : "<d:Scopes>" + scopes + "</d:Scopes>")
                + "<d:XAddrs>" + xaddrs + "</d:XAddrs>"
                + "</d:ProbeMatch></d:ProbeMatches></s:Body></s:Envelope>";
    }

    /** 拼一个包含 {@code count} 台设备（IP 依次 10.1.{third}.1…{count}）的应答包。 */
    private static String multiMatchPayload(int count, int thirdOctet) {
        StringBuilder xml = new StringBuilder(
                "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\""
                        + " xmlns:w=\"http://schemas.xmlsoap.org/ws/2004/08/addressing\""
                        + " xmlns:d=\"http://schemas.xmlsoap.org/ws/2005/04/discovery\">"
                        + "<s:Body><d:ProbeMatches>");
        for (int i = 1; i <= count; i++) {
            xml.append("<d:ProbeMatch>")
                    .append("<w:EndpointReference><w:Address>urn:uuid:cam-")
                    .append(i).append("</w:Address></w:EndpointReference>")
                    .append("<d:Types>dn:NetworkVideoTransmitter</d:Types>")
                    .append("<d:XAddrs>http://10.1.").append(thirdOctet).append('.').append(i)
                    .append("/onvif/device_service</d:XAddrs>")
                    .append("</d:ProbeMatch>");
        }
        return xml.append("</d:ProbeMatches></s:Body></s:Envelope>").toString();
    }
}

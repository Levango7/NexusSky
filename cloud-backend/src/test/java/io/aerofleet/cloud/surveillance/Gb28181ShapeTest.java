package io.aerofleet.cloud.surveillance;

import ch.qos.logback.classic.Level;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** E5 GB28181 国标形状层：编码/PTZ 指令码/信令构造/目录解析。 */
@DisplayName("GB28181 国标形状层")
class Gb28181ShapeTest {

    // ------------------------------------------------------------------
    // D1 20 位编码
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("D1 国标 20 位编码")
    class DeviceIdTest {
        @Test
        void 构造与回读一致() {
            Gb28181DeviceId id = Gb28181DeviceId.of("34010000001", "10", "132", "0001");
            // 段位 11/2/3/4
            assertThat(id.center).hasSize(11);
            assertThat(id.value()).hasSize(20);
            assertThat(id.value()).isEqualTo("34010000001" + "10" + "132" + "0001");
            assertThat(id.typeName()).isEqualTo("网络摄像机");
            assertThat(id.isCameraDevice()).isTrue();
            assertThat(id.isAlarmDevice()).isFalse();
        }

        @Test
        void 报警类与摄像类判定() {
            assertThat(Gb28181DeviceId.parse("34010000001" + "10" + "200" + "0001").isAlarmDevice()).isTrue();
            assertThat(Gb28181DeviceId.parse("34010000001" + "10" + "131" + "0001").isCameraDevice()).isTrue();
        }

        @Test
        void 长度非20拒绝() {
            assertThatThrownBy(() -> Gb28181DeviceId.parse("123"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("20 digits");
        }

        @Test
        void 含非数字拒绝() {
            assertThatThrownBy(() -> Gb28181DeviceId.parse("34010000001101" + "32" + "0" + "01X"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void 未知类型码明确拒绝不静默() {
            assertThatThrownBy(() -> Gb28181DeviceId.parse("34010000001" + "10" + "999" + "0001"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("unknown GB28181 type code: 999");
        }
    }

    // ------------------------------------------------------------------
    // D2 PTZ 指令码（国标字节）
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("D2 PTZ 指令码")
    class PtzTest {
        @Test
        void 上移指令逐字节断言国标值() {
            byte[] b = Gb28181PtzCommand.encode(Gb28181PtzCommand.UP);
            // 国标：A5 0F 01 <动作位>——上=0x08
            assertThat(b).containsExactly((byte) 0xA5, 0x0F, 0x01, 0x08);
            assertThat(Gb28181PtzCommand.encodeHex(Gb28181PtzCommand.UP)).isEqualTo("A50F0108");
        }

        @Test
        void 全部单动作位值() {
            assertThat(Gb28181PtzCommand.encodeHex(Gb28181PtzCommand.DOWN)).isEqualTo("A50F0104");
            assertThat(Gb28181PtzCommand.encodeHex(Gb28181PtzCommand.LEFT)).isEqualTo("A50F0120");
            assertThat(Gb28181PtzCommand.encodeHex(Gb28181PtzCommand.RIGHT)).isEqualTo("A50F0110");
            assertThat(Gb28181PtzCommand.encodeHex(Gb28181PtzCommand.ZOOM_IN)).isEqualTo("A50F0180");
            assertThat(Gb28181PtzCommand.encodeHex(Gb28181PtzCommand.ZOOM_OUT)).isEqualTo("A50F0140");
        }

        @Test
        void 未知动作拒绝不静默() {
            assertThatThrownBy(() -> Gb28181PtzCommand.encode("spin"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("unknown PTZ action");
        }
    }

    // ------------------------------------------------------------------
    // D3 SIP 信令形状
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("D3 SIP 信令形状")
    class SignalingTest {
        private final Gb28181Signaling sig = new Gb28181Signaling("34010000002000000001", "10.0.0.5:5060");
        private final Gb28181DeviceId dev = Gb28181DeviceId.of("34010000001", "10", "132", "0001");

        @Test
        void REGISTER_形状() {
            String m = sig.register(dev, 3600, 1);
            assertThat(m).startsWith("REGISTER sip:34010000002000000001 SIP/2.0");
            assertThat(m).contains("From: <sip:34010000001101320001@34010000002000000001>");
            assertThat(m).contains("Expires: 3600");
        }

        @Test
        void INVITE_点播含通道与SDP() {
            String m = sig.inviteStream(dev, 1, "10.0.0.5", 9000, 12345);
            assertThat(m).startsWith("INVITE sip:34010000001101320001:1@34010000002000000001 SIP/2.0");
            assertThat(m).contains("Content-Type: application/sdp");
            assertThat(m).contains("m=video 9000 TCP/RTP/AVP 96 98 97");
            assertThat(m).contains("a=rtpmap:96 PS/90000");   // 国标 PS 流
        }

        @Test
        void Catalog查询含MANSCDP类型() {
            String m = sig.catalogQuery(dev, 42);
            assertThat(m).startsWith("MESSAGE sip:");
            assertThat(m).contains("Content-Type: Application/MANSCDP+xml");
            assertThat(m).contains("<CmdType>Catalog</CmdType>");
            assertThat(m).contains("<DeviceID>34010000001101320001</DeviceID>");
        }

        @Test
        void 报警订阅SUBSCRIBE形状() {
            String m = sig.subscribeAlarm(dev, 3600, 1);
            assertThat(m).startsWith("SUBSCRIBE sip:34010000001101320001@");
            assertThat(m).contains("Event: Presence");
        }
    }

    // ------------------------------------------------------------------
    // D4 目录解析
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("D4 目录解析")
    class CatalogParserTest {
        private final Gb28181CatalogParser parser = new Gb28181CatalogParser();

        @Test
        void 标准目录解析出条目() {
            String xml = """
                    <?xml version="1.0"?>
                    <Response><CmdType>Catalog</CmdType><SN>1</SN><DeviceID>34010000002000000001</DeviceID>
                    <DeviceList Num="2">
                    <Item><DeviceID>34010000001101320001</DeviceID><Name>东门摄像机</Name>
                      <Manufacturer>演示厂</Manufacturer><Status>ON</Status></Item>
                    <Item><DeviceID>34010000001102000001</DeviceID><Name>围墙报警</Name>
                      <Status>OFF</Status></Item>
                    </DeviceList></Response>""";
            Gb28181CatalogParser.CatalogResult r = parser.parse(xml);
            assertThat(r.items()).hasSize(2);
            assertThat(r.skippedItems()).isZero();
            assertThat(r.items().get(0).name()).isEqualTo("东门摄像机");
            assertThat(r.items().get(0).status()).isEqualTo("ON");
            assertThat(r.items().get(1).status()).isEqualTo("OFF");
        }

        @Test
        void 非法DeviceID跳过并计数不静默() {
            String xml = """
                    <DeviceList Num="2">
                    <Item><DeviceID>123</DeviceID><Name>坏条目</Name></Item>
                    <Item><DeviceID>34010000001101320002</DeviceID><Name>好条目</Name></Item>
                    </DeviceList>""";
            Gb28181CatalogParser.CatalogResult r = parser.parse(xml);
            assertThat(r.items()).hasSize(1);
            assertThat(r.skippedItems()).isEqualTo(1);
        }

        @Test
        void 非Catalog结构明确拒绝() {
            assertThatThrownBy(() -> parser.parse("<Notify><CmdType>Keepalive</CmdType></Notify>"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not a GB28181 Catalog response");
        }

        @Test
        void 空输入拒绝() {
            assertThatThrownBy(() -> parser.parse("  "))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        /**
         * 变异收口：Manufacturer 的三元（显式保留 / 缺省 GB28181）此前无任何断言，
         * 取反后"有厂商被换成缺省码、无厂商的缺省被抹成 null"都穿了过去。
         */
        @Test
        void 制造商显式与缺省都如实保留() {
            String xml = """
                    <DeviceList Num="2">
                    <Item><DeviceID>34010000001101320001</DeviceID><Name>东门</Name>
                    <Status>ON</Status><Manufacturer>演示厂</Manufacturer></Item>
                    <Item><DeviceID>34010000001101320002</DeviceID><Name>西门</Name></Item>
                    </DeviceList>""";
            Gb28181CatalogParser.CatalogResult r = parser.parse(xml);
            assertThat(r.items().get(0).manufacturer()).isEqualTo("演示厂");
            assertThat(r.items().get(1).manufacturer()).isEqualTo("GB28181");
        }
    }

    // ------------------------------------------------------------------
    // D5 adapter
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("D5 适配器接入")
    class AdapterTest {
        private final Gb28181Adapter adapter = new Gb28181Adapter(
                new Gb28181CatalogParser(), "34010000002000000001", "10.0.0.5:5060");
        private final Gb28181DeviceId dev = Gb28181DeviceId.of("34010000001", "10", "132", "0001");

        private SurveillanceDevice camera() {
            return new SurveillanceDevice(dev.value(), "东门", SurveillanceDevice.Vendor.GB28181,
                    "0.0.0.0", 5060, "sip", "none");
        }

        @Test
        void discover从目录响应产出设备() {
            String xml = """
                    <DeviceList Num="1"><Item><DeviceID>34010000001101320001</DeviceID>
                    <Name>东门</Name><Status>ON</Status></Item></DeviceList>""";
            List<SurveillanceDevice> devices = adapter.discover(xml);
            assertThat(devices).hasSize(1);
            assertThat(devices.get(0).id).isEqualTo(dev.value());
            assertThat(devices.get(0).vendor).isEqualTo(SurveillanceDevice.Vendor.GB28181);
        }

        @Test
        void 点播返回描述性标识而非假RTSP() {
            String url = adapter.getStreamUrl(camera(), 1);
            // 媒体面属生产阶段：必须返回 gb28181-invite:// 标识，绝不假扮可播的 rtsp://
            assertThat(url).startsWith("gb28181-invite://").contains("channel=1");
            assertThat(url).doesNotStartWith("rtsp://");
        }

        @Test
        void PTZ控制返回国标指令码() {
            assertThat(adapter.ptzControl(camera(), "up")).isEqualTo("A50F0108");
        }

        @Test
        void 摄像机能力面() {
            assertThat(adapter.getCapabilities(camera()))
                    .containsExactlyInAnyOrder("PTZ", "STREAM", "RECORD");
        }

        @Test
        void 报警设备能力面() {
            SurveillanceDevice alarm = new SurveillanceDevice(
                    "34010000001102000001", "围墙报警", SurveillanceDevice.Vendor.GB28181,
                    "0.0.0.0", 5060, "sip", "none");
            assertThat(adapter.getCapabilities(alarm)).containsExactly("ALARM");
        }

        @Test
        void 跨厂商设备拒绝() {
            SurveillanceDevice hik = new SurveillanceDevice(
                    "HIK-001", "海康", SurveillanceDevice.Vendor.HIKVISION, "10.0.0.9", 80, "admin", "none");
            assertThatThrownBy(() -> adapter.ptzControl(hik, "up"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("only accepts GB28181 devices");
        }
    }

    // ------------------------------------------------------------------
    // D6 适配器可观测行为（日志契约与订阅句柄）
    // ------------------------------------------------------------------

    /**
     * 日志是这条链路的可观测面：目录解析跳过条目、PTZ 别名折叠、订阅建立
     * 都只在日志里表达。mock 掉 logger 就丢掉了这部分契约——此前的存活
     * 变异体（跳过分支取反/边界、别名箭头取反、订阅句柄抹空）全部从这里漏走。
     */
    @Nested
    @DisplayName("D6 适配器日志契约与订阅")
    class AdapterObservabilityTest {
        private final Gb28181Adapter adapter = new Gb28181Adapter(
                new Gb28181CatalogParser(), "34010000002000000001", "10.0.0.5:5060");
        private final Gb28181DeviceId dev = Gb28181DeviceId.of("34010000001", "10", "132", "0001");

        private SurveillanceDevice camera() {
            return new SurveillanceDevice(dev.value(), "东门", SurveillanceDevice.Vendor.GB28181,
                    "0.0.0.0", 5060, "sip", "none");
        }

        @Test
        void 目录含坏条目时必须warn_全好条目不许warn() {
            String bad = """
                    <DeviceList Num="2">
                    <Item><DeviceID>123</DeviceID><Name>坏条目</Name></Item>
                    <Item><DeviceID>34010000001101320001</DeviceID><Name>好条目</Name></Item>
                    </DeviceList>""";
            try (LogWiretap tap = LogWiretap.on(Gb28181Adapter.class)) {
                List<SurveillanceDevice> devices = adapter.discover(bad);
                assertThat(devices).hasSize(1);   // 好条目照常产出
                assertThat(tap.linesAtLeast(Level.WARN)).singleElement().asString()
                        .contains("skipped 1 item");
            }

            String clean = """
                    <DeviceList Num="1">
                    <Item><DeviceID>34010000001101320001</DeviceID><Name>好条目</Name></Item>
                    </DeviceList>""";
            try (LogWiretap tap = LogWiretap.on(Gb28181Adapter.class)) {
                adapter.discover(clean);
                // 没有跳过就不许发警告噪音（边界变异体会在 0 条跳过时也告警）
                assertThat(tap.linesAtLeast(Level.WARN)).isEmpty();
            }
        }

        @Test
        void PTZ别名折叠带箭头_直连命令不带() {
            try (LogWiretap tap = LogWiretap.on(Gb28181Adapter.class)) {
                adapter.ptzControl(camera(), "zoomIn");
                adapter.ptzControl(camera(), "up");

                String zoomLine = tap.lines().stream()
                        .filter(s -> s.contains("cmd=zoomIn"))
                        .findFirst().orElseThrow();
                assertThat(zoomLine).contains("(-> zoom_in");

                String upLine = tap.lines().stream()
                        .filter(s -> s.contains("cmd=up"))
                        .findFirst().orElseThrow();
                assertThat(upLine).doesNotContain("(->");
            }
        }

        @Test
        void 订阅句柄形状固定() {
            String handle = adapter.subscribeEvents(camera(), payload -> { });
            assertThat(handle).isEqualTo("gb28181-sub-" + dev.value());
        }
    }
}

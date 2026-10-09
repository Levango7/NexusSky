package io.aerofleet.cloud.surveillance;

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
}

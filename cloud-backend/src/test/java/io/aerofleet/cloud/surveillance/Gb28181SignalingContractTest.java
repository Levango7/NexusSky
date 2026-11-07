package io.aerofleet.cloud.surveillance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link Gb28181Signaling} 帧契约测试（GB/T 28181-2016 信令形状）。
 *
 * <p>**本批为何要新写**：原 {@code Gb28181ShapeTest.SignalingTest} 只断言"包含某某头"，
 * 从未核对 {@code Content-Length}。而那两个数是**硬编码魔数**（INVITE 145 / Catalog 141），
 * 实测 body 是 170 / 115 且随参数变化——按 RFC 3261 对端会挂起或被截断，
 * **旧实现发出去的帧真机读不了**。
 *
 * <p>本类把契约钉到字节级：
 * <ul>
 *   <li>Content-Length 必须等于 body 真实长度（数据驱动、多组参数）；</li>
 *   <li>Via 必须带 {@code z9hG4bK} 开头的 branch（RFC 3261 事务标识，旧实现整条缺）；</li>
 *   <li>起止行、必需头、SDP 必需行在位；</li>
 *   <li>响应帧必须回带 Via/From/To/Call-ID/CSeq，且 To 补 tag。</li>
 * </ul>
 */
@DisplayName("Gb28181Signaling：信令帧契约（Content-Length 由真实 body 导出）")
class Gb28181SignalingContractTest {

    private static final Pattern CONTENT_LENGTH =
            Pattern.compile("(?m)^Content-Length: (\\d+)\\r?$");

    private final Gb28181Signaling sig = new Gb28181Signaling("34010000002000000001", "10.0.0.5:5060", "t1");
    private final Gb28181DeviceId dev = Gb28181DeviceId.of("34010000001", "10", "132", "0001");

    private int contentLengthOf(String frame) {
        Matcher m = CONTENT_LENGTH.matcher(frame);
        assertThat(m.find()).as("帧里必须有 Content-Length").isTrue();
        return Integer.parseInt(m.group(1));
    }

    private String bodyOf(String frame) {
        int sep = frame.indexOf("\r\n\r\n");
        return sep < 0 ? "" : frame.substring(sep + 4);
    }

    // ------------------------------------------------------------------
    // Content-Length 不变式（数据驱动）
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Content-Length == body 真实字节数（参数化）")
    class ContentLengthMatchesBody {

        @Test
        @DisplayName("INVITE：多组 SDP 参数下都不再是魔数 145")
        void inviteContentLengthIsReal() {
            // (host, port, ssrc) —— 每组的 body 长度都不同（旧魔数只对其中一组碰巧接近）
            String[][] cases = {
                    {"10.0.0.5", "9000", "12345"},
                    {"192.168.1.20", "4000", "1234"},
                    {"10.0.0.1", "5000", "0"},
                    {"192.168.200.100", "30000", "999999999"},
            };
            for (String[] c : cases) {
                String frame = sig.inviteStream(dev, 1, c[0], Integer.parseInt(c[1]), Integer.parseInt(c[2]))
                        .renderText();
                int declared = contentLengthOf(frame);
                int actual = bodyOf(frame).getBytes(StandardCharsets.UTF_8).length;
                assertThat(declared)
                        .as("INVITE host=%s port=%s ssrc=%s", c[0], c[1], c[2])
                        .isEqualTo(actual);
            }
        }

        @Test
        @DisplayName("MESSAGE（Catalog）：SN 位数变化时长度随动，不再是魔数 141")
        void catalogContentLengthIsReal() {
            for (int sn : new int[]{1, 42, 999, 400000000}) {
                String frame = sig.catalogQuery(dev, sn).renderText();
                assertThat(contentLengthOf(frame))
                        .isEqualTo(bodyOf(frame).getBytes(StandardCharsets.UTF_8).length);
            }
        }

        @Test
        @DisplayName("无 body 的帧（REGISTER/SUBSCRIBE）Content-Length=0")
        void bodilessFramesAreZero() {
            assertThat(contentLengthOf(sig.register(dev, 3600, 1).renderText())).isZero();
            assertThat(contentLengthOf(sig.subscribeAlarm(dev, 3600, 1).renderText())).isZero();
            assertThat(contentLengthOf(sig.ack(invite(), ok(invite())).renderText())).isZero();
            assertThat(contentLengthOf(sig.bye(invite(), ok(invite())).renderText())).isZero();
        }

        @Test
        @DisplayName("响应帧（Catalog 200 OK）同样按真实 XML 长度导出")
        void responseContentLengthIsReal() {
            Gb28181SipMessage query = sig.catalogQuery(dev, 42);
            String xml = "<?xml version=\"1.0\"?>\r\n"
                    + "<Response><CmdType>Catalog</CmdType><SN>1</SN><DeviceID>"
                    + dev.value() + "</DeviceID><DeviceList Num=\"2\"></DeviceList></Response>";
            String frame = sig.okXml(query, xml).renderText();

            assertThat(contentLengthOf(frame))
                    .isEqualTo(bodyOf(frame).getBytes(StandardCharsets.UTF_8).length);
        }
    }

    // ------------------------------------------------------------------
    // 起止行与必需头
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("帧形状（起止行/必需头/国标语义位）")
    class FrameShape {

        @Test
        @DisplayName("REGISTER：目标域 + 设备 ID + Expires + Contact")
        void registerShape() {
            String f = sig.register(dev, 3600, 1).renderText();
            assertThat(f).startsWith("REGISTER sip:34010000002000000001 SIP/2.0\r\n");
            assertThat(f).contains("From: <sip:34010000001101320001@34010000002000000001>;tag=t1");
            assertThat(f).contains("To: <sip:34010000001101320001@34010000002000000001>");
            assertThat(f).contains("Expires: 3600");
            assertThat(f).contains("Contact: <sip:34010000001101320001@10.0.0.5:5060>");
        }

        @Test
        @DisplayName("INVITE：目标 URI 含通道号 + SDP 国标载荷类型 + Subject 双编码")
        void inviteShape() {
            String f = sig.inviteStream(dev, 7, "10.0.0.5", 9000, 12345).renderText();
            assertThat(f).startsWith("INVITE sip:34010000001101320001:7@34010000002000000001 SIP/2.0\r\n");
            assertThat(f).contains("Content-Type: application/sdp");
            assertThat(f).contains("m=video 9000 TCP/RTP/AVP 96 98 97");
            assertThat(f).contains("a=rtpmap:96 PS/90000");
            assertThat(f).contains("Subject: 34010000001101320001:7,34010000002000000001:0");
        }

        @Test
        @DisplayName("Via 带 z9hG4bK branch（事务标识；旧实现整条缺失）")
        void viaCarriesBranch() {
            for (String f : new String[]{
                    sig.register(dev, 3600, 1).renderText(),
                    sig.inviteStream(dev, 1, "10.0.0.5", 9000, 12345).renderText(),
                    sig.catalogQuery(dev, 1).renderText(),
                    sig.subscribeAlarm(dev, 3600, 1).renderText()}) {
                assertThat(f).containsPattern("Via: SIP/2.0/UDP 10\\.0\\.0\\.5:5060;branch=z9hG4bK-[0-9a-f]+");
            }
        }

        @Test
        @DisplayName("每笔事务的 branch 不重复（并发请求不会串事务）")
        void branchesAreUnique() {
            String b1 = sig.inviteStream(dev, 1, "10.0.0.5", 9000, 1).viaBranch();
            String b2 = sig.inviteStream(dev, 1, "10.0.0.5", 9000, 1).viaBranch();
            assertThat(b1).isNotEqualTo(b2);
            assertThat(b1).startsWith("z9hG4bK-");
        }

        @Test
        @DisplayName("INVITE 的 body 是合法 SDP（可被解析回读，字段一致）")
        void inviteBodyParsesBackAsSdp() {
            Gb28181SipMessage invite = sig.inviteStream(dev, 1, "10.0.0.5", 9000, 12345);
            Gb28181Sdp parsed = Gb28181Sdp.parse(invite.bodyText());

            assertThat(parsed.receiveHost()).isEqualTo("10.0.0.5");
            assertThat(parsed.receivePort()).isEqualTo(9000);
            assertThat(parsed.ssrc()).isEqualTo(12345);
            assertThat(Gb28181Sdp.rtpmapOf(invite.bodyText()))
                    .containsEntry(Gb28181Sdp.PAYLOAD_TYPE_PS, "PS/90000");
        }

        @Test
        @DisplayName("Catalog：MANSCDP 类型 + DeviceID + SN")
        void catalogShape() {
            String f = sig.catalogQuery(dev, 42).renderText();
            assertThat(f).startsWith("MESSAGE sip:34010000001101320001@34010000002000000001 SIP/2.0\r\n");
            assertThat(f).contains("Content-Type: Application/MANSCDP+xml");
            assertThat(f).contains("<CmdType>Catalog</CmdType>");
            assertThat(f).contains("<SN>42</SN>");
            assertThat(f).contains("<DeviceID>34010000001101320001</DeviceID>");
        }

        @Test
        @DisplayName("SUBSCRIBE：Event: Presence + Expires")
        void subscribeShape() {
            String f = sig.subscribeAlarm(dev, 3600, 1).renderText();
            assertThat(f).startsWith("SUBSCRIBE sip:34010000001101320001@34010000002000000001 SIP/2.0\r\n");
            assertThat(f).contains("Event: Presence");
            assertThat(f).contains("Expires: 3600");
        }
    }

    // ------------------------------------------------------------------
    // 响应形状（RFC 3261 §8.2.6 回带规则）
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("响应帧（回带 + To tag）")
    class ResponseShape {

        @Test
        @DisplayName("200 OK 回带 Via/From/To/Call-ID/CSeq，To 补 tag")
        void okCarriesBackRequestHeaders() {
            Gb28181SipMessage invite = invite();
            Gb28181SipMessage ok = sig.ok(invite);

            assertThat(ok.statusCode()).isEqualTo(200);
            assertThat(ok.firstHeader("Via")).isEqualTo(invite.firstHeader("Via"));
            assertThat(ok.firstHeader("From")).isEqualTo(invite.firstHeader("From"));
            assertThat(ok.firstHeader("Call-ID")).isEqualTo(invite.firstHeader("Call-ID"));
            assertThat(ok.cseqNumber()).isEqualTo(invite.cseqNumber());
            assertThat(ok.methodOfCseq()).isEqualTo("INVITE");
            // 响应必须给 To 补 tag（请求里的 To 无 tag）
            assertThat(ok.firstHeader("To")).contains("tag=");
        }

        @Test
        @DisplayName("401 带 WWW-Authenticate（Digest realm + nonce）")
        void unauthorizedCarriesChallenge() {
            Gb28181SipMessage reg = sig.register(dev, 3600, 1);
            Gb28181SipMessage u = sig.unauthorized(reg, "realm-x", "nonce-y");

            assertThat(u.statusCode()).isEqualTo(401);
            assertThat(u.firstHeader("WWW-Authenticate"))
                    .contains("Digest").contains("realm=\"realm-x\"").contains("nonce=\"nonce-y\"");
        }

        @Test
        @DisplayName("ACK/BYE 的 Request-URI 取自对端 To 且剥掉 tag")
        void ackAndByeUsePeerUri() {
            Gb28181SipMessage invite = invite();
            Gb28181SipMessage ok = sig.ok(invite);

            String ack = sig.ack(invite, ok).renderText();
            String bye = sig.bye(invite, ok).renderText();

            assertThat(ack).startsWith("ACK <sip:34010000001101320001:1@34010000002000000001> SIP/2.0\r\n");
            assertThat(bye).startsWith("BYE <sip:34010000001101320001:1@34010000002000000001> SIP/2.0\r\n");
            // ACK 复用 INVITE 的 Via（同一事务），BYE 用新 branch（新事务）
            assertThat(ack).contains("Via: " + invite.firstHeader("Via"));
            assertThat(bye).doesNotContain("branch=" + invite.viaBranch());
        }

        @Test
        @DisplayName("100 Trying 是 Provisional（无 body、无 tag 也算合法）")
        void tryingIsProvisional() {
            Gb28181SipMessage t = sig.trying(invite());
            assertThat(t.statusCode()).isEqualTo(100);
            assertThat(t.bodyBytes()).isZero();
            assertThat(t.transactionKey()).isEqualTo(invite().transactionKey());
        }
    }

    private Gb28181SipMessage invite() {
        return sig.inviteStream(dev, 1, "10.0.0.5", 9000, 12345);
    }

    private Gb28181SipMessage ok(Gb28181SipMessage req) {
        return sig.ok(req);
    }
}

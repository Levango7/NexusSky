package io.aerofleet.cloud.surveillance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link Gb28181SipMessage} 单元测试。
 *
 * <p>**最重要的一条不变式**：渲染出的帧里 {@code Content-Length} 必须**严格等于**
 * body 的真实字节数。这条不变式就是当初能抓出"硬编码 145/141"那条缺陷的信号——
 * 旧实现对 INVITE 写 145（实际 170）、Catalog 写 141（实际 115），
 * 对端按 Content-Length 读 body 时会挂起或被截断。
 * 现在 Content-Length 由 {@link #bodyBytes()} 导出，本测试把它钉死：
 * 任何"魔数复活"的改动都会立刻转红。
 */
@DisplayName("Gb28181SipMessage：Content-Length 不变式 + 解析回读")
class Gb28181SipMessageTest {

    private static final String SDP_BODY =
            "v=0\r\n"
            + "o=34010000002000000001 0 0 IN IP4 10.0.0.5\r\n"
            + "s=Play\r\n"
            + "c=IN IP4 10.0.0.5\r\n"
            + "t=0 0\r\n"
            + "m=video 9000 TCP/RTP/AVP 96 98 97\r\n"
            + "a=recvonly\r\n"
            + "a=rtpmap:96 PS/90000\r\n"
            + "y=12345\r\n\r\n";

    @Nested
    @DisplayName("Content-Length 不变式（魔数复活的唯一克星）")
    class ContentLengthInvariant {

        @Test
        @DisplayName("渲染帧的 Content-Length 严格等于 body 真实字节数")
        void renderedContentLengthEqualsBodyBytes() {
            Gb28181SipMessage msg = Gb28181SipMessage.request(
                    "INVITE sip:34010000001101320001:1@34010000002000000001 SIP/2.0",
                    SDP_BODY.getBytes(StandardCharsets.UTF_8))
                    .header("Via", "SIP/2.0/UDP 10.0.0.5:5060;branch=z9hG4bK-abc")
                    .header("From", "<sip:34010000002000000001@34010000002000000001>;tag=t1")
                    .header("To", "<sip:34010000001101320001:1@34010000002000000001>")
                    .header("Call-ID", "inv-12345")
                    .header("CSeq", "1 INVITE")
                    .header("Content-Type", "application/sdp");

            String rendered = msg.renderText();
            int declared = Integer.parseInt(
                    rendered.lines().filter(l -> l.startsWith("Content-Length:"))
                            .findFirst().orElseThrow().split(":")[1].trim());

            assertThat(declared).isEqualTo(msg.bodyBytes());
            assertThat(declared).isEqualTo(SDP_BODY.getBytes(StandardCharsets.UTF_8).length);
        }

        @Test
        @DisplayName("无 body 的帧 Content-Length=0（REGISTER / 100 Trying 一类）")
        void emptyBodyGivesZero() {
            Gb28181SipMessage msg = Gb28181SipMessage.request(
                    "REGISTER sip:34010000002000000001 SIP/2.0", null)
                    .header("Via", "SIP/2.0/UDP 10.0.0.5:5060;branch=z9hG4bK-abc")
                    .header("Call-ID", "reg-1")
                    .header("CSeq", "1 REGISTER");

            assertThat(msg.bodyBytes()).isZero();
            assertThat(msg.renderText()).contains("Content-Length: 0\r\n\r\n");
        }

        @Test
        @DisplayName("拒绝外部塞入 Content-Length（魔数没有入口）")
        void externalContentLengthRejected() {
            Gb28181SipMessage msg = Gb28181SipMessage.request(
                    "MESSAGE sip:x@y SIP/2.0", "body".getBytes(StandardCharsets.UTF_8));
            // header() 只过滤"渲染时算出来的值"，塞进来的 Content-Length 不影响渲染
            msg.header("Content-Length", "99999");
            assertThat(msg.renderText()).contains("Content-Length: 4\r\n");
        }

        @Test
        @DisplayName("对端按 Content-Length 读 body 一定能读完（解析侧按声明长度收）")
        void peerReadingByContentLengthAlwaysGetsFullBody() {
            Gb28181SipMessage msg = Gb28181SipMessage.request(
                    "INVITE sip:a@b SIP/2.0", SDP_BODY.getBytes(StandardCharsets.UTF_8))
                    .header("Call-ID", "inv-1")
                    .header("CSeq", "1 INVITE");

            byte[] rendered = msg.render();
            // 模拟对端：从头读到 Content-Length 指定的字节数
            int hdrEnd = indexOfHeadersEnd(rendered);
            int declared = declaredLength(rendered);
            int avail = rendered.length - hdrEnd;

            assertThat(declared).isEqualTo(msg.bodyBytes());
            assertThat(avail).isGreaterThanOrEqualTo(declared);
            assertThat(new String(rendered, hdrEnd, declared, StandardCharsets.UTF_8))
                    .isEqualTo(SDP_BODY);
        }
    }

    @Nested
    @DisplayName("解析（对端应答进得来）")
    class Parsing {

        @Test
        @DisplayName("200 OK 响应：状态码/头/body 全部回读")
        void responseParsed() {
            String frame = "SIP/2.0 200 OK\r\n"
                    + "Via: SIP/2.0/UDP 10.0.0.5:5060;branch=z9hG4bK-abc\r\n"
                    + "From: <sip:a@b>;tag=t1\r\n"
                    + "To: <sip:c@b>;tag=t2\r\n"
                    + "Call-ID: inv-1\r\n"
                    + "CSeq: 1 INVITE\r\n"
                    + "Content-Type: application/sdp\r\n"
                    + "Content-Length: 3\r\n\r\n"
                    + "abc";
            Gb28181SipMessage m = Gb28181SipMessage.parse(frame);

            assertThat(m.isResponse()).isTrue();
            assertThat(m.statusCode()).isEqualTo(200);
            assertThat(m.firstHeader("Call-ID")).isEqualTo("inv-1");
            assertThat(m.firstHeader("To")).contains("tag=t2");
            assertThat(m.methodOfCseq()).isEqualTo("INVITE");
            assertThat(m.bodyText()).isEqualTo("abc");
            assertThat(m.transactionKey()).isEqualTo("inv-1|1|INVITE");
        }

        @Test
        @DisplayName("头名大小写不敏感（Call-ID / call-id / CALL-ID 都查得到）")
        void headerNamesCaseInsensitive() {
            Gb28181SipMessage m = Gb28181SipMessage.parse(
                    "REGISTER sip:a SIP/2.0\r\ncall-id: inv-7\r\nCSEQ: 1 REGISTER\r\n\r\n");
            assertThat(m.firstHeader("Call-ID")).isEqualTo("inv-7");
            assertThat(m.firstHeader("CSeq")).isEqualTo("1 REGISTER");
        }

        @Test
        @DisplayName("同一 datagram 里的多条消息按 Content-Length 切分（不混帧）")
        void twoMessagesInOneDatagram() {
            String a = "SIP/2.0 100 Trying\r\nVia: SIP/2.0/UDP h;branch=b1\r\n"
                    + "Call-ID: inv-1\r\nCSeq: 1 INVITE\r\nContent-Length: 0\r\n\r\n";
            String b = "SIP/2.0 200 OK\r\nVia: SIP/2.0/UDP h;branch=b1\r\n"
                    + "Call-ID: inv-1\r\nCSeq: 1 INVITE\r\nContent-Length: 3\r\n\r\nxyz";
            String datagram = a + b;

            Gb28181SipMessage first = Gb28181SipMessage.parse(datagram);
            assertThat(first.statusCode()).isEqualTo(100);
            int consumed = first.render().length;
            Gb28181SipMessage second = Gb28181SipMessage.parse(datagram.substring(consumed));
            assertThat(second.statusCode()).isEqualTo(200);
            assertThat(second.bodyText()).isEqualTo("xyz");
        }

        @Test
        @DisplayName("头行折行（continuation，行首空白）并入上一条头的值")
        void continuationLineFoldedIntoPreviousHeader() {
            String frame = "REGISTER sip:a SIP/2.0\r\n"
                    + "Contact: <sip:a@h>\r\n"
                    + " ;expires=3600\r\n"
                    + "Call-ID: reg-1\r\n"
                    + "CSeq: 1 REGISTER\r\n"
                    + "\r\n";
            Gb28181SipMessage m = Gb28181SipMessage.parse(frame);

            assertThat(m.firstHeader("Contact")).isEqualTo("<sip:a@h> ;expires=3600");
        }
    }

    @Nested
    @DisplayName("事务键与拒绝路径")
    class TransactionKeys {

        @Test
        @DisplayName("请求与响应的事务键同源（Call-ID + CSeq 序号 + 方法）")
        void requestAndResponseShareKey() {
            Gb28181Signaling sig = new Gb28181Signaling("34010000002000000001", "10.0.0.5:5060");
            Gb28181DeviceId dev = Gb28181DeviceId.of("34010000001", "10", "132", "0001");
            Gb28181SipMessage invite = sig.inviteStream(dev, 1, "10.0.0.5", 9000, 12345);
            Gb28181SipMessage ok = sig.okSdp(invite, new Gb28181Sdp("u", "10.0.0.9", 4000, 1));

            assertThat(ok.transactionKey()).isEqualTo(invite.transactionKey());
        }

        @Test
        @DisplayName("空消息/坏起止行明确拒绝，不吞")
        void malformedRejected() {
            assertThatThrownBy(() -> Gb28181SipMessage.parse("   "))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Gb28181SipMessage.parse("garbage line with no colon\r\n\r\n"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("非法状态码拒绝（不造 700 响应）")
        void invalidStatusCodeRejected() {
            assertThatThrownBy(() -> Gb28181SipMessage.response(700, "X", null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static int indexOfHeadersEnd(byte[] rendered) {
        String s = new String(rendered, StandardCharsets.UTF_8);
        return s.indexOf("\r\n\r\n") + 4;
    }

    private static int declaredLength(byte[] rendered) {
        String s = new String(rendered, StandardCharsets.UTF_8);
        for (String line : s.split("\r\n")) {
            if (line.startsWith("Content-Length:")) {
                return Integer.parseInt(line.split(":")[1].trim());
            }
        }
        throw new IllegalStateException("no Content-Length");
    }
}

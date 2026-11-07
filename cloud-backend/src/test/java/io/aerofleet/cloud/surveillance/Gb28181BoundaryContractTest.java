package io.aerofleet.cloud.surveillance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * GB28181 信令/媒体形状的**边界与空值**契约测试。
 *
 * <p>**为什么单独写这一份**：surveillance 包的变异基线是**棘轮**（分数只能涨）。
 * 本批在包里加了 ~700 行新代码，PIT 第一轮实测 0.9050/0.9162，低于基线 0.94/0.95——
 * 新代码的**边界比较、空值分支、返回值形态**大多没被断言钉住（现有测试只走"正常路径"
 * 的少数取值）。本类把这些边界逐条钉死：每个 {@code if} 的两侧、每个返回值的"非空"。
 *
 * <p>写法纪律（与仓库既有变异收口一致）：**不写白盒佯装断言**——只断言契约上
 * 可观察的行为（抛不抛、返回什么、渲染出的字节），不注入 seam 去迁就 PIT。
 * 剩下真正无法按契约测试的（setDaemon / socket.close / 阻塞 receive 这类
 * "删掉也不改可观测行为"的算子）留给基线定性，不硬凑。
 */
@DisplayName("GB28181 形状层：边界与空值契约（变异收口）")
class Gb28181BoundaryContractTest {

    private static final String DOMAIN = "34010000002000000001";
    private static final String HOST = "127.0.0.1:5060";
    private final Gb28181Signaling sig = new Gb28181Signaling(DOMAIN, HOST, "tag-1");
    private final Gb28181DeviceId dev = Gb28181DeviceId.of("34010000001", "10", "132", "0001");

    // ==================================================================
    // SDP：构造入参边界
    // ==================================================================

    @Nested
    @DisplayName("Gb28181Sdp 构造与渲染的边界")
    class SdpConstruction {

        @Test
        @DisplayName("receiveHost 为 null 或全空白都拒绝（不是只挡 null）")
        void hostNullOrBlankRejected() {
            assertThatThrownBy(() -> new Gb28181Sdp("u", null, 1, 1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("receiveHost");
            assertThatThrownBy(() -> new Gb28181Sdp("u", "   ", 1, 1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("receiveHost");
        }

        @Test
        @DisplayName("端口边界：-1 与 65536 拒绝，0 与 65535 接受")
        void portBoundaries() {
            assertThatThrownBy(() -> new Gb28181Sdp("u", "10.0.0.1", -1, 1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("out of range");
            assertThatThrownBy(() -> new Gb28181Sdp("u", "10.0.0.1", 65536, 1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("out of range");
            // 0 与 65535 都要真的能构造（不是"碰巧不抛"）
            assertThat(new Gb28181Sdp("u", "10.0.0.1", 0, 1).receivePort()).isZero();
            assertThat(new Gb28181Sdp("u", "10.0.0.1", 65535, 1).receivePort()).isEqualTo(65535);
        }

        @Test
        @DisplayName("sessionUser 空/null → 渲染为 '-'（SDP 的 o= 用户名占位）")
        void sessionUserBlankFallsBackToDash() {
            assertThat(new Gb28181Sdp(null, "10.0.0.1", 9000, 7).body())
                    .startsWith("v=0\r\no=- 0 0 IN IP4 10.0.0.1\r\n");
            assertThat(new Gb28181Sdp("  ", "10.0.0.1", 9000, 7).body())
                    .startsWith("v=0\r\no=- 0 0 IN IP4 10.0.0.1\r\n");
            assertThat(new Gb28181Sdp("platform", "10.0.0.1", 9000, 7).body())
                    .startsWith("v=0\r\no=platform 0 0 IN IP4 10.0.0.1\r\n");
        }

        @Test
        @DisplayName("bodyBytes 等于 body 的 UTF-8 字节数（Content-Length 的唯一来源）")
        void bodyBytesMatchesActualLength() throws Exception {
            for (int port : new int[]{0, 1, 65535}) {
                for (int ssrc : new int[]{0, 1, 2147483647}) {
                    Gb28181Sdp sdp = new Gb28181Sdp("u", "10.0.0.1", port, ssrc);
                    assertThat(sdp.bodyBytes())
                            .isEqualTo(sdp.body().getBytes("UTF-8").length);
                }
            }
        }

        @Test
        @DisplayName("isWildcardHost 只认 0.0.0.0 / :: （不把 '0' 当通配）")
        void wildcardHostIsExact() {
            assertThat(Gb28181Sdp.isWildcardHost("0.0.0.0")).isTrue();
            assertThat(Gb28181Sdp.isWildcardHost("::")).isTrue();
            assertThat(Gb28181Sdp.isWildcardHost("127.0.0.1")).isFalse();
            assertThat(Gb28181Sdp.isWildcardHost("0")).isFalse();
            assertThat(Gb28181Sdp.isWildcardHost("")).isFalse();
        }
    }

    // ==================================================================
    // SDP：解析边界
    // ==================================================================

    @Nested
    @DisplayName("Gb28181Sdp.parse 的边界")
    class SdpParse {

        @Test
        @DisplayName("无 m=video 行拒绝（空文本、纯注释行也一样）")
        void missingVideoMediaLineRejected() {
            assertThatThrownBy(() -> Gb28181Sdp.parse(""))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Gb28181Sdp.parse("v=0\r\ns=x\r\n"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("m=video");
        }

        @Test
        @DisplayName("m=audio 拒绝（不是 video 就不当点播流）")
        void nonVideoMediaRejected() {
            assertThatThrownBy(() -> Gb28181Sdp.parse("v=0\r\nc=IN IP4 10.0.0.1\r\nm=audio 4000 RTP/AVP 96\r\n"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("video");
        }

        @Test
        @DisplayName("m= 行段数不足（<3）明确拒绝，不返回半成品")
        void shortMediaLineRejected() {
            assertThatThrownBy(() -> Gb28181Sdp.parse("v=0\r\nm=video 4000\r\n"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("malformed m= line");
        }

        @Test
        @DisplayName("c=/o= 行段数不足时不用它当地址（回退到另一行）")
        void shortConnectionLinesIgnored() {
            // c= 只有 2 段 ⇒ 不用；o= 有 6 段 ⇒ 用它兜底
            Gb28181Sdp sdp = Gb28181Sdp.parse(
                    "v=0\r\no=u 0 0 IN IP4 10.0.0.7\r\nc=IN\r\nm=video 4000 TCP/RTP/AVP 96\r\n");
            assertThat(sdp.receiveHost()).isEqualTo("10.0.0.7");
        }

        @Test
        @DisplayName("y= 负值拒绝（非负校验真的在跑）")
        void negativeYRejected() {
            assertThatThrownBy(() -> Gb28181Sdp.parse(
                    "v=0\r\nc=IN IP4 10.0.0.1\r\nm=video 4000 TCP/RTP/AVP 96\r\ny=-1\r\n"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("non-negative");
        }

        @Test
        @DisplayName("未知行与硬空行不致命（真设备带扩展行）")
        void unknownLinesTolerated() {
            Gb28181Sdp sdp = Gb28181Sdp.parse(
                    "v=0\r\n\r\ni=备注\r\nc=IN IP4 10.0.0.1\r\nm=video 4000 TCP/RTP/AVP 96\r\na=extra:1\r\n");
            assertThat(sdp.receivePort()).isEqualTo(4000);
            assertThat(sdp.ssrc()).isZero();
        }

        @Test
        @DisplayName("a=rtpmap 无空格（缺载荷类型）拒绝")
        void malformedRtpmapRejected() {
            assertThatThrownBy(() -> Gb28181Sdp.rtpmapOf("a=rtpmap:96\r\n"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no payload type");
        }

        @Test
        @DisplayName("rtpmapOf 按「载荷类型 / 编码名/时钟率」解析（PS/90000 不被截成 PS）")
        void rtpmapSplitsOnSpaceThenSlash() {
            Map<Integer, String> m = Gb28181Sdp.rtpmapOf(
                    "a=sendrecv\r\na=rtpmap:96 PS/90000\r\na=rtpmap:97 MPEG4-GENERIC/90000/1\r\n");
            assertThat(m).containsEntry(96, "PS/90000");
            assertThat(m).containsEntry(97, "MPEG4-GENERIC/90000/1");
            // 载荷类型非数字 ⇒ 拒绝（不静默丢掉这一行）
            assertThatThrownBy(() -> Gb28181Sdp.rtpmapOf("a=rtpmap:x PS/90000\r\n"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("payload type");
        }

        @Test
        @DisplayName("rtpmapOf(null) 返回空表而不是 NPE")
        void rtpmapOfNullIsEmpty() {
            assertThat(Gb28181Sdp.rtpmapOf(null)).isEmpty();
        }
    }

    // ==================================================================
    // SIP 消息：状态码 / 起止行 / 事务键边界
    // ==================================================================

    @Nested
    @DisplayName("Gb28181SipMessage 的边界")
    class SipMessageBoundaries {

        @Test
        @DisplayName("状态码边界：99 与 700 拒绝，100 与 699 接受")
        void statusCodeBoundaries() {
            assertThatThrownBy(() -> Gb28181SipMessage.response(99, null, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Gb28181SipMessage.response(700, null, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(Gb28181SipMessage.response(100, null, null).statusCode()).isEqualTo(100);
            assertThat(Gb28181SipMessage.response(699, null, null).statusCode()).isEqualTo(699);
        }

        @Test
        @DisplayName("请求-行缺方法名或缺 SIP/2.0 都拒绝（与 parse 同一把尺子）")
        void requestLineMustBeWellFormed() {
            // 缺 SIP/2.0
            assertThatThrownBy(() -> Gb28181SipMessage.request("INVITE sip:x@y", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("request-line");
            // 缺方法名（trim 后仍是两段）
            assertThatThrownBy(() -> Gb28181SipMessage.request(" sip:x@y SIP/2.0", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("request-line");
            // 正常三段式必须收下（不是"一律拒绝"）
            assertThat(Gb28181SipMessage.request("INVITE sip:x@y SIP/2.0", null).method())
                    .isEqualTo("INVITE");
        }

        @Test
        @DisplayName("起止行三段式校验：坏 token 字符 / 空 URI / 非三位码都拒")
        void startLineShapeEnforcedByParse() {
            // 方法名含非 token 字符（'!' 在 RFC 3261 token 集里是**合法**的，用 '@' 做反例）
            assertThatThrownBy(() -> Gb28181SipMessage.parse("INV@TE sip:x@y SIP/2.0\r\n\r\n"))
                    .isInstanceOf(IllegalArgumentException.class);
            // URI 为空（双空格）
            assertThatThrownBy(() -> Gb28181SipMessage.parse("INVITE  SIP/2.0\r\n\r\n"))
                    .isInstanceOf(IllegalArgumentException.class);
            // 状态行码非 100-699
            assertThatThrownBy(() -> Gb28181SipMessage.parse("SIP/2.0 700 X\r\n\r\n"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Gb28181SipMessage.parse("SIP/2.0 99 X\r\n\r\n"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Gb28181SipMessage.parse("SIP/2.0 abc X\r\n\r\n"))
                    .isInstanceOf(IllegalArgumentException.class);
            // 合法 token 字符的请求行必须收下（不是一律拒绝）
            assertThat(Gb28181SipMessage.parse("INV!TE sip:x@y SIP/2.0\r\n\r\n").method())
                    .isEqualTo("INV!TE");
        }

        @Test
        @DisplayName("method()：响应返回 null，请求返回方法名")
        void methodIsNullForResponsesOnly() {
            assertThat(Gb28181SipMessage.response(200, null, null).method()).isNull();
            assertThat(Gb28181SipMessage.request("BYE sip:x@y SIP/2.0", null).method())
                    .isEqualTo("BYE");
        }

        @Test
        @DisplayName("cseqNumber/viaBranch/methodOfCseq 的缺失路径返回哨兵而不是炸")
        void transactionAccessorsDegradeGracefully() {
            Gb28181SipMessage bare = Gb28181SipMessage.request("OPTIONS sip:x@y SIP/2.0", null);
            assertThat(bare.cseqNumber()).isEqualTo(-1);
            assertThat(bare.viaBranch()).isNull();
            assertThat(bare.methodOfCseq()).isNull();
            assertThat(bare.transactionKey()).isEqualTo("null|-1|OPTIONS");
        }

        @Test
        @DisplayName("viaBranch 取 branch= 到分号/逗号为止（同 Via 里还有别的参数）")
        void viaBranchStopsAtSemicolon() {
            Gb28181SipMessage m = Gb28181SipMessage.request("INVITE sip:x@y SIP/2.0", null)
                    .header("Via", "SIP/2.0/UDP h:5060;branch=z9hG4bK-abc;rport")
                    .header("Call-ID", "inv-1")
                    .header("CSeq", "1 INVITE");
            assertThat(m.viaBranch()).isEqualTo("z9hG4bK-abc");
            assertThat(m.methodOfCseq()).isEqualTo("INVITE");
        }

        @Test
        @DisplayName("CRLF 与 LF 两种换行都能解析（separatorLength 分支都走到）")
        void bothLineEndingsParse() {
            Gb28181SipMessage crlf = Gb28181SipMessage.parse(
                    "SIP/2.0 200 OK\r\nCall-ID: a\r\nCSeq: 1 MESSAGE\r\nContent-Length: 2\r\n\r\nhi");
            assertThat(crlf.bodyText()).isEqualTo("hi");

            Gb28181SipMessage lf = Gb28181SipMessage.parse(
                    "SIP/2.0 200 OK\nCall-ID: a\nCSeq: 1 MESSAGE\nContent-Length: 2\n\nhi");
            assertThat(lf.bodyText()).isEqualTo("hi");
            assertThat(lf.firstHeader("Call-ID")).isEqualTo("a");
        }
    }

    // ==================================================================
    // 信令构造的辅助边角
    // ==================================================================

    @Nested
    @DisplayName("Gb28181Signaling 的辅助边界")
    class SignalingAux {

        @Test
        @DisplayName("构造入参非空（domain/host 空白都拒）")
        void configNullOrBlankRejected() {
            assertThatThrownBy(() -> new Gb28181Signaling("  ", HOST))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sipDomain");
            assertThatThrownBy(() -> new Gb28181Signaling(DOMAIN, " "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sipHost");
        }

        @Test
        @DisplayName("getter 如实回读（不是 null/空串）")
        void gettersReturnConfiguredValues() {
            assertThat(sig.sipDomain()).isEqualTo(DOMAIN);
            assertThat(sig.sipHost()).isEqualTo(HOST);
            assertThat(sig.clientTag()).isEqualTo("tag-1");
            assertThat(Gb28181Signaling.newBranch()).startsWith("z9hG4bK-");
        }

        @Test
        @DisplayName("newBranch 每笔不同（事务标识不能重复）")
        void branchesAreUnique() {
            assertThat(Gb28181Signaling.newBranch())
                    .isNotEqualTo(Gb28181Signaling.newBranch());
        }

        @Test
        @DisplayName("响应构造成缺回带头时抛点名异常（不是 NPE(\"value\")）")
        void respondRequiresEchoHeaders() {
            Gb28181SipMessage noHeaders = Gb28181SipMessage.request("INVITE sip:x@y SIP/2.0", null);
            assertThatThrownBy(() -> sig.ok(noHeaders))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("header to echo");
        }

        @Test
        @DisplayName("ACK/BYE：To 头存在时用它的 URI 并剥掉 tag")
        void ackByeUsePeerUriAndStripTag() {
            Gb28181SipMessage invite = sig.inviteStream(dev, 1, "127.0.0.1", 9000, 12345);
            Gb28181SipMessage ok = sig.ok(invite);

            assertThat(sig.ack(invite, ok).renderText())
                    .startsWith("ACK <sip:" + dev.value() + ":1@" + DOMAIN + "> SIP/2.0\r\n");
            assertThat(sig.bye(invite, ok).renderText())
                    .startsWith("BYE <sip:" + dev.value() + ":1@" + DOMAIN + "> SIP/2.0\r\n");
        }

        @Test
        @DisplayName("ACK 沿用 INVITE 的 Via（同一事务），BYE 用新 branch（新事务）")
        void ackReusesInviteViaByeGetsNewBranch() {
            Gb28181SipMessage invite = sig.inviteStream(dev, 1, "127.0.0.1", 9000, 12345);
            Gb28181SipMessage ok = sig.okSdp(invite, new Gb28181Sdp("u", "127.0.0.1", 4000, 1));

            String ack = sig.ack(invite, ok).renderText();
            String bye = sig.bye(invite, ok).renderText();

            assertThat(ack).contains("Via: " + invite.firstHeader("Via"));
            assertThat(bye).doesNotContain(invite.viaBranch());
            assertThat(bye).containsPattern("branch=z9hG4bK-");
        }
    }

    // ==================================================================
    // 设备替身：Catalog XML / hostOf 边界
    // ==================================================================

    @Nested
    @DisplayName("Gb28181DeviceSimulator 的边界")
    class SimulatorBoundaries {

        @Test
        @DisplayName("catalogXml：通道数 0 时也给出合法空目录（不是坏 XML）")
        void catalogWithZeroChannelsIsStillWellFormed() throws Exception {
            Gb28181DeviceId id = Gb28181DeviceId.of("34010000001", "10", "132", "0001");
            Gb28181DeviceSimulator sim = new Gb28181DeviceSimulator(0, id, "127.0.0.1:5060", 4000, 0);
            String xml = sim.catalogXml();

            assertThat(xml).isNotEmpty().contains("<DeviceList Num=\"0\">");
            assertThat(new Gb28181CatalogParser().parse(xml).items()).isEmpty();
            sim.close();
        }

        @Test
        @DisplayName("catalogXml：频道编码是合法 20 位国标码（不是 URI 形态）")
        void catalogChannelsUseLegalIds() throws Exception {
            Gb28181DeviceId id = Gb28181DeviceId.of("34010000001", "10", "132", "0001");
            Gb28181DeviceSimulator sim = new Gb28181DeviceSimulator(0, id, "127.0.0.1:5060", 4000, 3);
            java.util.List<String> ids = new java.util.ArrayList<>();
            Gb28181CatalogParser.CatalogResult r =
                    new Gb28181CatalogParser().parse(sim.catalogXml());
            r.items().forEach(i -> ids.add(i.deviceId()));

            assertThat(ids).containsExactly("34010000001101320001",
                    "34010000001101320002", "34010000001101320003");
            assertThat(r.skippedItems()).isZero();
            sim.close();
        }

        @Test
        @DisplayName("address() 用回环地址（不发往 0.0.0.0 那种发不出去的地址）")
        void addressIsLoopback() throws Exception {
            Gb28181DeviceId id = Gb28181DeviceId.of("34010000001", "10", "132", "0001");
            Gb28181DeviceSimulator sim = new Gb28181DeviceSimulator(0, id, "127.0.0.1:5060", 4000, 1);
            assertThat(sim.address().getAddress().isLoopbackAddress()).isTrue();
            assertThat(sim.port()).isPositive();
            assertThat(sim.transactionLog()).isEmpty();
            assertThat(sim.received()).isEmpty();
            sim.close();
        }
    }

    // ==================================================================
    // 第二批：比较算子的**精确边界**（ConditionalsBoundaryMutator 专用）
    // 每个 `>=` / `<` / `<=` 都要有"刚好等于边界值"的正例与"越界一格"的反例，
    // 否则 PIT 把 `>=` 改成 `>` 也不会有任何测试红。
    // ==================================================================

    @Nested
    @DisplayName("比较算子的精确边界（变异收口第二批）")
    class ExactBoundaries {

        private Gb28181SipMessage parseOk(String frame) {
            return Gb28181SipMessage.parse(frame);
        }

        @Test
        @DisplayName("状态码 100/699 是合法边界（越界一格 99/700 拒绝）")
        void statusCodeExactBoundaries() {
            assertThat(parseOk("SIP/2.0 100 Trying\r\nCall-ID: a\r\nCSeq: 1 INVITE\r\n\r\n")
                    .statusCode()).isEqualTo(100);
            assertThat(parseOk("SIP/2.0 699 X\r\nCall-ID: a\r\nCSeq: 1 INVITE\r\n\r\n")
                    .statusCode()).isEqualTo(699);
            assertThatThrownBy(() -> parseOk("SIP/2.0  99 X\r\n\r\n"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> parseOk("SIP/2.0  700 X\r\n\r\n"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Content-Length 截断只在 body **超过**声明值时发生")
        void contentLengthTruncatesOnlyWhenLonger() {
            // body 比声明长 ⇒ 截到声明长度（同一 datagram 的后续字节不属于本条）
            Gb28181SipMessage longer = parseOk(
                    "SIP/2.0 200 OK\r\nCall-ID: a\r\nCSeq: 1 MESSAGE\r\nContent-Length: 2\r\n\r\nhelloEXTRA");
            assertThat(longer.bodyText()).isEqualTo("he");

            // body 与声明等长 ⇒ 原样
            Gb28181SipMessage exact = parseOk(
                    "SIP/2.0 200 OK\r\nCall-ID: a\r\nCSeq: 1 MESSAGE\r\nContent-Length: 5\r\n\r\nhello");
            assertThat(exact.bodyText()).isEqualTo("hello");

            // body 比声明短（对端谎报）⇒ 不补位、不抛，按实际收
            Gb28181SipMessage shorter = parseOk(
                    "SIP/2.0 200 OK\r\nCall-ID: a\r\nCSeq: 1 MESSAGE\r\nContent-Length: 50\r\n\r\nhello");
            assertThat(shorter.bodyText()).isEqualTo("hello");
        }

        @Test
        @DisplayName("没有头/body 分隔空行也能解析（head=全文、body 空）")
        void messageWithoutSeparatorStillParses() {
            Gb28181SipMessage m = parseOk(
                    "REGISTER sip:34010000002000000001 SIP/2.0\r\nCall-ID: r-1\r\nCSeq: 1 REGISTER");
            assertThat(m.method()).isEqualTo("REGISTER");
            assertThat(m.bodyBytes()).isZero();
            assertThat(m.firstHeader("Call-ID")).isEqualTo("r-1");
        }

        @Test
        @DisplayName("头行以冒号开头（colon<=0）拒绝；空白开头的折行并入上一条")
        void headerLineColonAtZeroRejected() {
            assertThatThrownBy(() -> parseOk(
                    "REGISTER sip:x SIP/2.0\r\n:oops\r\n\r\n"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("malformed sip header");

            // 折行（行首空白）并入前一条头
            Gb28181SipMessage folded = parseOk(
                    "REGISTER sip:x SIP/2.0\r\nContact: <sip:a@h>\r\n ;expires=3600\r\n\r\n");
            assertThat(folded.firstHeader("Contact")).isEqualTo("<sip:a@h> ;expires=3600");
        }

        @Test
        @DisplayName("CSeq 无空格时 methodOfCseq 为 null、cseqNumber 取全串数字")
        void cseqWithoutSpaceIsHandled() {
            Gb28181SipMessage m = Gb28181SipMessage.request("INVITE sip:x@y SIP/2.0", null)
                    .header("CSeq", "42");     // 没有 " 42 INVITE" 这种正常形态
            assertThat(m.methodOfCseq()).isNull();
            assertThat(m.cseqNumber()).isEqualTo(42);
        }

        @Test
        @DisplayName("Via 无 branch= 时 viaBranch 为 null（不抛、不返回半截）")
        void viaWithoutBranchIsNull() {
            Gb28181SipMessage m = Gb28181SipMessage.request("INVITE sip:x@y SIP/2.0", null)
                    .header("Via", "SIP/2.0/UDP h:5060");
            assertThat(m.viaBranch()).isNull();
        }

        @Test
        @DisplayName("addHeader 返回同一实例（链式调用不是每次新建）")
        void addHeaderReturnsSameInstance() {
            Gb28181SipMessage m = Gb28181SipMessage.request("INVITE sip:x@y SIP/2.0", null);
            assertThat(m.addHeader("Call-ID", "a")).isSameAs(m);
            assertThat(m.addHeader("X-A", "1").addHeader("X-A", "2").allHeaders("X-A"))
                    .containsExactly("1", "2");
        }

        @Test
        @DisplayName("SDP 的 y=0 是合法值（非负校验不误伤 0）")
        void yZeroIsAccepted() {
            Gb28181Sdp sdp = Gb28181Sdp.parse(
                    "v=0\r\nc=IN IP4 10.0.0.1\r\nm=video 4000 TCP/RTP/AVP 96\r\ny=0\r\n");
            assertThat(sdp.ssrc()).isZero();
        }

        @Test
        @DisplayName("SDP 里无 '=' 的行被跳过（不是整帧拒绝）")
        void sdpLineWithoutEqualsSkipped() {
            Gb28181Sdp sdp = Gb28181Sdp.parse(
                    "v=0\r\nnoequalshere\r\nc=IN IP4 10.0.0.1\r\nm=video 4000 TCP/RTP/AVP 96\r\n");
            assertThat(sdp.receivePort()).isEqualTo(4000);
        }

        @Test
        @DisplayName("rtpmapOf 对无 rtpmap 行的文本返回空表（有行则非空）")
        void rtpmapOfEmptyWhenNoRtpmapLines() {
            assertThat(Gb28181Sdp.rtpmapOf("")).isEmpty();
            assertThat(Gb28181Sdp.rtpmapOf("v=0\r\nm=video 4000 TCP/RTP/AVP 96\r\n")).isEmpty();
            assertThat(Gb28181Sdp.rtpmapOf("a=rtpmap:96 PS/90000")).isNotEmpty();
        }

        @Test
        @DisplayName("m= 行**恰好 3 段**也合法（'<' 被改成 '<=' 会让它误拒）")
        void mediaLineWithExactlyThreePartsParses() {
            Gb28181Sdp sdp = Gb28181Sdp.parse(
                    "v=0\r\nc=IN IP4 10.0.0.1\r\nm=video 4000 RTP/AVP\r\n");
            assertThat(sdp.receivePort()).isEqualTo(4000);
        }

        @Test
        @DisplayName("方法名含 RFC token 特殊字符（'-''.''!''%''*''_''+'''）均为合法")
        void methodTokenSpecialCharactersAreLegal() {
            for (String method : new String[]{"INV-TE", "INV.TE", "INV!TE", "INV%TE",
                    "INV*TE", "INV_TE", "INV+TE", "INV'TE"}) {
                assertThat(Gb28181SipMessage.parse(method + " sip:x@y SIP/2.0\r\n\r\n").method())
                        .as("method=%s 应被受理", method)
                        .isEqualTo(method);
            }
            // 非 token 字符仍然拒
            assertThatThrownBy(() -> Gb28181SipMessage.parse("INV@TE sip:x@y SIP/2.0\r\n\r\n"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ==================================================================
    // 传输：一个 datagram 两条消息 + 无事务键的诊断分支
    // ==================================================================

    @Nested
    @DisplayName("Gb28181SipTransport 的解码与诊断分支")
    class TransportDecoding {

        @Test
        @DisplayName("同一个 datagram 里两条消息都被解出来（不混帧、不丢第二条）")
        void twoMessagesInOneDatagramAreBothDecoded() throws Exception {
            Gb28181DeviceId id = Gb28181DeviceId.of("34010000001", "10", "132", "0001");
            java.net.InetSocketAddress gwAddr;
            String a;
            String b;
            try (Gb28181SipTransport t = new Gb28181SipTransport(0)) {
                t.start();
                gwAddr = t.localAddress();
                // 借 signaling 渲染两条形状合法的消息，串成一个 datagram 发过去
                Gb28181Signaling s = new Gb28181Signaling("34010000002000000001",
                        "127.0.0.1:" + 5099, "t");
                a = s.register(id, 3600, 1).renderText();
                b = s.register(id, 3600, 2).renderText();
                try (java.net.DatagramSocket peer = new java.net.DatagramSocket()) {
                    byte[] both = (a + b).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    peer.send(new java.net.DatagramPacket(both, both.length,
                            new java.net.InetSocketAddress("127.0.0.1", gwAddr.getPort())));
                }
                long deadline = System.currentTimeMillis() + 3000;
                while (System.currentTimeMillis() < deadline && t.received().size() < 2) {
                    Thread.sleep(25);
                }
                assertThat(t.received())
                        .as("一个 datagram 两条 REGISTER 都应解出（按 Content-Length 推进偏移）")
                        .hasSize(2);
            }
        }

        @Test
        @DisplayName("收到消息时 onMessage 回调真的被触发（不是白注册）")
        void messageListenerFiresOnInbound() throws Exception {
            try (Gb28181SipTransport t = new Gb28181SipTransport(0)) {
                t.start();
                java.util.concurrent.atomic.AtomicInteger hits =
                        new java.util.concurrent.atomic.AtomicInteger();
                t.onMessage(hits::incrementAndGet);
                Gb28181Signaling s = new Gb28181Signaling("34010000002000000001",
                        "127.0.0.1:" + 5099, "t");
                byte[] one = s.register(Gb28181DeviceId.of("34010000001", "10", "132", "0001"),
                        3600, 1).render();
                try (java.net.DatagramSocket peer = new java.net.DatagramSocket()) {
                    peer.send(new java.net.DatagramPacket(one, one.length,
                            new java.net.InetSocketAddress("127.0.0.1", t.localPort())));
                }
                long deadline = System.currentTimeMillis() + 3000;
                while (System.currentTimeMillis() < deadline && hits.get() == 0) {
                    Thread.sleep(25);
                }
                assertThat(hits.get()).as("onMessage 回调必须被触发").isPositive();
            }
        }

        @Test
        @DisplayName("接收线程是 daemon（否则单测 JVM 退不出去）")
        void receiveThreadIsDaemon() throws Exception {
            try (Gb28181SipTransport t = new Gb28181SipTransport(0)) {
                t.start();
                boolean daemon = false;
                for (Thread th : Thread.getAllStackTraces().keySet()) {
                    if (("sip-udp-" + t.localPort()).equals(th.getName())) {
                        daemon = th.isDaemon();
                    }
                }
                assertThat(daemon).as("接收线程必须 daemon").isTrue();
            }
        }

        @Test
        @DisplayName("设备替身的服务线程也是 daemon（同一理由）")
        void simulatorThreadIsDaemon() throws Exception {
            Gb28181DeviceSimulator sim = new Gb28181DeviceSimulator(0,
                    Gb28181DeviceId.of("34010000001", "10", "132", "0001"),
                    "127.0.0.1:5060", 4000, 1);
            sim.start();
            boolean daemon = false;
            for (Thread th : Thread.getAllStackTraces().keySet()) {
                if (("gb28181-device-sim-" + sim.port()).equals(th.getName())) {
                    daemon = th.isDaemon();
                }
            }
            assertThat(daemon).as("设备替身服务线程必须 daemon").isTrue();
            sim.close();
        }
    }

    @Nested
    @DisplayName("关闭后的可观测行为（硬信号，不靠时间窗）")
    class CloseBehaviour {

        @Test
        @DisplayName("设备替身 close 后不再回任何请求（不是\"假装关了\"）")
        void simulatorStopsReplyingAfterClose() throws Exception {
            Gb28181DeviceId id = Gb28181DeviceId.of("34010000001", "10", "132", "0001");
            Gb28181DeviceSimulator sim = new Gb28181DeviceSimulator(0, id, "127.0.0.1:5060", 4000, 1);
            sim.start();
            try (Gb28181SipTransport t = new Gb28181SipTransport(0)) {
                t.start();
                // 关之前：问一次 Catalog 能拿到 200 OK
                Gb28181SipMessage q = new Gb28181Signaling("34010000002000000001",
                        "127.0.0.1:" + 5099, "t").catalogQuery(id, 1);
                assertThat(t.transact(sim.address(), q, java.time.Duration.ofSeconds(3))
                        .statusCode()).isEqualTo(200);

                sim.close();
                // 关之后：再问一次必然超时（socket 已 close ⇒ 收不到回包）
                assertThatThrownBy(() -> t.transact(sim.address(), q, java.time.Duration.ofMillis(800)))
                        .isInstanceOf(Gb28181SipTransport.SipTimeoutException.class);
            }
        }

        @Test
        @DisplayName("hostOf：冒号在位置 0 时不当成 host:port 拆分")
        void hostOfWithLeadingColonIsNotSplit() throws Exception {
            // sipHost = ":5060" ⇒ lastIndexOf(':') == 0 ⇒ 整串当 host（不是空串）
            Gb28181DeviceSimulator sim = new Gb28181DeviceSimulator(0,
                    Gb28181DeviceId.of("34010000001", "10", "132", "0001"),
                    ":5060", 4000, 1);
            assertThat(sim.deviceSdp().receiveHost()).isEqualTo(":5060");
            sim.close();
        }
    }

    // ==================================================================
    // 传输：localPort / received / provisional 边界
    // ==================================================================

    @Nested
    @DisplayName("Gb28181SipTransport 的边界")
    class TransportBoundaries {

        @Test
        @DisplayName("通配 bind（0.0.0.0）时 localPort 是真实分配端口，地址形态如实")
        void localPortIsReal() throws Exception {
            try (Gb28181SipTransport t = new Gb28181SipTransport(0)) {
                // bindAddress 默认 0.0.0.0 ⇒ 本地地址是通配地址，不是回环地址
                assertThat(t.localPort()).isPositive();
                assertThat(t.localAddress().getPort()).isEqualTo(t.localPort());
                assertThat(t.localAddress().getAddress().isAnyLocalAddress()).isTrue();
                assertThat(t.received()).isEmpty();
            }
        }

        @Test
        @DisplayName("关闭后 localPort() 为 -1（DatagramSocket 语义：不是 0、不假装还在听）")
        void localPortIsMinusOneAfterClose() throws Exception {
            Gb28181SipTransport t = new Gb28181SipTransport(0);
            int p = t.localPort();
            assertThat(p).isPositive();
            t.close();
            // 关掉的 socket 报 -1：把\"端口还在\"当真会让人继续往一个死 socket 发包
            assertThat(t.localPort()).isEqualTo(-1);
        }
    }
}

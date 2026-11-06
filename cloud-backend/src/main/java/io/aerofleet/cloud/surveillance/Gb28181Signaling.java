package io.aerofleet.cloud.surveillance;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * GB28181 SIP 信令构造器（E5，spec D3）。
 *
 * <p>**2026-11-07 订正**：本类此前是纯字符串拼接，且 {@code Content-Length} 用**硬编码
 * 魔数**（INVITE 写 145、Catalog 写 141）——实测这两个 body 分别是 170 / 115 字节，
 * 且随参数（IP 位数、端口位数、SSRC 位数、SN 位数）变化。按 RFC 3261，对端严格按
 * Content-Length 读 body：写小 ⇒ 挂起等永不到来的字节，写大 ⇒ 截断。
 * **旧实现发出去的帧真机读不了**，而原单测只断言"包含某某头"，没碰过这个数。
 *
 * <p>现在所有帧经 {@link Gb28181SipMessage} 构造，{@code Content-Length} 由 body
 * 真实长度导出；Via 补 {@code branch}（RFC 3261 事务标识——原先缺它，真机无法把响应
 * 关联回请求）。本类仍**只出不进**（不做网络收发），传输见 {@link Gb28181SipTransport}。
 *
 * <p>命令方法名（{@code REGISTER} / {@code INVITE} / {@code MESSAGE} / {@code SUBSCRIBE}
 * / {@code ACK} / {@code BYE}）是 SIP 标准方法；国标语义由 {@code Subject}（设备编码 +
 * 通道号）与 MANSCDP XML 表达。
 */
public final class Gb28181Signaling {

    private final String sipDomain;
    private final String sipHost;
    private final String clientTag;

    public Gb28181Signaling(String sipDomain, String sipHost) {
        this(sipDomain, sipHost, "nexussky-" + UUID.randomUUID().toString().substring(0, 8));
    }

    public Gb28181Signaling(String sipDomain, String sipHost, String clientTag) {
        if (sipDomain == null || sipDomain.isBlank()) {
            throw new IllegalArgumentException("sipDomain must not be blank");
        }
        if (sipHost == null || sipHost.isBlank()) {
            throw new IllegalArgumentException("sipHost must not be blank");
        }
        this.sipDomain = sipDomain;
        this.sipHost = sipHost;
        this.clientTag = clientTag;
    }

    /** 本端（平台）使用的 From tag——同一条 dialog 内保持一致。 */
    public String clientTag() {
        return clientTag;
    }

    public String sipDomain() {
        return sipDomain;
    }

    public String sipHost() {
        return sipHost;
    }

    /**
     * 生成 Via branch（RFC 3261 §8.1.1.7：以 {@code z9hG4bK} 开头的 magic cookie）。
     * 每笔事务一个 branch，响应靠它归属请求。
     */
    public static String newBranch() {
        return "z9hG4bK-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    // ------------------------------------------------------------------
    // 下行：平台 → 设备
    // ------------------------------------------------------------------

    /**
     * REGISTER（设备注册）。鉴权握手（401 + Authorization）属传输层事务，
     * 本方法只出**首帧**（未含 Authorization 头）。
     */
    public Gb28181SipMessage register(Gb28181DeviceId deviceId, int expiresSec, int cseq) {
        String id = deviceId.value();
        return Gb28181SipMessage.request("REGISTER sip:" + sipDomain + " SIP/2.0", null)
                .header("Via", "SIP/2.0/UDP " + sipHost + ";branch=" + newBranch())
                .header("From", "<sip:" + id + "@" + sipDomain + ">;tag=" + clientTag)
                .header("To", "<sip:" + id + "@" + sipDomain + ">")
                .header("Call-ID", "reg-" + id + "-" + cseq)
                .header("CSeq", cseq + " REGISTER")
                .header("Contact", "<sip:" + id + "@" + sipHost + ">")
                .header("Max-Forwards", "70")
                .header("Expires", String.valueOf(expiresSec));
    }

    /** INVITE（实时点播）：目标 URI = {@code sip:<设备编码>:<通道>@<SIP域>}，SDP 描述接收端。 */
    public Gb28181SipMessage inviteStream(Gb28181DeviceId deviceId, int channel,
                                          String receiveHost, int receivePort, int ssrc) {
        String id = deviceId.value();
        Gb28181Sdp sdp = new Gb28181Sdp(sipDomain, receiveHost, receivePort, ssrc);
        return Gb28181SipMessage.request(
                "INVITE sip:" + id + ":" + channel + "@" + sipDomain + " SIP/2.0",
                sdp.body().getBytes(StandardCharsets.UTF_8))
                .header("Via", "SIP/2.0/UDP " + sipHost + ";branch=" + newBranch())
                .header("From", "<sip:" + sipDomain + "@" + sipDomain + ">;tag=" + clientTag)
                .header("To", "<sip:" + id + ":" + channel + "@" + sipDomain + ">")
                .header("Call-ID", "inv-" + ssrc)
                .header("CSeq", "1 INVITE")
                .header("Content-Type", "application/sdp")
                .header("Max-Forwards", "70")
                .header("Subject", id + ":" + channel + "," + sipDomain + ":0");
    }

    /** MESSAGE（目录查询 Catalog）：国标 XML 控制语义（MANSCDP）。 */
    public Gb28181SipMessage catalogQuery(Gb28181DeviceId deviceId, int sn) {
        String xml = "<?xml version=\"1.0\"?>\r\n"
                + "<Query><CmdType>Catalog</CmdType><SN>" + sn
                + "</SN><DeviceID>" + deviceId.value()
                + "</DeviceID></Query>";
        return Gb28181SipMessage.request(
                "MESSAGE sip:" + deviceId.value() + "@" + sipDomain + " SIP/2.0",
                xml.getBytes(StandardCharsets.UTF_8))
                .header("Via", "SIP/2.0/UDP " + sipHost + ";branch=" + newBranch())
                .header("From", "<sip:" + sipDomain + "@" + sipDomain + ">;tag=" + clientTag)
                .header("To", "<sip:" + deviceId.value() + "@" + sipDomain + ">")
                .header("Call-ID", "cat-" + sn)
                .header("CSeq", "1 MESSAGE")
                .header("Content-Type", "Application/MANSCDP+xml")
                .header("Max-Forwards", "70");
    }

    /** SUBSCRIBE（报警订阅）：国标移动设备订阅语义。 */
    public Gb28181SipMessage subscribeAlarm(Gb28181DeviceId deviceId, int expiresSec, int cseq) {
        String id = deviceId.value();
        return Gb28181SipMessage.request("SUBSCRIBE sip:" + id + "@" + sipDomain + " SIP/2.0", null)
                .header("Via", "SIP/2.0/UDP " + sipHost + ";branch=" + newBranch())
                .header("From", "<sip:" + sipDomain + "@" + sipDomain + ">;tag=" + clientTag)
                .header("To", "<sip:" + id + "@" + sipDomain + ">")
                .header("Call-ID", "sub-" + id + "-" + cseq)
                .header("CSeq", cseq + " SUBSCRIBE")
                .header("Event", "Presence")
                .header("Expires", String.valueOf(expiresSec));
    }

    /** ACK（对 2xx 响应的确认；INVITE 事务收尾必需）。 */
    public Gb28181SipMessage ack(Gb28181SipMessage invite, Gb28181SipMessage okResponse) {
        String id = invite.firstHeader("To");
        String uri = id == null ? sipDomain : stripTag(id);
        return Gb28181SipMessage.request("ACK " + uri + " SIP/2.0", null)
                .header("Via", invite.firstHeader("Via"))
                .header("From", invite.firstHeader("From"))
                .header("To", okResponse.firstHeader("To"))
                .header("Call-ID", invite.firstHeader("Call-ID"))
                .header("CSeq", invite.cseqNumber() + " ACK")
                .header("Max-Forwards", "70");
    }

    /** BYE（dialog 拆除）。 */
    public Gb28181SipMessage bye(Gb28181SipMessage invite, Gb28181SipMessage okResponse) {
        String id = invite.firstHeader("To");
        String uri = id == null ? sipDomain : stripTag(id);
        return Gb28181SipMessage.request("BYE " + uri + " SIP/2.0", null)
                .header("Via", "SIP/2.0/UDP " + sipHost + ";branch=" + newBranch())
                .header("From", invite.firstHeader("From"))
                .header("To", okResponse.firstHeader("To"))
                .header("Call-ID", invite.firstHeader("Call-ID"))
                .header("CSeq", "2 BYE")
                .header("Max-Forwards", "70");
    }

    // ------------------------------------------------------------------
    // 下行响应的形状（设备 → 平台）：模拟器与真设备同形
    // ------------------------------------------------------------------

    /** 100 Trying（INVITE 事务 Provisional）。 */
    public Gb28181SipMessage trying(Gb28181SipMessage invite) {
        return respond(invite, 100, null, null);
    }

    /** 200 OK（无 body）。 */
    public Gb28181SipMessage ok(Gb28181SipMessage request) {
        return respond(request, 200, null, null);
    }

    /** 200 OK + MANSCDP XML body（Catalog 应答）。 */
    public Gb28181SipMessage okXml(Gb28181SipMessage request, String xml) {
        return respond(request, 200, xml.getBytes(StandardCharsets.UTF_8), "Application/MANSCDP+xml");
    }

    /** 200 OK + SDP body（INVITE 应答：设备侧媒体参数）。 */
    public Gb28181SipMessage okSdp(Gb28181SipMessage request, Gb28181Sdp deviceSdp) {
        return respond(request, 200,
                deviceSdp.body().getBytes(StandardCharsets.UTF_8), "application/sdp");
    }

    /** 401 Unauthorized（带 WWW-Authenticate，触发鉴权握手）。 */
    public Gb28181SipMessage unauthorized(Gb28181SipMessage request, String realm, String nonce) {
        return respond(request, 401, null, null)
                .header("WWW-Authenticate", "Digest realm=\"" + realm + "\", nonce=\"" + nonce + "\"");
    }

    /**
     * 按请求构造响应（RFC 3261 §8.2.6：Via/From/To/Call-ID/CSeq 必须回带）。
     *
     * <p>To 头补 tag（响应必须带 tag，请求里的 To 无 tag）；CSeq 原样回带。
     */
    private Gb28181SipMessage respond(Gb28181SipMessage request, int code,
                                      byte[] body, String contentType) {
        Gb28181SipMessage msg = Gb28181SipMessage.response(code, null, body)
                .header("Via", request.firstHeader("Via"))
                .header("From", request.firstHeader("From"))
                .header("To", request.firstHeader("To"));
        // To 头补 tag：响应必须带，否则对端无法关联 dialog
        String to = msg.firstHeader("To");
        if (to != null && !to.contains("tag=")) {
            msg.header("To", to + ";tag=" + clientTag);
        }
        msg.header("Call-ID", request.firstHeader("Call-ID"))
                .header("CSeq", request.cseqNumber() + " " + request.method());
        if (contentType != null) {
            msg.header("Content-Type", contentType);
        }
        return msg;
    }

    private static String stripTag(String uriWithTag) {
        int i = uriWithTag.indexOf(";tag=");
        return i < 0 ? uriWithTag : uriWithTag.substring(0, i) + ">";
    }
}

package io.aerofleet.cloud.surveillance;

/**
 * GB28181 SIP 信令形状构造器（E5，spec D3）。
 * <p>
 * **纯构造器**：输出可断言的 SIP 消息文本（GB/T 28181-2016 信令格式），
 * 不做网络收发——SIP 传输与鉴权握手属生产阶段（诚实边界 spec §4）。
 */
public final class Gb28181Signaling {

    private final String sipDomain;      // SIP 服务器域（如 32011500002000000001）
    private final String sipHost;        // SIP 服务器地址 host:port

    public Gb28181Signaling(String sipDomain, String sipHost) {
        this.sipDomain = sipDomain;
        this.sipHost = sipHost;
    }

    /** REGISTER（设备注册，未含鉴权——401/Authorization 握手属传输层）。 */
    public String register(Gb28181DeviceId deviceId, int expiresSec, int cseq) {
        String id = deviceId.value();
        return "REGISTER sip:" + sipDomain + " SIP/2.0\r\n"
                + "Via: SIP/2.0/UDP " + sipHost + "\r\n"
                + "From: <sip:" + id + "@" + sipDomain + ">;tag=reg-" + cseq + "\r\n"
                + "To: <sip:" + id + "@" + sipDomain + ">\r\n"
                + "Call-ID: reg-" + id + "-" + cseq + "\r\n"
                + "CSeq: " + cseq + " REGISTER\r\n"
                + "Contact: <sip:" + id + "@" + sipHost + ">\r\n"
                + "Max-Forwards: 70\r\n"
                + "Expires: " + expiresSec + "\r\n"
                + "Content-Length: 0\r\n\r\n";
    }

    /** INVITE（实时点播）：目标 URI = sip:<设备编码>:<通道>@<SIP 域>，SDP 描述接收端。 */
    public String inviteStream(Gb28181DeviceId deviceId, int channel,
                               String receiveHost, int receivePort, int ssrc) {
        String id = deviceId.value();
        return "INVITE sip:" + id + ":" + channel + "@" + sipDomain + " SIP/2.0\r\n"
                + "Via: SIP/2.0/UDP " + sipHost + "\r\n"
                + "From: <sip:" + sipDomain + "@" + sipDomain + ">;tag=inv-" + ssrc + "\r\n"
                + "To: <sip:" + id + ":" + channel + "@" + sipDomain + ">\r\n"
                + "Call-ID: inv-" + ssrc + "\r\n"
                + "CSeq: 1 INVITE\r\n"
                + "Content-Type: application/sdp\r\n"
                + "Max-Forwards: 70\r\n"
                + "Subject: " + id + ":" + channel + "," + sipDomain + ":0\r\n"
                + "Content-Length: 145\r\n\r\n"
                + "v=0\r\n"
                + "o=" + sipDomain + " 0 0 IN IP4 " + receiveHost + "\r\n"
                + "s=Play\r\n"
                + "c=IN IP4 " + receiveHost + "\r\n"
                + "t=0 0\r\n"
                + "m=video " + receivePort + " TCP/RTP/AVP 96 98 97\r\n"
                + "a=recvonly\r\n"
                + "a=rtpmap:96 PS/90000\r\n"
                + "y=" + ssrc + "\r\n\r\n";
    }

    /** MESSAGE（目录查询 Catalog）：国标 XML 控制语义。 */
    public String catalogQuery(Gb28181DeviceId deviceId, int sn) {
        return "MESSAGE sip:" + deviceId.value() + "@" + sipDomain + " SIP/2.0\r\n"
                + "Via: SIP/2.0/UDP " + sipHost + "\r\n"
                + "From: <sip:" + sipDomain + "@" + sipDomain + ">;tag=cat-" + sn + "\r\n"
                + "To: <sip:" + deviceId.value() + "@" + sipDomain + ">\r\n"
                + "Call-ID: cat-" + sn + "\r\n"
                + "CSeq: 1 MESSAGE\r\n"
                + "Content-Type: Application/MANSCDP+xml\r\n"
                + "Max-Forwards: 70\r\n"
                + "Content-Length: 141\r\n\r\n"
                + "<?xml version=\"1.0\"?>\r\n"
                + "<Query><CmdType>Catalog</CmdType><SN>" + sn
                + "</SN><DeviceID>" + deviceId.value()
                + "</DeviceID></Query>";
    }

    /** SUBSCRIBE（报警订阅）：国标移动设备订阅语义。 */
    public String subscribeAlarm(Gb28181DeviceId deviceId, int expiresSec, int cseq) {
        String id = deviceId.value();
        return "SUBSCRIBE sip:" + id + "@" + sipDomain + " SIP/2.0\r\n"
                + "Via: SIP/2.0/UDP " + sipHost + "\r\n"
                + "From: <sip:" + sipDomain + "@" + sipDomain + ">;tag=sub-" + cseq + "\r\n"
                + "To: <sip:" + id + "@" + sipDomain + ">\r\n"
                + "Call-ID: sub-" + id + "-" + cseq + "\r\n"
                + "CSeq: " + cseq + " SUBSCRIBE\r\n"
                + "Event: Presence\r\n"
                + "Expires: " + expiresSec + "\r\n"
                + "Content-Length: 0\r\n\r\n";
    }
}

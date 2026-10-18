package io.aerofleet.cloud.surveillance;

import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;

/**
 * 按<b>厂商公开规范</b>拼 RTSP URL 的单一构造点。
 *
 * <h2>为什么要有这个类</h2>
 *
 * <p>海康 / 大华 / 宇视 / ONVIF 四个适配器各自拼一条 {@code rtsp://} 地址，
 * 路径部分各不相同（{@code /Streaming/Channels/N}、{@code /cam/realmonitor?channel=N&subtype=0}、
 * {@code /media/videoN}），但 <b>scheme + userinfo + authority 这一段是完全一样的</b>。
 * 此前这段被逐字抄了四份，于是同一个编码缺陷也复制了四份 —— 改一处不改其余三处
 * 就会留下三条"看着已经修好了"的路径。
 *
 * <p>所以路径部分继续由各适配器给（那是厂商差异，本来就该各自持有），
 * <b>凭据编码与 authority 拼装收敛到这里</b>。
 *
 * <h2>凭据编码：为什么不能用 {@code URLEncoder.encode}</h2>
 *
 * <p>{@code URLEncoder.encode} 实现的是 {@code application/x-www-form-urlencoded}
 * （HTML 表单提交）语义，不是 URL 语义。两者在**空格**上不一致：
 *
 * <pre>
 *   form 语义：空格 → "+"      （接收端把 "+" 解回空格）
 *   URL  语义：空格 → "%20"    （"+" 是字面加号，不是空格）
 * </pre>
 *
 * <p>把它用在 userinfo 上，密码 {@code "p w"} 会变成 {@code "p+w"}，而设备在
 * URL 语义下读到的是 {@code "p+w"} —— <b>密码错了</b>。现象只是"连不上"，
 * 排查时会一路怀疑摄像头、网络、账号，而不是这几行代码。
 *
 * <p>{@link UriUtils#encodeUserInfo} 用的是 RFC 3986 语义（{@code +} 保持字面，
 * 空格编为 {@code %20}），正是这里需要的。
 *
 * <h2>这条是否经过真实设备验证</h2>
 *
 * <p><b>没有，而且不能声称有</b>。URL <b>形状</b>已对真实 RTSP 服务器验证过
 * （见 {@code scripts/e2e-rtsp-url-shape.py}：正面能拉到 H.264 活流，
 * 反例密码错一位 401、路径错 404）；但"海康设备是否真的用这个路径"仍未向
 * 真实设备确认，对应 {@link StreamUrlOrigin#VENDOR_STANDARD_FORMAT}
 * 的 {@code deviceVerified=false}。
 */
public final class VendorRtspUrl {

    /** RTSP 默认端口。厂商规范里的 554。 */
    public static final int RTSP_PORT = 554;

    private VendorRtspUrl() {
    }

    /**
     * 拼一条厂商规范形状的 RTSP URL。
     *
     * @param username     登录用户名（原文，本方法负责编码）
     * @param password     登录密码（原文，本方法负责编码）
     * @param host         设备地址
     * @param port         端口
     * @param pathAndQuery 厂商特有的路径与查询串，例如
     *                     {@code "/Streaming/Channels/1"} 或
     *                     {@code "/cam/realmonitor?channel=1&subtype=0"}
     *                     —— 这段<b>不</b>做编码，因为它不是数据而是各家固定的字面量
     * @return {@code rtsp://user:pass@host:port/path}
     */
    public static String format(String username, String password,
                               String host, int port, String pathAndQuery) {
        return "rtsp://" + encodeUserInfo(username) + ":" + encodeUserInfo(password)
                + "@" + host + ":" + port + pathAndQuery;
    }

    /**
     * userinfo 的 RFC 3986 编码。
     *
     * <p>空值按空串处理：适配器上有 {@code validateDevice} 把关，但这里不做
     * 抛异常的判断 —— 让"缺凭据"表现为 URL 里空的一段，比在拼串工具里抛异常
     * 更容易在调用点被看见。
     */
    static String encodeUserInfo(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        return UriUtils.encodeUserInfo(raw, StandardCharsets.UTF_8);
    }
}
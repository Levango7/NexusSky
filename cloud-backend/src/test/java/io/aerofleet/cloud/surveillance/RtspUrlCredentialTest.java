package io.aerofleet.cloud.surveillance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 厂商适配器拼出的 RTSP URL：**凭据必须能原样还原**。
 *
 * <h2>为什么专门测这个</h2>
 *
 * <p>这些 URL 是按厂商公开规范「推导」出来的（{@code StreamUrlOrigin.VENDOR_STANDARD_FORMAT}），
 * 代码从未向设备确认过。格式对不对可以靠真 RTSP 服务器验（见
 * {@code scripts/e2e-rtsp-url-shape.py}），但**凭据有没有被拼坏**是另一回事：
 * 密码里带一个空格就能让整条 URL 指向错误的用户名/密码，而现象只是"设备连不上"，
 * 排查起来会一路怀疑到摄像头上。
 *
 * <h2>为什么不直接断言字符串</h2>
 *
 * <p>断言 {@code "rtsp://admin:a+b@..."} 这种写法会把实现细节写死，而且**看不出对不对**。
 * 这里的口径是：把生成 URL 的 userinfo 取出来、按 URL 规则解码，必须逐字符等于
 * 原始用户名/密码。这样无论实现用哪种编码，只要语义正确就能过；语义错了就一定红。
 *
 * <p>注意这里用 {@link URLDecoder} 是<b>有意</b>的：它对应
 * {@code application/x-www-form-urlencoded} 语义（{@code +} 表示空格）。如果实现用
 * form 编码而 URL 语义要求 percent-encoding，{@code +} 就会被解析回空格从而"碰巧通过"——
 * 所以下面还有一条**按 RFC 3986 语义**的独立断言（{@link URI#getUserInfo} 不做
 * {@code +}→空格 转换），两条一起才拦得住。
 */
@DisplayName("厂商适配器 RTSP URL：凭据可原样还原")
class RtspUrlCredentialTest {

    private static SurveillanceDevice device(String user, String pass) {
        SurveillanceDevice d = new SurveillanceDevice("cam-cred", "cred-test",
                SurveillanceDevice.Vendor.HIKVISION, "192.168.1.64", 80, user, pass);
        return d;
    }

    /** userinfo 的原始（未解码）文本。 */
    private static String rawUserInfo(String url) {
        int scheme = url.indexOf("://");
        assertThat(scheme).isGreaterThanOrEqualTo(0);
        String rest = url.substring(scheme + 3);
        int at = rest.lastIndexOf('@');
        assertThat(at).isGreaterThanOrEqualTo(0);
        return rest.substring(0, at);
    }

    /**
     * 按 RFC 3986 语义解出的 userinfo。
     *
     * <p>{@link URI#getUserInfo()} 返回的是**解码后**的值，且不做 {@code +}→空格
     * 转换（那是 form 语义，不是 URL 语义）。
     */
    private static String rfc3986UserInfo(String url) throws Exception {
        return new URI(url).getUserInfo();
    }

    // ===== ONVIF =====

    @Test
    @DisplayName("ONVIF：普通凭据可原样还原")
    void onvifPlainCredentials() throws Exception {
        OnvifClient client = new OnvifClient();
        String url = client.getRtspUrl("192.168.1.64", 80, "admin", "Passw0rd", 1);

        assertThat(rfc3986UserInfo(url)).isEqualTo("admin:Passw0rd");
        assertThat(url).startsWith("rtsp://").contains("192.168.1.64").contains("/Streaming/Channels/1");
    }

    /**
     * 这条是全套里最要紧的一条。
     *
     * <p>密码含空格时，若实现对 userinfo 用了 form 编码，空格会变成 {@code +}。
     * 在 URL 语义里 {@code +} 是**字面加号**，不是空格 —— 于是设备收到的是
     * {@code "pass+word"}，鉴权失败，而现象只是"连不上"。
     */
    @Test
    @DisplayName("ONVIF：密码含空格时必须按 URL 语义编码，不能变成加号")
    void onvifPasswordWithSpace() throws Exception {
        OnvifClient client = new OnvifClient();
        String url = client.getRtspUrl("192.168.1.64", 80, "admin", "pass word", 1);

        assertThat(rfc3986UserInfo(url))
                .as("URL 语义下 userinfo 应为 admin:pass word，而不是 admin:pass+word")
                .isEqualTo("admin:pass word");
        // 明确排除"碰巧靠 + 还原成空格"的通过路径
        assertThat(rawUserInfo(url)).doesNotContain("+");
    }

    /**
     * 这些字符会破坏 URL 结构（{@code @} 会截断 host、{@code /} 会改路径、
     * {@code ?}/{@code #} 会开 query/fragment），必须被百分号编码。
     */
    @Test
    @DisplayName("ONVIF：密码含 @ : / ? # 时不得破坏 URL 结构")
    void onvifPasswordWithStructuralChars() throws Exception {
        OnvifClient client = new OnvifClient();
        String tricky = "p@ss:w/rd?x#y";
        String url = client.getRtspUrl("192.168.1.64", 80, "a@b", tricky, 1);

        assertThat(rfc3986UserInfo(url)).isEqualTo("a@b:" + tricky);
        // 结构没被破坏：host 与 path 仍然解析得出来
        URI u = new URI(url);
        assertThat(u.getHost()).isEqualTo("192.168.1.64");
        assertThat(u.getPath()).isEqualTo("/Streaming/Channels/1");
        assertThat(u.getQuery()).isNull();
        assertThat(u.getFragment()).isNull();
    }

    // ===== maskCredentials 脱敏 =====

    @Test
    @DisplayName("maskCredentials：带凭据的地址只留下 ***@，host/path/query 不变")
    void maskCredentials_replacesUserinfo() {
        String masked = VendorRtspUrl.maskCredentials(
                "rtsp://admin:p%40ss@192.168.1.64:554/Streaming/Channels/101?x=1");

        assertThat(masked).isEqualTo("rtsp://***@192.168.1.64:554/Streaming/Channels/101?x=1");
        // 凭据的任何一部分都不能残留在脱敏结果里
        assertThat(masked).doesNotContain("admin").doesNotContain("p%40ss");
    }

    @Test
    @DisplayName("maskCredentials：null / 空串 / 无凭据地址原样返回（不 NPE、不误伤）")
    void maskCredentials_edgeInputsUnchanged() {
        assertThat(VendorRtspUrl.maskCredentials(null)).isNull();
        assertThat(VendorRtspUrl.maskCredentials("")).isEmpty();
        assertThat(VendorRtspUrl.maskCredentials("rtsp://192.168.1.64:554/live"))
                .isEqualTo("rtsp://192.168.1.64:554/live");
    }

    // ===== 私有协议适配器（海康 / 大华 / 宇视）=====

    @Test
    @DisplayName("海康：凭据可原样还原，密码含空格不变加号")
    void hikCredentials() throws Exception {
        HikAdapter adapter = new HikAdapter();
        String url = adapter.getStreamUrl(device("admin", "pass word"), 1);

        assertThat(rfc3986UserInfo(url)).isEqualTo("admin:pass word");
        assertThat(rawUserInfo(url)).doesNotContain("+");
        assertThat(url).contains("/Streaming/Channels/1");
    }

    @Test
    @DisplayName("大华：凭据可原样还原，密码含空格不变加号")
    void dahuaCredentials() throws Exception {
        DahuaAdapter adapter = new DahuaAdapter();
        String url = adapter.getStreamUrl(device("admin", "pass word"), 1);

        assertThat(rfc3986UserInfo(url)).isEqualTo("admin:pass word");
        assertThat(rawUserInfo(url)).doesNotContain("+");
        assertThat(url).contains("/cam/realmonitor");
        assertThat(url).contains("channel=1");
    }

    @Test
    @DisplayName("宇视：凭据可原样还原，密码含空格不变加号")
    void univiewCredentials() throws Exception {
        UniviewAdapter adapter = new UniviewAdapter();
        String url = adapter.getStreamUrl(device("admin", "pass word"), 1);

        assertThat(rfc3986UserInfo(url)).isEqualTo("admin:pass word");
        assertThat(rawUserInfo(url)).doesNotContain("+");
    }

    /**
     * 兜底：把所有适配器一起比一遍，防止将来新增适配器时漏掉某一条路径。
     */
    @Test
    @DisplayName("三个私有协议适配器与 ONVIF 口径一致（新增适配器不会漏网）")
    void allAdaptersAgree() throws Exception {
        SurveillanceDevice d = device("admin", "p w");
        String[] urls = {
            new HikAdapter().getStreamUrl(d, 1),
            new DahuaAdapter().getStreamUrl(d, 1),
            new UniviewAdapter().getStreamUrl(d, 1),
            new OnvifClient().getRtspUrl(d.ip, d.port, d.username, d.password, 1),
        };
        for (String u : urls) {
            assertThat(rfc3986UserInfo(u)).as("URL %s", u).isEqualTo("admin:p w");
        }
    }
}
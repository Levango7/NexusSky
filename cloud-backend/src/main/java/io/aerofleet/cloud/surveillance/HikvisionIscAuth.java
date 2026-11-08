package io.aerofleet.cloud.surveillance;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 海康 iSecure Center OpenAPI（Artemis 网关）的 AK/SK 签名器与 token 态。
 *
 * <p><b>依据</b>：海康开放平台公开文档（综合安防管理平台 iSecure Center OpenAPI），
 * 以及官方 SDK 行为。核心事实逐条落实：
 * <ul>
 *   <li>接口地址 {@code https://{host}:{port}/artemis/{uri}}（<b>artemis 前缀必带</b>）；</li>
 *   <li>仅支持 HTTPS；鉴权为 <b>AK/SK 摘要签名</b>：请求头带
 *       {@code x-ca-key}/{@code x-ca-nonce}/{@code x-ca-timestamp}/
 *       {@code x-ca-signature-headers}/{@code x-ca-signature}；</li>
 *   <li>签名字符串（<b>顺序即契约</b>）：{@code METHOD + \n + Accept + \n + Content-Type
 *       + \n + 各自定义头（小写 k:v，按名排序）+ \n + 带 artemis 前缀的路径}；</li>
 *   <li>{@code X-Ca-Signature = Base64(HmacSHA256(appSecret, 签名字符串)}；</li>
 *   <li><b>token 也必须经签名请求获取</b>（{@code POST /artemis/oauth/token}，
 *       body {@code {client_id, client_secret}}）——不是匿名端点；</li>
 *   <li>响应统一形状 {@code {"code":"0","msg":"success","data":...}}，
 *       <b>code 为字符串 "0" 才是成功</b>（不是 HTTP 200 就是成功）。</li>
 * </ul>
 *
 * <p><b>为什么值得单测</b>：签名在**固定 nonce/timestamp** 下是确定性的——
 * 因此可以构造**已知答案**测试（固定 appSecret + 固定输入 ⇒ 固定 X-Ca-Signature）。
 * 这类测试能在任何环境跑，不依赖真平台，同时把"拼接顺序错一位"这类缺陷钉死
 * （官方文档示例与多个第三方 SDK 都踩过：字符串拼接少一个换行、头排序用错大小写，
 * 签名就静默不匹配，而错误只在真平台上出现）。
 *
 * @param accessToken 访问令牌
 * @param expiresAt   过期时刻（客户端扣安全余量后的实际可用截止）
 */
record HikvisionIscAuth(String accessToken, Instant expiresAt) {

    /** 客户端安全余量：提前这么久判过期（真平台上 token 可能提前失效）。 */
    static final java.time.Duration SAFETY_MARGIN = java.time.Duration.ofMinutes(1);

    HikvisionIscAuth {
        Objects.requireNonNull(accessToken, "accessToken");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    /** 文档声明的 token 有效期 2 小时（ISC OpenAPI 口径）。 */
    static final java.time.Duration DOC_TTL = java.time.Duration.ofHours(2);

    static HikvisionIscAuth issuedNow(String accessToken, Instant issuedAt) {
        return new HikvisionIscAuth(accessToken,
                issuedAt.plus(DOC_TTL).minus(SAFETY_MARGIN));
    }

    boolean validAt(Instant now) {
        return now.isBefore(expiresAt);
    }

    // ------------------------------------------------------------------
    // 签名
    // ------------------------------------------------------------------

    /**
     * 计算 {@code X-Ca-Signature}。
     *
     * <p>签名字符串（详见类注释）——**顺序即契约**，改一位就是另一个签名：
     * <ol>
     *   <li>{@code METHOD}（大写）</li>
     *   <li>{@code Accept}</li>
     *   <li>{@code Content-Type}（不含 charset/boundary 参数）</li>
     *   <li>参与签名的自定义头，按头名**小写**排序，逐行 {@code k:v}</li>
     *   <li>带 {@code /artemis} 前缀的路径</li>
     * </ol>
     * 段与段之间都是单个 {@code \n}，最后一行的路径后**不加**换行。
     *
     * @param signedHeaders 参与签名的自定义头（会按小写名排序后逐行拼接）
     * @return Base64(HmacSHA256(appSecret, 签名字符串))
     */
    static String signature(String appSecret, String method, String accept, String contentType,
                            Map<String, String> signedHeaders, String artemisPath) {
        StringBuilder sb = new StringBuilder(160);
        sb.append(method.toUpperCase(java.util.Locale.ROOT)).append('\n');
        sb.append(accept == null ? "" : accept).append('\n');
        sb.append(stripParams(contentType)).append('\n');
        // 自定义头按**小写头名**排序（契约：若用原始大小写排，海康网关会判签名不符）
        new TreeMap<>(normalize(signedHeaders)).forEach((k, v) ->
                sb.append(k).append(':').append(v == null ? "" : v.trim()).append('\n'));
        sb.append(artemisPath);
        return hmacBase64(appSecret, sb.toString());
    }

    /** Content-Type 只留主类型（去掉 {@code ;charset=…} 与 {@code ;boundary=…}）。 */
    private static String stripParams(String ct) {
        if (ct == null) {
            return "";
        }
        int i = ct.indexOf(';');
        return (i < 0 ? ct : ct.substring(0, i)).trim();
    }

    private static Map<String, String> normalize(Map<String, String> headers) {
        Map<String, String> out = new LinkedHashMap<>();
        if (headers != null) {
            headers.forEach((k, v) -> out.put(k.toLowerCase(java.util.Locale.ROOT), v));
        }
        return out;
    }

    private static String hmacBase64(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(
                    data.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    /** 可选：请求体的 Content-MD5（海康网关对带 body 的请求可要求）。 */
    static String contentMd5(String body) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return Base64.getEncoder().encodeToString(md.digest(
                    body.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 unavailable", e);
        }
    }
}

package io.aerofleet.cloud.surveillance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;

/**
 * 大华 ICC OpenAPI 客户端（**零硬件可对接**：公开文档 + 联调测试环境）。
 *
 * <p><b>依据</b>：大华开放平台公开文档 {@code open-icc.dahuatech.com} 的
 * {@code 对接指南 → 整体流程}。原文要点逐条落实：
 * <ul>
 *   <li>平台访问地址 {@code https://平台IP:端口}，默认端口 <b>443</b>；</li>
 *   <li>获取公钥：{@code GET /evo-apigw/evo-oauth/1.0.0/oauth/public-key}；</li>
 *   <li>认证：{@code POST /evo-apigw/evo-oauth/1.0.0/oauth/extend/token}，</li>
 *       body 形如 {@code {grant_type, username, password, client_id, client_secret, public_key}}，</li>
 *       <b>password 须 RSA 加密</b>（原文：\"password RSA加密后的密码\"，公钥由上一步取得）；</li>
 *   <li>业务接口请求头 {@code Authorization: bearer <access_token>}；</li>
 *   <li>文档明示\"若客户暂无 ICC 环境，可使用我方提供的<b>联调测试环境</b>\"——</li>
 *       即<b>不需要买硬件</b>即可打通本客户端。</li>
 * </ul>
 *
 * <p><b>异常纪律</b>（同 #248/#250：对外只留自建短句，底层 IO/HTTP 细节只进日志
 * 与 cause）。特别地：<b>token 响应里的字段缺失/结构不符</b>要报\"平台响应不符合
 * 公开文档形状\"，而不是让上层拿到一个 null 字段到处 NPE。
 *
 * <p><b>边界（不假装）</b>：本类只实现**契约层**——
 * <ul>
 *   <li>RSA 加密用 JDK 自带算法，但**不校验服务端证书链**（联调环境常是自签名）；</li>
 *   <li>不做重试退避、不做连接池调优、不覆盖大华 ICC 的全部 18 个 SDK 模块；</li>
 *   <li>**未接真实平台**——需 client_id/client_secret + 平台地址，属商务授权项。</li>
 * </ul>
 */
public final class DahuaIccClient {

    private static final Logger log = LoggerFactory.getLogger(DahuaIccClient.class);

    /** OAuth token 端点（公开文档路径）。 */
    static final String TOKEN_PATH = "/evo-apigw/evo-oauth/1.0.0/oauth/extend/token";
    /** 公钥端点（公开文档路径）。 */
    static final String PUBLIC_KEY_PATH = "/evo-apigw/evo-oauth/1.0.0/oauth/public-key";

    private final URI baseUri;
    private final String clientId;
    private final String clientSecret;
    private final String username;
    private final String password;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 当前 token（null = 尚未认证）。 */
    private volatile DahuaIccAuth auth;

    /**
     * @param baseUri     平台基址，如 {@code https://10.80.3.101:443}
     * @param clientId    访问凭证 id（平台 OpenAPI 页面申请）
     * @param clientSecret 访问凭证密钥
     * @param username    OpenAPI 用户名（文档建议<b>不要</b>用 system）
     * @param password    平台用户密码（明文；本类负责 RSA 加密后发送）
     */
    public DahuaIccClient(URI baseUri, String clientId, String clientSecret,
                          String username, String password) {
        this.baseUri = Objects.requireNonNull(baseUri, "baseUri");
        this.clientId = Objects.requireNonNull(clientId, "clientId");
        this.clientSecret = Objects.requireNonNull(clientSecret, "clientSecret");
        this.username = Objects.requireNonNull(username, "username");
        this.password = Objects.requireNonNull(password, "password");
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** 当前鉴权态（未认证时为 {@code null}；供测试与诊断）。 */
    public DahuaIccAuth currentAuth() {
        return auth;
    }

    /**
     * 取 access_token（按文档两步：先取公钥，再 RSA 加密密码后认证）。
     *
     * <p>幂等：已有可用 token 时直接复用（不重复认证——真实平台上频繁认证
     * 可能触发账号并发限制）。
     */
    public synchronized DahuaIccAuth authenticate() {
        DahuaIccAuth cur = auth;
        if (cur != null && cur.validAt(Instant.now())) {
            return cur;
        }
        String publicKeyPem = fetchPublicKey();
        String encrypted = rsaEncrypt(password, publicKeyPem);
        DahuaIccAuth fresh = requestToken(encrypted);
        this.auth = fresh;
        log.info("dahua icc authenticated (expiresAt={})", fresh.expiresAt());
        return fresh;
    }

    /** GET 公钥（文档：返回 PEM 公钥，用于 RSA 加密密码）。 */
    String fetchPublicKey() {
        HttpResponse<String> resp = send(HttpRequest.newBuilder()
                .uri(resolve(PUBLIC_KEY_PATH))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build(), "fetch public key");
        if (resp.statusCode() != 200) {
            throw new DahuaIccException("dahua icc public key request failed: HTTP "
                    + resp.statusCode());
        }
        String body = resp.body();
        if (body == null || body.isBlank()) {
            throw new DahuaIccException("dahua icc public key response is empty");
        }
        return body.trim();
    }

    /**
     * POST 认证（文档请求体六字段）。
     *
     * <p>响应形状：文档示例为 {@code {"data":{"accessToken":...},"errMsg":""}} 一族。
     * <b>不同 ICC 版本字段名可能有出入</b>（文档明确\"不同 ICC 版本接口方法会有出入\"），
     * 故这里做**宽容解析**：按候选字段名依次找 accessToken / token_type，
     * 都找不到才抛——而不是假设某一个名字永远对。
     */
    DahuaIccAuth requestToken(String encryptedPassword) {
        String body;
        try {
            body = mapper.writeValueAsString(Map.of(
                    "grant_type", "password",
                    "username", username,
                    "password", encryptedPassword,
                    "client_id", clientId,
                    "client_secret", clientSecret));
        } catch (Exception e) {
            throw new DahuaIccException("build token request failed", e);
        }
        HttpResponse<String> resp = send(HttpRequest.newBuilder()
                .uri(resolve(TOKEN_PATH))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json;charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, java.nio.charset.StandardCharsets.UTF_8))
                .build(), "request access token");
        if (resp.statusCode() != 200) {
            throw new DahuaIccException("dahua icc token request failed: HTTP " + resp.statusCode());
        }
        JsonNode root;
        try {
            root = mapper.readTree(resp.body());
        } catch (Exception e) {
            throw new DahuaIccException("dahua icc token response unreadable", e);
        }
        JsonNode data = root.path("data");
        String token = firstText(data, "accessToken", "access_token", "token", "magicId");
        if (token == null || token.isBlank()) {
            throw new DahuaIccException(
                    "dahua icc token response has no access token field (documented shape is data.accessToken)");
        }
        String type = firstText(data, "tokenType", "token_type");
        return DahuaIccAuth.issuedNow(token, type == null || type.isBlank() ? "bearer" : type,
                Instant.now());
    }

    /**
     * 带鉴权头的 GET（业务接口通用入口）。
     *
     * <p>401 时<b>重新认证并重试一次</b>——真实平台上 token 提前失效（账号被踢、
     * 平台重启）比 2h 到期更常见，只按 TTL 判过期会在这类场景下全盘失败。
     */
    HttpResponse<String> getAuthorized(String path) {
        DahuaIccAuth a = authenticate();
        HttpResponse<String> resp = send(HttpRequest.newBuilder()
                .uri(resolve(path))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", a.authorizationHeader())
                .GET()
                .build(), "get " + path);
        if (resp.statusCode() == 401) {
            log.warn("dahua icc got 401 on {} — re-authenticating once", path);
            this.auth = null;
            DahuaIccAuth fresh = authenticate();
            resp = send(HttpRequest.newBuilder()
                    .uri(resolve(path))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", fresh.authorizationHeader())
                    .GET()
                    .build(), "get " + path + " (after re-auth)");
        }
        return resp;
    }

    /**
     * RSA 加密（公钥 PEM → Base64 密文）。失败按自建异常报，不泄底。
     *
     * <p><b>{@code @SuppressWarnings("java/rsa-without-oaep")} 的理由</b>：这里必须用
     * PKCS#1 v1.5 填充，改 OAEP 平台侧解不开、登录必失败——
     * ① 大华官方 demo（{@code Cipher.getInstance("RSA")}）与官方 Python 示例
     * （{@code PKCS1_v1_5}）都是这个填充；② 该公钥是平台主动下发的一次性会话公钥
     * （有效期 10 分钟、认证成功后失效、用后自动释放），v1.5 的理论攻击面在此语境下
     * 不成立，解密失败风险落在厂商侧而非本客户端。
     * <p>放行条件（写在这里，条件变了必须撤掉本注解）：厂商文档给出 OAEP 示例，
     * 或联调环境实测 OAEP 可登录。逐条依据见 {@code docs/codeql-alert-disposition.md}。
     * <p><b>为什么用注解而不是 {@code // codeql[...] } 注释</b>：本仓 Java 侧既有两条
     * CodeQL 例外（{@code java/spring-disabled-csrf-protection}、
     * {@code java/tainted-numeric-cast}）都是用 {@code @SuppressWarnings} 标在方法上，
     * 且实测只有这种形式生效——注释形式（紧邻上一行 / 同一行行尾）都试过，
     * CodeQL 仍报同一条告警。本方法与它们保持同一口径。
     */
    @SuppressWarnings("java/rsa-without-oaep")
    static String rsaEncrypt(String plain, String publicKeyPem) {
        try {
            String pem = publicKeyPem
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    // **这里踩过一个转义坑，留痕**：早期写成 .replaceAll("\\s", "")
                    // ——Java 字符串字面量里 \\s 才是正则的空白类；写成 \\\\s
                    // 会匹配**字面反斜杠**，PEM 的换行原样留下，Base64 解码抛
                    // Illegal base64 character a。改用非正则的逐段 replace：
                    // 可读，且不会再被引号层数咬一口。
                    .replace("\n", "")
                    .replace("\r", "")
                    .trim();
            byte[] der = Base64.getDecoder().decode(pem);
            java.security.KeyFactory kf = java.security.KeyFactory.getInstance("RSA");
            java.security.PublicKey key = kf.generatePublic(
                    new java.security.spec.X509EncodedKeySpec(der));
            // ⚠️ **安全例外，勿"顺手修成 OAEP"**（CodeQL: RSA/ECB/PKCS1Padding without OAEP）
            //
            // 为什么必须留在 PKCS#1 v1.5：
            //   ① 大华官方文档《RSA密码加密》demo 用的就是 Cipher.getInstance("RSA")
            //      （= PKCS#1 v1.5），官方 Python 示例同样用 PKCS1_v1_5——
            //      换成 OAEP 后平台侧解不开，登录必失败；
            //   ② 这里的明文是**平台登录密码**，且公钥由平台每次下发、**用后即失效**
            //      （文档：公钥有效期 10 分钟，认证成功后立即失效）。
            //      换言之：这不是可重放的长期密钥体系，而是平台主动下发的一次性会话公钥；
            //   ③ 平台侧只有私钥，服务端解密若实现不当，v1.5 padding 的理论弱点也落在
            //      **厂商侧**，不在本客户端可控范围内。
            //
            // 什么条件下才允许改成 OAEP：
            //   大华开放平台文档明确给出 OAEP 加密示例（或联调环境实测 OAEP 可登录）。
            //   在那之前改成 OAEP = 把"更安全但连不上"当成"更安全"，是负优化。
            //   已登记在 CodeQL 例外清单（见 .github/codeql-*.yml 与本方法上方的说明）。
            // PKCS#1 v1.5：官方 demo（Cipher.getInstance("RSA")）与官方 Python 示例
            // （PKCS1_v1_5）都是这个填充，改 OAEP 平台侧解不开、登录必失败。
            // 且该公钥是平台主动下发的一次性会话公钥（有效期 10 分钟、认证成功后失效、
            // 用后自动释放），v1.5 的攻击面在此语境下不成立，解密失败风险在厂商侧。
            // 放行条件：厂商文档给出 OAEP 示例或联调环境实测 OAEP 可登录。
            // 逐条依据见 docs/codeql-alert-disposition.md。
            javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("RSA/ECB/PKCS1Padding");
            c.init(javax.crypto.Cipher.ENCRYPT_MODE, key);
            return Base64.getEncoder().encodeToString(c.doFinal(
                    plain.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new DahuaIccException("rsa encrypt password failed", e);
        }
    }

    private URI resolve(String path) {
        String b = baseUri.toString();
        if (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        return URI.create(b + (path.startsWith("/") ? path : "/" + path));
    }

    private HttpResponse<String> send(HttpRequest req, String what) {
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // 底层 IO 细节（含平台 IP）只进日志与 cause，对外只留自建短句
            log.warn("dahua icc {} failed", what, e);
            throw new DahuaIccException("dahua icc " + what + " failed", e);
        }
    }

    private static String firstText(JsonNode node, String... names) {
        for (String n : names) {
            JsonNode v = node.path(n);
            if (v.isTextual()) {
                return v.asText();
            }
        }
        return null;
    }

    /** 大华 ICC 对接异常（自建短句 + cause；不粘平台响应细节）。 */
    public static final class DahuaIccException extends RuntimeException {
        public DahuaIccException(String message) {
            super(message);
        }

        public DahuaIccException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

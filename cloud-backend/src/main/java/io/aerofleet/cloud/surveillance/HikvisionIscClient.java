package io.aerofleet.cloud.surveillance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 海康 iSecure Center OpenAPI 客户端（Artemis 网关；**零硬件可对接**）。
 *
 * <p><b>依据</b>：海康开放平台公开文档（综合安防管理平台 OpenAPI）+ 官方 SDK 行为。
 * 文档要点：接口形如 {@code https://{host}:{port}/artemis/{uri}}、仅 HTTPS、
 * AK/SK 摘要签名（见 {@link HikvisionIscAuth#signature}）、**token 必须经签名请求
 * 获取**、响应统一为 {@code {"code":"0", ...}}（<b>code 是字符串 "0" 才算成功</b>）。
 *
 * <p><b>为什么这条也能零硬件做</b>：海康开放平台提供 <b>OpenAPI 接口测试工具</b>
 * （官方下载中心），签名算法完全公开且确定性；因此契约层可先用假平台（JDK
 * HttpServer）跑通，再拿真平台的 app_key/app_secret 打通。真实设备取流仍需
 * 真机——但那属互操作层，不属本契约层。
 *
 * <p><b>异常纪律</b>（同 #248/#250）：对外只留自建短句；平台响应体（含内网 IP、
 * 摄像头编号）只进日志与 cause。特别注意：<b>业务失败（code≠"0"）与传输失败
 * （IO/超时）必须分开</b>——前者是平台语义拒绝（带 msg 自建短句），后者是链路问题。
 */
public final class HikvisionIscClient {

    private static final Logger log = LoggerFactory.getLogger(HikvisionIscClient.class);

    /** Artemis 网关路径前缀（文档固定）。 */
    static final String ARTEMIS_PREFIX = "/artemis";
    /** token 端点（必须经签名请求）。 */
    static final String TOKEN_PATH = "/oauth/token";
    /** 实时预览 URL 端点（文档：返回 rtsp/hls 等地址）。 */
    static final String PREVIEW_URLS_PATH = "/api/video/v2/cameras/previewURLs";

    private final URI baseUri;
    private final String appKey;
    private final String appSecret;
    private final String artemisPrefix;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    private volatile HikvisionIscAuth auth;

    /**
     * @param baseUri 平台基址，如 {@code https://isc.example.com:4432}
     * @param appKey  AK（平台 API 管理页申请）
     * @param appSecret SK
     */
    public HikvisionIscClient(URI baseUri, String appKey, String appSecret) {
        this(baseUri, appKey, appSecret, ARTEMIS_PREFIX);
    }

    public HikvisionIscClient(URI baseUri, String appKey, String appSecret, String artemisPrefix) {
        this.baseUri = Objects.requireNonNull(baseUri, "baseUri");
        this.appKey = Objects.requireNonNull(appKey, "appKey");
        this.appSecret = Objects.requireNonNull(appSecret, "appSecret");
        this.artemisPrefix = artemisPrefix == null || artemisPrefix.isBlank()
                ? ARTEMIS_PREFIX : artemisPrefix;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    // ------------------------------------------------------------------
    // 签名与请求
    // ------------------------------------------------------------------

    /**
     * 取 access_token（文档：<b>token 也必须经签名请求获取</b>）。
     *
     * <p>幂等：已有可用 token 直接复用（真平台上频繁取 token 没有意义）。
     */
    public synchronized HikvisionIscAuth authenticate() {
        HikvisionIscAuth cur = auth;
        if (cur != null && cur.validAt(Instant.now())) {
            return cur;
        }
        JsonNode root = call("POST", TOKEN_PATH, null,
                mapper.createObjectNode()
                        .put("client_id", appKey)
                        .put("client_secret", appSecret));
        JsonNode data = root.path("data");
        String token = firstText(data, "access_token", "accessToken", "token");
        if (token == null || token.isBlank()) {
            throw new HikvisionIscException(
                    "hikvision isc token response has no access_token "
                            + "(documented shape is data.access_token)");
        }
        this.auth = HikvisionIscAuth.issuedNow(token, Instant.now());
        log.info("hikvision isc authenticated (expiresAt={})", this.auth.expiresAt());
        return this.auth;
    }

    public HikvisionIscAuth currentAuth() {
        return auth;
    }

    /**
     * POST 一个 Artemis 业务接口（自动签名 + 带 token）。
     *
     * <p>token 失效（平台语义 code≠"0" 且 msg 含 token 类关键字）时<b>重取一次并重试</b>：
     * 真实平台上 token 提前失效比 2h 到期更常见。
     *
     * @return 响应的 {@code data} 节点（平台语义失败已抛异常）
     */
    public JsonNode postJson(String path, JsonNode body) {
        JsonNode root = retryOnceOnAuthFailure(path, body);
        JsonNode data = root.path("data");
        // data 缺失而 code=="0"：某些接口确实无 data，交空对象而不是 null
        return data.isMissingNode() || data.isNull() ? mapper.createObjectNode() : data;
    }

    /**
     * 实时预览 URL（文档 {@code /api/video/v2/cameras/previewURLs}）。
     *
     * <p>返回平台给出的流地址（rtsp/hls）。**本方法不播放、不校验流可达**——
     * 播放与媒体面属前端/生产阶段；这里只交付"按文档拿到 url"这一层契约。
     */
    public String previewUrl(String cameraIndexCode, String protocol, int streamType) {
        if (cameraIndexCode == null || cameraIndexCode.isBlank()) {
            throw new IllegalArgumentException("cameraIndexCode must not be blank");
        }
        JsonNode body = mapper.createObjectNode()
                .put("cameraIndexCode", cameraIndexCode)
                .put("protocol", (protocol == null || protocol.isBlank()) ? "rtsp" : protocol)
                .put("streamType", streamType);
        JsonNode data = postJson(PREVIEW_URLS_PATH, body);
        String url = firstText(data, "url");
        if (url == null || url.isBlank()) {
            throw new HikvisionIscException(
                    "hikvision isc preview response has no data.url field");
        }
        return url;
    }

    private JsonNode retryOnceOnAuthFailure(String path, JsonNode body) {
        try {
            return call("POST", path, null, body);
        } catch (HikvisionIscAuthException e) {
            log.warn("hikvision isc auth seems stale on {} — re-authenticating once", path);
            this.auth = null;
            authenticate();
            return call("POST", path, null, body);
        }
    }

    // ------------------------------------------------------------------
    // 底层：签名 + 发请求 + 解析统一响应
    // ------------------------------------------------------------------

    private JsonNode call(String method, String path, Map<String, String> extraSignedHeaders,
                          JsonNode body) {
        String bodyText;
        try {
            bodyText = body == null ? "" : mapper.writeValueAsString(body);
        } catch (Exception e) {
            throw HikvisionIscException.wrap("serialize request body failed", e);
        }
        String contentType = "application/json";
        String accept = "*/*";
        String fullPath = artemisPrefix + (path.startsWith("/") ? path : "/" + path);

        Map<String, String> signed = new LinkedHashMap<>();
        signed.put("x-ca-key", appKey);
        signed.put("x-ca-nonce", UUID.randomUUID().toString());
        signed.put("x-ca-timestamp", String.valueOf(System.currentTimeMillis()));
        if (extraSignedHeaders != null) {
            signed.putAll(extraSignedHeaders);
        }
        String sig = HikvisionIscAuth.signature(appSecret, method, accept, contentType,
                signed, fullPath);

        HttpRequest.Builder b = HttpRequest.newBuilder(resolve(fullPath))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", accept)
                .header("Content-Type", contentType)
                .header("x-ca-key", appKey)
                .header("x-ca-nonce", signed.get("x-ca-nonce"))
                .header("x-ca-timestamp", signed.get("x-ca-timestamp"))
                .header("x-ca-signature-headers", String.join(",", signed.keySet()))
                .header("x-ca-signature", sig);
        if (auth != null) {
            b.header("access_token", auth.accessToken());
        }
        HttpRequest req = bodyText.isEmpty()
                ? b.POST(HttpRequest.BodyPublishers.noBody()).build()
                : b.POST(HttpRequest.BodyPublishers.ofString(bodyText,
                        java.nio.charset.StandardCharsets.UTF_8)).build();

        HttpResponse<String> resp = send(req, method + " " + path);
        JsonNode root;
        try {
            root = mapper.readTree(resp.body());
        } catch (Exception e) {
            throw HikvisionIscException.wrap("hikvision isc response unreadable", e);
        }
        // 文档口径：code 为**字符串 "0"** 才算成功
        String code = root.path("code").asText("");
        if (!"0".equals(code)) {
            String msg = root.path("msg").asText("");
            // token 类失败单列一类，供上层重取重试
            if (looksLikeAuthFailure(code, msg)) {
                throw new HikvisionIscAuthException("hikvision isc rejected (auth): "
                        + (msg.isBlank() ? "code " + code : msg), code);
            }
            throw new HikvisionIscException("hikvision isc rejected: "
                    + (msg.isBlank() ? "code " + code : msg), code);
        }
        return root;
    }

    /** 平台侧 token/session 失效的典型表现（用于决定是否重取）。 */
    private static boolean looksLikeAuthFailure(String code, String msg) {
        String m = (msg == null ? "" : msg).toLowerCase(Locale.ROOT);
        return m.contains("token") || m.contains("session") || m.contains("unauthor")
                || m.contains("app_key") || m.contains("appkey")
                || "401".equals(code) || "403".equals(code);
    }

    private URI resolve(String artemisPath) {
        String b = baseUri.toString();
        if (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        return URI.create(b + artemisPath);
    }

    private HttpResponse<String> send(HttpRequest req, String what) {
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("hikvision isc {} failed", what, e);
            throw HikvisionIscException.wrap("hikvision isc " + what + " failed", e);
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

    /** 平台语义拒绝（含 code，便于上层分流）。 */
    public static class HikvisionIscException extends RuntimeException {
        private final String code;

        public HikvisionIscException(String message) {
            super(message);
            this.code = null;
        }

        /**
         * 链路/序列化类失败：cause 保留底层细节（只进日志，不外泄）。
         *
         * <p>**为什么不与 {@link #HikvisionIscException(String, String)} 合并**：
         * 两个重载在 {@code null} 实参下引用不明确（`String` 与 `Throwable` 都是引用类型），
         * 报 "reference to HikvisionIscException is ambiguous"。分成两个工厂方法最稳。
         */
        public static HikvisionIscException wrap(String message, Throwable cause) {
            HikvisionIscException e = new HikvisionIscException(message);
            e.initCause(cause);
            return e;
        }

        public HikvisionIscException(String message, String code) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /** token/session 失效（触发一次重取重试）。 */
    public static final class HikvisionIscAuthException extends HikvisionIscException {
        public HikvisionIscAuthException(String message, String code) {
            super(message, code);
        }
    }

    /** 供测试直接构造 body（避免测试重复造 ObjectMapper）。 */
    static ObjectNode newBody() {
        return new ObjectMapper().createObjectNode();
    }
}

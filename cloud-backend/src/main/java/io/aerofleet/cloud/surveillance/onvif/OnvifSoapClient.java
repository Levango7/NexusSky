package io.aerofleet.cloud.surveillance.onvif;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.function.Supplier;

/**
 * 最小 ONVIF SOAP 客户端（无第三方依赖）。
 *
 * <h2>做了什么</h2>
 *
 * <ul>
 *   <li>SOAP 1.2 信封 + WS-Security {@code UsernameToken}/{@code PasswordDigest}</li>
 *   <li>HTTP POST、超时、响应体上限、SOAP Fault 识别</li>
 *   <li>安全 XML 解析（见 {@link OnvifXml}）</li>
 * </ul>
 *
 * <h2>PasswordDigest 的算法与坑</h2>
 *
 * <p>{@code Base64(SHA1(nonce || created || password))}，其中 {@code nonce} 是
 * <b>原始字节</b>（不是它的 Base64 文本），{@code created} 是 ISO-8601 UTC
 * 秒级时间串（形如 {@code 2026-10-26T09:00:00Z}），三者按顺序拼接后再哈希。
 * 把 nonce 的 Base64 文本拼进去是最常见的实现错误——设备只会回
 * {@code NotAuthorized}，看起来像密码错。
 *
 * <p>设备与服务器时钟偏差过大时 digest 也会失败（ONVIF 设备通常自带 NTP）。
 * 这里不自动改系统时间、也不静默重试：错误信息会带上 HTTP 状态与设备返回的
 * fault 文本，让运维能看出是"鉴权失败"而不是"连不上"。
 *
 * <h2>不记录什么</h2>
 *
 * <p>请求信封（含 digest/nonce/明文密码）不写日志、不进异常消息。异常里只出现
 * 服务 URL、HTTP 状态码和截断后的响应体。
 */
public class OnvifSoapClient {

    private static final String NS_SOAP = "http://www.w3.org/2003/05/soap-envelope";
    private static final String NS_WSSE =
            "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd";
    private static final String NS_WSU =
            "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd";
    private static final String NS_TDS = "http://www.onvif.org/ver10/device/wsdl";
    private static final String NS_TRT = "http://www.onvif.org/ver10/media/wsdl";
    private static final String NS_TPTZ = "http://www.onvif.org/ver20/ptz/wsdl";
    private static final String NS_TT = "http://www.onvif.org/ver10/schema";

    private static final String PASSWORD_DIGEST_TYPE =
            "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest";
    private static final String BASE64_BINARY_TYPE =
            "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary";

    private static final DateTimeFormatter CREATED_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final HttpClient http;
    private final OnvifSettings settings;
    private final Supplier<Instant> clock;
    private final Supplier<byte[]> nonceSource;

    public OnvifSoapClient(OnvifSettings settings) {
        this(newHttpClient(settings), settings, Instant::now, OnvifSoapClient::randomNonce);
    }

    /** 测试用：可注入时钟与 nonce，便于对 digest 做已知答案断言。 */
    OnvifSoapClient(HttpClient http, OnvifSettings settings,
                    Supplier<Instant> clock, Supplier<byte[]> nonceSource) {
        this.http = http;
        this.settings = settings;
        this.clock = clock;
        this.nonceSource = nonceSource;
    }

    /**
     * 调用一个 ONVIF 服务操作。
     *
     * @param serviceUrl 服务地址（Device/Media/PTZ 各自的 XAddr）
     * @param action     操作名（仅用于错误消息）
     * @param bodyXml    {@code <s:Body>} 内的完整元素，前缀 tds/trt/tptz/tt 已在信封上声明
     * @param user       用户名
     * @param pass       密码
     * @return 解析后的响应文档
     */
    public Document call(String serviceUrl, String action, String bodyXml, String user, String pass) {
        if (serviceUrl == null || serviceUrl.isBlank()) {
            throw new OnvifException(action + ": service URL is blank");
        }
        byte[] nonce = nonceSource.get();
        String created = CREATED_FORMAT.format(clock.get().truncatedTo(ChronoUnit.SECONDS));
        String envelope = buildEnvelope(user, pass, nonce, created, bodyXml);

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(serviceUrl))
                    .timeout(Duration.ofMillis(Math.max(1, settings.getReadTimeoutMs())))
                    .header("Content-Type", "application/soap+xml; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(envelope, StandardCharsets.UTF_8))
                    .build();
        } catch (IllegalArgumentException e) {
            throw new OnvifException(action + ": invalid service URL " + serviceUrl, e);
        }

        HttpResponse<InputStream> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new OnvifException(action + " failed: cannot reach " + serviceUrl
                    + " (" + e.getMessage() + ")", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OnvifException(action + " interrupted", e);
        }

        byte[] body;
        try (InputStream in = response.body()) {
            body = readLimited(in, settings.getMaxResponseBytes());
        } catch (IOException e) {
            throw new OnvifException(action + ": failed to read response body ("
                    + e.getMessage() + ")", e);
        }

        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new OnvifException(action + " failed: HTTP " + status + " from " + serviceUrl
                    + " body=" + snippet(body), status, null);
        }

        Document document = OnvifXml.parse(body, action + " response");
        checkSoapFault(document, action);
        return document;
    }

    // ------------------------------------------------------------------

    private static HttpClient newHttpClient(OnvifSettings settings) {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(1, settings.getConnectTimeoutMs())))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    private static byte[] randomNonce() {
        byte[] nonce = new byte[16];
        RANDOM.nextBytes(nonce);
        return nonce;
    }

    static String buildEnvelope(String user, String pass, byte[] nonce, String created, String bodyXml) {
        String digest = passwordDigest(nonce, created, pass == null ? "" : pass);
        String nonceB64 = Base64.getEncoder().encodeToString(nonce);
        return "<s:Envelope xmlns:s=\"" + NS_SOAP + "\""
                + " xmlns:tds=\"" + NS_TDS + "\""
                + " xmlns:trt=\"" + NS_TRT + "\""
                + " xmlns:tptz=\"" + NS_TPTZ + "\""
                + " xmlns:tt=\"" + NS_TT + "\">"
                + "<s:Header>"
                + "<wsse:Security xmlns:wsse=\"" + NS_WSSE + "\" xmlns:wsu=\"" + NS_WSU
                + "\" s:mustUnderstand=\"1\">"
                + "<wsse:UsernameToken>"
                + "<wsse:Username>" + OnvifXml.escape(user) + "</wsse:Username>"
                + "<wsse:Password Type=\"" + PASSWORD_DIGEST_TYPE + "\">" + digest + "</wsse:Password>"
                + "<wsse:Nonce EncodingType=\"" + BASE64_BINARY_TYPE + "\">" + nonceB64 + "</wsse:Nonce>"
                + "<wsu:Created>" + created + "</wsu:Created>"
                + "</wsse:UsernameToken>"
                + "</wsse:Security>"
                + "</s:Header>"
                + "<s:Body>" + bodyXml + "</s:Body>"
                + "</s:Envelope>";
    }

    /** {@code Base64(SHA1(nonce || created || password))}。 */
    static String passwordDigest(byte[] nonce, String created, String password) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(nonce);
            sha1.update(created.getBytes(StandardCharsets.UTF_8));
            sha1.update(password.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(sha1.digest());
        } catch (NoSuchAlgorithmException e) {
            // 每个 JDK 都必须有 SHA-1；真没有时也不能降级成明文密码
            throw new OnvifException("SHA-1 is unavailable; refusing to send a non-digest password", e);
        }
    }

    private static void checkSoapFault(Document document, String action) {
        Element root = document.getDocumentElement();
        List<Element> faults = OnvifXml.descendants(root, "Fault");
        if (faults.isEmpty()) {
            return;
        }
        List<Element> texts = OnvifXml.descendants(faults.get(0), "Text");
        String reason = texts.isEmpty() ? OnvifXml.childText(faults.get(0), "faultstring") : OnvifXml.text(texts.get(0));
        if (reason == null) {
            reason = "(no reason text)";
        }
        throw new OnvifException(action + " returned a SOAP fault: " + reason);
    }

    private static byte[] readLimited(InputStream in, int maxBytes) throws IOException {
        int limit = maxBytes <= 0 ? Integer.MAX_VALUE : maxBytes;
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(limit, 8192));
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > limit) {
                throw new IOException("response body exceeds " + limit + " bytes");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    /** 响应体截断用于错误消息：去掉控制字符，避免日志注入。 */
    private static String snippet(byte[] body) {
        if (body == null || body.length == 0) {
            return "(empty)";
        }
        String text = new String(body, StandardCharsets.UTF_8)
                .replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "?")
                .replaceAll("\\s+", " ")
                .trim();
        if (text.length() > 300) {
            text = text.substring(0, 300) + "…";
        }
        return text;
    }
}

package io.aerofleet.cloud.surveillance;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * GB28181 REGISTER 摘要鉴权（RFC 2617 Digest；经典形态 response=KD(HA1, nonce:HA2)，
 * 兼容 qop="auth"）。**纯静态工具：平台侧校验与设备侧（模拟器）生成共用同一份算法**——
 * 分开写就会像官方 SDK 的「拼接顺序错一位」那样，错误只在真机上现形（威胁建模 ⑤
 * 接线前置清单①的落实件）。
 */
public final class Gb28181DigestAuth {

    public static final String DEFAULT_REALM = "aerofleet";

    private static final SecureRandom RANDOM = new SecureRandom();

    private Gb28181DigestAuth() {
    }

    /** 签发一个 nonce（24 hex 字符，SecureRandom）。 */
    public static String nonce() {
        byte[] b = new byte[12];
        RANDOM.nextBytes(b);
        return hex(b);
    }

    /**
     * 解析 {@code Authorization: Digest k="v", k=v} 头（键小写化；引号值剥引号，
     * 引号内的逗号不切分）。非 Digest scheme 返回空 map。
     */
    public static Map<String, String> parseAuthorization(String header) {
        Map<String, String> out = new LinkedHashMap<>();
        if (header == null || header.isBlank()) {
            return out;
        }
        String v = header.trim();
        int sp = v.indexOf(' ');
        String scheme = sp < 0 ? v : v.substring(0, sp);
        if (!"Digest".equalsIgnoreCase(scheme.trim())) {
            return out;
        }
        String rest = sp < 0 ? "" : v.substring(sp + 1);
        boolean inQuotes = false;
        StringBuilder cur = new StringBuilder();
        java.util.List<String> parts = new java.util.ArrayList<>();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
                cur.append(c);
            } else if (c == ',' && !inQuotes) {
                parts.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        parts.add(cur.toString());
        for (String part : parts) {
            String t = part.trim();
            if (t.isEmpty()) {
                continue;
            }
            int eq = t.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String k = t.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String val = t.substring(eq + 1).trim();
            if (val.length() >= 2 && val.startsWith("\"") && val.endsWith("\"")) {
                val = val.substring(1, val.length() - 1);
            }
            out.put(k, val);
        }
        return out;
    }

    /**
     * 平台侧校验。
     *
     * @param auth         {@link #parseAuthorization} 的结果
     * @param method       请求方法（恒 REGISTER）
     * @param requestUri   请求-行里的 URI（与摘要计算里的 uri 必须逐字一致）
     * @param realm        平台 realm（与 401 挑战一致）
     * @param expectedUser 期望的设备编码（Authorization 的 username 必须等于它）
     * @param password     该设备的接入口令
     * @param issuedNonce  平台签发且未过期的 nonce（防重放：一次性池由调用方维护）
     */
    public static boolean verify(Map<String, String> auth, String method, String requestUri,
                                 String realm, String expectedUser, String password, String issuedNonce) {
        if (auth == null || auth.isEmpty() || issuedNonce == null) {
            return false;
        }
        String username = auth.get("username");
        String response = auth.get("response");
        String nonce = auth.get("nonce");
        String uri = auth.get("uri");
        if (username == null || response == null || nonce == null || uri == null) {
            return false;
        }
        if (!username.equals(expectedUser) || !nonce.equals(issuedNonce) || !uri.equals(requestUri)) {
            return false;
        }
        String qop = auth.get("qop");
        String ha1 = md5Hex(username + ":" + realm + ":" + password);
        String ha2 = md5Hex(method + ":" + uri);
        String expected;
        if (qop != null && !qop.isBlank()) {
            // RFC 2617 §3.2.1 qop="auth"：response = KD(HA1, nonce:nc:cnonce:qop:HA2)
            String nc = auth.getOrDefault("nc", "");
            String cnonce = auth.getOrDefault("cnonce", "");
            expected = md5Hex(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":" + qop + ":" + ha2);
        } else {
            // 经典形态（GB/T 28181 设备普遍实现）：response = KD(HA1, nonce:HA2)
            expected = md5Hex(ha1 + ":" + nonce + ":" + ha2);
        }
        return expected.equalsIgnoreCase(response);
    }

    /** 设备侧：按挑战构造 {@code Authorization} 头的完整值（经典形态，无 qop）。 */
    public static String authorizationHeader(String username, String realm, String nonce,
                                             String uri, String password, String method) {
        String ha1 = md5Hex(username + ":" + realm + ":" + password);
        String ha2 = md5Hex(method + ":" + uri);
        String response = md5Hex(ha1 + ":" + nonce + ":" + ha2);
        return "Digest username=\"" + username + "\", realm=\"" + realm + "\", nonce=\"" + nonce
                + "\", uri=\"" + uri + "\", response=\"" + response + "\"";
    }

    /**
     * MD5 在此**是协议强制**：RFC 2617 Digest 的 HA1/HA2/response 与 GB/T 28181 设备端
     * 实现就是 MD5（同 ONVIF SHA-1 摘要、海康 Content-MD5 的「协议强制」先例）——
     * 这是线路上的互操作格式，不是口令存储哈希；口令本体走配置注入，不落代码/日志。
     */
    static String md5Hex(String s) {
        try {
            return hex(MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 unavailable", e);
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        }
        return sb.toString();
    }
}

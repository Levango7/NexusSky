package io.aerofleet.cloud.surveillance;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * SIP 消息模型（RFC 3261 形状 + GB/T 28181 信令用法）。
 *
 * <p>**为什么要有这个类**：本仓的 GB28181 信令此前是字符串拼接，
 * {@code Content-Length} 用硬编码魔数（145 / 141）而不是真实 body 长度——
 * 实测 INVITE 的 SDP 是 170 字节、Catalog 的 XML 是 115 字节，且随参数变化。
 * 按 RFC 3261，对端**严格按 Content-Length 读 body**：写小 ⇒ 挂起等不到字节，
 * 写大 ⇒ 截断。旧实现发出去的帧真机读不了。
 *
 * <p>本类把消息变成 start-line + 头集合 + body 的结构，渲染时
 * {@code Content-Length} 由 {@link #bodyBytes()} **导出**——
 * 从此 Peer 侧只要按 Content-Length 读，就一定能读到完整 body（有专测锁这条不变式）。
 *
 * <p>同时提供 {@link #parse(String)}：对端应答（200 OK / 100 Trying / 401 等）
 * 由这里解析， transport 层靠它做事务匹配（Call-ID + CSeq + Via branch）。
 */
public final class Gb28181SipMessage {

    /** 请求-行（"INVITE sip:x SIP/2.0"）。 */
    private final String startLine;
    /** 有序头：name 大小写不敏感，同名的多值按出现序保留（如多个 Via）。 */
    private final Map<String, List<String>> headers = new LinkedHashMap<>();
    private final byte[] body;

    private Gb28181SipMessage(String startLine, byte[] body) {
        if (startLine == null || startLine.isBlank()) {
            throw new IllegalArgumentException("sip start-line must not be blank");
        }
        this.startLine = startLine.trim();
        this.body = body == null ? new byte[0] : body;
    }

    // ------------------------------------------------------------------
    // 构造
    // ------------------------------------------------------------------

    /** 以**请求**建消息（请求-行形如 {@code INVITE sip:<target> SIP/2.0}）。 */
    public static Gb28181SipMessage request(String requestLine, byte[] body) {
        String line = Objects.requireNonNull(requestLine, "requestLine").trim();
        // 请求-行必须含 "SIP/2.0" 且以 method+URI 开头
        int sp = line.indexOf(' ');
        if (sp <= 0 || !line.regionMatches(true, line.length() - "SIP/2.0".length(), "SIP/2.0", 0, 7)) {
            throw new IllegalArgumentException("not a SIP request-line: " + line);
        }
        return new Gb28181SipMessage(line, body);
    }

    /** 以**响应**建消息（状态-行形如 {@code SIP/2.0 200 OK}）。 */
    public static Gb28181SipMessage response(int statusCode, String reason, byte[] body) {
        if (statusCode < 100 || statusCode > 699) {
            throw new IllegalArgumentException("sip status code out of range: " + statusCode);
        }
        String r = reason == null || reason.isBlank() ? reasonPhrase(statusCode) : reason.trim();
        return new Gb28181SipMessage("SIP/2.0 " + statusCode + " " + r, body);
    }

    /** 与 {@link #response(int, String, byte[])} 同，但 body 走 UTF-8 文本。 */
    public static Gb28181SipMessage responseText(int statusCode, String reason, String bodyText) {
        return response(statusCode, reason, bodyText == null ? null : bodyText.getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------
    // 头
    // ------------------------------------------------------------------

    /** 设头（覆盖同名旧值；同名多值的场景用 {@link #addHeader}）。 */
    public Gb28181SipMessage header(String name, String value) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        headers.put(canonicalName(name), new ArrayList<>(List.of(value)));
        return this;
    }

    /** 追加同名头（保留出现序——多 Via / 多 Record-Route 场景必须有序）。 */
    public Gb28181SipMessage addHeader(String name, String value) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        headers.computeIfAbsent(canonicalName(name), k -> new ArrayList<>()).add(value);
        return this;
    }

    /** 首个同名头的值；不存在返回 {@code null}（不抛——查缺是常见路径）。 */
    public String firstHeader(String name) {
        List<String> v = headers.get(canonicalName(name));
        return v == null || v.isEmpty() ? null : v.get(0);
    }

    /** 同名头的全部值（有序）。 */
    public List<String> allHeaders(String name) {
        List<String> v = headers.get(canonicalName(name));
        return v == null ? List.of() : List.copyOf(v);
    }

    /** 按出现序遍历所有头（含同名多值展开）。 */
    public List<Map.Entry<String, String>> headersInOrder() {
        List<Map.Entry<String, String>> out = new ArrayList<>();
        headers.forEach((n, vs) -> vs.forEach(v -> out.add(Map.entry(n, v))));
        return List.copyOf(out);
    }

    /**
     * 头名规范化：SIP 头名大小写不敏感（RFC 3261 §7.3.1），内部按 canonical 形式存，
     * 避免对端回 {@code Call-ID} / {@code call-id} 时查不到。
     */
    private static String canonicalName(String name) {
        String t = name.trim();
        // 只规范化首段：Call-ID / Max-Forwards / Content-Length 等复合名保持原样
        String[] dashParts = t.split("-");
        StringBuilder sb = new StringBuilder(t.length());
        for (int i = 0; i < dashParts.length; i++) {
            if (i > 0) {
                sb.append('-');
            }
            String p = dashParts[i];
            sb.append(p.isEmpty() ? p
                    : Character.toUpperCase(p.charAt(0)) + p.substring(1).toLowerCase(Locale.ROOT));
        }
        String out = sb.toString();
        // SIP 习惯：Call-ID 与 Max-Forwards 的连字符段全首字母大写即可，无需特例
        return out.equals("Call-Id") ? "Call-ID" : out;
    }

    // ------------------------------------------------------------------
    // 渲染 / 解析
    // ------------------------------------------------------------------

    /**
     * 渲染为字节序列。
     *
     * <p>顺序：start-line / 头（按插入序）/ {@code Content-Length: N} / 空行 / body。
     * {@code Content-Length} **永远由 body 真实长度算出**——本类不允许外部塞入该头
     * （{@link #header} 遇到 Content-Length 直接拒绝，杜绝魔数复活）。
     */
    public byte[] render() {
        StringBuilder sb = new StringBuilder(512);
        sb.append(startLine).append("\r\n");
        for (Map.Entry<String, String> h : headersInOrder()) {
            if ("Content-Length".equalsIgnoreCase(h.getKey())) {
                continue; // 只认算出来的值
            }
            sb.append(h.getKey()).append(": ").append(h.getValue()).append("\r\n");
        }
        sb.append("Content-Length: ").append(bodyBytes()).append("\r\n\r\n");
        byte[] head = sb.toString().getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[head.length + body.length];
        System.arraycopy(head, 0, out, 0, head.length);
        System.arraycopy(body, 0, out, head.length, body.length);
        return out;
    }

    /** 渲染为文本（仅用于日志/断言展示；网络收发走 {@link #render()}）。 */
    public String renderText() {
        return new String(render(), StandardCharsets.UTF_8);
    }

    /** body 字节数。 */
    public int bodyBytes() {
        return body.length;
    }

    /** body 文本（UTF-8），无 body 返回 {@code null}。 */
    public String bodyText() {
        return body.length == 0 ? null : new String(body, StandardCharsets.UTF_8);
    }

    public String startLine() {
        return startLine;
    }

    /** 是否为响应（状态-行 {@code SIP/2.0 <code> <reason>}）。 */
    public boolean isResponse() {
        return startLine.startsWith("SIP/2.0");
    }

    /** 响应状态码；请求消息返回 {@code -1}。 */
    public int statusCode() {
        if (!isResponse()) {
            return -1;
        }
        String[] p = startLine.split(" ");
        try {
            return Integer.parseInt(p[1]);
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /** 请求方法；响应消息返回 {@code null}。 */
    public String method() {
        if (isResponse() || startLine.isEmpty()) {
            return null;
        }
        int sp = startLine.indexOf(' ');
        return sp < 0 ? startLine : startLine.substring(0, sp);
    }

    /**
     * 解析收到的字节为消息。
     *
     * <p>口径（RFC 3261 §18.3）：按 CRLF 或 LF 切行；头与 body 之间是**第一个空行**；
     * body 长度以 {@code Content-Length} 为准——**超过 Content-Length 的字节属于同一条
     * UDP 报文里的下一条消息**（真设备会把多条消息塞进一个 datagram），由调用方处理。
     * 缺 {@code Content-Length} 时按"剩余全部"收（宽松但可预期），并对无 body 的
     * 请求/响应（REGISTER、SUBSCRIBE、100 Trying）同样成立。
     */
    public static Gb28181SipMessage parse(byte[] data, int offset, int length) {
        if (data == null || length <= 0) {
            throw new IllegalArgumentException("empty sip datagram");
        }
        String text = new String(data, offset, length, StandardCharsets.UTF_8);
        return parse(text);
    }

    /** 见 {@link #parse(byte[], int, int)}（整段文本版本）。 */
    public static Gb28181SipMessage parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("empty sip message");
        }
        int sep = findHeaderBodySeparator(text);
        String head = sep < 0 ? text : text.substring(0, sep);
        String body = sep < 0 ? "" : text.substring(sep + separatorLength(text, sep));

        String[] lines = head.split("\r\n|\n");
        if (lines.length == 0) {
            throw new IllegalArgumentException("sip message has no start-line");
        }
        String startLine = lines[0].trim();
        if (!isStartLine(startLine)) {
            throw new IllegalArgumentException("malformed sip start-line: " + startLine);
        }

        Integer declared = null;
        List<String[]> nameValue = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                // 头行折行（continuation）不以冒号开头：并入上一条头的值
                if (!nameValue.isEmpty() && (line.startsWith(" ") || line.startsWith("\t"))) {
                    String[] prev = nameValue.get(nameValue.size() - 1);
                    prev[1] = prev[1] + " " + line.trim();
                    continue;
                }
                throw new IllegalArgumentException("malformed sip header line: " + line);
            }
            String name = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            if ("Content-Length".equalsIgnoreCase(name)) {
                try {
                    declared = Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("malformed Content-Length: " + value);
                }
                continue; // Content-Length 不进头集合：渲染时重算，避免与声明值打架
            }
            nameValue.add(new String[]{name, value});
        }

        if (declared != null && body.length() > declared) {
            // 同报文多条消息：只取本条的 body 部分
            body = body.substring(0, declared);
        }

        byte[] bodyBytes = body.isEmpty()
                ? new byte[0]
                : body.getBytes(StandardCharsets.UTF_8);
        Gb28181SipMessage msg = new Gb28181SipMessage(startLine, bodyBytes);
        for (String[] nv : nameValue) {
            msg.addHeader(nv[0], nv[1]);
        }
        return msg;
    }

    /**
     * 起止行校验（RFC 3261 §25.1）：响应-行 = {@code SIP/2.0 <code> <reason>}；
     * 请求-行 = {@code <METHOD> <Request-URI> SIP/2.0}（三段、末段严格为 SIP/2.0）。
     *
     * <p>此前只查"含空格"，{@code garbage line with no colon} 会被当成合法请求行放过去
     * （真链路测试抓出）。现在按三段式校验——**形状不对就拒，不静默收**。
     */
    private static boolean isStartLine(String line) {
        if (line.isEmpty()) {
            return false;
        }
        if (line.startsWith("SIP/2.0")) {
            String[] p = line.split(" ");
            if (p.length < 3) {
                return false;
            }
            try {
                int code = Integer.parseInt(p[1]);
                return code >= 100 && code <= 699;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        String[] p = line.split(" ");
        if (p.length < 3 || !p[p.length - 1].equals("SIP/2.0")) {
            return false;
        }
        // 方法名必须是 token（RFC 3261 §25.1 token 字符）
        String method = p[0];
        for (int i = 0; i < method.length(); i++) {
            char c = method.charAt(i);
            if (!Character.isLetterOrDigit(c) && "-.!%*_+`'~".indexOf(c) < 0) {
                return false;
            }
        }
        return !p[1].isEmpty();
    }

    /** 头/body 分隔空行（CRLFCRLF 或 LFLF）的起始下标；找不到返回 -1。 */
    private static int findHeaderBodySeparator(String text) {        int idx = text.indexOf("\r\n\r\n");
        if (idx >= 0) {
            return idx;
        }
        return text.indexOf("\n\n");
    }

    private static int separatorLength(String text, int sep) {
        return text.startsWith("\r\n\r\n", sep) ? 4 : 2;
    }

    // ------------------------------------------------------------------
    // 事务匹配（transport 层用）
    // ------------------------------------------------------------------

    /** 事务键：Call-ID + CSeq 序号 + 方法（同一事务内多响应靠它区分）。 */
    public String transactionKey() {
        return firstHeader("Call-ID") + "|" + cseqNumber() + "|" + (isResponse() ? methodOfCseq() : method());
    }

    /** 对端响应回带的请求方法（响应消息的 CSeq 形如 {@code 1 INVITE}）。 */
    public String methodOfCseq() {
        String cseq = firstHeader("CSeq");
        if (cseq == null) {
            return null;
        }
        int sp = cseq.indexOf(' ');
        return sp < 0 ? null : cseq.substring(sp + 1).trim();
    }

    /** CSeq 序号；缺省返回 {@code -1}。 */
    public int cseqNumber() {
        String cseq = firstHeader("CSeq");
        if (cseq == null) {
            return -1;
        }
        int sp = cseq.indexOf(' ');
        String num = sp < 0 ? cseq : cseq.substring(0, sp);
        try {
            return Integer.parseInt(num.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Via 的 branch 参数（事务匹配用；真设备回带同一 branch）。 */
    public String viaBranch() {
        String via = firstHeader("Via");
        if (via == null) {
            return null;
        }
        int i = via.indexOf("branch=");
        return i < 0 ? null : via.substring(i + "branch=".length()).split("[;,]")[0];
    }

    private static String reasonPhrase(int code) {
        return switch (code) {
            case 100 -> "Trying";
            case 180 -> "Ringing";
            case 200 -> "OK";
            case 401 -> "Unauthorized";
            case 404 -> "Not Found";
            case 486 -> "Busy Here";
            case 500 -> "Server Internal Error";
            case 603 -> "Decline";
            default -> "Unknown";
        };
    }

    @Override
    public String toString() {
        return "Gb28181SipMessage{" + startLine + ", headers=" + headers.size() + ", bodyBytes=" + bodyBytes() + '}';
    }

    /** 仅供 {@link Gb28181Sdp} 的兼容辅助——保持包内可见。 */
    static String describeBody(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }
}

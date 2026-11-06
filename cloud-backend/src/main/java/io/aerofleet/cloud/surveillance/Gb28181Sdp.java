package io.aerofleet.cloud.surveillance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * GB/T 28181 实时点播 SDP 模型（E5，spec D3 信令体的 message body）。
 *
 * <p>**为什么要有这个类**：SDP 此前是 {@link Gb28181Signaling} 里手工拼的字符串，
 * 而 {@code Content-Length} 写成**硬编码魔数**——实测 INVITE 的 body 是 170 字节
 * （魔数写 145）、Catalog 的 body 是 115 字节（魔数写 141），且都**随参数变化**
 * （IP 长度、端口位数、SSRC 位数、SN 位数）。按 RFC 3261，对端收到帧后严格按
 * Content-Length 读 body：写小了**挂起等永不到来的字节**，写大了**截断**——
 * 也就是说旧实现发出去的帧**真机读不了**。
 *
 * <p>本类把 SDP 变成可构造、可解析、可按字节计长的对象，`Content-Length` 由
 * {@link #bodyBytes()} 的真实长度导出——**魔数不再有容身之处**。
 *
 * <p>字段口径（GB/T 28181-2016 附录的实时点播 SDP）：
 * <ul>
 *   <li>{@code m=video <port> TCP/RTP/AVP 96 98 97}：国标默认**被动 TCP**收流；</li>
 *   <li>{@code a=rtpmap:96 PS/90000}：96 = MPEG2-PS（国标规定视频载荷类型）；</li>
 *   <li>{@code y=<ssrc>}：SSRC 标识（国标用它做流归属）。</li>
 * </ul>
 */
public final class Gb28181Sdp {

    /** 国标 PS 载荷类型（{@code a=rtpmap:96 PS/90000}）。 */
    public static final int PAYLOAD_TYPE_PS = 96;
    /** 国标备选载荷类型序列（96/98/97，见 spec）。 */
    public static final List<Integer> PAYLOAD_TYPES = List.of(96, 98, 97);

    private final String sessionUser;
    private final String receiveHost;
    private final int receivePort;
    private final int ssrc;

    public Gb28181Sdp(String sessionUser, String receiveHost, int receivePort, int ssrc) {
        if (receiveHost == null || receiveHost.isBlank()) {
            throw new IllegalArgumentException("sdp receiveHost must not be blank");
        }
        if (receivePort < 0 || receivePort > 65535) {
            throw new IllegalArgumentException("sdp receivePort out of range: " + receivePort);
        }
        this.sessionUser = sessionUser == null || sessionUser.isBlank() ? "-" : sessionUser;
        this.receiveHost = receiveHost;
        this.receivePort = receivePort;
        this.ssrc = ssrc;
    }

    public String receiveHost() {
        return receiveHost;
    }

    public int receivePort() {
        return receivePort;
    }

    public int ssrc() {
        return ssrc;
    }

    /**
     * 渲染 SDP 文本（行序固定：v / o / s / c / t / m / a / a / y）。
     *
     * <p>**行序与换行都参与断言**：GB/T 28181 对 SDP 的必需行有要求，
     * 本类用固定顺序渲染，解析侧按同一顺序回读，双向可证。
     */
    public String body() {
        StringBuilder sb = new StringBuilder(220);
        sb.append("v=0\r\n");
        sb.append("o=").append(sessionUser).append(" 0 0 IN IP4 ").append(receiveHost).append("\r\n");
        sb.append("s=Play\r\n");
        sb.append("c=IN IP4 ").append(receiveHost).append("\r\n");
        sb.append("t=0 0\r\n");
        sb.append("m=video ").append(receivePort)
                .append(" TCP/RTP/AVP 96 98 97\r\n");
        sb.append("a=recvonly\r\n");
        sb.append("a=rtpmap:").append(PAYLOAD_TYPE_PS).append(" PS/90000\r\n");
        sb.append("y=").append(ssrc).append("\r\n\r\n");
        return sb.toString();
    }

    /** SDP 文本的 UTF-8 字节数——**Content-Length 的唯一合法来源**。 */
    public int bodyBytes() {
        return body().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    @Override
    public String toString() {
        return "Gb28181Sdp{port=" + receivePort + ", ssrc=" + ssrc + ", bytes=" + bodyBytes() + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Gb28181Sdp that)) {
            return false;
        }
        return receivePort == that.receivePort && ssrc == that.ssrc
                && receiveHost.equals(that.receiveHost);
    }

    @Override
    public int hashCode() {
        return Objects.hash(receiveHost, receivePort, ssrc);
    }

    /**
     * 解析 SDP 文本（用于对端应答的 SDP，或回读自建帧做**自校验**）。
     *
     * <p>解析口径：只认上面五行一 m 一行三 a 一 y 的必需子集；遇到未知行
     * **不拒绝**（真实设备会带扩展行），但 {@code m=} 必须存在且为 video，
     * 否则这不是一个可播的实时点播 SDP。
     */
    public static Gb28181Sdp parse(String sdp) {
        if (sdp == null || sdp.isBlank()) {
            throw new IllegalArgumentException("sdp must not be blank");
        }
        String user = "-";
        String host = null;
        int port = -1;
        int ssrc = 0;
        boolean hasVideoMediaLine = false;

        for (String raw : sdp.split("\r\n|\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.indexOf('=') < 0) {
                continue;
            }
            char type = line.charAt(0);
            String value = line.substring(2);
            switch (type) {
                case 'o' -> {
                    // o=<username> <sess-id> <sess-version> <nettype> <addrtype> <unicast-address>
                    String[] parts = value.split(" ");
                    if (parts.length >= 6) {
                        user = parts[0];
                        host = parts[5];
                    }
                }
                case 'c' -> {
                    // c=IN IP4 <address>
                    String[] parts = value.split(" ");
                    if (parts.length >= 3) {
                        host = parts[2];
                    }
                }
                case 'm' -> {
                    String[] parts = value.split(" ");
                    if (parts.length < 3) {
                        throw new IllegalArgumentException("malformed m= line: " + line);
                    }
                    if (!"video".equals(parts[0])) {
                        throw new IllegalArgumentException("expected video media, got: " + parts[0]);
                    }
                    try {
                        port = Integer.parseInt(parts[1]);
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("malformed m= port: " + parts[1]);
                    }
                    hasVideoMediaLine = true;
                }
                case 'y' -> ssrc = parseNonNegativeInt(value, "y");
                default -> {
                    // v / s / t / a / i / b / k：必需但不进模型；未知类型行不拒绝
                }
            }
        }
        if (!hasVideoMediaLine) {
            throw new IllegalArgumentException("sdp has no m=video line");
        }
        if (host == null) {
            throw new IllegalArgumentException("sdp has no connection address (c= or o=)");
        }
        return new Gb28181Sdp(user, host, port, ssrc);
    }

    /**
     * SDP 的 {@code a=rtpmap} 行集合（按出现序），用于核对国标载荷类型。
     *
     * <p>行格式（RFC 4566）：{@code a=rtpmap:<payload type> <encoding name>/<clock rate>}
     * ——载荷类型与编码名之间是**空格**，编码名与时钟率之间是斜杠。此前把两者混成一个
     * 切分，{@code 96 PS/90000} 会被切成 {@code "96 PS"} 而解析失败（真链路测试抓出）。
     *
     * <p>返回值形如 {@code 96 -> "PS/90000"}（保留时钟率——核对国标 90000Hz 取样要用）。
     */
    public static Map<Integer, String> rtpmapOf(String sdp) {
        Map<Integer, String> out = new LinkedHashMap<>();
        if (sdp == null) {
            return out;
        }
        for (String raw : sdp.split("\r\n|\n")) {
            String line = raw.trim();
            if (!line.startsWith("a=rtpmap:")) {
                continue;
            }
            // <payload type> <encoding name>/<clock rate>
            String rest = line.substring("a=rtpmap:".length()).trim();
            int space = rest.indexOf(' ');
            if (space < 0) {
                throw new IllegalArgumentException("malformed a=rtpmap line (no payload type): " + line);
            }
            String pt = rest.substring(0, space).trim();
            String value = rest.substring(space + 1).trim();
            try {
                out.put(Integer.parseInt(pt), value);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("malformed a=rtpmap payload type: " + line);
            }
        }
        return out;
    }

    private static int parseNonNegativeInt(String v, String what) {
        try {
            int i = Integer.parseInt(v.trim());
            if (i < 0) {
                throw new IllegalArgumentException(what + " must be non-negative: " + v);
            }
            return i;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("malformed " + what + " value: " + v);
        }
    }

    /** 规范化 IP（{@code 0.0.0.0} 也接受——它表示"地址未定"，由传输层替换）。 */
    public static boolean isWildcardHost(String host) {
        return "0.0.0.0".equals(host) || "::".equals(host);
    }

    /** 供 {@link Gb28181SipMessage} 渲染时按出现序组织 header 用（避免重复造轮子）。 */
    static List<String> orderedPayloadTypeNames() {
        List<String> names = new ArrayList<>();
        PAYLOAD_TYPES.forEach(pt -> names.add(String.format(Locale.ROOT, "%d", pt)));
        return names;
    }
}

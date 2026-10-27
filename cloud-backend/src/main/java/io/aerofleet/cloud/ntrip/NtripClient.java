package io.aerofleet.cloud.ntrip;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * NTRIP 客户端（caster 拉流端）。
 * <p>
 * <b>为什么用裸 Socket 而不是 HttpURLConnection</b>：NTRIP 1.0 的成功响应是
 * {@code ICY 200 OK}——不是合法的 HTTP 状态行，{@code HttpURLConnection} 会直接抛
 * {@code ProtocolException}。NTRIP 2.0 的 caster 回 {@code HTTP/1.1 200 OK}，
 * 但为了同时兼容两代 caster，握手与响应头都按字节自己解析。
 * <p>
 * 协议流程（NTRIP 2.0 rev1 §3.2）：
 * <pre>
 * 客户端 → caster
 *   GET /{mountpoint} HTTP/1.1
 *   Host: {host}:{port}
 *   Ntrip-Version: Ntrip/2.0
 *   User-Agent: NTRIP NexusSky/1.0
 *   Authorization: Basic base64(user:password)
 *
 * caster → 客户端
 *   HTTP/1.1 200 OK        （或 ICY 200 OK）
 *   ...响应头...
 *   \r\n
 *   <RTCM3 二进制流，无长度、持续到连接关闭>
 * </pre>
 * <p>
 * <b>本类只做「连上 + 拿到字节流」</b>，不做帧切分（见 {@link Rtcm3Framer}）、
 * 不做重连策略（见 {@link NtripRtcmService}）、不发 MAVLink。这样每一层都能单测。
 * <p>
 * 失败语义：{@link #connect()} 在 caster 回非 200、认证失败（401）、
 * 挂载点不存在（404）时抛 {@link NtripException}，并把 caster 的响应头带在消息里——
 * NTRIP 的排障几乎全靠那一行状态码与 {@code Ntrip-Version} 头。
 */
public final class NtripClient implements AutoCloseable {

    private final String host;
    private final int port;
    private final String mountpoint;
    private final String user;
    private final String password;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    private Socket socket;
    private InputStream in;
    private boolean connected;

    public NtripClient(String host, int port, String mountpoint, String user, String password) {
        this(host, port, mountpoint, user, password, 10_000, 30_000);
    }

    public NtripClient(String host, int port, String mountpoint, String user, String password,
                       int connectTimeoutMs, int readTimeoutMs) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("caster host 不能为空");
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("端口非法：" + port);
        }
        if (mountpoint == null || mountpoint.isBlank()) {
            throw new IllegalArgumentException("mountpoint 不能为空（NTRIP 没有默认挂载点）");
        }
        this.host = host;
        this.port = port;
        // 允许调用方传 "MOUNT" 或 "/MOUNT"，统一成请求行里的 "/MOUNT"
        this.mountpoint = mountpoint.startsWith("/") ? mountpoint : "/" + mountpoint;
        this.user = user;
        this.password = password;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    /** 建立连接并完成 NTRIP 握手；失败抛 {@link NtripException}。 */
    public void connect() throws IOException {
        close();
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            s.setSoTimeout(readTimeoutMs);
            s.setTcpNoDelay(true);

            OutputStream out = s.getOutputStream();
            out.write(buildRequest());
            out.flush();

            InputStream raw = s.getInputStream();
            String statusLine = readHeaderBlock(raw);
            if (statusLine == null) {
                throw new NtripException("caster 未返回任何响应（连接被对端关闭）");
            }
            if (!isSuccess(statusLine)) {
                throw new NtripException("caster 拒绝连接：" + statusLine);
            }

            this.socket = s;
            this.in = raw;
            this.connected = true;
        } catch (IOException e) {
            // NtripException 是 IOException 的子类，不能并列在 multi-catch 里；
            // 单独 catch IOException 即可覆盖两者，异常类型原样抛给调用方。
            try {
                s.close();
            } catch (IOException ignored) {
                // 关闭失败不掩盖原始异常
            }
            // 契约归一（2026-10-27）：caster 静默关闭在 Windows 上是 RST（SocketException
            // "Connection reset"）而不是干净 EOF——两种语义都是"对端在回应前关了连接"，
            // 调用方（重连循环/测试）应看到同一个 NtripException，而不是随平台/时序
            // 随机挑异常类型（NtripClientTest.handlesSilentClose 在全量跑里偶发红即此）。
            if (e instanceof java.net.SocketException && !(e instanceof NtripException)) {
                throw new NtripException("caster 未返回任何响应（连接被重置：" + e.getMessage() + "）");
            }
            throw e;
        }
    }

    /**
     * 读一段原始字节（不保证帧对齐）。
     * <p>
     * 返回 {@code -1} 表示对端关闭。NTRIP 流是持续的，所以 -1 基本等价于"该重连了"。
     */
    public int read(byte[] buf) throws IOException {
        if (!connected) {
            throw new IllegalStateException("未连接，先调用 connect()");
        }
        return in.read(buf);
    }

    public boolean isConnected() {
        return connected && socket != null && !socket.isClosed();
    }

    /** 请求行 + 头。单独抽出来是为了能单测（不用真连 caster）。 */
    byte[] buildRequest() {
        StringBuilder sb = new StringBuilder(256);
        sb.append("GET ").append(mountpoint).append(" HTTP/1.1\r\n");
        sb.append("Host: ").append(host).append(':').append(port).append("\r\n");
        sb.append("Ntrip-Version: Ntrip/2.0\r\n");
        sb.append("User-Agent: NTRIP NexusSky/1.0\r\n");
        sb.append("Accept: */*\r\n");
        sb.append("Connection: close\r\n");
        if (user != null && !user.isEmpty()) {
            String token = Base64.getEncoder()
                    .encodeToString((user + ":" + (password == null ? "" : password))
                            .getBytes(StandardCharsets.UTF_8));
            sb.append("Authorization: Basic ").append(token).append("\r\n");
        }
        sb.append("\r\n");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 读到空行为止（响应头结束），返回状态行。
     * <p>
     * 逐字节读是为了**不越过头部的结束边界**——头部之后紧跟的就是 RTCM 二进制流，
     * 用带缓冲的 readLine 会把它一起吃掉，那些字节就丢了。
     */
    private static String readHeaderBlock(InputStream raw) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream(512);
        int prev3 = -1, prev2 = -1, prev1 = -1;
        while (true) {
            int b = raw.read();
            if (b < 0) {
                return null;
            }
            head.write(b);
            // 头部结束标志：\r\n\r\n
            if (prev3 == '\r' && prev2 == '\n' && prev1 == '\r' && b == '\n') {
                break;
            }
            prev3 = prev2;
            prev2 = prev1;
            prev1 = b;
            if (head.size() > 16 * 1024) {
                throw new NtripException("响应头超过 16KB 仍未结束，疑似不是 NTRIP caster");
            }
        }
        String text = head.toString(StandardCharsets.ISO_8859_1);
        int eol = text.indexOf('\n');
        String first = eol > 0 ? text.substring(0, eol) : text;
        return first.strip();
    }

    /** 接受 "HTTP/1.1 200 OK" 与 "ICY 200 OK"（NTRIP 1.0）。 */
    static boolean isSuccess(String statusLine) {
        String s = statusLine.strip();
        if (s.startsWith("ICY")) {
            return s.contains("200");
        }
        if (s.startsWith("HTTP/")) {
            String[] parts = s.split("\\s+");
            return parts.length >= 2 && "200".equals(parts[1]);
        }
        return false;
    }

    @Override
    public void close() {
        connected = false;
        in = null;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // 关闭失败无需上报
            }
            socket = null;
        }
    }

    /** NTRIP 层的失败（区别于底层 IO 失败）。 */
    public static final class NtripException extends IOException {
        public NtripException(String message) {
            super(message);
        }
    }
}

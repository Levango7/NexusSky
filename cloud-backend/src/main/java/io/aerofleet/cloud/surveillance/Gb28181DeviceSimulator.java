package io.aerofleet.cloud.surveillance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * GB28181 设备侧进程内模拟器（真 UDP socket 上的 SIP 对端）。
 *
 * <p>**定位**：它不是大疆官方模拟器那种"照协议抄一份"的受测对象，而是**替真机巢/真摄像机
 * 当对端**的执行面——让 {@link Gb28181SipTransport} 的收发逻辑在单测里就有真 socket
 * 可证伪（同 {@code DockGatewayLoopbackTest} 的真 HTTP 回环套路）。**真机联调仍需要
 * 硬件**，这条边界不因本类存在而消失。
 *
 * <p>支持的事务（按国标信令时序）：
 * <ol>
 *   <li>REGISTER → 401 + WWW-Authenticate（Digest）首帧；带 Authorization 再来 → 200 OK</li>
 *   <li>MESSAGE（Catalog 查询）→ 200 OK + MANSCDP {@code <Response><CmdType>Catalog}</li>
 *   <li>INVITE → 100 Trying + 200 OK（SDP 描述设备侧媒体参数）</li>
 *   <li>ACK → 不回（INVITE 事务已终结）</li>
 *   <li>BYE → 200 OK</li>
 *   <li>SUBSCRIBE → 200 OK（事件订阅；真实告警推送属事件面，另项）</li>
 * </ol>
 *
 * <p>口径：媒体面（RTP/PS 发送）**尚未**由本类实现——200 OK 的 SDP 只声明参数，
 * 不会真的推流。故上层不得把这里的 200 OK 当成"可播"（诚实边界，见
 * {@link StreamUrlOrigin#DESCRIPTIVE_ONLY}）。
 */
public final class Gb28181DeviceSimulator implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(Gb28181DeviceSimulator.class);

    private final DatagramSocket socket;
    private final Gb28181Signaling signaling;
    private final Gb28181DeviceId deviceId;
    private final String sipHost;
    private final String realm;
    private final String nonce;
    private final Gb28181Sdp deviceSdp;
    private final int channelCount;

    private final ExecutorService loop;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final CopyOnWriteArrayList<Gb28181SipMessage> received0 = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<String> log2 = new CopyOnWriteArrayList<>();

    public Gb28181DeviceSimulator(int port, Gb28181DeviceId deviceId,
                                  String sipHost, int mediaPort, int channelCount) throws IOException {
        this.socket = new DatagramSocket(port);
        this.deviceId = deviceId;
        this.sipHost = sipHost;
        this.signaling = new Gb28181Signaling(sipHost, "sip:" + sipHost);
        this.realm = sipHost;
        this.nonce = "sim-nonce";
        // 设备侧 SDP：声明它"将要"发流的地址与端口（本版不真正发流）
        this.deviceSdp = new Gb28181Sdp(deviceId.value(), hostOf(sipHost), mediaPort, 0x0A0B0C0D);
        this.channelCount = channelCount;
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "gb28181-device-sim-" + socket.getLocalPort());
            t.setDaemon(true);
            return t;
        };
        this.loop = Executors.newSingleThreadExecutor(tf);
    }

    public int port() {
        return socket.getLocalPort();
    }

    /**
     * 对端可达地址。
     *
     * <p>**为什么不用 {@code socket.getLocalAddress()}**：bind 到通配地址的
     * {@link DatagramSocket} 会返回 {@code 0.0.0.0}——拿它当 sendto 目标会
     * {@code BindException: Cannot assign requested address}。这里固定回环地址
     * （通配 bind 在回环上必然可达），与测试/生产同一条路。
     *
     * <p>**为什么缓存**：{@code close()} 之后 {@code socket.getLocalPort()} 返回 {@code -1}，
     * 现场 {@code new InetSocketAddress(host, -1)} 会抛
     * {@code IllegalArgumentException: port out of range:-1}——调用方拿到的是一个
     * 看不懂的异常，而不是"对端已经关了"（真链路测试抓出）。缓存后地址在生命周期内恒定，
     * 关掉之后拿去发包只会**超时**，语义正确。
     */
    public InetSocketAddress address() {
        InetSocketAddress cached = address;
        if (cached == null) {
            cached = new InetSocketAddress(java.net.InetAddress.getLoopbackAddress(),
                    socket.getLocalPort());
            address = cached;
        }
        return cached;
    }

    private volatile InetSocketAddress address;

    /** 收到的全部请求（诊断/断言用）。 */
    public List<Gb28181SipMessage> received() {
        return List.copyOf(received0);
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        loop.submit(this::serveLoop);
    }

    private void serveLoop() {
        byte[] buf = new byte[8192];
        while (running.get() && !socket.isClosed()) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                socket.receive(p);
                String text = new String(p.getData(), p.getOffset(), p.getLength(), StandardCharsets.UTF_8);
                Gb28181SipMessage req;
                try {
                    req = Gb28181SipMessage.parse(text);
                } catch (RuntimeException e) {
                    log.warn("device-sim: undecodable request from {}:{}", p.getAddress(), p.getPort(), e);
                    continue;
                }
                received0.add(req);
                InetSocketAddress peer = new InetSocketAddress(p.getAddress(), p.getPort());
                respond(peer, req);
            } catch (SocketTimeoutException e) {
                // 无请求：继续等
            } catch (IOException e) {
                if (running.get()) {
                    log.warn("device-sim: receive failed", e);
                }
                return;
            }
        }
    }

    private void respond(InetSocketAddress peer, Gb28181SipMessage req) {
        String method = req.method();
        try {
            switch (method == null ? "" : method) {
                case "REGISTER" -> {
                    boolean hasAuth = req.firstHeader("Authorization") != null;
                    if (hasAuth) {
                        reply(peer, signaling.ok(req));
                    } else {
                        reply(peer, signaling.unauthorized(req, realm, nonce));
                    }
                }
                case "MESSAGE" -> reply(peer, signaling.okXml(req, catalogXml()));
                case "INVITE" -> {
                    reply(peer, signaling.trying(req));
                    reply(peer, signaling.okSdp(req, deviceSdp));
                }
                case "ACK" -> {
                    // INVITE 事务已终结，不回（RFC 3261 §13.2.2.4）
                    log2.add("ACK");
                }                case "BYE" -> reply(peer, signaling.ok(req));
                case "SUBSCRIBE" -> reply(peer, signaling.ok(req));
                default -> {
                    // 未知方法：不发假成功——记日志，让对方按超时收尾
                    log.warn("device-sim: unexpected method {}", method);
                    log2.add("UNEXPECTED:" + method);
                }
            }
        } catch (IOException e) {
            log.warn("device-sim: reply failed for {}", method, e);
        }
    }

    private void reply(InetSocketAddress peer, Gb28181SipMessage msg) throws IOException {
        byte[] bytes = msg.render();
        socket.send(new DatagramPacket(bytes, bytes.length, peer));
    }

    /**
     * MANSCDP Catalog 响应（N 个通道）。
     *
     * <p>**频道编码用合法的 20 位国标码**（中心码 11 + 行业 2 + 类型 3 + 序号 4），
     * <b>不是</b> {@code <设备编码>:<通道>} 那种 URI 形态——后者是 21 位，
     * {@link Gb28181CatalogParser} 会按"编码非法"整条跳过（真链路测试抓出：
     * 目录解析出来 0 条）。URI 形态只出现在 INVITE 的 Request-URI 上。
     */
    String catalogXml() {
        StringBuilder sb = new StringBuilder(400);
        sb.append("<?xml version=\"1.0\"?>\r\n")
                .append("<Response><CmdType>Catalog</CmdType><SN>1</SN>")
                .append("<DeviceID>").append(deviceId.value()).append("</DeviceID>")
                .append("<DeviceList Num=\"").append(channelCount).append("\">");
        for (int ch = 1; ch <= channelCount; ch++) {
            Gb28181DeviceId channelId = Gb28181DeviceId.of(
                    deviceId.center, deviceId.industry, deviceId.typeCode,
                    String.format("%04d", ch));
            sb.append("<Item><DeviceID>").append(channelId.value()).append("</DeviceID>")
                    .append("<Name>通道 ").append(ch).append("</Name>")
                    .append("<Manufacturer>演示厂</Manufacturer><Status>ON</Status></Item>");
        }
        sb.append("</DeviceList></Response>");
        return sb.toString();
    }

    /** 设备侧 SDP（媒体参数声明；本版不真正推流）。 */
    public Gb28181Sdp deviceSdp() {
        return deviceSdp;
    }

    /** 事务流水（诊断用）。 */
    public List<String> transactionLog() {
        return List.copyOf(log2);
    }

    @Override
    public void close() {
        running.set(false);
        loop.shutdownNow();
        socket.close();
    }

    private static String hostOf(String sipHost) {
        int colon = sipHost.lastIndexOf(':');
        return colon > 0 ? sipHost.substring(0, colon) : sipHost;
    }

    /** 供 {@code main} 单独运行（e2e 用；与 cloud-backend 无关）。 */
    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 5060;
        int channels = args.length > 1 ? Integer.parseInt(args[1]) : 2;
        Gb28181DeviceId id = Gb28181DeviceId.of("34010000001", "10", "132", "0001");
        Gb28181DeviceSimulator sim = new Gb28181DeviceSimulator(port, id, "127.0.0.1:5060", 4000, channels);
        sim.start();
        System.out.println("gb28181 device simulator on 127.0.0.1:" + sim.port()
                + " (channels=" + channels + ")");
        Thread.currentThread().join();
    }
}

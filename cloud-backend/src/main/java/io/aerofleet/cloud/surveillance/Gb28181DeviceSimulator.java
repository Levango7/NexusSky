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

    /** RTP 媒体面推送目的地；null = 只做信令、不推流（默认）。 */
    private volatile InetSocketAddress mediaTarget;
    /** 本进程内的收流会话（null = 不收流）。 */
    private volatile Gb28181MediaSession mediaSession;
    /** 收流侧观察者（E2E/断言用）。 */
    private final java.util.List<java.util.function.Consumer<Gb28181MediaFrame>> mediaObservers =
            new CopyOnWriteArrayList<>();
    /** 推流周期（ms）；GB/T 28181-2022 对帧率无规定，只规定时间戳纪律。 */
    private volatile long mediaPeriodMs = 200;
    /** 90 kHz 时钟每帧步长（默认 25fps ⇒ 3600 ticks/帧）。 */
    private volatile long mediaStepTicks = 3600;

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

    /** 设备侧 SDP（媒体参数声明；{@link #streamMediaTo} 非空时本模拟器真按此推流）。 */
    public Gb28181Sdp deviceSdp() {
        return deviceSdp;
    }

    /**
     * 设置 RTP 媒体面推送目的地（真 UDP）。
     *
     * <p>GB/T 28181-2022 附录 C.1：PS 以 RTP 负载封装，PT=96 / PS / 90kHz。
     * 本模拟器逐帧推送，**每帧一个 PS 包**，关键帧带系统头 + PSM、非关键帧不带。
     *
     * @param target 收流端地址；null = 停止推流
     */
    public void streamMediaTo(InetSocketAddress target) {
        this.mediaTarget = target;
    }

    /** 推流节奏：周期（ms）与每帧的 90 kHz 时钟步长。 */
    public void setMediaTiming(long periodMs, long stepTicks) {
        if (periodMs <= 0) {
            throw new IllegalArgumentException("media period must be positive: " + periodMs);
        }
        this.mediaPeriodMs = periodMs;
        this.mediaStepTicks = stepTicks > 0 ? stepTicks : 3600;
    }

    /** 本进程内的收流会话（E2E 用；null = 不本地收流）。 */
    public void attachMediaSession(Gb28181MediaSession session) {
        this.mediaSession = session;
    }

    /** 注册收流观察者。 */
    public void onMediaFrame(java.util.function.Consumer<Gb28181MediaFrame> cb) {
        mediaObservers.add(cb);
        Gb28181MediaSession s = mediaSession;
        if (s != null) {
            s.onFrame(cb);
        }
    }

    public List<Gb28181MediaFrame> mediaFrames() {
        Gb28181MediaSession s = mediaSession;
        return s == null ? List.of() : s.frames();
    }

    /**
     * 推一帧（真 UDP）。E2E 与单测都可直接调，不依赖定时器。
     *
     * <p><b>序号由内部自增维护</b>：调用方只给起始序号（首帧），之后每帧自动接续。
     * 若让调用方每次传序号，一旦与上一帧撞号，收流侧的去重逻辑会把新帧的第一片
     * 判成"重复"⇒ 该帧永久缺片（{@code Gb28181MediaSession} 宁可丢帧也不交半帧）。
     * 真链路测试 {@code frameTimestampDisciplineHoldsOnTheWire} 撞过这个坑。
     *
     * @param seqStart 起始 RTP 序号（仅首帧有意义；内部会往后自增）
     * @return 本帧用到的起始序号（便于调用方知道下一帧从哪继续，若它仍想自己管）
     */
    int pushFrame(int seqStart, long timestamp, boolean keyframe) throws IOException {
        InetSocketAddress target = mediaTarget;
        if (target == null) {
            return seqStart;
        }
        int seq = seqInitialized ? nextSeq : seqStart;
        seqInitialized = true;
        byte[] ps = Gb28181PsPacketizer.pack(timestamp, keyframe, List.of(
                new Gb28181PsPacketizer.ElementaryStream(
                        Gb28181PsPacketizer.STREAM_ID_VIDEO,
                        Gb28181PsPacketizer.STREAM_TYPE_H264,
                        new byte[]{(byte) 0x65, (byte) frameSeq, (byte) 0x02})));
        frameSeq++;
        java.net.DatagramSocket out = mediaSocket();
        int cut = Math.max(1, ps.length / 3);
        int off = 0;
        int part = 0;
        int total = (ps.length + cut - 1) / cut;
        while (off < ps.length) {
            int end = Math.min(ps.length, off + cut);
            byte[] payload = java.util.Arrays.copyOfRange(ps, off, end);
            boolean last = ++part == total;
            Gb28181RtpPacket pkt = Gb28181RtpPacket.of(last, Gb28181RtpPacket.PAYLOAD_TYPE_PS,
                    seq, timestamp, ssrcValue, payload);
            out.send(new DatagramPacket(pkt.encode(), pkt.length(), target));
            nextSeq = Gb28181RtpPacket.nextSequence(seq);
            seq = nextSeq;
            off = end;
        }
        return seq;
    }

    private volatile long ssrcValue = 0x0A0B0C0D;
    private int frameSeq;
    /** 下一个可用序号（内部自增，防调用方撞号）。 */
    private int nextSeq;
    private boolean seqInitialized;

    private volatile java.net.DatagramSocket cachedOut;

    private java.net.DatagramSocket mediaSocket() throws IOException {
        java.net.DatagramSocket s = cachedOut;
        if (s == null || s.isClosed()) {
            s = new java.net.DatagramSocket();
            cachedOut = s;
        }
        return s;
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
        java.net.DatagramSocket s = cachedOut;
        if (s != null) {
            s.close();
        }
        Gb28181MediaSession ms = mediaSession;
        if (ms != null) {
            ms.close();
        }
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

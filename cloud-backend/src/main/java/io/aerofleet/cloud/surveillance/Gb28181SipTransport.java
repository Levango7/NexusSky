package io.aerofleet.cloud.surveillance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SIP over UDP 传输（RFC 3261 §18 形状 + GB/T 28181 信令收发）。
 *
 * <p>**为什么要有这个类**：本仓的 GB28181 此前只到"消息文本"——{@code SIP 传输}被记作
 * 生产阶段。于是"帧形状对不对"这件事**没有任何执行面能证伪**：拼出来的字符串哪怕
 * Content-Length 是错的，单测也只断言"包含某几个头"。
 *
 * <p>本类把消息送上真 UDP socket：发送、按事务接收响应、超时、重传（RFC 3261 §17.1.2
 * 的定时器 E1/E2 口径）。它**不需要真设备**——GB28181 设备侧由
 * {@link Gb28181DeviceSimulator} 在进程内充当（同 {@code DockGatewayLoopbackTest} 的
 * "真 HTTP 回环"套路：真 socket、假对端、跑在单测里）。
 *
 * <p>纪律：传输层只负责"把字节送到/收到"，**不解释语义**——200 OK 里的 SDP 怎么用是
 * 上层 dialog 的事；本类不返回假成功（无响应就抛 {@link SipTimeoutException}）。
 */
public final class Gb28181SipTransport implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(Gb28181SipTransport.class);

    /** RFC 3261 §17.1.2.1：定时器 E1 默认 500ms（首次重传间隔）。 */
    public static final int RETRANSMIT_INTERVAL_MS = 500;
    /** 单笔事务默认等待上限。 */
    public static final int DEFAULT_TRANSACTION_TIMEOUT_MS = 4000;

    private final DatagramSocket socket;
    /** 接收循环线程；{@link #start()} 时创建（启动前为空）。 */
    private ExecutorService loop;
    private final AtomicBoolean running = new AtomicBoolean(false);
    /** 按事务键投递的响应（含 100 Trying 这类非终结响应）。 */
    private final ConcurrentHashMap<String, ConcurrentLinkedQueue<Gb28181SipMessage>> byKey =
            new ConcurrentHashMap<>();
    /** 收到的全部消息（含无法归属的）——诊断与断言用。 */
    private final CopyOnWriteArrayList<Gb28181SipMessage> received = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    /**
     * 在指定本地端口开 socket。
     *
     * @param localPort 0 = 让系统分配（测试用随机端口，避免抢端口）
     */
    public Gb28181SipTransport(int localPort) throws SocketException {
        this.socket = new DatagramSocket(localPort);
        this.socket.setSoTimeout(RETRANSMIT_INTERVAL_MS);
    }

    /** 本地端口（发送方地址，SDP 的 {@code c=} 与 {@code m=} 要填它）。 */
    public int localPort() {
        return socket.getLocalPort();
    }

    public InetSocketAddress localAddress() {
        return new InetSocketAddress(socket.getLocalAddress(), socket.getLocalPort());
    }

    /** 启动接收循环。幂等。 */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "sip-udp-" + localPort());
            t.setDaemon(true);
            return t;
        };
        ExecutorService exec = java.util.concurrent.Executors.newSingleThreadExecutor(tf);
        this.loop = exec;
        exec.submit(this::receiveLoop);
    }

    private void receiveLoop() {
        byte[] buf = new byte[8192];
        while (running.get() && !socket.isClosed()) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                socket.receive(p);
                List<Gb28181SipMessage> msgs = decodeDatagram(p);
                for (Gb28181SipMessage m : msgs) {
                    onMessage(m);
                }
            } catch (SocketTimeoutException e) {
                // 读超时：收尾循环即可（事务等待由调用方轮询 byKey 完成，不依赖本循环）
            } catch (IOException e) {
                if (running.get()) {
                    log.warn("sip udp receive failed on port {}", localPort(), e);
                }
                return;
            }
        }
    }

    /** 解一个 datagram：一个报文里可能有多条消息（RFC 3261 §18.3 要求支持）。 */
    private List<Gb28181SipMessage> decodeDatagram(DatagramPacket p) {
        List<Gb28181SipMessage> out = new ArrayList<>();
        String text = new String(p.getData(), p.getOffset(), p.getLength(), StandardCharsets.UTF_8);
        int consumed = 0;
        while (consumed < text.length()) {
            try {
                Gb28181SipMessage m = Gb28181SipMessage.parse(text.substring(consumed));
                out.add(m);
                // 按渲染长度推进；解析失败时退回整段
                int advanced = advanceBy(m, text, consumed);
                consumed += Math.max(1, advanced);
            } catch (RuntimeException e) {
                log.warn("sip datgram payload undecodable from {}:{}", p.getAddress(), p.getPort(), e);
                break;
            }
        }
        return out;
    }

    private int advanceBy(Gb28181SipMessage m, String text, int consumed) {
        // 重新渲染长度即本条消息占用字节数（渲染与解析互为逆运算）
        return m.render().length;
    }

    private void onMessage(Gb28181SipMessage m) {
        received.add(m);
        String key = m.transactionKey();
        byKey.computeIfAbsent(key, k -> new ConcurrentLinkedQueue<>()).add(m);
        if (m.methodOfCseq() == null && !m.isResponse()) {
            // 非响应且无 CSeq：无法归属，仍入 received（诊断面）——但绝不静默吞
            log.debug("sip message without transaction key from peer: {}", m.startLine());
        }
        listeners.forEach(Runnable::run);
    }

    /** 注册「有新消息到达」的回调（同步调用，勿阻塞）。 */
    public void onMessage(Runnable callback) {
        listeners.add(Objects.requireNonNull(callback, "callback"));
    }

    /** 已收到的全部消息（诊断/断言用）。 */
    public List<Gb28181SipMessage> received() {
        return List.copyOf(received);
    }

    /** 发送消息到对端。 */
    public void send(InetSocketAddress to, Gb28181SipMessage msg) throws IOException {
        byte[] bytes = msg.render();
        socket.send(new DatagramPacket(bytes, bytes.length, to));
    }

    /**
     * 事务请求：发送 + 等待**终结**响应（Call-ID + CSeq + 方法一致）。
     *
     * <p>**1xx Provisional 被跳过**（RFC 3261 §17.1.1.2：INVITE 事务的 100 Trying
     * 不是终结响应）。首版实现把 100 Trying 当终结响应返回，调用方拿到 {@code 100}
     * 却以为事务结束——真链路测试当场抓出。100 Trying 仍在
     * {@link #received()} 里可断言。
     *
     * <p>**不重传**的原因：进程内模拟器在同一台机器上，UDP 丢包概率可忽略；
     * 重传会改变对端看到的 CSeq 计数，把"等待逻辑"变成"重传逻辑"的混合体。
     * 生产环境接真设备时用 {@link #transact(InetSocketAddress, Gb28181SipMessage, Duration, boolean)}。
     *
     * @return 首条终结响应；超时抛 {@link SipTimeoutException}（**不返回假成功**）
     */
    public Gb28181SipMessage transact(InetSocketAddress to, Gb28181SipMessage request,
                                     Duration timeout) throws IOException {
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(timeout, "timeout");
        String key = request.transactionKey();
        send(to, request);
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            ConcurrentLinkedQueue<Gb28181SipMessage> q = byKey.get(key);
            if (q != null) {
                // 先取干净：provisional 也入队（诊断面），但不作为终结响应返回
                Gb28181SipMessage m = q.poll();
                if (m != null) {
                    if (isProvisional(m)) {
                        continue;
                    }
                    return m;
                }
            }
            try {
                Thread.sleep(Math.max(5, Math.min(25, deadline - System.currentTimeMillis())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SipTimeoutException("interrupted waiting for " + key);
            }
        }
        throw new SipTimeoutException("no final response matched " + key
                + " within " + timeout.toMillis() + "ms");
    }

    /** Provisional（1xx）不是终结响应。 */
    private static boolean isProvisional(Gb28181SipMessage m) {
        return m.isResponse() && m.statusCode() < 200;
    }

    /** 无匹配响应的事务异常（自建短句，不粘底层 IO 文本——同 #248/#250 纪律）。 */
    public static final class SipTimeoutException extends IOException {
        public SipTimeoutException(String message) {
            super(message);
        }
    }

    @Override
    public void close() {
        running.set(false);
        if (loop != null) {
            loop.shutdownNow();
        }
        socket.close();
    }
}

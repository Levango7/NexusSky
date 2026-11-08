package io.aerofleet.mavlink.transport;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.MavlinkParser;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * MAVLink over UDP 单线程传输：一个线程收包解析 + 回调分发，发送线程安全。
 * 与 PX4/ArduPilot 的 UDP 端口约定一致（GCS 常用 14550，飞控常用 14540）。
 *
 * bindIp：Windows/Linux 的 127.0.0.0/8 整段回环允许每个进程绑定不同的 127.x.y.z，
 * 用于给每架虚拟飞机一个独立的“网段身份”——链路损伤代理按 IP 区分流量，
 * 在无物理网段条件下模拟多网络环境。
 */
public class UdpMavlinkTransport implements AutoCloseable {

    private static final Logger log = Logger.getLogger(UdpMavlinkTransport.class.getName());

    /** peer 超时清理：超过此时间未活跃的 peer 记录将被移除（ms）。 */
    private static final long PEER_TIMEOUT_MS = 120_000L;
    /** peer 清理周期（ms）。 */
    private static final long PEER_CLEANUP_INTERVAL_MS = 30_000L;
    /** close() 等待收包线程退出的上限（ms）：线程退出后端口释放必然完成。 */
    private static final long RECEIVE_JOIN_TIMEOUT_MS = 5_000L;

    private final DatagramSocket socket;
    private final MavlinkParser parser = new MavlinkParser();
    private final Thread receiveThread;
    private final Thread cleanupThread;
    private volatile boolean running = true;

    private final ConcurrentLinkedQueue<Consumer<MavlinkFrame>> frameListeners = new ConcurrentLinkedQueue<>();
    /** 高级监听器（带源地址）；与普通监听器互斥使用，注册了高级就不回调普通的。 */
    private final ConcurrentLinkedQueue<BiConsumer<MavlinkFrame, SocketAddress>> sourceListeners =
            new ConcurrentLinkedQueue<>();

    /** 最后一个向我们发包的对端（自动学习，模拟“飞控记住 GCS 地址”的行为）。 */
    private volatile SocketAddress lastPeer;

    public UdpMavlinkTransport(int bindPort) throws IOException {
        this("0.0.0.0", bindPort);
    }

    /** 绑定指定 IP（如 127.0.0.2）实现“网段身份”。 */
    public UdpMavlinkTransport(String bindIp, int bindPort) throws IOException {
        this.socket = new DatagramSocket(new InetSocketAddress(
                InetAddress.getByName(bindIp), bindPort));
        this.receiveThread = new Thread(this::receiveLoop, "mavlink-udp-" + bindPort);
        this.receiveThread.setDaemon(true);
        this.receiveThread.start();
        // 启动 peer 清理线程：定期移除超时未活跃的 peer 记录
        this.cleanupThread = new Thread(this::peerCleanupLoop, "mavlink-peer-cleanup-" + bindPort);
        this.cleanupThread.setDaemon(true);
        this.cleanupThread.start();
    }

    /** 注册带源地址的监听器（多机路由用）；注册后普通监听器不再被回调。 */
    public void addFrameListener(BiConsumer<MavlinkFrame, SocketAddress> listener) {
        sourceListeners.add(listener);
    }

    /**
     * 调整内核接收缓冲区大小。
     * <p>
     * <b>为什么需要</b>：本类的收包是**单线程串行**的（{@code receiveLoop}），
     * 且监听器在收包线程内同步执行。当对端以突发方式发帧（一轮内连发 N 帧）时，
     * 内核缓冲区若太小就会溢出丢包，而丢包在应用层**完全不可见**——
     * 本类没有任何溢出计数器。容量实测显示摄入侧在约 4,100 帧/s 进入临界区，
     * 加大缓冲区是最低成本、不改线程模型的缓解手段。
     * <p>
     * <b>默认不调用</b>：保持既有行为不变（JVM/OS 默认值）。只有显式配置才生效，
     * 因此对现有调用点（{@code UdpGateway} / {@code VirtualDrone} / 硬件适配器 / 测试）零影响。
     *
     * @param bytes 期望字节数；{@code <= 0} 表示不做任何调整
     * @return 实际生效值（OS 可能低于请求值，例如 Windows 有上限）
     */
    public int setReceiveBufferSize(int bytes) {
        if (bytes <= 0) {
            return currentReceiveBufferSize();
        }
        try {
            socket.setReceiveBufferSize(bytes);
            int actual = socket.getReceiveBufferSize();
            if (actual < bytes) {
                log.info("MAVLink UDP receive buffer: requested " + bytes + "B, actual " + actual
                        + "B（OS 上限限制，请求值未完全生效）on port " + socket.getLocalPort());
            } else {
                log.info("MAVLink UDP receive buffer: " + actual + "B on port " + socket.getLocalPort());
            }
            return actual;
        } catch (java.net.SocketException e) {
            // 设不上不影响功能：只是回到「突发更容易丢包」的旧状态，必须记录但不能中断
            int fallback = currentReceiveBufferSize();
            log.log(Level.WARNING, "设置 UDP 接收缓冲区失败（保持 " + fallback + "B）", e);
            return fallback;
        }
    }

    /** 读当前内核缓冲区大小；读不到返回 -1（仅用于报告，不代表故障）。 */
    private int currentReceiveBufferSize() {
        try {
            return socket.getReceiveBufferSize();
        } catch (java.net.SocketException e) {
            return -1;
        }
    }

    public void addFrameListener(Consumer<MavlinkFrame> listener) {
        frameListeners.add(listener);
    }

    /** 发现任务：每目标一个，独立运行；收到“来自该目标”的回包后该任务自停。 */
    private static final class DiscoveryTask {
        final SocketAddress target;
        final java.util.function.Supplier<MavlinkFrame> supplier;
        final AtomicBoolean answered = new AtomicBoolean(false);

        DiscoveryTask(SocketAddress target, java.util.function.Supplier<MavlinkFrame> supplier) {
            this.target = target;
            this.supplier = supplier;
        }
    }

    private final java.util.concurrent.CopyOnWriteArrayList<DiscoveryTask> discoveryTasks =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    /** discovery 线程引用（P2: close() 时中断，避免线程泄漏）。 */
    private final java.util.concurrent.CopyOnWriteArrayList<Thread> discoveryThreads =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /** 每个对端最近发包时间（发现任务的静默检测用）。 */
    private final java.util.concurrent.ConcurrentHashMap<SocketAddress, Long> peerLastSeen =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 启用对端发现：每秒向 target 发一帧由 supplier 生成的消息（如 GCS 心跳），
     * 直到收到来自该 target 的回包后自动停止。这是 QGroundControl 式
     * “GCS 主动打招呼”行为的抽象，解决双方互相等待的问题。
     * 多个 target 可并存，各自独立发现（多机骨架）。
     */
    public void enablePeerDiscovery(SocketAddress target,
                                    java.util.function.Supplier<MavlinkFrame> discoveryFrameSupplier) {
        DiscoveryTask task = new DiscoveryTask(target, discoveryFrameSupplier);
        discoveryTasks.add(task);
        Thread t = new Thread(() -> discoveryLoop(task), "mavlink-discovery");
        t.setDaemon(true);
        discoveryThreads.add(t);
        t.start();
    }

    /** receiveLoop 在收到包时调用：标记对应目标的发现任务已完成。 */
    private void onPacketFrom(SocketAddress peer) {
        for (DiscoveryTask task : discoveryTasks) {
            if (!task.answered.get() && task.target.equals(peer)) {
                task.answered.set(true);
            }
        }
    }

    private void discoveryLoop(DiscoveryTask task) {
        while (running) {
            // 已应答：休眠监听，若该目标 30s 静默（设备重启/换机）则恢复发现
            if (task.answered.get()) {
                long silent = System.currentTimeMillis() - lastPacketFrom(task.target);
                if (silent < 30_000) {
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    continue;
                }
                task.answered.set(false); // 目标静默超时，重新进入主动发现
            }
            try {
                MavlinkFrame frame = task.supplier.get();
                if (frame != null) {
                    byte[] data = frame.encodeV2();
                    socket.send(new DatagramPacket(data, data.length, task.target));
                }
            } catch (IOException | RuntimeException e) {
                // 发现包发送失败：记录日志，下轮重试
                log.log(Level.WARNING, "MAVLink discovery send failed to " + task.target + ": " + e.getMessage());
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** 最近一次收到该目标数据包的时间戳（epoch ms）。 */
    private long lastPacketFrom(SocketAddress target) {
        Long ts = peerLastSeen.get(target);
        return ts != null ? ts : 0L;
    }

    /**
     * peer 清理循环：定期移除超过 {@link #PEER_TIMEOUT_MS} 未活跃的 peer 记录，
     * 防止 peerLastSeen map 只增不删导致内存泄漏。
     */
    private void peerCleanupLoop() {
        while (running) {
            try {
                Thread.sleep(PEER_CLEANUP_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            long now = System.currentTimeMillis();
            int removed = 0;
            for (var entry : peerLastSeen.entrySet()) {
                // 原子比较时间戳后删除，避免移除已被收包线程更新的活跃 peer。
                if (now - entry.getValue() > PEER_TIMEOUT_MS
                        && peerLastSeen.remove(entry.getKey(), entry.getValue())) {
                    removed++;
                }
            }
            if (removed > 0) {
                log.log(Level.FINE, "peer cleanup: removed " + removed
                        + " stale peers (timeout=" + PEER_TIMEOUT_MS + "ms)");
            }
        }
    }

    private void receiveLoop() {
        byte[] buf = new byte[2048];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        while (running) {
            try {
                packet.setLength(buf.length);
                socket.receive(packet);
                lastPeer = packet.getSocketAddress();
                peerLastSeen.put(lastPeer, System.currentTimeMillis());
                onPacketFrom(lastPeer);
                ByteBuffer buffer = ByteBuffer.wrap(buf, 0, packet.getLength());
                MavlinkParser.ParseResult result;
                while ((result = parser.parse(buffer)) != null) {
                    if (!sourceListeners.isEmpty()) {
                        for (BiConsumer<MavlinkFrame, SocketAddress> l : sourceListeners) {
                            try {
                                l.accept(result.frame, lastPeer);
                            } catch (RuntimeException ignore) {
                                // 单个监听器异常不拖垮收包循环
                            }
                        }
                    } else {
                        for (Consumer<MavlinkFrame> l : frameListeners) {
                            try {
                                l.accept(result.frame);
                            } catch (RuntimeException ignore) {
                                // 单个监听器异常不拖垮收包循环
                            }
                        }
                    }
                }
            } catch (IOException e) {
                if (running) {
                    // 端口被占或套接字异常：记录日志，由健康检查暴露
                    log.log(Level.WARNING, "MAVLink UDP receive error on port "
                            + socket.getLocalPort() + ": " + e.getMessage());
                }
            }
        }
    }

    /** 发到指定对端。 */
    public void send(MavlinkFrame frame, SocketAddress peer) throws IOException {
        byte[] data = frame.encodeV2();
        socket.send(new DatagramPacket(data, data.length, peer));
    }

    /** 发到最后已知对端（未收到过任何包时静默丢弃）。 */
    public void sendToLastPeer(MavlinkFrame frame) throws IOException {
        SocketAddress peer = lastPeer;
        if (peer != null) {
            send(frame, peer);
        }
    }

    public SocketAddress getLastPeer() {
        return lastPeer;
    }

    public int getLocalPort() {
        return socket.getLocalPort();
    }

    @Override
    public void close() {
        running = false;
        socket.close();
        receiveThread.interrupt();
        cleanupThread.interrupt();
        // P2: 中断 discovery 线程，避免线程泄漏
        for (Thread t : discoveryThreads) {
            t.interrupt();
        }
        // 端口释放同步化：JDK 阻塞模式下 socket.close() 是延迟关闭——收包线程
        // 阻塞于 receive 时 close() 只做 preClose 即返回，真正 fd 关闭由收包线程
        // 退出路径完成（DatagramChannelImpl.endRead -> tryFinishClose）；不 join 则
        // close() 返回后端口可能仍被占，紧随的下一绑定会 BindException（CI 两个
        // E2E 间歇红取证与此吻合）。join 有界，正常路径瞬时返回。
        try {
            receiveThread.join(RECEIVE_JOIN_TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (receiveThread.isAlive()) {
            log.log(Level.WARNING, receiveThread.getName() + " still alive after close ("
                    + RECEIVE_JOIN_TIMEOUT_MS + "ms join timeout); UDP port release may lag");
        }
    }
}

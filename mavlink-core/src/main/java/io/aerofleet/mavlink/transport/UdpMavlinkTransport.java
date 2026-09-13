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

/**
 * MAVLink over UDP 单线程传输：一个线程收包解析 + 回调分发，发送线程安全。
 * 与 PX4/ArduPilot 的 UDP 端口约定一致（GCS 常用 14550，飞控常用 14540）。
 *
 * bindIp：Windows/Linux 的 127.0.0.0/8 整段回环允许每个进程绑定不同的 127.x.y.z，
 * 用于给每架虚拟飞机一个独立的“网段身份”——链路损伤代理按 IP 区分流量，
 * 在无物理网段条件下模拟多网络环境。
 */
public class UdpMavlinkTransport implements AutoCloseable {

    private final DatagramSocket socket;
    private final MavlinkParser parser = new MavlinkParser();
    private final Thread receiveThread;
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
    }

    /** 注册带源地址的监听器（多机路由用）；注册后普通监听器不再被回调。 */
    public void addFrameListener(BiConsumer<MavlinkFrame, SocketAddress> listener) {
        sourceListeners.add(listener);
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
                // 发现包发送失败：下轮重试
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
                    // 端口被占或套接字异常：骨架阶段仅吞掉，由健康检查暴露
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
    }
}

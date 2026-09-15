package io.aerofleet.linksim;

import java.io.IOException;
import java.net.BindException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

// === hopCount 扩展点（FR-21）：一跳静态中继不处理跳数 ===
// MAVLink 标准帧无 TTL/hopCount 字段。本里程碑仅一跳固定拓扑，无环。
// 若未来支持多跳：需在此引入帧级 hopCount 字段（应用层扩展），
// 转发前递减并在 <=0 时丢弃，以防止多跳环路。
// 当前明确不实现运行时跳数处理（FR-18/19）。
// 预留占位常量，多跳扩展后启用为帧级字段并接入判决逻辑。

/**
 * 一跳静态中继运行时（FR-05~17）。
 *
 * <p>持双 {@link DatagramSocket}（面向 GCS/后端 + 面向远端飞机）与双独立
 * {@link ImpairmentEngine}（上下行异构链路），实现 learn-once 地址学习、
 * 双向透明转发、延迟投递与周期统计。
 *
 * <p>线程模型（design.md §2.1.3.2，2 接收 + 1 发送 + 1 统计）：
 * <ul>
 *   <li>{@code relay-uplink}（daemon）：{@code gcsSocket.receive} → 学习 gcsAddr → uplink.verdict → 入队</li>
 *   <li>主线程（{@code downlinkLoop}）：{@code relaySocket.receive} → 学习 droneAddr → downlink.verdict → 入队</li>
 *   <li>{@code relay-wire}（daemon）：消费队列，按 dueAtMs 延迟，选 socket 透明转发</li>
 *   <li>{@code relay-stats}（daemon）：10s 周期输出上下行统计</li>
 * </ul>
 *
 * <p>透明性：全程不解析/不修改 MAVLink 帧字节，仅按 {@link DatagramPacket#getLength()} 判决。
 */
final class RelayNode {

    /** hopCount 扩展点占位（FR-21）：一跳静态中继不启用跳数守卫。多跳扩展后改为帧级字段。 */
    static final int HOP_COUNT_DISABLED = -1;

    private final RelayConfig cfg;
    private final DatagramSocket gcsSocket;     // 面向 GCS/后端
    private final DatagramSocket relaySocket;   // 面向远端飞机
    private final ImpairmentEngine uplink;      // 上行独立引擎（GCS -> 飞机）
    private final ImpairmentEngine downlink;    // 下行独立引擎（飞机 -> GCS）
    /** 延迟调度：到期任务按时间出队发送（DFX 4.1 初始容量 256）。 */
    private final PriorityBlockingQueue<ScheduledPacket> wire =
            new PriorityBlockingQueue<>(256);
    private final AtomicLong seq = new AtomicLong();

    /**
     * 生产构造器：绑定双 socket + 从 cfg 画像创建双独立 engine。
     *
     * <p>绑定失败（端口占用）捕获 {@link BindException} 打印失败端口并 {@code exit(1)}
     * （FR 5.1.3-2）。
     */
    RelayNode(RelayConfig cfg) throws IOException {
        this(cfg, cfg.uplink.engine(), cfg.downlink.engine());
    }

    /**
     * 测试构造器：注入自定义 engine（如 100% drop），用于单测验证上下行独立损伤。
     */
    RelayNode(RelayConfig cfg, ImpairmentEngine uplink, ImpairmentEngine downlink)
            throws IOException {
        this.cfg = cfg;
        this.uplink = uplink;
        this.downlink = downlink;
        this.gcsSocket = bindSocket(cfg.gcsPort, "gcs-port");
        this.relaySocket = bindSocket(cfg.relayPort, "relay-port");
    }

    /** 绑定端口，失败捕获 BindException 打印并 exit(1)。 */
    private static DatagramSocket bindSocket(int port, String label) throws IOException {
        try {
            return new DatagramSocket(new InetSocketAddress(port));
        } catch (BindException e) {
            System.out.println("[mesh-relay] bind failed for " + label + "=" + port + ": " + e.getMessage());
            System.exit(1);
            return null; // 不可达
        }
    }

    /**
     * 启动 uplinkLoop/wirePump/statsLoop 三个 daemon 线程，主线程跑 downlinkLoop 阻塞。
     */
    void run() throws IOException {
        Thread up = new Thread(this::uplinkLoop, "relay-uplink");
        up.setDaemon(true);
        up.start();

        Thread wire = new Thread(this::wirePump, "relay-wire");
        wire.setDaemon(true);
        wire.start();

        Thread stats = new Thread(this::statsLoop, "relay-stats");
        stats.setDaemon(true);
        stats.start();

        System.out.println("[mesh-relay] running (Ctrl+C to stop)");
        downlinkLoop(); // 主线程阻塞下行接收
    }

    /**
     * 上行接收循环（daemon）：GCS -> 飞机。
     *
     * <p>{@code gcsSocket.receive} → learn-once 学习 {@code cfg.gcsAddr} →
     * {@code droneAddr==null} 时 {@code continue}（FR-11 上行等待期）→
     * {@code uplink.verdict(len)}，{@code delay<0} 丢弃（FR-08），否则入队
     * {@code (to=droneAddr, toDrone=true)}。
     */
    private void uplinkLoop() {
        byte[] buf = new byte[2048];
        DatagramPacket pkt = new DatagramPacket(buf, buf.length);
        while (true) {
            try {
                pkt.setLength(buf.length);
                gcsSocket.receive(pkt);
                byte[] data = Arrays.copyOf(buf, pkt.getLength());
                InetSocketAddress from = (InetSocketAddress) pkt.getSocketAddress();
                // FR-10 学习 gcsAddr（learn-once，design.md §2.1.3.1：仅在 null 时赋值）
                if (cfg.gcsAddr == null) {
                    cfg.gcsAddr = from;
                }
                // FR-11 上行等待期：droneAddr 未学习则不转发（静默丢弃）
                if (cfg.droneAddr == null) continue;
                // FR-14 上行独立引擎判决
                long delay = uplink.verdict(data.length);
                if (delay < 0) continue; // FR-08 丢弃
                wire.add(new ScheduledPacket(System.currentTimeMillis() + delay,
                        seq.incrementAndGet(), data, cfg.droneAddr, true));
            } catch (IOException e) {
                // 接收异常：真实链路偶发，不中断循环
            }
        }
    }

    /**
     * 下行接收循环（主线程）：飞机 -> GCS。
     *
     * <p>{@code relaySocket.receive} → learn-once 学习 {@code cfg.droneAddr} →
     * {@code gcsAddr==null} 时 {@code continue}（对端未学习静默丢弃，FR 5.2.3-1）→
     * {@code downlink.verdict(len)}，{@code delay<0} 丢弃，否则入队
     * {@code (to=gcsAddr, toDrone=false)}。
     */
    private void downlinkLoop() throws IOException {
        byte[] buf = new byte[2048];
        DatagramPacket pkt = new DatagramPacket(buf, buf.length);
        while (true) {
            pkt.setLength(buf.length);
            relaySocket.receive(pkt);
            byte[] data = Arrays.copyOf(buf, pkt.getLength());
            InetSocketAddress from = (InetSocketAddress) pkt.getSocketAddress();
            // FR-09 学习 droneAddr（learn-once）
            if (cfg.droneAddr == null) {
                cfg.droneAddr = from;
            }
            // 对端未学习时静默丢弃（FR 5.2.3-1）
            if (cfg.gcsAddr == null) continue;
            // FR-14 下行独立引擎判决
            long delay = downlink.verdict(data.length);
            if (delay < 0) continue; // FR-08 丢弃
            wire.add(new ScheduledPacket(System.currentTimeMillis() + delay,
                    seq.incrementAndGet(), data, cfg.gcsAddr, false));
        }
    }

    /**
     * 单发送线程（daemon）：统一延迟投递到双 socket（design.md §2.1.3.3）。
     *
     * <p>沿用现有 {@code LinkSimMain.wirePump} 的"睡到最早到期时间"轮询模式，
     * 扩展为按 {@code toDrone} 选 socket。透明转发：不篡改字节。
     * {@code IOException} 丢弃该包不中断循环（FR 5.2.3-2）。
     */
    private void wirePump() {
        while (true) {
            try {
                ScheduledPacket p = wire.take();
                long wait = p.dueAtMs - System.currentTimeMillis();
                if (wait > 0) {
                    // PriorityBlockingQueue 无延时轮询：睡到最早到期时间
                    Thread.sleep(Math.min(wait, 50));
                    if (p.dueAtMs - System.currentTimeMillis() > 0) {
                        wire.add(p); // 还没到点，放回
                        continue;
                    }
                }
                DatagramSocket out = p.toDrone ? relaySocket : gcsSocket; // 选 socket
                out.send(new DatagramPacket(p.data, p.data.length, p.to)); // 透明转发，字节不变
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (IOException e) {
                // 目标暂时不可达：丢弃该包（FR 5.2.3-2，真实链路也是这样）
            }
        }
    }

    /**
     * 统计循环（daemon）：10s 周期输出上下行 fwd/drop/avgDelay（FR-16, DFX 4.4）。
     */
    private void statsLoop() {
        while (true) {
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                return;
            }
            System.out.println("[mesh-relay] up(gcs->drone)   " + uplink.stats());
            System.out.println("[mesh-relay] down(drone->gcs) " + downlink.stats());
        }
    }

    /** 带方向的待发数据包：按到期时间再按入队顺序排序。 */
    private record ScheduledPacket(long dueAtMs, long order, byte[] data,
                                   InetSocketAddress to, boolean toDrone) implements
            Comparable<ScheduledPacket> {
        @Override
        public int compareTo(ScheduledPacket o) {
            int c = Long.compare(dueAtMs, o.dueAtMs);
            return c != 0 ? c : Long.compare(order, o.order);
        }
    }
}
package io.aerofleet.linksim;

import java.io.IOException;
import java.net.BindException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

// === hopCount 扩展点（FR-21 → FR-06~08a M5 启用）===
// MAVLink 标准帧无 TTL/hopCount 字段。M5 多跳中继在帧 payload 尾部追加 1 字节 hopCount。
// multiHopEnabled=true 时：解析尾部 hopCount，递减后转发；耗尽(<=0)或越界(>15)丢弃。
// multiHopEnabled=false 时：完全保持 M0a 一跳透明转发语义（DFX 5.5.1）。
// HOP_COUNT_DISABLED = -1：旧帧兼容标记，收到未携带 hopCount 的 M0a 旧帧时按 MAX_HOPS=15 处理。

/**
 * 一跳静态中继运行时（FR-05~17）+ M5 多跳中继扩展（FR-06~08a）。
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
 * <p>透明性：multiHopEnabled=false 时全程不解析/不修改 MAVLink 帧字节，仅按
 * {@link DatagramPacket#getLength()} 判决（M0a 退化兼容，DFX 5.5.1）。
 * multiHopEnabled=true 时在帧尾部追加/解析 1 字节 hopCount（FR-06/07/08）。
 */
final class RelayNode {

    /** hopCount 扩展点占位（FR-21）：M0a 旧帧兼容标记，收到未携带 hopCount 的旧帧时按 MAX_HOPS=15 处理。 */
    static final int HOP_COUNT_DISABLED = -1;
    /** M5 最大跳数（FR-08）。 */
    static final int MAX_HOPS = 15;

    private final RelayConfig cfg;
    private final MultiHopRelayConfig multiHopCfg;
    private final DatagramSocket gcsSocket;     // 面向 GCS/后端
    private final DatagramSocket relaySocket;   // 面向远端飞机
    private final ImpairmentEngine uplink;      // 上行独立引擎（GCS -> 飞机）
    private final ImpairmentEngine downlink;    // 下行独立引擎（飞机 -> GCS）
    /** 延迟调度：到期任务按时间出队发送（DFX 4.1 初始容量 256）。 */
    private final PriorityBlockingQueue<ScheduledPacket> wire =
            new PriorityBlockingQueue<>(256);
    private final AtomicLong seq = new AtomicLong();

    /**
     * 生产构造器：绑定双 socket + 从 cfg 画像创建双独立 engine + 默认多跳配置（禁用）。
     *
     * <p>绑定失败（端口占用）捕获 {@link BindException} 打印失败端口并 {@code exit(1)}
     * （FR 5.1.3-2）。
     */
    RelayNode(RelayConfig cfg) throws IOException {
        this(cfg, cfg.uplink.engine(), cfg.downlink.engine(), MultiHopRelayConfig.defaults());
    }

    /**
     * 测试构造器：注入自定义 engine（如 100% drop），用于单测验证上下行独立损伤。
     */
    RelayNode(RelayConfig cfg, ImpairmentEngine uplink, ImpairmentEngine downlink)
            throws IOException {
        this(cfg, uplink, downlink, MultiHopRelayConfig.defaults());
    }

    /**
     * 全参数构造器：注入多跳配置（M5）。
     */
    RelayNode(RelayConfig cfg, ImpairmentEngine uplink, ImpairmentEngine downlink,
              MultiHopRelayConfig multiHopCfg) throws IOException {
        this.cfg = cfg;
        this.multiHopCfg = multiHopCfg;
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
     * <p>multiHopEnabled=true 时：解析帧尾部 1 字节 hopCount（无尾部则 MAX_HOPS=15 兼容降级），
     * hopCount > MAX_HOPS 丢弃（FR-08），hopCount <= 0 丢弃（FR-07），递减后入队。
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
                // M5 多跳 hopCount 处理（FR-06/07/08）
                int hopCount = HOP_COUNT_DISABLED;
                if (multiHopCfg.multiHopEnabled) {
                    hopCount = extractHopCount(data);
                    if (hopCount > MAX_HOPS) continue;  // FR-08 越界丢弃
                    if (hopCount <= 0) continue;        // FR-07 耗尽丢弃
                    hopCount--;                          // FR-06 递减
                }
                wire.add(new ScheduledPacket(System.currentTimeMillis() + delay,
                        seq.incrementAndGet(), data, cfg.droneAddr, true, hopCount));
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
     * <p>multiHopEnabled=true 时：同 uplinkLoop 的 hopCount 处理。
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
            // M5 多跳 hopCount 处理（FR-06/07/08）
            int hopCount = HOP_COUNT_DISABLED;
            if (multiHopCfg.multiHopEnabled) {
                hopCount = extractHopCount(data);
                if (hopCount > MAX_HOPS) continue;  // FR-08 越界丢弃
                if (hopCount <= 0) continue;        // FR-07 耗尽丢弃
                hopCount--;                          // FR-06 递减
            }
            wire.add(new ScheduledPacket(System.currentTimeMillis() + delay,
                    seq.incrementAndGet(), data, cfg.gcsAddr, false, hopCount));
        }
    }

    /**
     * 从帧 payload 尾部提取 hopCount（M5 多跳扩展）。
     * <p>无尾部（M0a 旧帧）则返回 MAX_HOPS=15 兼容降级（FR-08a）。
     */
    private static int extractHopCount(byte[] data) {
        // MAVLink v2 帧尾部 2 字节 CRC，hopCount 追加在 CRC 之后
        // 简化：若数据长度 > 12（最小帧头+CRC），取最后一字节作为 hopCount
        if (data.length < 13) {
            return MAX_HOPS;  // FR-08a 旧帧兼容
        }
        return data[data.length - 1] & 0xFF;
    }

    /**
     * 将 hopCount 写回帧尾部（M5 多跳扩展）。
     * <p>
     * P1-fix(Major 3+4):
     * <ul>
     *   <li>帧已有 hopCount 尾部（data.length >= 13）→ 原地更新最后一字节，避免每跳 +1 字节导致帧持续增长。</li>
     *   <li>帧无尾部（data.length < 13，M0a 旧帧）→ 追加 1 字节。</li>
     *   <li>hopCount == 0 时仍保留尾部字节（值为 0），接收方 extractHopCount 返回 0，
     *       hopCount <= 0 丢弃，避免已耗尽帧被重新当作新帧开始 15 跳转发。</li>
     * </ul>
     */
    private static byte[] writeHopCount(byte[] data, int hopCount) {
        if (data.length >= 13) {
            // 帧已有 hopCount 尾部：原地更新最后一字节，避免帧持续增长
            byte[] out = Arrays.copyOf(data, data.length);
            out[out.length - 1] = (byte) hopCount;
            return out;
        } else {
            // 帧无尾部（M0a 旧帧）：追加 1 字节 hopCount（含 hopCount==0 也保留尾部）
            byte[] out = Arrays.copyOf(data, data.length + 1);
            out[out.length - 1] = (byte) hopCount;
            return out;
        }
    }

    /**
     * 单发送线程（daemon）：统一延迟投递到双 socket（design.md §2.1.3.3）。
     *
     * <p>沿用现有 {@code LinkSimMain.wirePump} 的"睡到最早到期时间"轮询模式，
     * 扩展为按 {@code toDrone} 选 socket。
     * <p>multiHopEnabled=false 时透明转发：不篡改字节（M0a 退化兼容，DFX 5.5.1）。
     * <p>multiHopEnabled=true 时：hopCount > 0 则将 hopCount 写回帧尾部转发，
     * hopCount == 0 则剥离尾部转发最后一跳。
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
                byte[] sendData = p.data;
                // M5 多跳：hopCount 写回帧尾部
                if (multiHopCfg.multiHopEnabled && p.hopCount != HOP_COUNT_DISABLED) {
                    sendData = writeHopCount(p.data, p.hopCount);
                }
                out.send(new DatagramPacket(sendData, sendData.length, p.to));
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

    /** 带方向的待发数据包：按到期时间再按入队顺序排序。hopCount 为 HOP_COUNT_DISABLED 表示 M0a 旧帧。 */
    private record ScheduledPacket(long dueAtMs, long order, byte[] data,
                                   InetSocketAddress to, boolean toDrone,
                                   int hopCount) implements
            Comparable<ScheduledPacket> {
        @Override
        public int compareTo(ScheduledPacket o) {
            int c = Long.compare(dueAtMs, o.dueAtMs);
            return c != 0 ? c : Long.compare(order, o.order);
        }
    }
}
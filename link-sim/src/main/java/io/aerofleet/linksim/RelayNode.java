package io.aerofleet.linksim;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.BindException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

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
 * <p><b>全程不解析/不修改 MAVLink 帧字节</b>：只按 {@link DatagramPacket#getLength()}
 * 判决。这是本类唯一的行为口径，也是 2026-10-12 拆掉多跳扩展后固化下来的约束——
 * 见文件底部的「为什么没有多跳」。
 */
final class RelayNode {

    private static final Logger log = LoggerFactory.getLogger(RelayNode.class);

    private final RelayConfig cfg;
    private final DatagramSocket gcsSocket;     // 面向 GCS/后端
    private final DatagramSocket relaySocket;   // 面向远端飞机
    private final ImpairmentEngine uplink;      // 上行独立引擎（GCS -> 飞机）
    private final ImpairmentEngine downlink;    // 下行独立引擎（飞机 -> GCS）
    /** 安全画像引擎（null 表示非安全画像，使用 ImpairmentEngine 路径）。 */
    private final SecurityImpairmentEngine secUplink;
    /** 安全画像引擎（null 表示非安全画像，使用 ImpairmentEngine 路径）。 */
    private final SecurityImpairmentEngine secDownlink;
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
        // 安全画像：根据 cfg 画像类型创建 SecurityImpairmentEngine（非安全画像为 null）
        this.secUplink = cfg.uplink.isSecurityProfile()
                ? new SecurityImpairmentEngine(cfg.uplink, 1.0) : null;
        this.secDownlink = cfg.downlink.isSecurityProfile()
                ? new SecurityImpairmentEngine(cfg.downlink, 1.0) : null;
        this.gcsSocket = bindSocket(cfg.gcsPort, "gcs-port");
        this.relaySocket = bindSocket(cfg.relayPort, "relay-port");
    }

    /** 绑定端口，失败捕获 BindException 打印并 exit(1)。 */
    private static DatagramSocket bindSocket(int port, String label) throws IOException {
        try {
            return new DatagramSocket(new InetSocketAddress(port));
        } catch (BindException e) {
            log.error("[mesh-relay] bind failed for {}={}: {}", label, port, e.getMessage());
            System.exit(1);
            return null; // 不可达
        }
    }

    /**
     * 启动 uplinkLoop/wirePump/statsLoop 三个 daemon 线程，主线程跑 downlinkLoop 阻塞。
     */
    void run() throws IOException {
        // P2 #20：运行时控制通道（默认关闭）。中继模式上下行可能是不同画像，
        // 控制面按 direction 分别改；前端默认 both。
        // 安全画像同样支持（P2 #20 收尾）：但语义层必须换成 SecurityControl——
        // 对安全链路挂 LinkSimControl 等于摆一排"改了没人看"的十元组假旋钮
        // （延迟/丢包/带宽/分区在安全链路上一个都不存在）。
        if (cfg.controlPort > 0) {
            try {
                LinkSimControlServer control;
                if (cfg.uplink.isSecurityProfile() || cfg.downlink.isSecurityProfile()) {
                    // 任一侧是安全画像就走安全语义层；混合画像（一侧安全一侧普通）
                    // 是测试构造，此时控制面只表达安全侧的可调参数，另一半如实记日志，
                    // 不假装能两层同屏。
                    SecurityImpairmentEngine sec = secUplink != null ? secUplink : secDownlink;
                    if (secUplink != null && secDownlink == null
                            || secUplink == null && secDownlink != null) {
                        log.warn("[mesh-relay] mixed security/impairment profiles: control exposes"
                                + " the security side only (tamperRate); {}", cfg.uplink.name);
                    }
                    control = new LinkSimControlServer(cfg.controlPort, new SecurityControl(sec));
                } else {
                    control = new LinkSimControlServer(cfg.controlPort,
                            new LinkSimControl(cfg.uplink, uplink, cfg.downlink, downlink, false));
                }
                control.start();
                log.info("[mesh-relay] control listening on http://127.0.0.1:{}/profile", control.port());
            } catch (IOException e) {
                log.error("[mesh-relay] control port {} bind failed: {}", cfg.controlPort, e.getMessage());
                System.exit(1);
                return;
            }
        }

        Thread up = new Thread(this::uplinkLoop, "relay-uplink");
        up.setDaemon(true);
        up.start();

        Thread wire = new Thread(this::wirePump, "relay-wire");
        wire.setDaemon(true);
        wire.start();

        Thread stats = new Thread(this::statsLoop, "relay-stats");
        stats.setDaemon(true);
        stats.start();

        log.info("[mesh-relay] running (Ctrl+C to stop)");
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
                // 安全画像：使用 SecurityImpairmentEngine.verdict() 替代 ImpairmentEngine.verdict()
                if (secUplink != null) {
                    SecurityImpairmentEngine.SecurityAction action = secUplink.verdict(data);
                    if (action == SecurityImpairmentEngine.SecurityAction.TAMPER) {
                        data = SecurityImpairmentEngine.applyTamper(data);
                    } else if (action == SecurityImpairmentEngine.SecurityAction.STRIP_SIGNATURE) {
                        data = SecurityImpairmentEngine.applyStripSignature(data);
                    }
                    // FORWARD: data 不变，正常转发
                    wire.add(new ScheduledPacket(System.currentTimeMillis(),
                            seq.incrementAndGet(), data, cfg.droneAddr, true));
                    continue;
                }
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
            // 安全画像：使用 SecurityImpairmentEngine.verdict() 替代 ImpairmentEngine.verdict()
            if (secDownlink != null) {
                SecurityImpairmentEngine.SecurityAction action = secDownlink.verdict(data);
                if (action == SecurityImpairmentEngine.SecurityAction.TAMPER) {
                    data = SecurityImpairmentEngine.applyTamper(data);
                } else if (action == SecurityImpairmentEngine.SecurityAction.STRIP_SIGNATURE) {
                    data = SecurityImpairmentEngine.applyStripSignature(data);
                }
                // FORWARD: data 不变，正常转发
                wire.add(new ScheduledPacket(System.currentTimeMillis(),
                        seq.incrementAndGet(), data, cfg.gcsAddr, false));
                continue;
            }
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
     * 扩展为按 {@code toDrone} 选 socket。
     * <p>{@code IOException} 丢弃该包不中断循环（FR 5.2.3-2）。
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
                out.send(new DatagramPacket(p.data, p.data.length, p.to));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (IOException e) {
                // 目标暂时不可达：丢弃该包（FR 5.2.3-2，真实链路也是这样）
            }
        }
    }

    /**
     * 统计循环（daemon）：10s 周期输出上下行统计（FR-16, DFX 4.4）。
     * <p>
     * 安全画像方向输出安全损伤统计（fwd/tampered/stripped），
     * 非安全画像方向输出链路损伤统计（fwd/drop/avgDelay）。
     */
    private void statsLoop() {
        while (true) {
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                return;
            }
            if (secUplink != null) {
                log.info("[mesh-relay] security up:   {}", secUplink.stats());
            } else {
                log.info("[mesh-relay] up(gcs->drone)   {}", uplink.stats());
            }
            if (secDownlink != null) {
                log.info("[mesh-relay] security down: {}", secDownlink.stats());
            } else {
                log.info("[mesh-relay] down(drone->gcs) {}", downlink.stats());
            }
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

    // ====================================================================
    // 为什么没有多跳（2026-10-12 拆除了曾经的 M5 多跳扩展）
    //
    // 原实现在帧<b> payload 尾部追加 1 字节 hopCount</b>，并用
    // `多跳开关 → extractHopCount → 递减 → writeHopCount` 这条路运转。
    // 2026-10-12 用真实 MAVLink v2 帧实测后确认它在真实流量上<b>不可能工作</b>：
    //
    //   1. extractHopCount 取"帧的最后一个字节"。MAVLink v2 帧末两字节是 CRC，
    //      所以读到的是 CRC 字节而非跳数。实测一帧签名的 Heartbeat：CRC 末字节
    //      106 > MAX_HOPS=15 ⇒ 帧在入口就被判"越界"直接丢弃。
    //   2. 即便跳过第 1 条，writeHopCount 对 >=13B 的帧"原地覆写最后一字节"，
    //      而所有真实帧都 >=13B ⇒ 覆写的正是 CRC。实测确认改的就是帧尾第 0 字节。
    //   3. 追加尾字节的方案（而非覆写）在 link-sim 内部能自洽，但链路对端
    //      （cloud-backend UdpGateway / drone-sim）并不知道尾字节的存在，
    //      也就不会剥掉它 ⇒ 端到端 mesh 仍然不成立。
    //
    // 结论：MAVLink v2 帧<b>本身没有 TTL/hop 字段</b>，要在真实链路上做多跳，
    // 必须引入双方都认识的封装（或走 payload 里带 hopCount 的 mesh 路由消息，
    // 如 MeshRouter 的 30030–30034 —— 那条路已经在跑，与本文件无关）。
    // 在协议缺口补上之前，保留一个"接通即断链"的开关比没有开关更危险：
    // 它让 README 敢于宣称多跳可用，并且曾产生 11 个基于合成字节数组
    // （new byte[13]）的假绿测试。
    //
    // 若将来要恢复多跳：先定封装协议（链路两端都要会封/解包），
    // 并且测试必须用**真实帧**而非合成数组——那 11 个测试全绿也没能
    // 发现上面第 1、2 条，原因就在于此。
    // ====================================================================
}

package io.aerofleet.linksim;

import io.aerofleet.mavlink.MavlinkFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MAVLink/UDP 链路损伤代理：插在飞机与 GCS/云端之间的一个透明双向 UDP 管道。
 *
 *   drone (bindIp:dronePort) <----> [link-sim:proxyPort] <----> gcs (:proxyPort 收到即转发)
 *
 * 用法：飞机和 GCS 都把对方地址写成 link-sim 的 proxyPort；link-sim 收到任一侧
 * 的包后，经损伤引擎判决（丢弃 or 延迟 X ms）再转发给另一侧。两个方向各一套
 * 独立的损伤引擎实例（真实链路上下行不对称：下行的视频/遥测带宽是瓶颈，
 * 上行的命令小而关键）。
 *
 * 源地址学习：第一次收到 A 侧的包就记住 A（每个方向一个"对端"），之后
 * 只在这两个已知对端之间转发，天然拒绝第三方流量混入。
 */
public final class LinkSimMain {

    private static final Logger log = LoggerFactory.getLogger(LinkSimMain.class);

    public static void main(String[] args) throws Exception {
        // 预扫描 --relay：命中走中继路径，否则原点对点路径逐行不变（FR-01, DFX 4.5）
        if (containsFlag(args, "--relay")) {
            RelayConfig cfg = RelayConfig.parse(args); // 内部校验失败 exit(1)
            log.info("[mesh-relay] mode=relay gcs-port={} relay-port={} uplink={} downlink={}",
                    cfg.gcsPort, cfg.relayPort, cfg.uplink.name, cfg.downlink.name);
            log.info("[mesh-relay] gcsAddr=unlearned droneAddr=unlearned");
            new RelayNode(cfg).run();
            return;
        }
        // === 以下原点对点路径完全不变（FR 4.5 兼容性）===
        String profileName = "lte";
        int proxyPort = 14600;
        int dronePort = 14540;
        String droneIp = "127.0.0.1";
        boolean envCoupled = false;  // M0b 雨衰叠加开关（FR-15）

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--profile") && i + 1 < args.length) {
                profileName = args[++i];
            } else if (a.equals("--port") && i + 1 < args.length) {
                proxyPort = Integer.parseInt(args[++i]);
            } else if (a.equals("--drone-ip") && i + 1 < args.length) {
                droneIp = args[++i];
            } else if (a.equals("--drone-port") && i + 1 < args.length) {
                dronePort = Integer.parseInt(args[++i]);
            } else if (a.equals("--list")) {
                log.info("profiles: {}", LinkProfile.names());
                return;
            } else if (a.equals("--help")) {
                usage();
                return;
            } else if (a.equals("--env-coupled")) {
                envCoupled = true;
            }
        }

        LinkProfile profile = LinkProfile.of(profileName);
        if (profile == null) {
            log.warn("[link-sim] unknown profile: {}", profileName);
            usage();
            System.exit(1);
            return;
        }

        log.info("[link-sim] AeroFleet link simulator");
        log.info("[link-sim] profile={} delay={}+/-{}ms drop(g/b)={}/{} rate={}B/s{}",
                profile.name, profile.delayMs, profile.jitterMs, profile.pGoodDrop, profile.pBadDrop,
                (long) profile.rateBytesPerSec,
                profile.partitionDownSec > 0
                        ? " partition=" + profile.partitionUpSec + "s/" + profile.partitionDownSec + "s"
                        : "");
        log.info("[link-sim] proxy={} -> drone={}:{}", proxyPort, droneIp, dronePort);
        if (envCoupled) {
            log.info("[link-sim] env-coupled: ON (rain attenuation overlay enabled)");
        }

        new LinkSimMain(profile, proxyPort, new InetSocketAddress(droneIp, dronePort), envCoupled).run();
    }

    private static void usage() {
        log.info("[link-sim] usage: link-sim --profile P [--port N] [--drone-ip IP] [--drone-port N]");
        log.info("[link-sim]   --profile   {}", LinkProfile.names());
        log.info("[link-sim]   --port      proxy bind port (default 14600)");
        log.info("[link-sim]   --drone-ip  drone side address (default 127.0.0.1)");
        log.info("[link-sim]   --drone-port drone side port (default 14540)");
        log.info("[link-sim]   --env-coupled  enable rain attenuation overlay (M0b)");
        // 追加 relay 模式参数说明（FR-01, DFX 4.4 配置可追溯）
        RelayConfig.usage();
    }

    /** 预扫描命令行是否含指定 flag（用于 --relay 模式分流，不消费参数）。 */
    private static boolean containsFlag(String[] args, String flag) {
        for (String a : args) {
            if (a.equals(flag)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------

    private final LinkProfile profile;
    private final int proxyPort;
    private final InetSocketAddress droneAddr;
    /** M0b 雨衰叠加开关（FR-15）。 */
    private final boolean envCoupled;
    /** GCS/云端侧学到的对端（谁往 proxy 发过包，转发目标就是它）。 */
    private volatile InetSocketAddress gcsAddr;
    /** 延迟调度：到期任务按时间出队发送。 */
    private final PriorityBlockingQueue<ScheduledPacket> wire =
            new PriorityBlockingQueue<>(256);
    private final AtomicLong seq = new AtomicLong();

    /** 带方向的待发数据包。 */
    private record ScheduledPacket(long dueAtMs, long order, byte[] data,
                                  InetSocketAddress to, boolean toDrone) implements
            Comparable<ScheduledPacket> {
        @Override
        public int compareTo(ScheduledPacket o) {
            int c = Long.compare(dueAtMs, o.dueAtMs);
            return c != 0 ? c : Long.compare(order, o.order);
        }
    }

    private LinkSimMain(LinkProfile profile, int proxyPort, InetSocketAddress droneAddr,
                         boolean envCoupled) {
        this.profile = profile;
        this.proxyPort = proxyPort;
        this.droneAddr = droneAddr;
        this.envCoupled = envCoupled;
    }

    private void run() throws Exception {
        // 上下行独立损伤：上行 = GCS -> drone（命令，小包）；下行 = drone -> GCS（遥测大头）
        ImpairmentEngine uplinkBase = profile.engine();
        ImpairmentEngine downlinkBase = profile.engine();
        // M0b 雨衰叠加（FR-15）：envCoupled 时用 EnvAwareImpairmentEngine 包装
        EnvAwareImpairmentEngine uplink = envCoupled
                ? new EnvAwareImpairmentEngine(uplinkBase, profile.delayMs) : null;
        EnvAwareImpairmentEngine downlink = envCoupled
                ? new EnvAwareImpairmentEngine(downlinkBase, profile.delayMs) : null;

        try (DatagramSocket socket = new DatagramSocket(new InetSocketAddress(proxyPort))) {
            Thread pump = new Thread(() -> wirePump(socket), "link-wire");
            pump.setDaemon(true);
            pump.start();

            Thread stats = new Thread(() -> statsLoop(uplinkBase, downlinkBase, uplink, downlink),
                    "link-stats");
            stats.setDaemon(true);
            stats.start();

            byte[] buf = new byte[2048];
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            log.info("[link-sim] running (Ctrl+C to stop)");
            while (true) {
                packet.setLength(buf.length);
                socket.receive(packet);
                byte[] data = java.util.Arrays.copyOf(buf, packet.getLength());
                InetSocketAddress from = (InetSocketAddress) packet.getSocketAddress();
                boolean fromDrone = from.equals(droneAddr);

                if (fromDrone) {
                    // 下行：drone -> gcs（转发给已学到的 GCS 对端）
                    if (gcsAddr == null) continue;
                    // M0b 雨衰叠加：envCoupled 时从 ENVIRONMENT_STATUS 帧更新环境状态
                    if (envCoupled) {
                        updateEnvFromFrame(data, downlink);
                    }
                    long delay = envCoupled ? downlink.verdict(data.length) : downlinkBase.verdict(data.length);
                    if (delay >= 0) {
                        wire.add(new ScheduledPacket(System.currentTimeMillis() + delay,
                                seq.incrementAndGet(), data, gcsAddr, false));
                    }
                } else {
                    // 上行：gcs -> drone；同时学习 GCS 对端地址
                    gcsAddr = from;
                    long delay = envCoupled ? uplink.verdict(data.length) : uplinkBase.verdict(data.length);
                    if (delay >= 0) {
                        wire.add(new ScheduledPacket(System.currentTimeMillis() + delay,
                                seq.incrementAndGet(), data, droneAddr, true));
                    }
                }
            }
        }
    }

    private void wirePump(DatagramSocket socket) {
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
                socket.send(new DatagramPacket(p.data, p.data.length, p.to));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (IOException e) {
                // 目标暂时不可达：丢弃该包（真实链路也是这样）
            }
        }
    }

    private void statsLoop(ImpairmentEngine up, ImpairmentEngine down,
                           EnvAwareImpairmentEngine upEnv, EnvAwareImpairmentEngine downEnv) {
        while (true) {
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                return;
            }
            log.info("[link-sim] up(gcs->drone)   {}", up.stats());
            log.info("[link-sim] down(drone->gcs) {}", down.stats());
            if (upEnv != null) {
                log.info("[link-sim] up rain   {}", upEnv.rainStats());
                log.info("[link-sim] down rain {}", downEnv.rainStats());
            }
        }
    }

    /**
     * M0b 雨衰叠加：从 ENVIRONMENT_STATUS（msgId=422）帧松耦合读取 weather + rainRate，
     * 更新下行 EnvAwareImpairmentEngine 环境状态。
     * <p>
     * MAVLink v2 帧布局：STX(1) | LEN(1) | INC(1) | COMPAT(1) | SEQ(1) | SID(1) | CID(1) | MSGID(3B LE) | PAYLOAD | CRC(2)。
     * ENVIRONMENT_STATUS payload：偏移1=weather(u8)，偏移2=rainRate(u8)。
     * 非环境帧静默跳过（松耦合，不依赖完整 MAVLink 解析）。
     */
    private static void updateEnvFromFrame(byte[] data, EnvAwareImpairmentEngine downlink) {
        // 最小帧长度：STX + LEN + INC + COMPAT + SEQ + SID + CID + MSGID(3) + PAYLOAD(13) + CRC(2) = 25
        if (data.length < 25 || data[0] != MavlinkFrame.STX_V2) {
            return;
        }
        int msgId = (data[7] & 0xFF) | ((data[8] & 0xFF) << 8) | ((data[9] & 0xFF) << 16);
        if (msgId != 422) {  // ENVIRONMENT_STATUS.ID
            return;
        }
        int payloadLen = data[1] & 0xFF;
        if (payloadLen < 3) {
            return;
        }
        int weather = data[10 + 1] & 0xFF;   // payload 偏移 1 = weather
        int rainRate = data[10 + 2] & 0xFF;  // payload 偏移 2 = rainRate
        downlink.updateEnvironment(weather, rainRate);
    }
}

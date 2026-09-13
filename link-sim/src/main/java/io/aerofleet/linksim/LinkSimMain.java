package io.aerofleet.linksim;

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

    public static void main(String[] args) throws Exception {
        String profileName = "lte";
        int proxyPort = 14600;
        int dronePort = 14540;
        String droneIp = "127.0.0.1";

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
                System.out.println("profiles: " + LinkProfile.names());
                return;
            } else if (a.equals("--help")) {
                usage();
                return;
            }
        }

        LinkProfile profile = LinkProfile.of(profileName);
        if (profile == null) {
            System.out.println("[link-sim] unknown profile: " + profileName);
            usage();
            System.exit(1);
            return;
        }

        System.out.println("[link-sim] AeroFleet link simulator");
        System.out.println("[link-sim] profile=" + profile.name
                + " delay=" + profile.delayMs + "+/-" + profile.jitterMs + "ms"
                + " drop(g/b)=" + profile.pGoodDrop + "/" + profile.pBadDrop
                + " rate=" + (long) profile.rateBytesPerSec + "B/s"
                + (profile.partitionDownSec > 0
                        ? " partition=" + profile.partitionUpSec + "s/" + profile.partitionDownSec + "s"
                        : ""));
        System.out.println("[link-sim] proxy=" + proxyPort
                + " -> drone=" + droneIp + ":" + dronePort);

        new LinkSimMain(profile, proxyPort, new InetSocketAddress(droneIp, dronePort)).run();
    }

    private static void usage() {
        System.out.println("[link-sim] usage: link-sim --profile P [--port N] [--drone-ip IP] [--drone-port N]");
        System.out.println("[link-sim]   --profile   " + LinkProfile.names());
        System.out.println("[link-sim]   --port      proxy bind port (default 14600)");
        System.out.println("[link-sim]   --drone-ip  drone side address (default 127.0.0.1)");
        System.out.println("[link-sim]   --drone-port drone side port (default 14540)");
    }

    // ------------------------------------------------------------------

    private final LinkProfile profile;
    private final int proxyPort;
    private final InetSocketAddress droneAddr;
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

    private LinkSimMain(LinkProfile profile, int proxyPort, InetSocketAddress droneAddr) {
        this.profile = profile;
        this.proxyPort = proxyPort;
        this.droneAddr = droneAddr;
    }

    private void run() throws Exception {
        // 上下行独立损伤：上行 = GCS -> drone（命令，小包）；下行 = drone -> GCS（遥测大头）
        ImpairmentEngine uplink = profile.engine();
        ImpairmentEngine downlink = profile.engine();

        try (DatagramSocket socket = new DatagramSocket(new InetSocketAddress(proxyPort))) {
            Thread pump = new Thread(() -> wirePump(socket), "link-wire");
            pump.setDaemon(true);
            pump.start();

            Thread stats = new Thread(() -> statsLoop(uplink, downlink), "link-stats");
            stats.setDaemon(true);
            stats.start();

            byte[] buf = new byte[2048];
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            System.out.println("[link-sim] running (Ctrl+C to stop)");
            while (true) {
                packet.setLength(buf.length);
                socket.receive(packet);
                byte[] data = java.util.Arrays.copyOf(buf, packet.getLength());
                InetSocketAddress from = (InetSocketAddress) packet.getSocketAddress();
                boolean fromDrone = from.equals(droneAddr);

                if (fromDrone) {
                    // 下行：drone -> gcs（转发给已学到的 GCS 对端）
                    if (gcsAddr == null) continue;
                    long delay = downlink.verdict(data.length);
                    if (delay >= 0) {
                        wire.add(new ScheduledPacket(System.currentTimeMillis() + delay,
                                seq.incrementAndGet(), data, gcsAddr, false));
                    }
                } else {
                    // 上行：gcs -> drone；同时学习 GCS 对端地址
                    gcsAddr = from;
                    long delay = uplink.verdict(data.length);
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

    private void statsLoop(ImpairmentEngine up, ImpairmentEngine down) {
        while (true) {
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                return;
            }
            System.out.println("[link-sim] up(gcs->drone)   " + up.stats());
            System.out.println("[link-sim] down(drone->gcs) " + down.stats());
        }
    }
}

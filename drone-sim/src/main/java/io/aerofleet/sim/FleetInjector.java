package io.aerofleet.sim;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.messages.Heartbeat;
import io.aerofleet.mavlink.messages.TaskStatusMsg;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 容量基线的 MAVLink 负载注入器：一个进程模拟 N 架无人机。
 * <p>
 * 为什么不用 N 个 {@code drone-sim} 进程：那是 N 个 JVM（每个 ~50MB 常驻 + 各自 20Hz 物理
 * 循环），压测 100 架时光注入端就吃掉几个 GB，测出来的是「注入端资源耗尽」而不是
 * 「平台容量」。这里只发帧、不算物理，一个进程足够。
 * <p>
 * 发两类帧，分别打两条不同的后端路径：
 * <ul>
 *   <li>{@link Heartbeat}（1Hz/架）—— 只为让设备在 {@code DeviceRegistry} 保持 online，
 *       从而进入 {@code TelemetryPusher} 的 1Hz 聚合推送（N×2 条/秒的批量帧）。</li>
 *   <li>{@link TaskStatusMsg}（{@code --hz} 帧/秒/架）—— 命中 {@code WS_TYPE_MAP}，
 *       走 {@code onMavlinkMessage} 的**逐帧**扇出路径（帧里带 {@code timestamp}，可算端到端时延）。</li>
 * </ul>
 * 两条路径的负载模型不同，必须分开压：聚合推送压的是「batch 大小 × 客户端数」，
 * 逐帧推送压的是「帧率 × 客户端数」。
 * <p>
 * 本类**不测时延**，只负责制造负载——时延测量在 {@link WsProbe}，两进程隔离，
 * 避免注入循环的 CPU 占用污染测量。
 */
final class FleetInjector {

    /** 单帧 payload 上限（MAVLink v2）。 */
    private static final int MAX_PAYLOAD = 255;
    /**
     * 单实例可模拟的设备数上限 = 254。
     * <p>
     * 这不是「协议能编多少」（uint8 是 255），而是**平台架构的上限**：
     * {@code DeviceRegistry} 用 {@code Map<Integer, DroneSnapshot>} 以 systemId 为唯一键，
     * {@code provision()} 显式校验 {@code 1..254}；{@code UdpGateway.GCS_SYSID = 255}
     * 留给地面站，且 {@code componentId} 完全不参与设备区分。
     * <p>
     * 所以注入超过 254 个 sysid 只会被静默截断/串号，测出来的是「254 架 + 重复 ID」，
     * 却看起来像一次成功的大机队测量——比没有数据更糟。要压更大机队，
     * 必须先改平台（注册表键加 linkId，或按 tenant 分片多实例），本工具不做。
     */
    private static final int MAX_DEVICE_SYSID = 254;

    private final int droneCount;
    private final int hz;
    private final InetAddress target;
    private final int port;
    private final long durationMs;

    private final AtomicLong framesSent = new AtomicLong();
    /** 只统计 TaskStatusMsg（高频帧），用于算「实际 Hz/架」——Heartbeat 是 1Hz 固定开销。 */
    private final AtomicLong taskSent = new AtomicLong();
    private volatile boolean running = true;
    /** 起始 sysid（含）。多进程分摊同一机队时，每个进程负责一段连续 sysid。 */
    private final int sysidStart;

    /** 进度输出间隔：便于事后区分「注入器发不满」与「服务端处理不了」。 */
    private static final long REPORT_INTERVAL_MS = 10_000L;

    FleetInjector(int droneCount, int hz, String targetHost, int port, long durationMs)
            throws IOException {
        this(droneCount, hz, targetHost, port, durationMs, 1);
    }

    /**
     * @param sysidStart 本进程负责的第一架 sysid（含）。多进程分摊同一机队时使用：
     *                   单线程注入在 254 架时实测只能跑到约 10 Hz/架，要压更高帧率
     *                   必须多进程各管一段 sysid，否则测的是注入器上限而非服务端容量。
     */
    FleetInjector(int droneCount, int hz, String targetHost, int port, long durationMs,
                  int sysidStart) throws IOException {
        if (droneCount <= 0) {
            throw new IllegalArgumentException("--drones 必须 > 0");
        }
        if (hz <= 0) {
            throw new IllegalArgumentException("--hz 必须 > 0");
        }
        if (hz > 100) {
            // 后端 aerofleet.udp.max-frame-rate-per-sysid 默认 100，超了会被限速桶丢掉，
            // 那样压的就不是平台容量而是限速器——直接拒绝，避免测出一个假上限。
            throw new IllegalArgumentException(
                    "--hz 不得超过 100（后端 max-frame-rate-per-sysid 默认 100，超限会被限速桶丢弃）");
        }
        if (droneCount > MAX_DEVICE_SYSID) {
            // 上限来自平台架构而非协议：DeviceRegistry 以 sysid 为唯一键、provision 校验 1..254，
            // GCS_SYSID=255 留给地面站，componentId 不参与区分。
            // 超限会被静默截断成重复 ID，测出「254 架 + 串号」而非大机队。
            throw new IllegalArgumentException(
                    "--drones 不得超过 " + MAX_DEVICE_SYSID
                            + "（平台架构上限：DeviceRegistry 以 systemId 为唯一键，"
                            + "provision 校验 1..254，GCS_SYSID=255 保留；"
                            + "要压更大机队需改注册表键或按 tenant 分片多实例）");
        }
        if (sysidStart < 1 || sysidStart + droneCount - 1 > MAX_DEVICE_SYSID) {
            throw new IllegalArgumentException(
                    "--sysid-start " + sysidStart + " + " + droneCount
                            + " 架超出合法 sysid 区间 1.." + MAX_DEVICE_SYSID);
        }
        this.droneCount = droneCount;
        this.hz = hz;
        this.target = InetAddress.getByName(targetHost);
        this.port = port;
        this.durationMs = durationMs;
        this.sysidStart = sysidStart;
    }

    long framesSent() {
        return framesSent.get();
    }

    long taskSent() {
        return taskSent.get();
    }

    int droneCount() {
        return droneCount;
    }

    /**
     * 按固定节拍发送，直到时长耗尽或被 {@link #stop()} 打断。
     * <p>
     * 节拍取 10ms：单 tick 内最多发 {@code droneCount × hz / 100} 帧，100 架 × 10Hz = 10 帧/tick，
     * 单线程足够。用「每架各自的下次到期时间」而不是「tick 计数取模」，避免 N 与 hz 不整除时
     * 实际频率偏离设定值——容量报告里的负载必须是真的。
     */
    void run() throws IOException {
        long[] nextTask = new long[droneCount];
        long[] nextHeartbeat = new long[droneCount];
        long now = System.currentTimeMillis();
        long deadline = durationMs > 0 ? now + durationMs : Long.MAX_VALUE;
        long intervalMs = Math.max(1L, 1000L / hz);

        for (int i = 0; i < droneCount; i++) {
            // 错开首帧：否则 N 架会在同一毫秒齐发，制造后端看不到的瞬时尖峰
            nextTask[i] = now + (i * intervalMs / Math.max(1, droneCount));
            nextHeartbeat[i] = now + (i * 1000L / Math.max(1, droneCount));
        }

        long lastReport = 0L;

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(0);
            while (running && System.currentTimeMillis() < deadline) {
                long tickStart = System.currentTimeMillis();
                for (int i = 0; i < droneCount && running; i++) {
                    int sysid = sysidStart + i;
                    if (tickStart >= nextHeartbeat[i]) {
                        Heartbeat hb = new Heartbeat(0, 2 /*MAV_TYPE_QUADROTOR*/,
                                12 /*MAV_AUTOPILOT_PX4*/, 0, 4 /*MAV_STATE_ACTIVE*/);
                        send(socket, sysid, hb.encode(), Heartbeat.ID, Heartbeat.CRC_EXTRA);
                        nextHeartbeat[i] = tickStart + 1000L;
                    }
                    if (tickStart >= nextTask[i]) {
                        // 进度按 sysid 做相位差，避免所有机上报完全相同的值（缓存/去重会失真）
                        int progress = (int) ((tickStart / 100L + sysid * 7) % 100);
                        TaskStatusMsg task = new TaskStatusMsg(1000L + sysid, tickStart,
                                sysid, 2 /*执行中*/, progress);
                        send(socket, sysid, task.encode(), TaskStatusMsg.ID, TaskStatusMsg.CRC_EXTRA);
                        taskSent.incrementAndGet();
                        nextTask[i] = tickStart + intervalMs;
                    }
                }
                long loopStart = System.currentTimeMillis() - now;
                if (loopStart - lastReport >= REPORT_INTERVAL_MS) {
                    lastReport = loopStart;
                    double secs = loopStart / 1000.0;
                    long task = taskSent.get();
                    // 实际 Hz/架是判断「注入器是否发得满」的唯一依据：
                    // 探针测到的速率若低于目标，必须先排除注入器自身瓶颈，否则会把
                    // 工具上限误报成服务端容量上限。
                    System.out.printf(
                            "[inject] t=%ds 高频帧=%d 实际 %.2f Hz/架（目标 %d）心跳=%d%n",
                            loopStart / 1000, task, secs > 0 ? task / secs / droneCount : 0,
                            hz, framesSent.get() - task);
                    System.out.flush();
                }
                long elapsed = System.currentTimeMillis() - tickStart;
                long sleep = 10L - elapsed;
                if (sleep > 0) {
                    try {
                        Thread.sleep(sleep);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }

    private void send(DatagramSocket socket, int sysid, byte[] payload, int msgId, int crcExtra)
            throws IOException {
        if (payload.length > MAX_PAYLOAD) {
            throw new IllegalStateException("payload 超长: " + payload.length);
        }
        MavlinkFrame frame = MavlinkFrame.of(sysid, 1 /*MAV_COMP_ID_AUTOPILOT1*/,
                (int) (framesSent.get() & 0xFF), msgId, crcExtra, payload);
        byte[] raw = frame.encodeV2();
        socket.send(new DatagramPacket(raw, raw.length, target, port));
        framesSent.incrementAndGet();
    }

    void stop() {
        running = false;
    }
}

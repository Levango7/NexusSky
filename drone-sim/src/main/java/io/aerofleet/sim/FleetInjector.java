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

    private final int droneCount;
    private final int hz;
    private final InetAddress target;
    private final int port;
    private final long durationMs;

    private final AtomicLong framesSent = new AtomicLong();
    private volatile boolean running = true;

    FleetInjector(int droneCount, int hz, String targetHost, int port, long durationMs)
            throws IOException {
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
        this.droneCount = droneCount;
        this.hz = hz;
        this.target = InetAddress.getByName(targetHost);
        this.port = port;
        this.durationMs = durationMs;
    }

    long framesSent() {
        return framesSent.get();
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

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(0);
            while (running && System.currentTimeMillis() < deadline) {
                long tickStart = System.currentTimeMillis();
                for (int i = 0; i < droneCount && running; i++) {
                    int sysid = i + 1;   // sysid 从 1 开始（0 是 GCS 惯例）
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
                        nextTask[i] = tickStart + intervalMs;
                    }
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

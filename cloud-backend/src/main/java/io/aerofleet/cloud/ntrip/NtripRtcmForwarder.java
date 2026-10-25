package io.aerofleet.cloud.ntrip;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import io.aerofleet.cloud.gateway.UdpGateway;
import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.messages.GpsRtcmData;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * NTRIP 差分改正数转发服务（F6 / GAPS §十.10-3）：定时拉流 → 切帧 → 分片 → 发 {@code GPS_RTCM_DATA}。
 *
 * <p>装配：{@code @ConditionalOnProperty(aerofleet.ntrip.enabled=true)}，缺省**关闭**——
 * 没有 caster 配置就拉起一个空转线程没有任何意义。host/port/mountpoint 必配，
 * user/password 视 caster 而定（匿名源留空）。
 *
 * <p>通路：{@link NtripClient}（协议层，#166）拿到原始字节流 → {@link Rtcm3Framer}
 * （CRC-24Q 校验，坏帧直接丢）→ 本类切成 ≤180 字节的 {@link GpsRtcmData}（超 720 字节的
 * RTCM 帧发不了——分片号只有 2 bit，最多 4×180，直接丢弃并计数，**绝不静默截断**：
 * 半条改正数比没有更糟，飞控会拿半条去解算）→ 经 {@link UdpGateway} 逐机下发
 * （只发**在线**机；未配置 --rtk 的机收到也不抬升 fix_type——上界语义，#169——所以广播无害）。
 *
 * <p>线程模型：单 worker 线程串行做 connect/read/forward，无共享可变状态；
 * 断线按 {@code reconnect-delay-ms} 退避重连（caster 掉线、read 超时、EOF 同路处理），
 * 重连时 framer 重建（跨连接的字节流没有边界连续性，旧 framer 的半帧缓冲必须丢）。
 */
@Component
@ConditionalOnProperty(prefix = "aerofleet.ntrip", name = "enabled", havingValue = "true")
public class NtripRtcmForwarder implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(NtripRtcmForwarder.class);

    /** 单条 GPS_RTCM_DATA 里 data 的容量（官方布局，见 GpsRtcmData.DATA_CAPACITY）。 */
    static final int FRAGMENT_CAPACITY = 180;
    /** 分片号只有 2 bit（0~3）⇒ 一条 RTCM 最多 4×180=720 字节，超出发不了。 */
    static final int MAX_RTCM_BYTES = FRAGMENT_CAPACITY * 4;

    private final UdpGateway gateway;
    private final DeviceRegistry registry;
    private final String host;
    private final int port;
    private final String mountpoint;
    private final String user;
    private final String password;
    private final long reconnectDelayMs;

    private volatile boolean running;
    private Thread worker;
    private final AtomicLong reconnects = new AtomicLong();
    private final AtomicLong bytesReceived = new AtomicLong();
    private final AtomicLong rtcmFrames = new AtomicLong();
    private final AtomicLong messagesForwarded = new AtomicLong();
    private final AtomicLong framesDroppedTooLarge = new AtomicLong();
    private final AtomicLong forwardsSkippedNoTarget = new AtomicLong();
    /** 5 bit 序号（0~31）：同一条 RTCM 的各分片共用一个，跨消息递增取模。 */
    private final AtomicInteger sequence = new AtomicInteger();
    /** MAVLink 帧序号：下发用，与各控制器同款自增。 */
    private final AtomicInteger frameSequence = new AtomicInteger();

    @Autowired
    public NtripRtcmForwarder(UdpGateway gateway,
                              DeviceRegistry registry,
                              @Value("${aerofleet.ntrip.host}") String host,
                              @Value("${aerofleet.ntrip.port}") int port,
                              @Value("${aerofleet.ntrip.mountpoint}") String mountpoint,
                              @Value("${aerofleet.ntrip.username:}") String user,
                              @Value("${aerofleet.ntrip.password:}") String password,
                              @Value("${aerofleet.ntrip.reconnect-delay-ms:5000}") long reconnectDelayMs) {
        this.gateway = gateway;
        this.registry = registry;
        this.host = host;
        this.port = port;
        this.mountpoint = mountpoint;
        this.user = user;
        this.password = password;
        this.reconnectDelayMs = reconnectDelayMs;
    }

    @PostConstruct
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        worker = new Thread(this::runLoop, "ntrip-rtcm-forwarder");
        worker.setDaemon(true);
        worker.start();
        log.info("NTRIP 差分转发已启动：{}:{}/{}（重连退避 {}ms；目标=全部在线机）",
                host, port, mountpoint, reconnectDelayMs);
    }

    @Override
    @PreDestroy
    public synchronized void close() {
        running = false;
        if (worker != null) {
            worker.interrupt();
        }
    }

    private void runLoop() {
        Rtcm3Framer framer = new Rtcm3Framer();
        NtripClient client = new NtripClient(host, port, mountpoint, user, password);
        byte[] buf = new byte[8192];
        while (running) {
            try {
                client.connect();
                log.info("NTRIP caster 已连接：{}:{}/{}", host, port, mountpoint);
                while (running) {
                    int n = client.read(buf);
                    if (n == -1) {
                        throw new IOException("caster 关闭了连接（EOF）");
                    }
                    bytesReceived.addAndGet(n);
                    byte[] chunk = new byte[n];
                    System.arraycopy(buf, 0, chunk, 0, n);
                    for (byte[] rtcm : framer.feed(chunk)) {
                        rtcmFrames.incrementAndGet();
                        forward(rtcm);
                    }
                }
            } catch (IOException e) {
                if (!running) {
                    return;   // 关停路径：别把 close() 咬出的异常当故障刷日志
                }
                log.warn("NTRIP 链路中断，{}ms 后重连（累计重连 {}）：{}",
                        reconnectDelayMs, reconnects.incrementAndGet(), e.getMessage());
            }
            // 断线后字节流边界作废，framer 必须重建
            framer = new Rtcm3Framer();
            client = new NtripClient(host, port, mountpoint, user, password);
            sleepQuietly(reconnectDelayMs);
        }
    }

    /** 把一条 CRC 校验通过的 RTCM 帧分片后下发给全部在线机。 */
    void forward(byte[] rtcm) {
        List<Integer> targets = new ArrayList<>();
        for (DroneSnapshot s : registry.all()) {
            if (s.online) {
                targets.add(s.sysid);
            }
        }
        if (targets.isEmpty()) {
            forwardsSkippedNoTarget.incrementAndGet();
            return;
        }
        int seq = sequence.getAndIncrement() & 0x1F;
        List<GpsRtcmData> messages = fragment(rtcm, seq);
        if (messages.isEmpty()) {
            framesDroppedTooLarge.incrementAndGet();
            log.warn("RTCM 帧超过 4×180={} 字节上限，丢弃（分片号只有 2 bit，无法分片；绝不静默截断）",
                    MAX_RTCM_BYTES);
            return;
        }
        for (int sysid : targets) {
            for (GpsRtcmData msg : messages) {
                try {
                    gateway.send(sysid, msg.toFrame(UdpGateway.GCS_SYSID, UdpGateway.GCS_COMPID,
                            frameSequence.getAndIncrement() & 0xFF));
                } catch (IOException e) {
                    log.warn("RTCM 下发失败 sysid={}：{}", sysid, e.getMessage());
                }
            }
        }
        messagesForwarded.addAndGet(messages.size());
    }

    /**
     * 一条 RTCM 帧 → 1~4 条 {@link GpsRtcmData}（单测直接可见的纯函数）。
     * 分片编码遵循 flags 位定义：bit0=分片标志、bit1-2=分片号、bit3-7=序号；
     * 单片消息（≤180）不分片（bit0=0），与 {@link GpsRtcmData#broadcast(byte[], int)} 同款。
     */
    static List<GpsRtcmData> fragment(byte[] rtcm, int sequenceId) {
        List<GpsRtcmData> out = new ArrayList<>();
        if (rtcm.length > MAX_RTCM_BYTES) {
            return out;   // 调用方负责计数与告警
        }
        if (rtcm.length <= FRAGMENT_CAPACITY) {
            out.add(new GpsRtcmData((sequenceId & 0x1F) << 3, rtcm.length,
                    padded(rtcm), 0, 0));
            return out;
        }
        int fragmentCount = (rtcm.length + FRAGMENT_CAPACITY - 1) / FRAGMENT_CAPACITY;
        for (int i = 0; i < fragmentCount; i++) {
            int from = i * FRAGMENT_CAPACITY;
            int len = Math.min(FRAGMENT_CAPACITY, rtcm.length - from);
            byte[] chunk = new byte[FRAGMENT_CAPACITY];
            System.arraycopy(rtcm, from, chunk, 0, len);
            int flags = 0x01 | (i << 1) | ((sequenceId & 0x1F) << 3);
            out.add(new GpsRtcmData(flags, len, chunk, 0, 0));
        }
        return out;
    }

    /** GpsRtcmData 要求 data 数组等长于容量（encode 按 data.length 拷贝）。 */
    private static byte[] padded(byte[] src) {
        byte[] out = new byte[FRAGMENT_CAPACITY];
        System.arraycopy(src, 0, out, 0, src.length);
        return out;
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // 观测计数（log 排障与后续状态端点共用；不做 REST 面——端点数是门禁账，按需再开）
    long reconnects() { return reconnects.get(); }
    long rtcmFrames() { return rtcmFrames.get(); }
    long messagesForwarded() { return messagesForwarded.get(); }
    long framesDroppedTooLarge() { return framesDroppedTooLarge.get(); }
    long forwardsSkippedNoTarget() { return forwardsSkippedNoTarget.get(); }
}

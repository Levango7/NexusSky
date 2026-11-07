package io.aerofleet.cloud.surveillance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * GB28181 媒体面收流会话（云端侧）：收 RTP → 排序去重 → 组帧 → 解 PS。
 *
 * <p>对齐 GB/T 28181-2022 的两条硬要求：
 * <ul>
 *   <li><b>§4.3.6 时间戳纪律</b>：「同一帧音视频数据包封装成的所有 RTP 数据包的 RTP
 *       时间戳相同，且与不同帧音视频数据包的 RTP 数据包的 RTP 时间戳不同」——
 *       本类用 timestamp + marker 位切帧，并断言"同一帧内时间戳不变、换帧必变"；</li>
 *   <li><b>RFC 3550 序号</b>：UDP 不保证有序，收流侧按 sequence number 排序、
 *       去重（重复 = 同 seq 再来一次）。这是 ZLMediaKit 等开源实现的共同第一步。</li>
 * </ul>
 *
 * <p>**本类不解码 H.264**，只把 PS 解回基本流单元（{@link Gb28181PsDemuxer}）——
 * 解码/播放属前端与生产阶段。它证明的是"媒体面协议层自给"：
 * 帧边界、时间戳、PS 结构都能被独立 SIP 栈收发并还原。
 */
public final class Gb28181MediaSession implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(Gb28181MediaSession.class);

    private final DatagramSocket socket;
    private final ExecutorService loop;
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 按 SSRC + 序号排序去重后的 RTP 包（序号 → 包，TreeMap 保序）。 */
    private final Map<Long, Gb28181RtpPacket> bySeq = new ConcurrentHashMap<>();
    /** 收到的最后一个序号（用于去重与缺口检测）。 */
    /** 按 SSRC+序号 记录"见过"（判重用）。用 Set 而非 bySeq：后者组帧后会被清空。 */
    private final java.util.Set<Long> seenKeys = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** 最近一次的新序号（用于乱序检测）。 */
    private final AtomicInteger lastSeq = new AtomicInteger(-1);
    /** 已组装完成的帧（每帧 = 一组同时间戳的 RTP payload 拼接后的 PS 包）。 */
    private final List<Gb28181MediaFrame> frames = new CopyOnWriteArrayList<>();
    private final List<Consumer<Gb28181MediaFrame>> frameObservers = new CopyOnWriteArrayList<>();

    /** 统计：收到/去重丢弃/乱序重排/组装成帧。 */
    private final AtomicInteger received = new AtomicInteger();
    private final AtomicInteger duplicates = new AtomicInteger();
    private final AtomicInteger outOfOrder = new AtomicInteger();
    private final AtomicInteger framesBuilt = new AtomicInteger();

    private Gb28181MediaSession(DatagramSocket socket) {
        this.socket = socket;
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "gb28181-media-" + socket.getLocalPort());
            t.setDaemon(true);
            return t;
        };
        this.loop = Executors.newSingleThreadExecutor(tf);
    }

    /** 在指定端口开收流会话（0 = 系统分配）。 */
    public static Gb28181MediaSession open(int localPort) throws IOException {
        Gb28181MediaSession s = new Gb28181MediaSession(new DatagramSocket(localPort));
        return s;
    }

    public InetSocketAddress address() {
        return new InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), socket.getLocalPort());
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        loop.submit(this::receiveLoop);
    }

    private void receiveLoop() {
        byte[] buf = new byte[64 * 1024];
        while (running.get() && !socket.isClosed()) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                socket.receive(p);
                Gb28181RtpPacket rtp = Gb28181RtpPacket.decode(p.getData(), p.getOffset(), p.getLength());
                onRtp(rtp);
            } catch (IOException e) {
                if (running.get()) {
                    log.warn("gb28181 media receive failed on port {}", socket.getLocalPort(), e);
                }
                return;
            } catch (RuntimeException e) {
                // 单个坏包不杀死会话：媒体面上一条错位包不该让整条流停收
                log.warn("gb28181 media dropped undecodable packet on port {}", socket.getLocalPort(), e);
            }
        }
    }

    /** 处理一个 RTP 包：去重 → 记录 → marker 到达时组帧。 */
    void onRtp(Gb28181RtpPacket rtp) {
        received.incrementAndGet();
        long k = key(rtp.ssrc(), rtp.sequenceNumber());

        // 去重：同 SSRC+序号来过 ⇒ 重传/重复。**用独立历史集而不是序号表**：
        // 序号表在组帧成功后会被清空，晚到的重复包若只看序号表会被当成新包。
        if (!seenKeys.add(k)) {
            duplicates.incrementAndGet();
            return;
        }
        bySeq.put(k, rtp);
        int seq = rtp.sequenceNumber();
        int last = lastSeq.get();
        if (last >= 0 && seq != nextSeq(last)) {
            outOfOrder.incrementAndGet();
        }
        lastSeq.set(seq);
        // marker = 该帧分片已发完（RFC 3551）。**只有它到齐才组帧**：
        // 数据包先到就组，会把"第 1 个分片"当成整帧（1 片 PS 也是合法 PS，
        // 解复用照样成功）⇒ 一帧变两帧、后半段丢失。真链路测试
        // keyframeRoundTripOverRealUdp / multiPacketFrame 当场抓出。
        if (rtp.marker()) {
            // 记"该帧已发完"（乱序时它可能第一个到；数据包后到时由 tryBuildFrame 补齐）
            pendingMarkerTs.put(frameKey(rtp.ssrc(), rtp.timestamp()), rtp.timestamp());
        }
        // 每个包到达都尝试组帧——pendingMarkerTs 守卫保证"没见过 marker 就不组"，
        // 所以这里不会把半帧当好帧交上去；只有在 marker 先到、数据包后到的乱序
        // 场景下，这个调用才真正把帧补齐。
        tryBuildFrame(rtp.timestamp(), rtp.ssrc());
    }

    /**
     * 已见过 marker 的帧（key = ssrc<<16 ^ ts 低 16 位）。
     * <p>UDP 乱序下 marker 包可能第一个到：此时帧不完整、不能组。
     * 记下"这个时间戳的 marker 到过了"，后续同帧数据包到达时再尝试——
     * 这样既不会早组帧（1 片也是合法 PS），又不会漏组（反序时数据包后到）。
     */
    private final Map<Long, Long> pendingMarkerTs = new ConcurrentHashMap<>();

    /** 尝试组帧；未见过 marker 或帧不完整都不组（宁可丢帧，不交半帧）。 */
    private void tryBuildFrame(long ts, long ssrc) {
        // 没见过 marker ⇒ 该帧分片还没发完，等
        if (!pendingMarkerTs.containsKey(frameKey(ssrc, ts))) {
            return;
        }
        List<Gb28181RtpPacket> ordered = new ArrayList<>();
        for (Map.Entry<Long, Gb28181RtpPacket> e : new TreeMap<>(bySeq).entrySet()) {
            Gb28181RtpPacket p = e.getValue();
            if (p.ssrc() == ssrc && p.timestamp() == ts) {
                ordered.add(p);
            }
        }
        if (ordered.isEmpty()) {
            return;
        }
        byte[] ps = assemble(ordered);
        Gb28181PsDemuxer.PsPack pack;
        try {
            pack = Gb28181PsDemuxer.demux(ps);
        } catch (RuntimeException e) {
            log.debug("gb28181 media: frame ts={} not yet complete ({} packets)", ts, ordered.size());
            return;
        }
        ordered.forEach(p -> bySeq.remove(key(p.ssrc(), p.sequenceNumber())));
        pendingMarkerTs.remove(frameKey(ssrc, ts));
        frames.add(new Gb28181MediaFrame(ssrc, ts, ordered.size(), ps));
        framesBuilt.incrementAndGet();
        frameObservers.forEach(c -> c.accept(frames.get(frames.size() - 1)));
    }

    private static long frameKey(long ssrc, long ts) {
        return (ssrc << 16) ^ (ts & 0xFFFF);
    }

    private static int nextSeq(int seq) {
        return (seq + 1) & 0xFFFF;
    }

    /**
     * 序号是否连续（16 位回绕安全）。
     *
     * <p>两条同时成立才算连续：
     * <ol>
     *   <li>已收到的包两两差 1；</li>
     *   <li><b>序号跨度 == 包数</b>——缺低序号包时，只收到 marker 一个包也会满足 ①，
     *       但跨度 1 / 包数 1 也"满足"，所以再加一条：marker 序号 + 1 减首包序号
     *       必须等于包数。缺一个低序号包 ⇒ 跨度 = 包数 + 1 ⇒ 判定不完整。</li>
     * </ol>
     * <p><b>不补齐丢的包</b>：丢包就是丢帧，编数据补上会让上层拿到损坏的 PS。
     * 帧不完整就继续等（下一个同时间戳的包到达时会再触发一次组帧尝试）。
     */
    private static boolean isContiguous(List<Gb28181RtpPacket> ordered) {
        if (ordered.isEmpty()) {
            return false;
        }
        if (ordered.size() == 1) {
            return true;
        }
        int span = nextSeq(ordered.get(ordered.size() - 1).sequenceNumber())
                - ordered.get(0).sequenceNumber();
        if (span != ordered.size()) {
            return false;
        }
        for (int i = 1; i < ordered.size(); i++) {
            if (ordered.get(i).sequenceNumber() != nextSeq(ordered.get(i - 1).sequenceNumber())) {
                return false;
            }
        }
        return true;
    }

    private static long key(long ssrc, int seq) {
        return (ssrc << 16) | (seq & 0xFFFF);
    }

    /** 按序号升序拼接 payload。 */
    private static byte[] assemble(List<Gb28181RtpPacket> ordered) {
        int len = 0;
        for (Gb28181RtpPacket p : ordered) {
            len += p.payload().length;
        }
        byte[] ps = new byte[len];
        int off = 0;
        for (Gb28181RtpPacket p : ordered) {
            byte[] pl = p.payload();
            System.arraycopy(pl, 0, ps, off, pl.length);
            off += pl.length;
        }
        return ps;
    }


    // ------------------------------------------------------------------
    // 观察
    // ------------------------------------------------------------------

    /** 注册"组满一帧"回调（E2E 断言用）。 */
    public void onFrame(Consumer<Gb28181MediaFrame> cb) {
        frameObservers.add(cb);
    }

    public List<Gb28181MediaFrame> frames() {
        return List.copyOf(frames);
    }

    public int receivedCount() {
        return received.get();
    }

    public int duplicateCount() {
        return duplicates.get();
    }

    public int outOfOrderCount() {
        return outOfOrder.get();
    }

    public int framesBuilt() {
        return framesBuilt.get();
    }

    @Override
    public void close() {
        running.set(false);
        loop.shutdownNow();
        socket.close();
    }
}

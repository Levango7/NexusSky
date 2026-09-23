package io.aerofleet.sim.mesh;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.sim.SimLog;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * LoRa 传输适配器（丐版 Mesh，千元级 EMERGENCY_STANDARD 模式）。
 * <p>
 * 为千元级 LoRa Mesh 网络提供简化版 AODV-lite 传输层，相比完整版 AODV-lite 做以下放宽：
 * <ul>
 *   <li>HELLO 间隔：从 1s 放宽至 5s（LoRa 带宽窄，减少 HELLO 开销）</li>
 *   <li>邻居超时：从 5s 放宽至 15s（LoRa 链路延迟高，容忍更长超时）</li>
 *   <li>MAX_HOPS=10（LoRa 链路跳数限制更严格，相比完整版 15 跳）</li>
 *   <li>LoRa 分片：MAVLink 帧分片为 ~50 bytes LoRa 帧</li>
 * </ul>
 * <p>
 * 分片协议：每个分片头 = [frameId(1) | totalFragments(1) | fragIndex(1) | payload(<=maxPayload-3)]。
 * 与 {@link io.aerofleet.sim.comm.LoRaTransport} 分片协议兼容。
 * <p>
 * 线程安全：监听器列表 ConcurrentLinkedQueue；重组缓冲 ConcurrentHashMap；
 * 接收线程单线程消费私有队列。
 *
 * @see BudgetMeshRouter 丐版 Mesh 路由器
 */
public final class LoRaTransportAdapter implements AutoCloseable {

    /** LoRa 简化 AODV-lite HELLO 间隔（ms），从 1s 放宽至 5s。 */
    public static final long HELLO_INTERVAL_MS = 5_000L;

    /** LoRa 简化 AODV-lite 邻居超时（ms），从 5s 放宽至 15s。 */
    public static final long NEIGHBOR_TIMEOUT_MS = 15_000L;

    /** LoRa 简化 AODV-lite 最大跳数。 */
    public static final int MAX_HOPS = 10;

    /** LoRa 单帧最大载荷（字节，含 3 字节分片头）。 */
    public static final int LORA_MAX_PAYLOAD_BYTES = 50;

    /** 分片头长度：frameId(1) + totalFragments(1) + fragIndex(1)。 */
    private static final int FRAG_HEADER_BYTES = 3;

    /** 重组缓冲最大帧数，超过时淘汰最旧帧（防 OOM）。 */
    private static final int MAX_REASSEMBLY_FRAMES = 64;

    /** 信道标识符 → 共享 LoRa 广播信道的全局映射。 */
    private static final ConcurrentHashMap<Object, LoRaAirChannel> CHANNELS = new ConcurrentHashMap<>();

    /** LoRa 分片值对象（不可变）。 */
    public record Fragment(int frameId, int totalFragments, int fragIndex, byte[] payload) {
        /**
         * 序列化为 LoRa 线上字节：[frameId(1) | totalFragments(1) | fragIndex(1) | payload]。
         *
         * @return 含分片头的 LoRa 线上字节
         */
        public byte[] toBytes() {
            byte[] buf = new byte[FRAG_HEADER_BYTES + payload.length];
            buf[0] = (byte) frameId;
            buf[1] = (byte) totalFragments;
            buf[2] = (byte) fragIndex;
            System.arraycopy(payload, 0, buf, FRAG_HEADER_BYTES, payload.length);
            return buf;
        }

        /**
         * 从 LoRa 线上字节解析为 Fragment。
         *
         * @param raw LoRa 线上字节（含 3 字节头）
         * @return 解析后的 Fragment，若长度不足返回 null
         */
        public static Fragment fromBytes(byte[] raw) {
            if (raw == null || raw.length < FRAG_HEADER_BYTES) {
                return null;
            }
            int frameId = raw[0] & 0xFF;
            int total = raw[1] & 0xFF;
            int index = raw[2] & 0xFF;
            byte[] payload = new byte[raw.length - FRAG_HEADER_BYTES];
            System.arraycopy(raw, FRAG_HEADER_BYTES, payload, 0, payload.length);
            return new Fragment(frameId, total, index, payload);
        }
    }

    private final Object channelKey;
    private final LoRaAirChannel channel;
    private final int sourceId;
    private final ConcurrentLinkedQueue<Consumer<MavlinkFrame>> frameListeners = new ConcurrentLinkedQueue<>();
    private final LinkedBlockingQueue<byte[]> receiveQueue;
    private final Thread receiveThread;
    private volatile boolean running = true;

    /** 帧序号生成器，0-255 循环。 */
    private final AtomicInteger frameIdSeq = new AtomicInteger(0);

    /** 重组缓冲：frameId → 分片数组（按 fragIndex 槽位存放）。使用 LinkedHashMap 保持插入顺序，淘汰最旧帧。 */
    private final java.util.Map<Integer, byte[][]> reassemblyBuffer =
            java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>());

    /** 累计发送帧数。 */
    private final AtomicInteger totalSentFrames = new AtomicInteger(0);
    /** 累计接收帧数。 */
    private final AtomicInteger totalReceivedFrames = new AtomicInteger(0);
    /** 累计分片数。 */
    private final AtomicInteger totalFragments = new AtomicInteger(0);

    /**
     * 构造 LoRa 传输适配器，绑定到指定广播信道。
     *
     * @param channelKey 信道标识（通常为共享对象），同一 key 共享同一信道
     * @param sourceId   本节点源标识（如 sysid）
     */
    public LoRaTransportAdapter(Object channelKey, int sourceId) {
        if (channelKey == null) {
            throw new IllegalArgumentException("channelKey must not be null");
        }
        this.sourceId = sourceId;
        this.channelKey = channelKey;
        this.channel = CHANNELS.computeIfAbsent(channelKey, k -> new LoRaAirChannel());
        this.receiveQueue = channel.subscribe(sourceId);
        this.receiveThread = new Thread(this::receiveLoop, "lora-budget-recv-" + sourceId);
        this.receiveThread.setDaemon(true);
        this.receiveThread.start();
        SimLog.info("LoRaTransportAdapter (budget) created: sourceId=" + sourceId
                + " helloInterval=" + HELLO_INTERVAL_MS + "ms"
                + " neighborTimeout=" + NEIGHBOR_TIMEOUT_MS + "ms"
                + " maxHops=" + MAX_HOPS);
    }

    /**
     * 将 MAVLink 帧分片为 LoRa Fragment 列表。
     * <p>
     * 每个分片载荷大小 ≤ {@link #LORA_MAX_PAYLOAD_BYTES} - {@link #FRAG_HEADER_BYTES} = 47 字节。
     * 帧序号自动递增（0-255 循环）。
     *
     * @param frame 待分片的 MAVLink 帧
     * @return 分片列表，长度 ≥ 1
     */
    public List<Fragment> fragmentFrame(MavlinkFrame frame) {
        if (frame == null) {
            throw new IllegalArgumentException("frame must not be null");
        }
        byte[] encoded = frame.encodeV2();
        int frameId = frameIdSeq.getAndIncrement() & 0xFF;
        int chunkSize = LORA_MAX_PAYLOAD_BYTES - FRAG_HEADER_BYTES;
        int total = (encoded.length + chunkSize - 1) / chunkSize;
        if (total == 0) {
            total = 1;
        }
        if (total > 255) {
            throw new IllegalArgumentException(
                    "frame too large: " + encoded.length + " bytes need " + total + " fragments (max 255)");
        }

        List<Fragment> fragments = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            int offset = i * chunkSize;
            int len = Math.min(chunkSize, encoded.length - offset);
            byte[] chunk = new byte[len];
            System.arraycopy(encoded, offset, chunk, 0, len);
            fragments.add(new Fragment(frameId, total, i, chunk));
        }
        totalFragments.addAndGet(total);
        SimLog.info("LoRaBudget fragment: frameId=" + frameId + " total=" + total
                + " frameLen=" + encoded.length);
        return Collections.unmodifiableList(fragments);
    }

    /**
     * 重组 LoRa 分片列表为完整 MAVLink 帧字节。
     * <p>
     * 输入必须是同一 frameId 的全部分片，按 fragIndex 顺序排列。
     * 重组后的字节可用 {@link io.aerofleet.mavlink.MavlinkParser} 解析为 {@link MavlinkFrame}。
     *
     * @param fragments 同一帧的全部分片列表
     * @return 完整 MAVLink 帧字节，若分片不完整返回 null
     */
    public byte[] reassembleFragments(List<Fragment> fragments) {
        if (fragments == null || fragments.isEmpty()) {
            return null;
        }
        int frameId = fragments.get(0).frameId();
        int total = fragments.get(0).totalFragments();
        if (fragments.size() != total) {
            SimLog.warn("LoRaBudget reassemble: incomplete fragments, expected=" + total
                    + " got=" + fragments.size());
            return null;
        }
        // 按 fragIndex 排序确保顺序正确
        List<Fragment> sorted = new ArrayList<>(fragments);
        sorted.sort((a, b) -> Integer.compare(a.fragIndex(), b.fragIndex()));

        int totalLen = 0;
        for (Fragment f : sorted) {
            if (f.frameId() != frameId || f.totalFragments() != total) {
                SimLog.warn("LoRaBudget reassemble: fragment mismatch");
                return null;
            }
            totalLen += f.payload().length;
        }
        byte[] result = new byte[totalLen];
        int pos = 0;
        for (Fragment f : sorted) {
            System.arraycopy(f.payload(), 0, result, pos, f.payload().length);
            pos += f.payload().length;
        }
        SimLog.info("LoRaBudget reassemble: frameId=" + frameId + " complete, len=" + totalLen);
        return result;
    }

    /**
     * 发送 MAVLink 帧：分片后逐片广播到 LoRa 信道。
     *
     * @param frame 待发送的 MAVLink 帧
     */
    public void sendFrame(MavlinkFrame frame) {
        if (!running) {
            SimLog.warn("LoRaTransportAdapter.sendFrame: adapter closed, drop frame");
            return;
        }
        if (frame == null) {
            throw new IllegalArgumentException("frame must not be null");
        }
        List<Fragment> fragments = fragmentFrame(frame);
        for (Fragment frag : fragments) {
            channel.broadcast(sourceId, frag.toBytes());
        }
        totalSentFrames.incrementAndGet();
    }

    /** 注册帧监听器：收到完整 MAVLink 帧时回调。 */
    public void addFrameListener(Consumer<MavlinkFrame> listener) {
        frameListeners.add(listener);
    }

    /** 当前信道订阅者数量。 */
    public int subscriberCount() {
        return channel.subscriberCount();
    }

    /** 累计发送帧数。 */
    public int totalSentFrames() {
        return totalSentFrames.get();
    }

    /** 累计接收帧数。 */
    public int totalReceivedFrames() {
        return totalReceivedFrames.get();
    }

    /** 累计分片数。 */
    public int totalFragments() {
        return totalFragments.get();
    }

    /** 接收线程主循环：从私有队列取分片 → 重组 → 解析 → 通知监听器。 */
    private void receiveLoop() {
        while (running) {
            byte[] raw;
            try {
                raw = receiveQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (raw == null) {
                continue;
            }
            try {
                Fragment frag = Fragment.fromBytes(raw);
                if (frag == null) {
                    SimLog.warn("LoRaBudget receive: invalid fragment");
                    continue;
                }
                byte[] complete = reassembleIncoming(frag);
                if (complete != null) {
                    io.aerofleet.mavlink.MavlinkParser parser = new io.aerofleet.mavlink.MavlinkParser();
                    io.aerofleet.mavlink.MavlinkParser.ParseResult result =
                            parser.parse(ByteBuffer.wrap(complete));
                    if (result != null) {
                        totalReceivedFrames.incrementAndGet();
                        for (Consumer<MavlinkFrame> l : frameListeners) {
                            try {
                                l.accept(result.frame);
                            } catch (RuntimeException e) {
                                SimLog.warn("LoRaBudget listener exception: " + e.getMessage());
                            }
                        }
                    }
                }
            } catch (RuntimeException e) {
                SimLog.warn("LoRaBudget receive error: " + e.getMessage());
            }
        }
        SimLog.info("LoRaTransportAdapter receive loop exited: sourceId=" + sourceId);
    }

    /**
     * 重组接收到的分片（内部方法，使用 reassemblyBuffer 缓冲）。
     * <p>
     * 当某 frameId 的全部分片收齐后返回拼接后的原始 MAVLink 帧字节；未收齐返回 null。
     *
     * @param frag 接收到的单个分片
     * @return 完整 MAVLink 帧字节，或 null 表示尚未收齐
     */
    private byte[] reassembleIncoming(Fragment frag) {
        int frameId = frag.frameId();
        int total = frag.totalFragments();
        int index = frag.fragIndex();

        if (total == 0 || index >= total) {
            SimLog.warn("LoRaBudget reassemble: invalid header total=" + total + " index=" + index);
            return null;
        }

        byte[][] frags = reassemblyBuffer.get(frameId);
        if (frags == null || frags.length != total) {
            if (frags != null) {
                SimLog.warn("LoRaBudget reassemble: total mismatch for frameId=" + frameId
                        + " reinitializing");
            }
            // 检查缓冲上限：淘汰最旧帧（LinkedHashMap 按插入顺序迭代）
            if (reassemblyBuffer.size() >= MAX_REASSEMBLY_FRAMES) {
                Integer oldest = reassemblyBuffer.keySet().iterator().next();
                reassemblyBuffer.remove(oldest);
                SimLog.warn("LoRaBudget reassemble: buffer full, evicted oldest frameId=" + oldest);
            }
            frags = new byte[total][];
            reassemblyBuffer.put(frameId, frags);
        }

        frags[index] = frag.payload();

        // 检查是否收齐
        for (int i = 0; i < total; i++) {
            if (frags[i] == null) {
                return null;
            }
        }

        // 收齐，拼接
        int totalLen = 0;
        for (byte[] f : frags) {
            totalLen += f.length;
        }
        byte[] result = new byte[totalLen];
        int pos = 0;
        for (byte[] f : frags) {
            System.arraycopy(f, 0, result, pos, f.length);
            pos += f.length;
        }
        reassemblyBuffer.remove(frameId);
        return result;
    }

    @Override
    public void close() {
        running = false;
        receiveThread.interrupt();
        // M4: join 等待接收线程退出，防止线程仍在运行时资源被释放
        try {
            receiveThread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        channel.unsubscribe(sourceId, receiveQueue);
        reassemblyBuffer.clear();
        // C2: 当最后一个订阅者退出后，从静态 CHANNELS 中移除该 channel 条目，防止内存泄漏
        if (channel.subscriberCount() == 0) {
            CHANNELS.remove(channelKey, channel);
        }
        SimLog.info("LoRaTransportAdapter (budget) closed: sourceId=" + sourceId);
    }

    /**
     * LoRa 共享广播信道：模拟 LoRa 广播物理层。
     * <p>
     * 每个订阅者有自己的 {@link LinkedBlockingQueue} 接收队列。
     * {@code broadcast} 将分片放入所有其他订阅者的队列（跳过发送方）。
     */
    public static final class LoRaAirChannel {
        private final CopyOnWriteArrayList<Subscriber> subscribers = new CopyOnWriteArrayList<>();
        private final AtomicInteger totalBroadcasts = new AtomicInteger(0);
        private final AtomicInteger totalDrops = new AtomicInteger(0);

        private static final class Subscriber {
            final int sourceId;
            final LinkedBlockingQueue<byte[]> queue;

            Subscriber(int sourceId) {
                this.sourceId = sourceId;
                this.queue = new LinkedBlockingQueue<>();
            }
        }

        LinkedBlockingQueue<byte[]> subscribe(int sourceId) {
            Subscriber sub = new Subscriber(sourceId);
            subscribers.add(sub);
            return sub.queue;
        }

        void unsubscribe(int sourceId, LinkedBlockingQueue<byte[]> queue) {
            subscribers.removeIf(s -> s.sourceId == sourceId && s.queue == queue);
        }

        public void broadcast(int sourceId, byte[] data) {
            totalBroadcasts.incrementAndGet();
            for (Subscriber sub : subscribers) {
                if (sub.sourceId == sourceId) {
                    continue;
                }
                if (!sub.queue.offer(data)) {
                    totalDrops.incrementAndGet();
                    SimLog.warn("LoRaAirChannel (budget): queue full, drop for sourceId=" + sub.sourceId);
                }
            }
        }

        public int subscriberCount() {
            return subscribers.size();
        }

        public int totalBroadcasts() {
            return totalBroadcasts.get();
        }

        public int totalDrops() {
            return totalDrops.get();
        }
    }
}
package io.aerofleet.sim.comm;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.mavlink.MavlinkParser;
import io.aerofleet.sim.SimLog;

import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * LoRa 传输适配器：将 {@link LoRaMavlinkTransport} 适配为 {@link MavlinkTransport}。
 * <p>
 * 用于 {@link io.aerofleet.sim.mesh.MeshRouter} 在 {@code TransportType=LORA} 模式下
 * 的统一传输调用。多个适配器共享同一个"空中信道"（由共享的 {@code BlockingQueue} 标识），
 * 实现广播物理层语义：一个节点发送，所有其他节点都能收到。
 * <p>
 * <b>信道模型</b>：LoRa 物理层是广播信道——所有在范围内的节点都能听到同一次发送。
 * 本适配器用 {@link LoRaAirChannel} 模拟：每个适配器订阅信道，发送时信道将分片广播
 * 到所有其他订阅者的私有接收队列（跳过发送方自身，避免自回环）。
 * <p>
 * <b>发送流程</b>：{@code send(frame)} → {@code loRaMavlinkTransport.send(frame.encodeV2())}
 * 将帧分片入待发送队列 → 逐片取出广播到空中信道。
 * <p>
 * <b>接收流程</b>：接收线程从私有接收队列取出分片 →
 * {@code loRaMavlinkTransport.receive(fragment)} 重组 → 重组完成后用
 * {@link MavlinkParser} 解析为 {@link MavlinkFrame} → 通知所有监听器。
 * <p>
 * <b>线程安全</b>：监听器列表 ConcurrentLinkedQueue；接收线程单线程消费私有队列；
 * LoRaMavlinkTransport.receive 非线程安全，由单线程接收保证。
 * <p>
 * <b>生命周期</b>：{@link #close()} 停止接收线程并从信道取消订阅，释放资源。
 *
 * @see MavlinkTransport 通用传输接口
 * @see LoRaMavlinkTransport 被包装的 LoRa 帧适配器
 * @see LoRaAirChannel 共享空中信道
 */
public final class LoRaTransportAdapter implements MavlinkTransport {

    /** 信道标识符 → 共享空中信道的全局映射（同一 BlockingQueue 共享同一信道）。 */
    private static final ConcurrentHashMap<Object, LoRaAirChannel> CHANNELS = new ConcurrentHashMap<>();

    private final LoRaMavlinkTransport loRaMavlinkTransport;
    private final MavlinkParser parser = new MavlinkParser();
    private final LoRaAirChannel channel;
    private final int sourceId;
    private final int channelId;
    private final ConcurrentLinkedQueue<Consumer<MavlinkFrame>> frameListeners = new ConcurrentLinkedQueue<>();
    private final LinkedBlockingQueue<byte[]> receiveQueue;
    private final Thread receiveThread;
    private volatile boolean running = true;

    /** 累计发送字节数（供延迟统计）。 */
    private final AtomicLong totalSentBytes = new AtomicLong(0);
    /** 累计接收帧数（供延迟统计）。 */
    private final AtomicLong totalReceivedFrames = new AtomicLong(0);
    /** 累计发送时间戳（ns，供延迟统计）。 */
    private final AtomicLong totalSendTimeNs = new AtomicLong(0);

    /**
     * 构造 LoRa 适配器，绑定到指定空中信道。
     *
     * @param loRaMavlinkTransport 被包装的 LoRa 帧适配器，不能为 null
     * @param channelKey           信道标识（通常为共享 BlockingQueue），同一 key 共享同一信道
     * @param sourceId             本节点源标识（用于过滤自回环），如 sysid
     * @param channelId            信道编号（用于 getLocalPort 报告）
     */
    public LoRaTransportAdapter(LoRaMavlinkTransport loRaMavlinkTransport,
                                Object channelKey, int sourceId, int channelId) {
        if (loRaMavlinkTransport == null) {
            throw new IllegalArgumentException("loRaMavlinkTransport must not be null");
        }
        if (channelKey == null) {
            throw new IllegalArgumentException("channelKey must not be null");
        }
        this.loRaMavlinkTransport = loRaMavlinkTransport;
        this.sourceId = sourceId;
        this.channelId = channelId;
        this.channel = CHANNELS.computeIfAbsent(channelKey, k -> new LoRaAirChannel());
        this.receiveQueue = channel.subscribe(sourceId);
        this.receiveThread = new Thread(this::receiveLoop, "lora-recv-" + sourceId);
        this.receiveThread.setDaemon(true);
        this.receiveThread.start();
        SimLog.info("LoRaTransportAdapter created: sourceId=" + sourceId + " channelId=" + channelId);
    }

    /** 被包装的原始 LoRa 帧适配器。 */
    public LoRaMavlinkTransport unwrap() {
        return loRaMavlinkTransport;
    }

    /** 共享空中信道（供测试与诊断）。 */
    public LoRaAirChannel channel() {
        return channel;
    }

    @Override
    public void send(MavlinkFrame frame) {
        if (!running) {
            SimLog.warn("LoRaTransportAdapter.send: adapter closed, drop frame");
            return;
        }
        long startNs = System.nanoTime();
        byte[] encoded = frame.encodeV2();
        // 分片入待发送队列
        loRaMavlinkTransport.send(encoded);
        // 逐片取出广播到空中信道
        byte[] frag;
        while ((frag = loRaMavlinkTransport.pollFragment()) != null) {
            channel.broadcast(sourceId, frag);
        }
        totalSentBytes.addAndGet(encoded.length);
        totalSendTimeNs.addAndGet(System.nanoTime() - startNs);
        totalReceivedFrames.incrementAndGet(); // 计发送次数（语义：已处理帧数）
    }

    @Override
    public void addFrameListener(Consumer<MavlinkFrame> listener) {
        frameListeners.add(listener);
    }

    @Override
    public int getLocalPort() {
        return channelId;
    }

    /** 累计发送字节数。 */
    public long totalSentBytes() {
        return totalSentBytes.get();
    }

    /** 累计接收（含发送）帧数。 */
    public long totalFrameCount() {
        return totalReceivedFrames.get();
    }

    /** 平均发送耗时（ns）。 */
    public long averageSendNs() {
        long frames = totalReceivedFrames.get();
        return frames > 0 ? totalSendTimeNs.get() / frames : 0;
    }

    /** 接收线程主循环：从私有队列取分片 → 重组 → 解析 → 通知监听器。 */
    private void receiveLoop() {
        while (running) {
            byte[] fragment;
            try {
                fragment = receiveQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (fragment == null) {
                continue;
            }
            try {
                byte[] complete = loRaMavlinkTransport.receive(fragment);
                if (complete != null) {
                    MavlinkParser.ParseResult result =
                            parser.parse(ByteBuffer.wrap(complete));
                    if (result != null) {
                        for (Consumer<MavlinkFrame> l : frameListeners) {
                            try {
                                l.accept(result.frame);
                            } catch (RuntimeException e) {
                                SimLog.warn("LoRa listener exception: " + e.getMessage());
                            }
                        }
                    }
                }
            } catch (RuntimeException e) {
                SimLog.warn("LoRa receive error: " + e.getMessage());
            }
        }
        SimLog.info("LoRaTransportAdapter receive loop exited: sourceId=" + sourceId);
    }

    @Override
    public void close() {
        running = false;
        receiveThread.interrupt();
        channel.unsubscribe(sourceId, receiveQueue);
        SimLog.info("LoRaTransportAdapter closed: sourceId=" + sourceId);
    }

    /**
     * LoRa 共享空中信道：模拟 LoRa 广播物理层。
     * <p>
     * 每个订阅者有自己的 {@link LinkedBlockingQueue} 接收队列。{@code broadcast} 将分片
     * 放入所有其他订阅者的队列（跳过发送方）。{@code unsubscribe} 移除订阅者队列。
     * <p>
     * 线程安全：订阅者列表 CopyOnWriteArrayList；offer 非阻塞。
     */
    public static final class LoRaAirChannel {
        private final CopyOnWriteArrayList<Subscriber> subscribers = new CopyOnWriteArrayList<>();
        private final AtomicLong totalBroadcasts = new AtomicLong(0);
        private final AtomicLong totalDrops = new AtomicLong(0);

        /** 订阅者记录：源标识 + 接收队列。 */
        private static final class Subscriber {
            final int sourceId;
            final LinkedBlockingQueue<byte[]> queue;
            Subscriber(int sourceId) {
                this.sourceId = sourceId;
                this.queue = new LinkedBlockingQueue<>();
            }
        }

        /** 订阅信道，返回本订阅者的接收队列。 */
        LinkedBlockingQueue<byte[]> subscribe(int sourceId) {
            Subscriber sub = new Subscriber(sourceId);
            subscribers.add(sub);
            return sub.queue;
        }

        /** 取消订阅。 */
        void unsubscribe(int sourceId, LinkedBlockingQueue<byte[]> queue) {
            subscribers.removeIf(s -> s.sourceId == sourceId && s.queue == queue);
        }

        /** 广播分片到所有其他订阅者（跳过发送方）。 */
        public void broadcast(int sourceId, byte[] fragment) {
            totalBroadcasts.incrementAndGet();
            for (Subscriber sub : subscribers) {
                if (sub.sourceId == sourceId) {
                    continue; // 跳过发送方，避免自回环
                }
                if (!sub.queue.offer(fragment)) {
                    totalDrops.incrementAndGet();
                    SimLog.warn("LoRaAirChannel: queue full, drop fragment for sourceId=" + sub.sourceId);
                }
            }
        }

        /** 当前订阅者数量。 */
        public int subscriberCount() {
            return subscribers.size();
        }

        /** 累计广播次数。 */
        public long totalBroadcasts() {
            return totalBroadcasts.get();
        }

        /** 累计因队列满丢弃的分片数。 */
        public long totalDrops() {
            return totalDrops.get();
        }
    }
}
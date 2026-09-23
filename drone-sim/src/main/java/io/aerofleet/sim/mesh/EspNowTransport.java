package io.aerofleet.sim.mesh;

import io.aerofleet.mavlink.MavlinkFrame;
import io.aerofleet.sim.SimLog;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * ESP-NOW 传输层模拟（丐版 Mesh，百元级 EMERGENCY_TOY 模式）。
 * <p>
 * 模拟 ESP32 ESP-NOW 广播物理层：所有节点广播所有帧，无路由表、无 RREQ/RREP。
 * <ul>
 *   <li>hopCount 守卫：MAX_HOPS=5，防止无限广播</li>
 *   <li>节点数限制：最多 8 个节点（ESP-NOW 带宽限制）</li>
 *   <li>数据帧精简：仅传 HEARTBEAT + ATTITUDE + COMMAND_LONG + 自定义精简帧</li>
 * </ul>
 * <p>
 * 信道模型：与 LoRaAirChannel 类似，所有在范围内的节点都能收到同一次广播。
 * 发送方不会收到自己的广播（避免自回环）。hopCount 递减守卫由调用方
 * （BudgetMeshRouter）在转发时检查，本类仅负责传输。
 * <p>
 * 线程安全：订阅者列表 CopyOnWriteArrayList；接收队列 LinkedBlockingQueue。
 *
 * @see BudgetMeshRouter 丐版 Mesh 路由器
 */
public final class EspNowTransport implements AutoCloseable {

    /** ESP-NOW 最大跳数守卫，防止无限广播。 */
    public static final int MAX_HOPS = 5;

    /** ESP-NOW 最大节点数（带宽限制）。 */
    public static final int MAX_NODES = 8;

    /** ESP-NOW 最小节点数。 */
    public static final int MIN_NODES = 5;

    /** 允许转发的 MAVLink 消息 ID 白名单（数据帧精简）。 */
    public static final int[] ALLOWED_MSG_IDS = {
            0,    // HEARTBEAT
            30,   // ATTITUDE
            76,   // COMMAND_LONG
            477,  // AlarmTriggerMsg（自定义）
            478,  // AlarmAckMsg（自定义）
            479   // SurveillanceStatusMsg（自定义）
    };

    /** 信道标识符 → 共享 ESP-NOW 广播信道的全局映射。 */
    private static final ConcurrentHashMap<Object, EspNowChannel> CHANNELS = new ConcurrentHashMap<>();

    private final EspNowChannel channel;
    private final int sourceId;
    private final ConcurrentLinkedQueue<Consumer<MavlinkFrame>> frameListeners = new ConcurrentLinkedQueue<>();
    private final LinkedBlockingQueue<byte[]> receiveQueue;
    private final Thread receiveThread;
    private volatile boolean running = true;

    /** 累计广播帧数。 */
    private final AtomicInteger totalBroadcastFrames = new AtomicInteger(0);
    /** 累计因 hopCount 耗尽丢弃的帧数。 */
    private final AtomicInteger totalHopDroppedFrames = new AtomicInteger(0);
    /** 累计因节点数超限拒绝加入的次数。 */
    private final AtomicInteger totalNodeLimitRejects = new AtomicInteger(0);

    /**
     * 构造 ESP-NOW 传输层，绑定到指定广播信道。
     *
     * @param channelKey 信道标识（通常为共享对象），同一 key 共享同一信道
     * @param sourceId   本节点源标识（如 sysid）
     */
    public EspNowTransport(Object channelKey, int sourceId) {
        if (channelKey == null) {
            throw new IllegalArgumentException("channelKey must not be null");
        }
        this.sourceId = sourceId;
        this.channel = CHANNELS.computeIfAbsent(channelKey, k -> new EspNowChannel());
        // 检查节点数限制
        if (channel.subscriberCount() >= MAX_NODES) {
            this.totalNodeLimitRejects.incrementAndGet();
            SimLog.warn("EspNowTransport: node limit (" + MAX_NODES + ") reached, "
                    + "new node " + sourceId + " may experience degraded performance");
        }
        this.receiveQueue = channel.subscribe(sourceId);
        this.receiveThread = new Thread(this::receiveLoop, "espnow-recv-" + sourceId);
        this.receiveThread.setDaemon(true);
        this.receiveThread.start();
        SimLog.info("EspNowTransport created: sourceId=" + sourceId
                + " channelSubscribers=" + channel.subscriberCount());
    }

    /**
     * 广播一帧到所有其他节点（ESP-NOW 广播模式）。
     * <p>
     * hopCount 守卫：若 hopCount <= 0，丢弃帧并记录统计。
     * 调用方（BudgetMeshRouter）负责在转发时递减 hopCount 并检查守卫。
     *
     * @param frame    待广播的 MAVLink 帧
     * @param hopCount 当前 hopCount（由调用方传入，本方法不做递减）
     */
    public void broadcastFrame(MavlinkFrame frame, int hopCount) {
        if (!running) {
            SimLog.warn("EspNowTransport.broadcastFrame: transport closed, drop frame");
            return;
        }
        if (frame == null) {
            throw new IllegalArgumentException("frame must not be null");
        }
        if (hopCount <= 0) {
            totalHopDroppedFrames.incrementAndGet();
            SimLog.warn("EspNowTransport: hopCount=" + hopCount + " <= 0, drop frame msgId=" + frame.getMessageId());
            return;
        }
        if (hopCount > MAX_HOPS) {
            totalHopDroppedFrames.incrementAndGet();
            SimLog.warn("EspNowTransport: hopCount=" + hopCount + " > MAX_HOPS(" + MAX_HOPS + "), drop frame");
            return;
        }
        byte[] encoded = frame.encodeV2();
        // 将 hopCount 附在帧尾（1 字节），供接收方读取
        byte[] withHop = new byte[encoded.length + 1];
        System.arraycopy(encoded, 0, withHop, 0, encoded.length);
        withHop[withHop.length - 1] = (byte) hopCount;
        channel.broadcast(sourceId, withHop);
        totalBroadcastFrames.incrementAndGet();
    }

    /**
     * 检查消息 ID 是否在允许的白名单内（数据帧精简）。
     *
     * @param msgId MAVLink 消息 ID
     * @return true 若该消息类型允许在 ESP-NOW 模式下传输
     */
    public static boolean isAllowedMsgId(int msgId) {
        for (int allowed : ALLOWED_MSG_IDS) {
            if (msgId == allowed) {
                return true;
            }
        }
        return false;
    }

    /** 注册帧监听器：收到完整 MAVLink 帧时回调。 */
    public void addFrameListener(Consumer<MavlinkFrame> listener) {
        frameListeners.add(listener);
    }

    /** 当前信道订阅者数量。 */
    public int subscriberCount() {
        return channel.subscriberCount();
    }

    /** 累计广播帧数。 */
    public int totalBroadcastFrames() {
        return totalBroadcastFrames.get();
    }

    /** 累计因 hopCount 耗尽丢弃的帧数。 */
    public int totalHopDroppedFrames() {
        return totalHopDroppedFrames.get();
    }

    /** 累计因节点数超限拒绝加入的次数。 */
    public int totalNodeLimitRejects() {
        return totalNodeLimitRejects.get();
    }

    /** 接收线程主循环：从私有队列取帧 → 解析 → 通知监听器。 */
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
                // 最后 1 字节是 hopCount，前面是 MAVLink v2 帧
                int hopCount = raw[raw.length - 1] & 0xFF;
                byte[] frameBytes = new byte[raw.length - 1];
                System.arraycopy(raw, 0, frameBytes, 0, frameBytes.length);
                // 使用 MavlinkParser 解析
                io.aerofleet.mavlink.MavlinkParser parser = new io.aerofleet.mavlink.MavlinkParser();
                io.aerofleet.mavlink.MavlinkParser.ParseResult result =
                        parser.parse(java.nio.ByteBuffer.wrap(frameBytes));
                if (result != null) {
                    for (Consumer<MavlinkFrame> l : frameListeners) {
                        try {
                            l.accept(result.frame);
                        } catch (RuntimeException e) {
                            SimLog.warn("EspNow listener exception: " + e.getMessage());
                        }
                    }
                }
            } catch (RuntimeException e) {
                SimLog.warn("EspNow receive error: " + e.getMessage());
            }
        }
        SimLog.info("EspNowTransport receive loop exited: sourceId=" + sourceId);
    }

    @Override
    public void close() {
        running = false;
        receiveThread.interrupt();
        channel.unsubscribe(sourceId, receiveQueue);
        SimLog.info("EspNowTransport closed: sourceId=" + sourceId);
    }

    /**
     * ESP-NOW 共享广播信道：模拟 ESP-NOW 广播物理层。
     * <p>
     * 每个订阅者有自己的 {@link LinkedBlockingQueue} 接收队列。
     * {@code broadcast} 将帧放入所有其他订阅者的队列（跳过发送方）。
     */
    public static final class EspNowChannel {
        private final CopyOnWriteArrayList<Subscriber> subscribers = new CopyOnWriteArrayList<>();
        private final AtomicInteger totalBroadcasts = new AtomicInteger(0);
        private final AtomicInteger totalDrops = new AtomicInteger(0);

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

        /** 广播帧到所有其他订阅者（跳过发送方，避免自回环）。 */
        public void broadcast(int sourceId, byte[] data) {
            totalBroadcasts.incrementAndGet();
            for (Subscriber sub : subscribers) {
                if (sub.sourceId == sourceId) {
                    continue;
                }
                if (!sub.queue.offer(data)) {
                    totalDrops.incrementAndGet();
                    SimLog.warn("EspNowChannel: queue full, drop frame for sourceId=" + sub.sourceId);
                }
            }
        }

        /** 当前订阅者数量。 */
        public int subscriberCount() {
            return subscribers.size();
        }

        /** 累计广播次数。 */
        public int totalBroadcasts() {
            return totalBroadcasts.get();
        }

        /** 累计因队列满丢弃的帧数。 */
        public int totalDrops() {
            return totalDrops.get();
        }
    }
}
package io.aerofleet.sim.mesh;

import io.aerofleet.mavlink.MavlinkFrame;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.locks.ReentrantLock;

/**
 * QoS 优先级队列（灾害应急通讯组网，FR-02）。
 * <p>
 * 4 个优先级：EMERGENCY(0) > COMMAND(1) > MAPPING(2) > ROUTINE(3)。
 * 每个优先级独立的 FIFO 队列，dequeue 时按优先级从高到低扫描，高优先级先出。
 * EMERGENCY 帧可抢占低优先级帧的转发资源（preemptLowPriority）。
 * <p>
 * 线程安全：内部 {@link ReentrantLock} 保护所有队列操作。
 */
public final class QoSPriorityQueue {

    /** 优先级枚举：序数越小优先级越高。 */
    public enum PriorityClass {
        /** 搜救通讯，最高优先级。 */
        EMERGENCY(0),
        /** 指挥通讯。 */
        COMMAND(1),
        /** 测绘数据。 */
        MAPPING(2),
        /** 常规遥测，最低优先级。 */
        ROUTINE(3);

        private final int code;

        PriorityClass(int code) {
            this.code = code;
        }

        /** 稳定序数（供 MAVLink 编码用）。 */
        public int code() {
            return code;
        }

        /** 按 code 反查。 */
        public static PriorityClass fromCode(int code) {
            return switch (code) {
                case 0 -> EMERGENCY;
                case 1 -> COMMAND;
                case 2 -> MAPPING;
                default -> ROUTINE;
            };
        }
    }

    /** 队列条目：帧 + 优先级 + 入队时间戳。 */
    public record QueueEntry(MavlinkFrame frame, PriorityClass priorityClass, long enqueuedAtMs) {}

    /** 优先级队列数量。 */
    public static final int NUM_PRIORITIES = 4;

    /** 每个优先级的 FIFO 队列。 */
    private final Deque<QueueEntry>[] queues;

    /** 锁保护所有队列操作。 */
    private final ReentrantLock lock = new ReentrantLock();

    /** 各队列当前长度（供统计用，锁内更新）。 */
    private final int[] queueSizes = new int[NUM_PRIORITIES];

    @SuppressWarnings("unchecked")
    public QoSPriorityQueue() {
        queues = new Deque[NUM_PRIORITIES];
        for (int i = 0; i < NUM_PRIORITIES; i++) {
            queues[i] = new ArrayDeque<>();
        }
    }

    /**
     * 入队：将帧按指定优先级放入对应 FIFO 队列尾部。
     *
     * @param frame         MAVLink 帧
     * @param priorityClass 优先级分类
     */
    public void enqueue(MavlinkFrame frame, PriorityClass priorityClass) {
        if (frame == null) {
            throw new IllegalArgumentException("frame must not be null");
        }
        lock.lock();
        try {
            queues[priorityClass.code()].addLast(
                    new QueueEntry(frame, priorityClass, System.currentTimeMillis()));
            queueSizes[priorityClass.code()]++;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 出队：按优先级从高到低扫描，返回第一个非空队列的队首帧。
     *
     * @return 最高优先级的队首条目；所有队列为空时返回 null
     */
    public QueueEntry dequeue() {
        lock.lock();
        try {
            for (int i = 0; i < NUM_PRIORITIES; i++) {
                if (!queues[i].isEmpty()) {
                    queueSizes[i]--;
                    return queues[i].pollFirst();
                }
            }
            return null;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 出队指定优先级及以上的帧（FR-02 优先级过滤）。
     * <p>
     * 例如 maxPriority=COMMAND(1) 时，仅从 EMERGENCY 和 COMMAND 队列出队。
     *
     * @param maxPriority 最低允许优先级（含），序数越小优先级越高
     * @return 符合优先级条件的队首条目；无符合条件的帧时返回 null
     */
    public QueueEntry dequeue(PriorityClass maxPriority) {
        lock.lock();
        try {
            int limit = maxPriority.code();
            for (int i = 0; i <= limit; i++) {
                if (!queues[i].isEmpty()) {
                    queueSizes[i]--;
                    return queues[i].pollFirst();
                }
            }
            return null;
        } finally {
            lock.unlock();
        }
    }

    /**
     * EMERGENCY 帧抢占低优先级帧的转发资源（FR-02）。
     * <p>
     * 将一个低优先级帧从队列中移除并返回，为 EMERGENCY 帧腾出转发资源。
     * 从最低优先级（ROUTINE）开始抢占，依次向上。
     *
     * @return 被抢占的低优先级条目；无低优先级帧可抢占时返回 null
     */
    public QueueEntry preemptLowPriority() {
        lock.lock();
        try {
            // 从最低优先级开始抢占
            for (int i = NUM_PRIORITIES - 1; i >= PriorityClass.COMMAND.code(); i--) {
                if (!queues[i].isEmpty()) {
                    queueSizes[i]--;
                    return queues[i].pollFirst();
                }
            }
            return null;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 当前队列总长度（所有优先级之和）。
     */
    public int size() {
        lock.lock();
        try {
            int total = 0;
            for (int i = 0; i < NUM_PRIORITIES; i++) {
                total += queueSizes[i];
            }
            return total;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 指定优先级队列的当前长度。
     */
    public int size(PriorityClass priorityClass) {
        lock.lock();
        try {
            return queueSizes[priorityClass.code()];
        } finally {
            lock.unlock();
        }
    }

    /**
     * 是否所有队列都为空。
     */
    public boolean isEmpty() {
        return size() == 0;
    }

    /**
     * 清空所有队列。
     */
    public void clear() {
        lock.lock();
        try {
            for (int i = 0; i < NUM_PRIORITIES; i++) {
                queues[i].clear();
                queueSizes[i] = 0;
            }
        } finally {
            lock.unlock();
        }
    }
}
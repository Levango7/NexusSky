package io.aerofleet.sim.mesh;

import io.aerofleet.mavlink.MavlinkFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QoSPriorityQueue 优先级队列单测（灾害应急通讯组网，FR-02）。
 * <p>
 * 覆盖 enqueue/dequeue/优先级排序/抢占/指定优先级出队/队列统计。
 */
@DisplayName("QoSPriorityQueue 优先级队列 (FR-02)")
class QoSPriorityQueueTest {

    /** 创建测试用 MavlinkFrame。 */
    private static MavlinkFrame createFrame(int sysid, int msgId) {
        return MavlinkFrame.of(sysid, 1, 0, msgId, 0, new byte[]{1, 2, 3});
    }

    @Test
    @DisplayName("enqueue 后 dequeue 按 EMERGENCY > COMMAND > MAPPING > ROUTINE 顺序出队")
    void enqueueDequeuePriorityOrder() {
        QoSPriorityQueue queue = new QoSPriorityQueue();
        MavlinkFrame routineFrame = createFrame(1, 10);
        MavlinkFrame mappingFrame = createFrame(2, 20);
        MavlinkFrame commandFrame = createFrame(3, 30);
        MavlinkFrame emergencyFrame = createFrame(4, 40);

        // 按 ROUTINE → MAPPING → COMMAND → EMERGENCY 入队
        queue.enqueue(routineFrame, QoSPriorityQueue.PriorityClass.ROUTINE);
        queue.enqueue(mappingFrame, QoSPriorityQueue.PriorityClass.MAPPING);
        queue.enqueue(commandFrame, QoSPriorityQueue.PriorityClass.COMMAND);
        queue.enqueue(emergencyFrame, QoSPriorityQueue.PriorityClass.EMERGENCY);

        // 出队顺序应为 EMERGENCY → COMMAND → MAPPING → ROUTINE
        QoSPriorityQueue.QueueEntry e1 = queue.dequeue();
        assertThat(e1.priorityClass()).isEqualTo(QoSPriorityQueue.PriorityClass.EMERGENCY);
        assertThat(e1.frame().getSystemId()).isEqualTo(4);

        QoSPriorityQueue.QueueEntry e2 = queue.dequeue();
        assertThat(e2.priorityClass()).isEqualTo(QoSPriorityQueue.PriorityClass.COMMAND);

        QoSPriorityQueue.QueueEntry e3 = queue.dequeue();
        assertThat(e3.priorityClass()).isEqualTo(QoSPriorityQueue.PriorityClass.MAPPING);

        QoSPriorityQueue.QueueEntry e4 = queue.dequeue();
        assertThat(e4.priorityClass()).isEqualTo(QoSPriorityQueue.PriorityClass.ROUTINE);
    }

    @Test
    @DisplayName("同优先级 FIFO：先入队的先出队")
    void samePriorityFifo() {
        QoSPriorityQueue queue = new QoSPriorityQueue();
        MavlinkFrame f1 = createFrame(1, 10);
        MavlinkFrame f2 = createFrame(2, 20);
        MavlinkFrame f3 = createFrame(3, 30);

        queue.enqueue(f1, QoSPriorityQueue.PriorityClass.EMERGENCY);
        queue.enqueue(f2, QoSPriorityQueue.PriorityClass.EMERGENCY);
        queue.enqueue(f3, QoSPriorityQueue.PriorityClass.EMERGENCY);

        assertThat(queue.dequeue().frame().getSystemId()).isEqualTo(1);
        assertThat(queue.dequeue().frame().getSystemId()).isEqualTo(2);
        assertThat(queue.dequeue().frame().getSystemId()).isEqualTo(3);
    }

    @Test
    @DisplayName("dequeue(maxPriority) 仅出队指定优先级及以上的帧")
    void dequeueWithMaxPriority() {
        QoSPriorityQueue queue = new QoSPriorityQueue();
        queue.enqueue(createFrame(1, 10), QoSPriorityQueue.PriorityClass.ROUTINE);
        queue.enqueue(createFrame(2, 20), QoSPriorityQueue.PriorityClass.MAPPING);
        queue.enqueue(createFrame(3, 30), QoSPriorityQueue.PriorityClass.COMMAND);
        queue.enqueue(createFrame(4, 40), QoSPriorityQueue.PriorityClass.EMERGENCY);

        // maxPriority=COMMAND → 仅出队 EMERGENCY 和 COMMAND
        QoSPriorityQueue.QueueEntry e1 = queue.dequeue(QoSPriorityQueue.PriorityClass.COMMAND);
        assertThat(e1.priorityClass()).isEqualTo(QoSPriorityQueue.PriorityClass.EMERGENCY);

        QoSPriorityQueue.QueueEntry e2 = queue.dequeue(QoSPriorityQueue.PriorityClass.COMMAND);
        assertThat(e2.priorityClass()).isEqualTo(QoSPriorityQueue.PriorityClass.COMMAND);

        // 再出队 COMMAND 及以上 → 无符合条件的帧
        assertThat(queue.dequeue(QoSPriorityQueue.PriorityClass.COMMAND)).isNull();

        // MAPPING 和 ROUTINE 仍在队列中
        assertThat(queue.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("preemptLowPriority 从最低优先级开始抢占")
    void preemptLowPriority() {
        QoSPriorityQueue queue = new QoSPriorityQueue();
        queue.enqueue(createFrame(1, 10), QoSPriorityQueue.PriorityClass.ROUTINE);
        queue.enqueue(createFrame(2, 20), QoSPriorityQueue.PriorityClass.MAPPING);
        queue.enqueue(createFrame(3, 30), QoSPriorityQueue.PriorityClass.COMMAND);

        // 抢占：从 ROUTINE 开始
        QoSPriorityQueue.QueueEntry preempted = queue.preemptLowPriority();
        assertThat(preempted.priorityClass()).isEqualTo(QoSPriorityQueue.PriorityClass.ROUTINE);

        // 再抢占：MAPPING
        preempted = queue.preemptLowPriority();
        assertThat(preempted.priorityClass()).isEqualTo(QoSPriorityQueue.PriorityClass.MAPPING);

        // 再抢占：COMMAND（最低可抢占优先级）
        preempted = queue.preemptLowPriority();
        assertThat(preempted.priorityClass()).isEqualTo(QoSPriorityQueue.PriorityClass.COMMAND);

        // 无更多低优先级帧可抢占
        assertThat(queue.preemptLowPriority()).isNull();
    }

    @Test
    @DisplayName("空队列出队返回 null")
    void emptyQueueDequeueReturnsNull() {
        QoSPriorityQueue queue = new QoSPriorityQueue();
        assertThat(queue.dequeue()).isNull();
        assertThat(queue.dequeue(QoSPriorityQueue.PriorityClass.EMERGENCY)).isNull();
        assertThat(queue.preemptLowPriority()).isNull();
    }

    @Test
    @DisplayName("size 按优先级统计正确")
    void sizeByPriority() {
        QoSPriorityQueue queue = new QoSPriorityQueue();
        queue.enqueue(createFrame(1, 10), QoSPriorityQueue.PriorityClass.EMERGENCY);
        queue.enqueue(createFrame(2, 20), QoSPriorityQueue.PriorityClass.EMERGENCY);
        queue.enqueue(createFrame(3, 30), QoSPriorityQueue.PriorityClass.COMMAND);
        queue.enqueue(createFrame(4, 40), QoSPriorityQueue.PriorityClass.ROUTINE);

        assertThat(queue.size()).isEqualTo(4);
        assertThat(queue.size(QoSPriorityQueue.PriorityClass.EMERGENCY)).isEqualTo(2);
        assertThat(queue.size(QoSPriorityQueue.PriorityClass.COMMAND)).isEqualTo(1);
        assertThat(queue.size(QoSPriorityQueue.PriorityClass.ROUTINE)).isEqualTo(1);
    }

    @Test
    @DisplayName("isEmpty 和 clear")
    void isEmptyAndClear() {
        QoSPriorityQueue queue = new QoSPriorityQueue();
        assertThat(queue.isEmpty()).isTrue();

        queue.enqueue(createFrame(1, 10), QoSPriorityQueue.PriorityClass.EMERGENCY);
        assertThat(queue.isEmpty()).isFalse();

        queue.clear();
        assertThat(queue.isEmpty()).isTrue();
        assertThat(queue.size()).isZero();
    }

    @Test
    @DisplayName("PriorityClass fromCode 反查正确")
    void priorityClassFromCode() {
        assertThat(QoSPriorityQueue.PriorityClass.fromCode(0)).isEqualTo(QoSPriorityQueue.PriorityClass.EMERGENCY);
        assertThat(QoSPriorityQueue.PriorityClass.fromCode(1)).isEqualTo(QoSPriorityQueue.PriorityClass.COMMAND);
        assertThat(QoSPriorityQueue.PriorityClass.fromCode(2)).isEqualTo(QoSPriorityQueue.PriorityClass.MAPPING);
        assertThat(QoSPriorityQueue.PriorityClass.fromCode(3)).isEqualTo(QoSPriorityQueue.PriorityClass.ROUTINE);
        // 越界值默认 ROUTINE
        assertThat(QoSPriorityQueue.PriorityClass.fromCode(99)).isEqualTo(QoSPriorityQueue.PriorityClass.ROUTINE);
    }

    @Test
    @DisplayName("EMERGENCY 帧与 ROUTINE 帧同时到达 → EMERGENCY 先被转发")
    void emergencyBeforeRoutine() {
        QoSPriorityQueue queue = new QoSPriorityQueue();
        // 同时入队（模拟同时到达）
        queue.enqueue(createFrame(1, 10), QoSPriorityQueue.PriorityClass.ROUTINE);
        queue.enqueue(createFrame(2, 20), QoSPriorityQueue.PriorityClass.EMERGENCY);

        QoSPriorityQueue.QueueEntry first = queue.dequeue();
        assertThat(first.priorityClass()).isEqualTo(QoSPriorityQueue.PriorityClass.EMERGENCY);
        assertThat(first.frame().getSystemId()).isEqualTo(2);
    }
}
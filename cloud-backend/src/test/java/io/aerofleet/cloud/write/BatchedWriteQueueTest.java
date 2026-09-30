package io.aerofleet.cloud.write;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link BatchedWriteQueue} 单元测试。
 * <p>
 * 每条断言都对应这个组件存在的一个理由：不阻塞生产者、成批落地、失败有兜底、
 * 关闭不丢数、以及**写不发生在调用线程上**（这是这一批的全部动机）。
 */
@DisplayName("BatchedWriteQueue 有界批量写队列")
class BatchedWriteQueueTest {

    /** 轮询等待某个条件成立，替代"睡一下再看"的假确定性。 */
    private static void awaitUntil(java.util.function.BooleanSupplier condition, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("条件未在 " + timeoutMs + "ms 内成立");
    }

    @Test
    @DisplayName("sink 收到的批次不超过 batchSize，且总条数守恒")
    void batchesRespectBatchSize() throws InterruptedException {
        List<List<Integer>> batches = new CopyOnWriteArrayList<>();
        BatchedWriteQueue<Integer> queue = new BatchedWriteQueue<>(
                "t-batch", 1000, 50, 20,
                batch -> batches.add(new ArrayList<>(batch)),
                (batch, err) -> {
                });

        for (int i = 0; i < 120; i++) {
            assertThat(queue.offer(i)).isTrue();
        }
        awaitUntil(() -> queue.writtenCount() == 120, 3000);
        queue.close();

        assertThat(batches.stream().mapToInt(List::size).sum()).isEqualTo(120);
        assertThat(batches).allMatch(b -> b.size() <= 50);
        // 120 条 / 批上限 50 → 至少 3 批，证明它真的在合批而不是逐条提交
        assertThat(batches.size()).isGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("落库发生在 writer 线程，不是调用线程")
    void writesHappenOnWriterThreadNotCallerThread() throws InterruptedException {
        AtomicReference<String> writerSeen = new AtomicReference<>();
        BatchedWriteQueue<Integer> queue = new BatchedWriteQueue<>(
                "t-thread", 100, 10, 20,
                batch -> writerSeen.set(Thread.currentThread().getName()),
                (batch, err) -> {
                });

        queue.offer(1);
        awaitUntil(() -> writerSeen.get() != null, 2000);
        queue.close();

        assertThat(writerSeen.get())
                .isEqualTo("db-write-t-thread")
                .isNotEqualTo(Thread.currentThread().getName());
    }

    @Test
    @DisplayName("队列满时 offer 立即返回 false，绝不阻塞生产者")
    void overflowRejectsWithoutBlocking() throws Exception {
        CountDownLatch sinkStarted = new CountDownLatch(1);
        CountDownLatch releaseSink = new CountDownLatch(1);
        BatchedWriteQueue<Integer> queue = new BatchedWriteQueue<>(
                "t-overflow", 1, 1, 5,
                batch -> {
                    sinkStarted.countDown();
                    try {
                        releaseSink.await(3, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                },
                (batch, err) -> {
                });

        // 第一条被 writer 取走并卡在 sink 里，队列随即只有 capacity=1 的余量
        queue.offer(1);
        assertThat(sinkStarted.await(2, TimeUnit.SECONDS)).isTrue();
        queue.offer(2);   // 占满队列

        long start = System.nanoTime();
        boolean accepted = queue.offer(3);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(accepted).as("队列满时应拒绝而不是背压").isFalse();
        assertThat(elapsedMs).as("offer 必须是立即返回（<100ms）").isLessThan(100);
        assertThat(queue.rejectedCount()).isGreaterThanOrEqualTo(1);

        releaseSink.countDown();
        queue.close();
    }

    @Test
    @DisplayName("落库异常整批交给兜底回调，队列继续工作")
    void failureHandsWholeBatchToCallback() throws InterruptedException {
        List<List<Integer>> failed = new CopyOnWriteArrayList<>();
        AtomicReference<List<Integer>> okBatch = new AtomicReference<>();
        BatchedWriteQueue<Integer> queue = new BatchedWriteQueue<>(
                "t-fail", 100, 10, 10,
                batch -> {
                    if (batch.contains(99)) {
                        throw new IllegalStateException("db down");
                    }
                    okBatch.set(new ArrayList<>(batch));
                },
                (batch, err) -> failed.add(new ArrayList<>(batch)));

        queue.offer(99);
        awaitUntil(() -> !failed.isEmpty(), 2000);
        queue.offer(7);
        awaitUntil(() -> okBatch.get() != null, 2000);
        queue.close();

        assertThat(failed.get(0)).containsExactly(99);
        assertThat(okBatch.get()).containsExactly(7);
    }

    @Test
    @DisplayName("close 前入队的条目全部落地，不静默丢失")
    void closeDrainsPendingItems() {
        List<Integer> landed = Collections.synchronizedList(new ArrayList<>());
        BatchedWriteQueue<Integer> queue = new BatchedWriteQueue<>(
                "t-close", 1000, 50, 20,
                landed::addAll,
                (batch, err) -> {
                });

        for (int i = 0; i < 200; i++) {
            queue.offer(i);
        }
        queue.close();

        assertThat(landed).hasSize(200);
        assertThat(queue.pendingCount()).isZero();
    }

    @Test
    @DisplayName("关闭后再 offer 不抛错、不吞线程，只拒绝")
    void offerAfterCloseIsRejectedNotThrown() {
        BatchedWriteQueue<Integer> queue = new BatchedWriteQueue<>(
                "t-closed", 10, 5, 10,
                batch -> {
                },
                (batch, err) -> {
                });
        queue.close();

        assertThatCode(() -> queue.offer(1)).doesNotThrowAnyException();
        assertThat(queue.offer(2)).isFalse();
    }
}

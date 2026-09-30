package io.aerofleet.cloud.write;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 有界、单线程、成批落地的写队列：把数据库写从调用线程（尤其是 MAVLink UDP 接收线程
 * 与那个只有 1 条线程的调度池）上摘下来。
 * <p>
 * <b>为什么需要它</b>：{@code aerofleet.flightlog.persist-to-db=true} 一旦打开，
 * 每次遥测快照与每条告警都会在<b>产生它的线程</b>上做一次同步 JPA 写——告警路径是
 * {@code TelemetrySnapshotListener → AlertBus.publish()（同步派发的
 * CopyOnWriteArrayList）→ TelemetryPusher.pushAlert → FlightLogService.append →
 * repository.save}，而这条链的起点就是 {@code UdpMavlinkTransport} 的
 * {@code mavlink-udp-<port>} 单线程接收循环。DB 一慢，收包就停：丢心跳、命令延迟、
 * 限速判定失真。默认关（false）时只追加文件，所以这个阻塞从来没有被观测到——
 * 它是"遥测入库可以打开"的前置条件，不是可选优化。
 * <p>
 * <b>溢出与失败都不回压</b>：{@link #offer(Object)} 在队列满时返回 false，
 * 由调用方就地决定兜底（本仓的兜底是既有的 JSONL 追加），绝不阻塞生产者。
 * 落库抛异常时整批交给 {@code onFailure} 回调，同样由调用方兜底——
 * 保留"DB 不可用不影响记账"的原有语义。
 * <p>
 * <b>线程是 daemon</b>：测试里每个用例都会 new 一个持有本队列的服务而不显式关闭，
 * 非守护线程会把 JVM 挂住。正常运行期的排空靠 {@link #close()}。
 *
 * @param <T> 待写入的条目类型
 */
public final class BatchedWriteQueue<T> {

    private static final Logger log = LoggerFactory.getLogger(BatchedWriteQueue.class);

    private final String name;
    private final BlockingQueue<T> queue;
    private final Consumer<List<T>> sink;
    private final java.util.function.BiConsumer<List<T>, Exception> onFailure;
    private final int batchSize;
    private final long flushIntervalMs;

    private final AtomicLong offered = new AtomicLong();
    private final AtomicLong written = new AtomicLong();
    private final AtomicLong failedBatches = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    /** 正在提交中的批次数（队列已空但批次未完时不算空闲）。 */
    private final AtomicLong inFlight = new AtomicLong();

    private volatile boolean running = true;
    private final Thread writer;

    /**
     * @param name            日志标识（统计行与告警都用它）
     * @param capacity        队列容量；满即 {@code offer} 返回 false，由调用方兜底
     * @param batchSize       单次最多取多少条交给 sink（应等于 Hibernate jdbc.batch_size）
     * @param flushIntervalMs 空闲时的轮询间隔，也决定 close() 的最长排空延迟
     * @param sink            实际落库动作，整批调用；抛异常视为该批失败
     * @param onFailure       该批失败的兜底动作（参数为原批次与异常）
     */
    public BatchedWriteQueue(String name, int capacity, int batchSize, long flushIntervalMs,
                             Consumer<List<T>> sink,
                             java.util.function.BiConsumer<List<T>, Exception> onFailure) {
        this.name = name;
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.batchSize = batchSize;
        this.flushIntervalMs = flushIntervalMs;
        this.sink = sink;
        this.onFailure = onFailure;
        this.writer = new Thread(this::runLoop, "db-write-" + name);
        this.writer.setDaemon(true);
        this.writer.start();
    }

    /**
     * 入队一条。
     *
     * @return true 已接收；false 队列满或已停止——调用方必须自己兜底，这里不阻塞也不丢计数
     */
    public boolean offer(T item) {
        if (!running || !queue.offer(item)) {
            rejected.incrementAndGet();
            return false;
        }
        offered.incrementAndGet();
        return true;
    }

    /**
     * 停止接收并把残余条目写完（最多等 {@code flushIntervalMs} 后进入最后一批）。
     * 调用方应保证此后不再 offer。
     */
    public void close() {
        running = false;
        try {
            writer.join(Math.max(2000L, flushIntervalMs * 4));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!queue.isEmpty()) {
            log.warn("{} 写队列关闭时仍有 {} 条未落地，交给兜底回调", name, queue.size());
            List<T> rest = new ArrayList<>();
            queue.drainTo(rest);
            onFailure.accept(rest, new IllegalStateException("queue closed before flush"));
        }
    }

    private void runLoop() {
        while (running || !queue.isEmpty()) {
            List<T> batch = new ArrayList<>(batchSize);
            try {
                T first = queue.poll(flushIntervalMs, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                batch.add(first);
                queue.drainTo(batch, batchSize - 1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                queue.drainTo(batch, batchSize - 1);
                if (batch.isEmpty()) {
                    return;
                }
            }
            flush(batch);
        }
    }

    /**
     * 等待已入队的条目全部处理完（队列排空<b>且</b>没有批次正在提交中）。
     * <p>
     * 只看 {@code queue.isEmpty()} 会漏掉"已从队列取走、还在 sink 里执行"的批次，
     * 所以另有 {@link #inFlight} 计数。给测试与运维确认"这批已落地"用，
     * 生产路径不依赖它。
     *
     * @return true 已排空；false 超时（调用方自行决定是断言失败还是继续等）
     */
    public boolean awaitIdle(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (queue.isEmpty() && inFlight.get() == 0) {
                // 批次提交与计数清零之间有窗口，再让出一次调度避免假"空闲"
                Thread.sleep(5);
                if (queue.isEmpty() && inFlight.get() == 0) {
                    return true;
                }
            }
            Thread.sleep(5);
        }
        return queue.isEmpty() && inFlight.get() == 0;
    }

    /** 落一批；失败只记一次 WARN，避免 DB 抖动时每批刷一条堆栈。 */
    private void flush(List<T> batch) {
        inFlight.incrementAndGet();
        try {
            sink.accept(batch);
            written.addAndGet(batch.size());
        } catch (Exception e) {
            long n = failedBatches.incrementAndGet();
            if (n == 1 || n % 50 == 0) {
                log.warn("{} 批量落库失败（第 {} 批，{} 条）：{}", name, n, batch.size(), e.toString());
            } else {
                log.debug("{} 批量落库失败（第 {} 批，{} 条）：{}", name, n, batch.size(), e.toString());
            }
            onFailure.accept(batch, e);
        } finally {
            inFlight.decrementAndGet();
        }
    }

    /** 供日志与断言读取的计数快照。 */
    public String statsLine() {
        return String.format("queue=%s offered=%d written=%d failedBatches=%d rejected=%d pending=%d",
                name, offered.get(), written.get(), failedBatches.get(), rejected.get(), queue.size());
    }

    public long offeredCount() {
        return offered.get();
    }

    public long writtenCount() {
        return written.get();
    }

    public long rejectedCount() {
        return rejected.get();
    }

    public int pendingCount() {
        return queue.size();
    }
}

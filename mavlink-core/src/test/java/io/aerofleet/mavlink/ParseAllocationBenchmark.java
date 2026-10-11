package io.aerofleet.mavlink;

import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1 基准：MAVLink 帧解析路径的分配与吞吐画像。
 *
 * <p><b>为什么先测再改</b>：ROADMAP P1（"UDP 接收线程零分配：帧解码对象池化 +
 * GC 抖动消除"）是性能立项。但 {@code MavlinkParser.parse()} 每帧会分配
 * {@code new byte[payloadLen]} + {@code new MavlinkFrame} + {@code ParseResult}，
 * 而 **payload 会逃逸给异步 WS 推送**（TelemetryPusher 的 @Scheduled 才序列化），
 * 简单池化会把"复用"变成"数据竞态"。所以正确顺序是：先量出每帧分配量与
 * 解析吞吐，与真实负载（遥测 1-5Hz/机 ×在飞机队）对比，再决定是否值得为
 * 零拷贝改造 MavlinkFrame 的 API 语义（那会影响全部 payload 消费方）。
 *
 * <p><b>不进 CI</b>：用 {@code -Dbench=1} 显式开启（assumeTrue），
 * 默认跳过，不给 CI 时长增加负担。
 *
 * 用法：
 *   mvn test -pl mavlink-core -Dtest=ParseAllocationBenchmark -Dbench=1 \
 *     -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
 */
class ParseAllocationBenchmark {

    /** 与 drone-sim 遥测帧同量级的 payload（GLOBAL_POSITION_INT 类 28-33 字节）。 */
    private static final int PAYLOAD_LEN = 32;

    /**
     * 每线程分配字节（HotSpot 扩展接口：java.lang.management.ThreadMXBean 没有此方法；
     * CI runner 与生产都是 HotSpot/JVM，可用）。取不到时返回 -1，调用方降级跳过。
     */
    private static long threadAllocatedBytes() {
        try {
            var base = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
            if (!base.isThreadAllocatedMemorySupported()) {
                return -1;
            }
            if (!base.isThreadAllocatedMemoryEnabled()) {
                base.setThreadAllocatedMemoryEnabled(true); // 本进程内打开
            }
            return base.getThreadAllocatedBytes(Thread.currentThread().getId());
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /**
     * 造一帧可直接过 parser CRC 的 v2 帧（与真实链路同形状）。
     * CRC 用 MavlinkCrc 逐字段累加 + crcExtraOf（与 MavlinkParser.computeCrc
     * 同一口径；encodeV2 直接采用构造传入的 crc，不重算）。
     */
    private static byte[] buildFrame() {
        byte[] payload = new byte[PAYLOAD_LEN];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i * 7 + 3);
        }
        int msgId = 0; // HEARTBEAT：CRC_EXTRA 表内必有；parser 不按 msgId 解 payload，帧长/形状不变
        int crc = MavlinkCrc.init();
        crc = MavlinkCrc.accumulate(crc, payload.length);
        crc = MavlinkCrc.accumulate(crc, 0); // incompat
        crc = MavlinkCrc.accumulate(crc, 0); // compat
        crc = MavlinkCrc.accumulate(crc, 1); // seq
        crc = MavlinkCrc.accumulate(crc, 42); // sysid
        crc = MavlinkCrc.accumulate(crc, 1); // compid
        crc = MavlinkCrc.accumulate(crc, msgId & 0xFF);
        crc = MavlinkCrc.accumulate(crc, (msgId >> 8) & 0xFF);
        crc = MavlinkCrc.accumulate(crc, (msgId >> 16) & 0xFF);
        crc = MavlinkCrc.accumulate(crc, payload, 0, payload.length);
        crc = MavlinkCrc.accumulate(crc, MavlinkMessageInfo.crcExtraOf(msgId));
        MavlinkFrame frame = new MavlinkFrame(payload.length, 0, 0, 1, 42, 1, msgId,
                payload, crc, 0, 0L, null);
        return frame.encodeV2();
    }

    @Test
    void benchmark() {
        org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.getBoolean("bench")
                || System.getProperty("bench") != null,
                "跳过：加 -Dbench=1 开启基准");

        byte[] wire = buildFrame();
        int iterations = 2_000_000;

        // ---- 预热：JIT 生效后再测，避免冷启动偏差 ----
        warmup(wire, 200_000);

        // ---- 1) 吞吐 ----
        MavlinkParser parser = new MavlinkParser();
        long t0 = System.nanoTime();
        int frames = 0;
        for (int i = 0; i < iterations; i++) {
            ByteBuffer buf = ByteBuffer.wrap(wire);
            while (parser.parse(buf) != null) {
                frames++;
            }
        }
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        double framesPerSec = frames * 1000.0 / Math.max(1, elapsedMs);

        // ---- 2) 每帧分配（BytesAllocated 增量 / 帧数）----
        MavlinkParser allocParser = new MavlinkParser();
        int allocIters = 200_000;
        long a0 = threadAllocatedBytes();
        int allocFrames = 0;
        for (int i = 0; i < allocIters; i++) {
            ByteBuffer buf = ByteBuffer.wrap(wire);
            while (allocParser.parse(buf) != null) {
                allocFrames++;
            }
        }
        long a1 = threadAllocatedBytes();
        double bytesPerFrame = (a0 < 0 || a1 < 0 || allocFrames == 0)
                ? -1 : (a1 - a0) / (double) allocFrames;

        // ---- 3) 单帧最坏延迟（pacing 抖动：每 50k 帧采一次）----
        MavlinkParser paceParser = new MavlinkParser();
        long worstNs = 0;
        for (int i = 0; i < 500_000; i++) {
            ByteBuffer buf = ByteBuffer.wrap(wire);
            long s = System.nanoTime();
            paceParser.parse(buf);
            long d = System.nanoTime() - s;
            if ((i % 50_000) == 0 && d > worstNs) {
                worstNs = d;
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n===== P1 基准：MAVLink 帧解析路径 =====\n");
        sb.append(String.format("帧长（含头/CRC）        : %d bytes%n", wire.length));
        sb.append(String.format("解析吞吐                 : %s 帧/秒 (%,d 帧耗时 %d ms)%n",
                String.format("%,.0f", framesPerSec), frames, elapsedMs));
        sb.append(bytesPerFrame < 0
                ? "每帧堆分配               : 本 JVM 不支持线程分配计量（跳过）%n"
                : String.format("每帧堆分配               : %.1f bytes/帧%n", bytesPerFrame));
        sb.append(String.format("单帧最坏解析耗时         : %.1f μs%n", worstNs / 1000.0));
        sb.append(String.format("参考负载（遥测 5Hz×9 机）: 45 帧/秒%n"));
        sb.append("========================================\n");
        System.out.println(sb);

        // 基准自身不断言性能阈值——数字是给人决策的；这里只保证解析真的发生了
        assertTrue(frames >= iterations, "解析帧数应至少等于迭代数");
    }

    private static void warmup(byte[] wire, int iterations) {
        MavlinkParser warm = new MavlinkParser();
        for (int i = 0; i < iterations; i++) {
            ByteBuffer buf = ByteBuffer.wrap(wire);
            while (warm.parse(buf) != null) {
                // 丢弃结果
            }
        }
    }
}

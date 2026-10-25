package io.aerofleet.linksim;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 链路损伤引擎：把一份链路规格变成数据包级别的命运判决。
 *
 * 四类损伤相互独立、可叠加：
 *  - 延迟：base + uniform(0, jitter)
 *  - 丢包：Gilbert-Elliot 两状态马尔可夫模型。真实无线链路的丢包是
 *    "突发+有记忆"的——一个坏区往往连坏几包。独立随机丢包测不出
 *    协议的重传韧性，必须用 GE。
 *    pGoodDrop: 好状态丢包率; pBadDrop: 坏状态丢包率;
 *    pGoodToBad: 好->坏转移率; pBadToGood: 坏->好恢复率。
 *  - 带宽：令牌桶。桶容量 burst 字节，按 rate 字节/秒补充——
 *    MAVLink 遥测约 2-5 KB/s，视频流是 30KB/s+ 量级。
 *  - 分区：up/down 周期性黑洞（模拟 Starlink 换星/树遮挡/数传被干扰）。
 */
final class ImpairmentEngine {

    // --- 延迟（毫秒） ---
    // 非 final：P2 #20 起支持运行时 PATCH /profile。所有读写都在 synchronized
    // （verdict / apply / spec / stats）里，参数替换对判决是原子的。
    double baseDelayMs;
    double jitterMs;

    // --- GE 丢包 ---
    private double pGoodDrop;
    private double pBadDrop;
    private double pGoodToBad;
    private double pBadToGood;

    // --- 令牌桶 ---
    private double burstBytes;
    private double rateBytesPerSec;

    // --- 周期分区 ---
    private double partitionUpSec;
    private double partitionDownSec;

    // --- 运行态 ---
    private boolean inBadState = false;
    private long tokens;
    private long lastRefillNs = System.nanoTime();
    private final long bootMs = System.currentTimeMillis();
    private long forwarded;
    private long dropped;
    private long totalDelayMs;

    ImpairmentEngine(double baseDelayMs, double jitterMs,
                     double pGoodDrop, double pBadDrop, double pGoodToBad, double pBadToGood,
                     double burstBytes, double rateBytesPerSec,
                     double partitionUpSec, double partitionDownSec) {
        this.baseDelayMs = baseDelayMs;
        this.jitterMs = jitterMs;
        this.pGoodDrop = pGoodDrop;
        this.pBadDrop = pBadDrop;
        this.pGoodToBad = pGoodToBad;
        this.pBadToGood = pBadToGood;
        this.burstBytes = burstBytes;
        this.rateBytesPerSec = rateBytesPerSec;
        this.partitionUpSec = partitionUpSec;
        this.partitionDownSec = partitionDownSec;
        this.tokens = (long) burstBytes;
    }

    /**
     * 按完整规格构造（P2 #20 运行时控制通道用；与十参构造器等价）。
     */
    ImpairmentEngine(ImpairmentSpec spec) {
        this(spec.delayMs(), spec.jitterMs(), spec.pGoodDrop(), spec.pBadDrop(),
                spec.pGoodToBad(), spec.pBadToGood(), spec.burstBytes(),
                spec.rateBytesPerSec(), spec.partitionUpSec(), spec.partitionDownSec());
    }

    /**
     * 原子替换损伤参数（P2 #20）：控制通道收到 PATCH 后调用。
     *
     * <p>语义边界：
     * <ul>
     *   <li>统计计数（forwarded/dropped/totalDelayMs）跨参数替换保留——面板上的
     *       累计值不应因为一次调参从零开始；</li>
     *   <li>令牌桶的现有令牌被 clamp 到新桶容量（新桶更小则立即生效，不会带着
     *       超额令牌运行）；</li>
     *   <li>分区相位仍以进程启动时刻为基准（{@code bootMs} 不变）——运行期改
     *       分区参数不会重置周期相位，这是刻意行为，避免调参本身制造一次假黑洞。</li>
     * </ul>
     */
    synchronized void apply(ImpairmentSpec spec) {
        this.baseDelayMs = spec.delayMs();
        this.jitterMs = spec.jitterMs();
        this.pGoodDrop = spec.pGoodDrop();
        this.pBadDrop = spec.pBadDrop();
        this.pGoodToBad = spec.pGoodToBad();
        this.pBadToGood = spec.pBadToGood();
        this.burstBytes = spec.burstBytes();
        this.rateBytesPerSec = spec.rateBytesPerSec();
        this.partitionUpSec = spec.partitionUpSec();
        this.partitionDownSec = spec.partitionDownSec();
        this.tokens = Math.min(this.tokens, (long) spec.burstBytes());
        this.lastRefillNs = System.nanoTime();
    }

    /** 当前参数的不可变快照（控制面读取；与 apply 同一把锁）。 */
    synchronized ImpairmentSpec spec() {
        return new ImpairmentSpec(baseDelayMs, jitterMs, pGoodDrop, pBadDrop,
                pGoodToBad, pBadToGood, burstBytes, rateBytesPerSec,
                partitionUpSec, partitionDownSec);
    }

    /** 累计统计快照（控制面读取）。 */
    synchronized Stats statsSnapshot() {
        long total = forwarded + dropped;
        return new Stats(forwarded, dropped,
                total == 0 ? 0 : 100.0 * dropped / total,
                forwarded == 0 ? 0 : totalDelayMs / forwarded);
    }

    /** 不可变统计视图。 */
    record Stats(long forwarded, long dropped, double dropPct, long avgDelayMs) {
    }

    /** 数据包判决：返回放行延迟毫秒；-1 表示丢弃。 */
    synchronized long verdict(int packetBytes) {
        long nowMs = System.currentTimeMillis();

        // 1) 周期分区：down 窗口内全部黑洞
        if (partitionDownSec > 0 && partitionUpSec > 0) {
            double t = (nowMs - bootMs) / 1000.0;
            double cycle = partitionUpSec + partitionDownSec;
            if (t % cycle >= partitionUpSec) {
                dropped++;
                return -1;
            }
        }

        // 2) GE 丢包：先按当前状态抽签，再走状态机
        double dropP = inBadState ? pBadDrop : pGoodDrop;
        boolean lost = ThreadLocalRandom.current().nextDouble() < dropP;
        stepState();
        if (lost) {
            dropped++;
            return -1;
        }

        // 3) 令牌桶带宽：桶空即丢（无线链路拥塞的真实行为是丢而非排无穷长的队）
        if (rateBytesPerSec > 0) {
            long now = System.nanoTime();
            double elapsedSec = (now - lastRefillNs) / 1e9;
            tokens = (long) Math.min(burstBytes, tokens + elapsedSec * rateBytesPerSec);
            lastRefillNs = now;
            if (tokens < packetBytes) {
                dropped++;
                return -1;
            }
            tokens -= packetBytes;
        }

        // 4) 延迟：base + 均匀抖动
        long delay = (long) (baseDelayMs
                + (jitterMs > 0 ? ThreadLocalRandom.current().nextDouble(jitterMs) : 0));
        forwarded++;
        totalDelayMs += delay;
        return delay;
    }

    private void stepState() {
        if (inBadState) {
            if (ThreadLocalRandom.current().nextDouble() < pBadToGood) {
                inBadState = false;
            }
        } else {
            if (ThreadLocalRandom.current().nextDouble() < pGoodToBad) {
                inBadState = true;
            }
        }
    }

    synchronized String stats() {
        Stats s = statsSnapshot();
        return String.format("fwd=%d drop=%d(%.1f%%) avgDelay=%dms",
                s.forwarded(), s.dropped(), s.dropPct(), s.avgDelayMs());
    }
}

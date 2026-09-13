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
    final double baseDelayMs;
    final double jitterMs;

    // --- GE 丢包 ---
    private final double pGoodDrop;
    private final double pBadDrop;
    private final double pGoodToBad;
    private final double pBadToGood;

    // --- 令牌桶 ---
    private final double burstBytes;
    private final double rateBytesPerSec;

    // --- 周期分区 ---
    private final double partitionUpSec;
    private final double partitionDownSec;

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
        long total = forwarded + dropped;
        return String.format("fwd=%d drop=%d(%.1f%%) avgDelay=%dms",
                forwarded, dropped, total == 0 ? 0 : 100.0 * dropped / total,
                forwarded == 0 ? 0 : totalDelayMs / forwarded);
    }
}

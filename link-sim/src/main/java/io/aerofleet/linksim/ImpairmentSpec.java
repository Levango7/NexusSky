package io.aerofleet.linksim;

/**
 * 一份完整的链路损伤参数（{@link ImpairmentEngine} 的全部输入）。
 *
 * <p>存在的理由：P2 #20 之前损伤参数是构造器入参、字段全 final，进程启动后
 * 不可再改。运行时控制通道（{@code PATCH /profile}）需要把一份新参数原子地
 * 交给引擎，于是把"十元组"收敛成一个不可变 record —— 校验在控制层做，
 * 引擎只负责应用，避免两处各写一遍字段清单。
 *
 * <p>{@code pGoodDrop / pBadDrop / pGoodToBad / pBadToGood} 是 Gilbert-Elliot
 * 四参数；控制面板的"丢包率"滑块走 {@link #uniformDrop}：pGood=pBad=p 且
 * 不进坏态，即<b>独立随机丢包</b>（不模拟画像的突发记忆）。
 */
record ImpairmentSpec(double delayMs,
                      double jitterMs,
                      double pGoodDrop,
                      double pBadDrop,
                      double pGoodToBad,
                      double pBadToGood,
                      double burstBytes,
                      double rateBytesPerSec,
                      double partitionUpSec,
                      double partitionDownSec) {

    /** 画像声明的默认参数。 */
    static ImpairmentSpec of(LinkProfile profile) {
        return new ImpairmentSpec(profile.delayMs, profile.jitterMs,
                profile.pGoodDrop, profile.pBadDrop,
                profile.pGoodToBad, profile.pBadToGood,
                profile.burstBytes, profile.rateBytesPerSec,
                profile.partitionUpSec, profile.partitionDownSec);
    }

    /** 由本规格建一个引擎（保留给测试/一次性使用；运行时更新用 {@link ImpairmentEngine#apply}）。 */
    ImpairmentEngine engine() {
        return new ImpairmentEngine(this);
    }

    /** 令牌桶速率换算成 kbps（控制面/前端用；0 表示不限速）。 */
    double bandwidthKbps() {
        return rateBytesPerSec * 8.0 / 1000.0;
    }

    /** 分区周期 = 好窗口 + 黑洞窗口（秒）。 */
    double partitionCycleSec() {
        return partitionUpSec + partitionDownSec;
    }

    /**
     * 独立随机丢包：把 GE 收敛成 Bernoulli(p)（pGood=pBad=p，pGoodToBad=0，
     * pBadToGood=1）。画像原有的 GE 突发语义被显式替换，调用方必须把它
     * 当作"模式切换"而不是"调一个参数"。
     */
    ImpairmentSpec withUniformDrop(double dropPct) {
        double p = dropPct / 100.0;
        return new ImpairmentSpec(delayMs, jitterMs, p, p, 0, 1,
                burstBytes, rateBytesPerSec, partitionUpSec, partitionDownSec);
    }
}

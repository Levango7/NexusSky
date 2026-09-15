package io.aerofleet.linksim;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 雨衰叠加包装层（FR-15/16，M0b 环境气象）：包装 {@link ImpairmentEngine}，
 * 在既有判决之上按环境天气状态叠加额外延迟/丢包。
 * <p>
 * 不修改 {@link ImpairmentEngine#verdict(int)} 源码与统计语义（FR-16）——
 * 本类持一个 base 引用，先调 base.verdict() 得既有判决，再叠加雨衰影响。
 * <p>
 * 环境状态由外部通过 {@link #updateEnvironment(int, int)} 松耦合更新
 * （link-sim 监听 ENVIRONMENT_STATUS msgId=422 帧读取 weather + rainRate）。
 * <p>
 * 包私有——由同包 {@link LinkSimMain} 在 --env-coupled 启用时构造。
 */
final class EnvAwareImpairmentEngine {
    private final ImpairmentEngine base;
    /** 环境天气状态（volatile，由环境状态读取线程写，verdict 线程读）。 */
    private volatile int weatherCode;
    private volatile int rainRate;
    /** 基线延迟 ms（用于计算额外延迟比例）。 */
    private final double baseDelayMs;
    /** 雨衰叠加独立统计。 */
    private long rainForwarded;
    private long rainDropped;

    /**
     * 构造雨衰叠加包装。
     *
     * @param base        被包装的既有损伤引擎（不修改）
     * @param baseDelayMs 基线延迟 ms（用于额外延迟计算）
     */
    EnvAwareImpairmentEngine(ImpairmentEngine base, double baseDelayMs) {
        this.base = base;
        this.baseDelayMs = baseDelayMs;
        this.weatherCode = 0;  // 默认 CLEAR
        this.rainRate = 0;
    }

    /**
     * 更新环境状态（松耦合，由环境状态读取线程调用）。
     *
     * @param weatherCode 天气枚举 code（0=CLEAR,1=CLOUDY,2=RAIN,3=SNOW,4=FOG）
     * @param rainRate    降雨率 mm/h
     */
    void updateEnvironment(int weatherCode, int rainRate) {
        this.weatherCode = weatherCode;
        this.rainRate = rainRate;
    }

    /**
     * 数据包判决：先调 base.verdict() 得既有判决，再叠加雨衰影响。
     * <ul>
     *   <li>base 判丢弃（delay &lt; 0）：直接返回 -1</li>
     *   <li>环境无衰减（attenDb ≤ 0）：返回原 delay</li>
     *   <li>按 extraDropProb 概率丢包；否则叠加 extraDelayMs 返回</li>
     * </ul>
     *
     * @param packetBytes 数据包字节数
     * @return 放行延迟 ms；-1 表示丢弃
     */
    long verdict(int packetBytes) {
        long delay = base.verdict(packetBytes);
        if (delay < 0) {
            return -1;  // base 已丢弃
        }
        double attenDb = RainAttenuation.attenuationDb(weatherCode, rainRate);
        if (attenDb <= 0) {
            rainForwarded++;
            return delay;  // 无雨衰叠加
        }
        // 额外丢包概率
        if (ThreadLocalRandom.current().nextDouble() < RainAttenuation.extraDropProb(attenDb)) {
            rainDropped++;
            return -1;
        }
        // 叠加额外延迟
        rainForwarded++;
        return delay + (long) RainAttenuation.extraDelayMs(attenDb, baseDelayMs);
    }

    /** 雨衰叠加独立统计（不影响 base.stats()）。 */
    String rainStats() {
        long total = rainForwarded + rainDropped;
        return String.format("rainFwd=%d rainDrop=%d(%.1f%%)",
                rainForwarded, rainDropped,
                total == 0 ? 0 : 100.0 * rainDropped / total);
    }

    /** 被包装的既有引擎（用于 stats 聚合）。 */
    ImpairmentEngine base() {
        return base;
    }
}
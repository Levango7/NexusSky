package io.aerofleet.linksim;

/**
 * 简化 ITU-R P.838 雨衰模型 + 雾衰常数（FR-15，M0b 环境气象）。
 * <p>
 * 雨衰：γ = K · R^α · d（dB），简化 K=0.0001、α=1.0、d=1km；
 * 雾衰：常数 3dB（能见度 300m 量级）；
 * 晴朗/多云：0dB（无叠加）。
 * <p>
 * 叠加策略（由 {@link EnvAwareImpairmentEngine} 调用）：
 * <ul>
 *   <li>额外延迟 = attenDb × 0.1 × baseDelayMs（每 dB → 10% 基线延迟）</li>
 *   <li>额外丢包概率 = min(0.05, attenDb × 0.005)（每 dB → 0.5% 丢包，上限 5%）</li>
 * </ul>
 * <p>
 * 包私有——由同包 {@link EnvAwareImpairmentEngine} 调用。
 */
final class RainAttenuation {
    /** 雨衰系数 K（简化 ITU-R P.838，2.4GHz 量级）。 */
    static final double K = 0.0001;
    /** 雨衰指数 α（简化线性）。 */
    static final double ALPHA = 1.0;
    /** 雾衰常数（dB，能见度 300m 量级）。 */
    static final double FOG_ATTEN_DB = 3.0;
    /** 链路距离（km，简化单跳 1km）。 */
    static final double LINK_DISTANCE_KM = 1.0;

    private RainAttenuation() {
    }

    /**
     * 计算环境天气引起的附加衰减（dB）。
     *
     * @param weatherCode   天气枚举 code（0=CLEAR,1=CLOUDY,2=RAIN,3=SNOW,4=FOG）
     * @param rainRateMmPerH 降雨率 mm/h
     * @return 衰减 dB（≥0，CLEAR/CLOUDY 为 0）
     */
    static double attenuationDb(int weatherCode, int rainRateMmPerH) {
        return switch (weatherCode) {
            case 4 -> FOG_ATTEN_DB;  // FOG
            case 2, 3 -> K * Math.pow(Math.max(0, rainRateMmPerH), ALPHA) * LINK_DISTANCE_KM;  // RAIN/SNOW
            default -> 0;  // CLEAR/CLOUDY/未知
        };
    }

    /**
     * 额外延迟（ms）：每 dB → 10% 基线延迟。
     *
     * @param attenDb     衰减 dB
     * @param baseDelayMs 基线延迟 ms
     * @return 额外延迟 ms（≥0）
     */
    static double extraDelayMs(double attenDb, double baseDelayMs) {
        return attenDb * 0.1 * baseDelayMs;
    }

    /**
     * 额外丢包概率：每 dB → 0.5% 丢包，上限 5%（数据约束 6.4）。
     *
     * @param attenDb 衰减 dB
     * @return 额外丢包概率 [0, 0.05]
     */
    static double extraDropProb(double attenDb) {
        return Math.min(0.05, attenDb * 0.005);
    }
}
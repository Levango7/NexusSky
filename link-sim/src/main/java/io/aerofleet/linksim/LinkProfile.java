package io.aerofleet.linksim;

/**
 * 真实链路画像：参数来自公开的链路测量文献与行业经验值（数量级正确，
 * 用于测试协议韧性，不是精确复现某运营商）。
 *
 * delayMs/jitterMs: 单向；pG/pB: 好坏状态丢包率；pGB/pBG: GE 状态转移率；
 * burst/rate: 令牌桶；up/down: 周期分区秒数（0 = 无分区）。
 */
enum LinkProfile {

    /** 局域网基线（无损伤，对照组） */
    LAN("lan", 0, 1, 0, 0, 0, 0, 1_000_000, 1_000_000, 0, 0),

    /** 5GHz WiFi 近距离：偶发抖动，几乎不丢 */
    WIFI5("wifi5", 2, 4, 0.001, 0.30, 0.002, 0.5, 64_000, 400_000, 0, 0),

    /** 2.4GHz WiFi 远距离/干扰环境：GE 突发丢包明显 */
    WIFI24_FAR("wifi24-far", 8, 25, 0.01, 0.45, 0.05, 0.4, 32_000, 150_000, 0, 0),

    /** 4G LTE 蜂窝：30-80ms，切换时突发丢包 */
    LTE("lte", 45, 35, 0.005, 0.25, 0.01, 0.5, 16_000, 80_000, 0, 0),

    /** 4G 弱信号小区边缘：高丢包+高抖动，协议韧性试金石 */
    LTE_EDGE("lte-edge", 120, 90, 0.03, 0.55, 0.08, 0.3, 8_000, 30_000, 0, 0),

    /** 数传电台（SiK 57600bps 空中链路）：窄带宽是第一约束，长延迟 */
    RADIO_SIK("radio-sik", 60, 40, 0.02, 0.35, 0.03, 0.35, 2_000, 5_760, 0, 0),

    /** Starlink：低延迟好带宽，但换星/遮挡造成周期性 2-3s 中断 */
    STARLINK("starlink", 35, 20, 0.001, 0.30, 0.005, 0.5, 128_000, 600_000, 25, 2.5);

    final String name;
    final double delayMs;
    final double jitterMs;
    final double pGoodDrop;
    final double pBadDrop;
    final double pGoodToBad;
    final double pBadToGood;
    final double burstBytes;
    final double rateBytesPerSec;
    final double partitionUpSec;
    final double partitionDownSec;

    LinkProfile(String name, double delayMs, double jitterMs,
                double pGoodDrop, double pBadDrop, double pGoodToBad, double pBadToGood,
                double burstBytes, double rateBytesPerSec,
                double partitionUpSec, double partitionDownSec) {
        this.name = name;
        this.delayMs = delayMs;
        this.jitterMs = jitterMs;
        this.pGoodDrop = pGoodDrop;
        this.pBadDrop = pBadDrop;
        this.pGoodToBad = pGoodToBad;
        this.pBadToGood = pBadToGood;
        this.burstBytes = burstBytes;
        this.rateBytesPerSec = rateBytesPerSec;
        this.partitionUpSec = partitionUpSec;
        this.partitionDownSec = partitionDownSec;
    }

    ImpairmentEngine engine() {
        return new ImpairmentEngine(delayMs, jitterMs, pGoodDrop, pBadDrop,
                pGoodToBad, pBadToGood, burstBytes, rateBytesPerSec,
                partitionUpSec, partitionDownSec);
    }

    static LinkProfile of(String name) {
        for (LinkProfile p : values()) {
            if (p.name.equalsIgnoreCase(name)) {
                return p;
            }
        }
        return null;
    }

    static String names() {
        StringBuilder sb = new StringBuilder();
        for (LinkProfile p : values()) {
            sb.append(p.name).append(' ');
        }
        return sb.toString().trim();
    }
}

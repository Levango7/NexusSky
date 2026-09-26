package io.aerofleet.linksim;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 链路质量监控器（FR-01 异构链路桥接辅助）。
 * <p>
 * 监控各通信介质的链路质量指标（RSSI、丢包率、延迟），在链路质量降级时
 * 自动推荐切换到备用介质，保障通信连续性。
 *
 * <h3>核心职责</h3>
 * <ul>
 *   <li>记录各介质的链路质量指标（RSSI、丢包率、延迟）</li>
 *   <li>评估链路质量等级（EXCELLENT/GOOD/FAIR/POOR/BROKEN）</li>
 *   <li>链路降级时推荐备用介质切换</li>
 * </ul>
 *
 * <h3>链路质量等级判定</h3>
 * <table>
 *   <tr><th>等级</th><th>RSSI</th><th>丢包率</th><th>延迟</th></tr>
 *   <tr><td>EXCELLENT</td><td>>=-50dBm</td><td><1%</td><td><20ms</td></tr>
 *   <tr><td>GOOD</td><td>>=-70dBm</td><td><5%</td><td><100ms</td></tr>
 *   <tr><td>FAIR</td><td>>=-85dBm</td><td><15%</td><td><300ms</td></tr>
 *   <tr><td>POOR</td><td>>=-100dBm</td><td><50%</td><td><1000ms</td></tr>
 *   <tr><td>BROKEN</td><td><-100dBm</td><td>>=50%</td><td>>=1000ms</td></tr>
 * </table>
 *
 * <h3>线程安全</h3>
 * 使用 ConcurrentHashMap 存储链路质量数据，支持并发更新与查询。
 */
public final class LinkQualityMonitor {

    /** 链路质量等级 */
    public enum QualityLevel {
        EXCELLENT, GOOD, FAIR, POOR, BROKEN
    }

    /** 链路质量数据记录 */
    public static final class LinkQuality {
        /** RSSI 信号强度（dBm） */
        public volatile double rssiDbm;
        /** 丢包率（0.0~1.0） */
        public volatile double packetLossRate;
        /** 平均延迟（毫秒） */
        public volatile double delayMs;
        /** 最后更新时间戳（毫秒） */
        public volatile long lastUpdatedMs;

        public LinkQuality(double rssiDbm, double packetLossRate, double delayMs) {
            this.rssiDbm = rssiDbm;
            this.packetLossRate = packetLossRate;
            this.delayMs = delayMs;
            this.lastUpdatedMs = System.currentTimeMillis();
        }

        /** 默认质量：中等偏上 */
        public static LinkQuality defaultQuality() {
            return new LinkQuality(-65, 0.02, 50);
        }
    }

    /** 各介质的链路质量数据 */
    private final ConcurrentHashMap<LinkType, LinkQuality> qualityData = new ConcurrentHashMap<>();
    /** 各介质的备用介质映射 */
    private final Map<LinkType, LinkType> fallbackLinks = new EnumMap<>(LinkType.class);

    /**
     * 创建链路质量监控器，初始化默认备用介质映射。
     * <p>
     * 默认备用策略：
     * <ul>
     *   <li>WiFi → LTE（WiFi 断时切蜂窝）</li>
     *   <li>LTE → Satellite（蜂窝断时切卫星）</li>
     *   <li>LoRa → Satellite（LoRa 断时切卫星）</li>
     *   <li>Satellite → LTE（卫星断时切蜂窝）</li>
     * </ul>
     */
    public LinkQualityMonitor() {
        fallbackLinks.put(LinkType.WIFI, LinkType.LTE);
        fallbackLinks.put(LinkType.LTE, LinkType.SATELLITE);
        fallbackLinks.put(LinkType.LORA, LinkType.SATELLITE);
        fallbackLinks.put(LinkType.SATELLITE, LinkType.LTE);

        // 初始化各介质默认质量
        for (LinkType type : LinkType.values()) {
            qualityData.put(type, LinkQuality.defaultQuality());
        }
    }

    /**
     * 更新指定介质的链路质量数据。
     *
     * @param linkType        通信介质
     * @param rssiDbm         RSSI 信号强度（dBm）
     * @param packetLossRate  丢包率（0.0~1.0）
     * @param delayMs         平均延迟（毫秒）
     */
    public void updateQuality(LinkType linkType, double rssiDbm, double packetLossRate, double delayMs) {
        qualityData.put(linkType, new LinkQuality(rssiDbm, packetLossRate, delayMs));
    }

    /**
     * 获取指定介质的链路质量数据。
     *
     * @param linkType 通信介质
     * @return 链路质量数据，无记录则返回默认值
     */
    public LinkQuality getQuality(LinkType linkType) {
        return qualityData.getOrDefault(linkType, LinkQuality.defaultQuality());
    }

    /**
     * 评估指定介质的链路质量等级。
     * <p>
     * 取 RSSI、丢包率、延迟三项指标中最差的等级作为综合等级。
     *
     * @param linkType 通信介质
     * @return 链路质量等级
     */
    public QualityLevel evaluateLevel(LinkType linkType) {
        LinkQuality q = getQuality(linkType);
        QualityLevel rssiLevel = evaluateRssi(q.rssiDbm);
        QualityLevel lossLevel = evaluatePacketLoss(q.packetLossRate);
        QualityLevel delayLevel = evaluateDelay(q.delayMs);
        // 取最差等级
        return worstOf(rssiLevel, lossLevel, delayLevel);
    }

    /** 评估 RSSI 等级 */
    private QualityLevel evaluateRssi(double rssiDbm) {
        if (rssiDbm >= -50) return QualityLevel.EXCELLENT;
        if (rssiDbm >= -70) return QualityLevel.GOOD;
        if (rssiDbm >= -85) return QualityLevel.FAIR;
        if (rssiDbm >= -100) return QualityLevel.POOR;
        return QualityLevel.BROKEN;
    }

    /** 评估丢包率等级 */
    private QualityLevel evaluatePacketLoss(double lossRate) {
        if (lossRate < 0.01) return QualityLevel.EXCELLENT;
        if (lossRate < 0.05) return QualityLevel.GOOD;
        if (lossRate < 0.15) return QualityLevel.FAIR;
        if (lossRate < 0.50) return QualityLevel.POOR;
        return QualityLevel.BROKEN;
    }

    /** 评估延迟等级 */
    private QualityLevel evaluateDelay(double delayMs) {
        if (delayMs < 20) return QualityLevel.EXCELLENT;
        if (delayMs < 100) return QualityLevel.GOOD;
        if (delayMs < 300) return QualityLevel.FAIR;
        if (delayMs < 1000) return QualityLevel.POOR;
        return QualityLevel.BROKEN;
    }

    /** 取多个等级中最差的 */
    private QualityLevel worstOf(QualityLevel... levels) {
        QualityLevel worst = QualityLevel.EXCELLENT;
        for (QualityLevel level : levels) {
            if (level.ordinal() > worst.ordinal()) {
                worst = level;
            }
        }
        return worst;
    }

    /**
     * 判断指定介质是否需要切换到备用介质。
     * <p>
     * 当链路质量等级为 POOR 或 BROKEN 时，推荐切换。
     *
     * @param linkType 通信介质
     * @return true 表示需要切换到备用介质
     */
    public boolean needsFallback(LinkType linkType) {
        QualityLevel level = evaluateLevel(linkType);
        return level == QualityLevel.POOR || level == QualityLevel.BROKEN;
    }

    /**
     * 获取指定介质的备用介质。
     *
     * @param linkType 当前通信介质
     * @return 备用介质，无备用则 null
     */
    public LinkType getFallbackLink(LinkType linkType) {
        return fallbackLinks.get(linkType);
    }

    /**
     * 设置指定介质的备用介质。
     *
     * @param linkType  当前通信介质
     * @param fallback  备用介质
     */
    public void setFallbackLink(LinkType linkType, LinkType fallback) {
        fallbackLinks.put(linkType, fallback);
    }

    /**
     * 自动推荐介质切换（链路降级时）。
     * <p>
     * 若当前介质质量降级（POOR/BROKEN），返回备用介质；
     * 若备用介质质量也差，尝试寻找所有中介质中质量最好的。
     *
     * @param currentLink 当前通信介质
     * @return 推荐切换的介质，无需切换则返回 currentLink
     */
    public LinkType recommendLinkSwitch(LinkType currentLink) {
        if (!needsFallback(currentLink)) {
            return currentLink;
        }

        // 先看预设备用介质
        LinkType fallback = fallbackLinks.get(currentLink);
        if (fallback != null && !needsFallback(fallback)) {
            return fallback;
        }

        // 备用也差：找所有介质中质量最好的
        LinkType bestLink = currentLink;
        QualityLevel bestLevel = evaluateLevel(currentLink);
        for (LinkType type : LinkType.values()) {
            if (type == currentLink) continue;
            QualityLevel level = evaluateLevel(type);
            if (level.ordinal() < bestLevel.ordinal()) {
                bestLevel = level;
                bestLink = type;
            }
        }
        return bestLink;
    }

    /**
     * 获取所有介质的链路质量等级快照。
     *
     * @return 介质 → 质量等级映射
     */
    public Map<LinkType, QualityLevel> getAllLevels() {
        Map<LinkType, QualityLevel> levels = new EnumMap<>(LinkType.class);
        for (LinkType type : LinkType.values()) {
            levels.put(type, evaluateLevel(type));
        }
        return Collections.unmodifiableMap(levels);
    }
}
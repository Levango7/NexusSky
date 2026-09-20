package io.aerofleet.cloud.commadapt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 通信链路质量监控服务。
 * <p>
 * 定期采集三种通信链路（mesh/卫星/基站）的质量指标，
 * 为每架无人机生成综合通信质量评分。
 * <p>
 * 采集采用模拟实现：为每种链路类型生成随机但合理的质量指标。
 *
 * @see LinkQuality
 * @see CommQualityScore
 */
@Service
public class LinkQualityMonitor {

    private static final Logger log = LoggerFactory.getLogger(LinkQualityMonitor.class);

    /** 各无人机的最新链路质量数据：sysid → (linkType → LinkQuality) */
    private final Map<Integer, Map<LinkQuality.LinkType, LinkQuality>> qualityData =
            new ConcurrentHashMap<>();

    /** 随机数生成器（用于模拟质量指标） */
    private final Random random = new Random();

    /**
     * 定期采集所有已注册无人机的链路质量。
     * <p>
     * 每 5 秒执行一次（可通过配置调整）。
     */
    @Scheduled(fixedDelayString = "${aerofleet.comm-adapt.detection-interval-ms:5000}")
    public void scheduledCollect() {
        log.debug("Scheduled link quality collection for {} drones", qualityData.size());
        for (Integer sysid : qualityData.keySet()) {
            collectQuality(sysid);
        }
    }

    /**
     * 采集指定无人机的三种链路质量。
     * <p>
     * 为 MESH、SATELLITE、CELLULAR 三种链路类型分别生成模拟质量指标。
     *
     * @param sysid 无人机 systemId
     * @return 三种链路的质量列表
     */
    public List<LinkQuality> collectQuality(int sysid) {
        List<LinkQuality> qualities = new ArrayList<>(3);
        long now = System.currentTimeMillis();

        // MESH 链路：低延迟、中等带宽、信号较强
        LinkQuality mesh = new LinkQuality(
                LinkQuality.LinkType.MESH,
                5 + random.nextDouble() * 20,         // latencyMs: 5-25ms
                500 + random.nextDouble() * 1500,     // bandwidthKbps: 500-2000kbps
                -40 + random.nextDouble() * 20,       // rssiDbm: -40 to -20 dBm
                random.nextDouble() * 2,              // packetLossPct: 0-2%
                1 + random.nextDouble() * 4,          // jitterMs: 1-5ms
                now,
                sysid
        );
        qualities.add(mesh);

        // SATELLITE 链路：高延迟、低带宽、信号较弱
        LinkQuality satellite = new LinkQuality(
                LinkQuality.LinkType.SATELLITE,
                200 + random.nextDouble() * 300,      // latencyMs: 200-500ms
                50 + random.nextDouble() * 150,       // bandwidthKbps: 50-200kbps
                -100 + random.nextDouble() * 30,      // rssiDbm: -100 to -70 dBm
                random.nextDouble() * 5,              // packetLossPct: 0-5%
                10 + random.nextDouble() * 20,        // jitterMs: 10-30ms
                now,
                sysid
        );
        qualities.add(satellite);

        // CELLULAR 链路：中等延迟、中等带宽、信号中等
        LinkQuality cellular = new LinkQuality(
                LinkQuality.LinkType.CELLULAR,
                30 + random.nextDouble() * 70,        // latencyMs: 30-100ms
                200 + random.nextDouble() * 800,      // bandwidthKbps: 200-1000kbps
                -80 + random.nextDouble() * 30,       // rssiDbm: -80 to -50 dBm
                random.nextDouble() * 3,              // packetLossPct: 0-3%
                5 + random.nextDouble() * 10,         // jitterMs: 5-15ms
                now,
                sysid
        );
        qualities.add(cellular);

        // 存储最新质量数据
        Map<LinkQuality.LinkType, LinkQuality> droneData = qualityData.computeIfAbsent(
                sysid, k -> new EnumMap<>(LinkQuality.LinkType.class));
        for (LinkQuality lq : qualities) {
            droneData.put(lq.getLinkType(), lq);
        }

        log.debug("Collected quality for sysid={}: mesh={}, satellite={}, cellular={}",
                sysid, mesh.getLatencyMs(), satellite.getLatencyMs(), cellular.getLatencyMs());

        return qualities;
    }

    /**
     * 获取指定无人机的综合通信质量评分。
     * <p>
     * 综合评分基于各链路的加权评分（延迟40% + 带宽30% + 丢包20% + RSSI10%），
     * 取最优链路的评分作为总体评分，并给出等级（A/B/C/D/F）。
     *
     * @param sysid 无人机 systemId
     * @return 综合质量评分，若无数据则返回 null
     */
    public CommQualityScore getOverallQuality(int sysid) {
        Map<LinkQuality.LinkType, LinkQuality> droneData = qualityData.get(sysid);
        if (droneData == null || droneData.isEmpty()) {
            return null;
        }

        // 计算各链路评分
        LinkQuality.LinkType bestLink = null;
        int bestScore = -1;
        Map<LinkQuality.LinkType, LinkQuality> details = new EnumMap<>(LinkQuality.LinkType.class);

        for (Map.Entry<LinkQuality.LinkType, LinkQuality> entry : droneData.entrySet()) {
            LinkQuality lq = entry.getValue();
            int score = calculateLinkScore(lq);
            details.put(entry.getKey(), lq);
            if (score > bestScore) {
                bestScore = score;
                bestLink = entry.getKey();
            }
        }

        CommQualityScore.Grade grade = scoreToGrade(bestScore);

        return new CommQualityScore(bestScore, grade, bestLink, details);
    }

    /**
     * 获取机队所有无人机的综合通信质量评分列表。
     *
     * @return 各无人机的综合质量评分列表
     */
    public List<CommQualityScore> getFleetQuality() {
        List<CommQualityScore> fleet = new ArrayList<>();
        for (Integer sysid : qualityData.keySet()) {
            CommQualityScore score = getOverallQuality(sysid);
            if (score != null) {
                fleet.add(score);
            }
        }
        return fleet;
    }

    /**
     * 获取指定无人机的最新链路质量数据。
     *
     * @param sysid 无人机 systemId
     * @return 链路类型到质量数据的映射，若无数据则返回空映射
     */
    public Map<LinkQuality.LinkType, LinkQuality> getLatestData(int sysid) {
        Map<LinkQuality.LinkType, LinkQuality> data = qualityData.get(sysid);
        if (data == null) {
            return new EnumMap<>(LinkQuality.LinkType.class);
        }
        return new EnumMap<>(data);
    }

    /**
     * 计算单条链路的质量评分（0-100）。
     * <p>
     * 评分权重：延迟40% + 带宽30% + 丢包20% + RSSI10%。
     *
     * @param lq 链路质量数据
     * @return 质量评分（0-100）
     */
    public static int calculateLinkScore(LinkQuality lq) {
        // 延迟评分：延迟越低越好，0ms=100分，500ms=0分
        double latencyScore = Math.max(0, 100 - (lq.getLatencyMs() / 500.0) * 100);

        // 带宽评分：带宽越高越好，0kbps=0分，2000kbps=100分
        double bandwidthScore = Math.min(100, (lq.getBandwidthKbps() / 2000.0) * 100);

        // 丢包评分：丢包越低越好，0%=100分，10%=0分
        double packetLossScore = Math.max(0, 100 - (lq.getPacketLossPct() / 10.0) * 100);

        // RSSI评分：信号越强越好，-100dBm=0分，-20dBm=100分
        double rssiScore = Math.max(0, Math.min(100, ((lq.getRssiDbm() + 100) / 80.0) * 100));

        // 加权综合评分
        double overall = latencyScore * 0.4
                + bandwidthScore * 0.3
                + packetLossScore * 0.2
                + rssiScore * 0.1;

        return (int) Math.round(overall);
    }

    /**
     * 将评分转换为等级。
     * <p>
     * A: 90-100, B: 80-89, C: 70-79, D: 60-69, F: <60
     *
     * @param score 质量评分（0-100）
     * @return 质量等级
     */
    public static CommQualityScore.Grade scoreToGrade(int score) {
        if (score >= 90) {
            return CommQualityScore.Grade.A;
        } else if (score >= 80) {
            return CommQualityScore.Grade.B;
        } else if (score >= 70) {
            return CommQualityScore.Grade.C;
        } else if (score >= 60) {
            return CommQualityScore.Grade.D;
        } else {
            return CommQualityScore.Grade.F;
        }
    }

    /**
     * 初始化指定无人机的质量数据（首次注册时调用）。
     *
     * @param sysid 无人机 systemId
     */
    public void initDrone(int sysid) {
        qualityData.computeIfAbsent(sysid, k -> new EnumMap<>(LinkQuality.LinkType.class));
        collectQuality(sysid);
    }

    /**
     * 移除指定无人机的质量数据。
     *
     * @param sysid 无人机 systemId
     */
    public void removeDrone(int sysid) {
        qualityData.remove(sysid);
    }
}
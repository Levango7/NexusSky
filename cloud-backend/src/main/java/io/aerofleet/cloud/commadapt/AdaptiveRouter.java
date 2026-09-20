package io.aerofleet.cloud.commadapt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 自适应路由决策服务。
 * <p>
 * 根据通信链路质量评分，自动选择最优通信路径（mesh→卫星→基站），
 * 确保灾害场景下通信连续性。
 * <p>
 * 决策策略：按综合评分（延迟40% + 带宽30% + 丢包20% + RSSI10%）排序，
 * 切换阈值：当前链路评分 < switchThreshold 且备选链路评分 > 80 时建议切换。
 *
 * @see LinkQualityMonitor
 * @see SwitchDecision
 * @see CommAdaptConfig
 */
@Service
public class AdaptiveRouter {

    private static final Logger log = LoggerFactory.getLogger(AdaptiveRouter.class);

    /** 备选链路评分需高于此值才建议切换 */
    private static final int CANDIDATE_SCORE_THRESHOLD = 80;

    private final LinkQualityMonitor monitor;
    private final CommAdaptConfig config;

    /** 各无人机当前使用的链路类型 */
    private final Map<Integer, LinkQuality.LinkType> currentLinks = new java.util.concurrent.ConcurrentHashMap<>();

    public AdaptiveRouter(LinkQualityMonitor monitor, CommAdaptConfig config) {
        this.monitor = monitor;
        this.config = config;
    }

    /**
     * 为指定无人机选择最优链路。
     * <p>
     * 根据各链路的综合评分，返回评分最高的链路类型。
     *
     * @param sysid 无人机 systemId
     * @return 最优链路类型，若无数据则默认返回 MESH
     */
    public LinkQuality.LinkType selectBestLink(int sysid) {
        CommQualityScore score = monitor.getOverallQuality(sysid);
        if (score == null) {
            log.warn("No quality data for sysid={}, defaulting to MESH", sysid);
            return LinkQuality.LinkType.MESH;
        }
        LinkQuality.LinkType best = score.getBestLinkType();
        log.debug("Best link for sysid={}: {} (score={})", sysid, best, score.getOverallScore());
        return best;
    }

    /**
     * 为机队所有无人机选择最优链路。
     *
     * @return sysid → 最优链路类型的映射
     */
    public Map<Integer, LinkQuality.LinkType> selectBestLinkForAll() {
        List<CommQualityScore> fleetScores = monitor.getFleetQuality();
        Map<Integer, LinkQuality.LinkType> result = new LinkedHashMap<>();
        for (CommQualityScore score : fleetScores) {
            Map<LinkQuality.LinkType, LinkQuality> details = score.getDetails();
            if (details != null && !details.isEmpty()) {
                // 从 details 中获取 sysid
                int sysid = details.values().iterator().next().getSysid();
                result.put(sysid, score.getBestLinkType());
            }
        }
        return result;
    }

    /**
     * 判断指定无人机是否应该切换链路。
     * <p>
     * 切换条件：当前链路评分 < switchThreshold 且备选链路评分 > 80。
     * 紧急程度：当前评分 < failoverThreshold 时为 IMMEDIATE，否则为 DELAYED。
     *
     * @param sysid   无人机 systemId
     * @param current 当前链路类型
     * @return 切换决策
     */
    public SwitchDecision shouldSwitch(int sysid, LinkQuality.LinkType current) {
        CommQualityScore score = monitor.getOverallQuality(sysid);
        if (score == null) {
            return new SwitchDecision(sysid, current, current, 0, 0,
                    "no quality data available", SwitchDecision.Urgency.NO_SWITCH);
        }

        Map<LinkQuality.LinkType, LinkQuality> details = score.getDetails();
        if (details == null || !details.containsKey(current)) {
            return new SwitchDecision(sysid, current, score.getBestLinkType(), 0,
                    score.getOverallScore(), "current link has no data",
                    SwitchDecision.Urgency.IMMEDIATE);
        }

        int currentScore = LinkQualityMonitor.calculateLinkScore(details.get(current));
        LinkQuality.LinkType bestLink = score.getBestLinkType();
        int bestScore = score.getOverallScore();

        CommAdaptConfig.ConfigSnapshot cfg = config.snapshot();
        int switchThreshold = cfg.switchThreshold();
        int failoverThreshold = cfg.failoverThreshold();

        // 当前链路就是最优链路，无需切换
        if (bestLink == current && currentScore >= switchThreshold) {
            return new SwitchDecision(sysid, current, current, currentScore, bestScore,
                    "current link is optimal", SwitchDecision.Urgency.NO_SWITCH);
        }

        // 当前链路评分低于切换阈值，且备选链路评分高于候选阈值
        if (currentScore < switchThreshold && bestScore > CANDIDATE_SCORE_THRESHOLD) {
            SwitchDecision.Urgency urgency = currentScore < failoverThreshold
                    ? SwitchDecision.Urgency.IMMEDIATE
                    : SwitchDecision.Urgency.DELAYED;
            String reason = String.format(
                    "current %s score=%d below threshold=%d, candidate %s score=%d above %d",
                    current, currentScore, switchThreshold,
                    bestLink, bestScore, CANDIDATE_SCORE_THRESHOLD);
            log.info("Switch recommended for sysid={}: {} → {} ({})", sysid, current, bestLink, urgency);
            return new SwitchDecision(sysid, current, bestLink, currentScore, bestScore,
                    reason, urgency);
        }

        // 当前链路评分低于切换阈值，但备选链路评分也不够高
        if (currentScore < switchThreshold) {
            return new SwitchDecision(sysid, current, bestLink, currentScore, bestScore,
                    "current link degraded but no better alternative available",
                    SwitchDecision.Urgency.NO_SWITCH);
        }

        // 当前链路评分尚可，无需切换
        return new SwitchDecision(sysid, current, current, currentScore, bestScore,
                "current link is adequate", SwitchDecision.Urgency.NO_SWITCH);
    }

    /**
     * 获取指定无人机当前使用的链路类型。
     *
     * @param sysid 无人机 systemId
     * @return 当前链路类型，若未设置则默认返回 MESH
     */
    public LinkQuality.LinkType getCurrentLink(int sysid) {
        return currentLinks.getOrDefault(sysid, LinkQuality.LinkType.MESH);
    }

    /**
     * 设置指定无人机当前使用的链路类型。
     *
     * @param sysid 无人机 systemId
     * @param link  链路类型
     */
    public void setCurrentLink(int sysid, LinkQuality.LinkType link) {
        currentLinks.put(sysid, link);
        log.info("Current link set for sysid={}: {}", sysid, link);
    }

    /**
     * 获取机队所有无人机的链路切换建议。
     *
     * @return sysid → 切换决策的映射
     */
    public Map<Integer, SwitchDecision> getRecommendations() {
        Map<Integer, SwitchDecision> recommendations = new LinkedHashMap<>();
        for (Integer sysid : currentLinks.keySet()) {
            SwitchDecision decision = shouldSwitch(sysid, getCurrentLink(sysid));
            if (decision.getUrgency() != SwitchDecision.Urgency.NO_SWITCH) {
                recommendations.put(sysid, decision);
            }
        }
        return recommendations;
    }
}
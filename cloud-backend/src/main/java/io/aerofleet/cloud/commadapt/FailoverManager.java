package io.aerofleet.cloud.commadapt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 链路冗余与故障切换管理服务。
 * <p>
 * 检测通信链路故障并执行自动/手动故障切换，确保通信连续性。
 * <p>
 * 故障检测策略：连续3次质量评分 < failoverThreshold 判定为故障。
 * 故障切换时自动选择最优备选链路，切换失败时支持回滚。
 *
 * @see LinkQualityMonitor
 * @see AdaptiveRouter
 * @see FailoverResult
 * @see FailoverRecord
 */
@Service
public class FailoverManager {

    private static final Logger log = LoggerFactory.getLogger(FailoverManager.class);

    /** 连续低评分次数阈值，达到此值判定为故障 */
    private static final int FAILURE_CONSECUTIVE_COUNT = 3;

    private final LinkQualityMonitor monitor;
    private final AdaptiveRouter router;
    private final CommAdaptConfig config;

    /** 各无人机连续低评分计数：sysid → 连续低评分次数 */
    private final Map<Integer, AtomicInteger> failureCounters = new ConcurrentHashMap<>();

    /** 各无人机故障切换历史：sysid → 切换记录列表 */
    private final Map<Integer, List<FailoverRecord>> failoverHistory = new ConcurrentHashMap<>();

    /** 各无人机当前链路状态（是否处于故障中）：sysid → 是否故障 */
    private final Map<Integer, Boolean> failureStatus = new ConcurrentHashMap<>();

    public FailoverManager(LinkQualityMonitor monitor, AdaptiveRouter router, CommAdaptConfig config) {
        this.monitor = monitor;
        this.router = router;
        this.config = config;
    }

    /**
     * 检测指定无人机的当前链路是否故障。
     * <p>
     * 连续3次质量评分 < failoverThreshold 判定为故障。
     *
     * @param sysid 无人机 systemId
     * @return true 表示当前链路已故障
     */
    public boolean detectFailure(int sysid) {
        CommQualityScore score = monitor.getOverallQuality(sysid);
        if (score == null) {
            return false;
        }

        LinkQuality.LinkType currentLink = router.getCurrentLink(sysid);
        Map<LinkQuality.LinkType, LinkQuality> details = score.getDetails();
        if (details == null || !details.containsKey(currentLink)) {
            return false;
        }

        int currentScore = LinkQualityMonitor.calculateLinkScore(details.get(currentLink));

        AtomicInteger counter = failureCounters.computeIfAbsent(sysid, k -> new AtomicInteger(0));

        if (currentScore < config.getFailoverThreshold()) {
            int count = counter.incrementAndGet();
            log.warn("Low quality score for sysid={}: score={}, consecutiveCount={}",
                    sysid, currentScore, count);
            if (count >= FAILURE_CONSECUTIVE_COUNT) {
                failureStatus.put(sysid, true);
                log.error("Link failure detected for sysid={}: {} consecutive low scores",
                        sysid, count);
                return true;
            }
        } else {
            // 评分恢复正常，重置计数器
            if (counter.get() > 0) {
                counter.set(0);
                failureStatus.put(sysid, false);
                log.info("Quality recovered for sysid={}, failure counter reset", sysid);
            }
        }

        return false;
    }

    /**
     * 执行故障切换。
     * <p>
     * 将指定无人机从当前链路切换到目标链路。
     * 切换成功后更新当前链路并记录历史；切换失败时尝试回滚。
     *
     * @param sysid      无人机 systemId
     * @param targetLink 目标链路类型
     * @return 切换结果
     */
    public FailoverResult executeFailover(int sysid, LinkQuality.LinkType targetLink) {
        LinkQuality.LinkType fromLink = router.getCurrentLink(sysid);
        long triggerTime = System.currentTimeMillis();
        String recordId = UUID.randomUUID().toString();

        log.info("Executing failover for sysid={}: {} → {}", sysid, fromLink, targetLink);

        // 模拟切换过程：检查目标链路是否有数据
        CommQualityScore score = monitor.getOverallQuality(sysid);
        if (score == null || score.getDetails() == null
                || !score.getDetails().containsKey(targetLink)) {
            FailoverResult result = new FailoverResult(sysid, fromLink, targetLink,
                    FailoverResult.Status.FAILED, System.currentTimeMillis(),
                    "target link has no quality data");
            recordFailover(sysid, recordId, fromLink, targetLink, triggerTime,
                    System.currentTimeMillis(), FailoverResult.Status.FAILED,
                    "target link unavailable");
            return result;
        }

        // 模拟切换：目标链路评分需 > failoverThreshold 才认为切换成功
        int targetScore = LinkQualityMonitor.calculateLinkScore(
                score.getDetails().get(targetLink));

        if (targetScore < config.getFailoverThreshold()) {
            // 目标链路质量也不达标，切换失败
            FailoverResult result = new FailoverResult(sysid, fromLink, targetLink,
                    FailoverResult.Status.FAILED, System.currentTimeMillis(),
                    "target link quality too low: score=" + targetScore);
            recordFailover(sysid, recordId, fromLink, targetLink, triggerTime,
                    System.currentTimeMillis(), FailoverResult.Status.FAILED,
                    "target link quality insufficient");
            return result;
        }

        // 切换成功
        router.setCurrentLink(sysid, targetLink);
        failureCounters.computeIfAbsent(sysid, k -> new AtomicInteger(0)).set(0);
        failureStatus.put(sysid, false);

        long completeTime = System.currentTimeMillis();
        FailoverResult result = new FailoverResult(sysid, fromLink, targetLink,
                FailoverResult.Status.SUCCESS, completeTime,
                "failover completed successfully");
        recordFailover(sysid, recordId, fromLink, targetLink, triggerTime,
                completeTime, FailoverResult.Status.SUCCESS,
                "automatic failover to " + targetLink);

        log.info("Failover success for sysid={}: {} → {} (score={})",
                sysid, fromLink, targetLink, targetScore);
        return result;
    }

    /**
     * 获取指定无人机的故障切换历史。
     *
     * @param sysid 无人机 systemId
     * @return 故障切换记录列表（按时间升序）
     */
    public List<FailoverRecord> getFailoverHistory(int sysid) {
        List<FailoverRecord> history = failoverHistory.get(sysid);
        if (history == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(history);
    }

    /**
     * 检查指定无人机当前是否处于故障状态。
     *
     * @param sysid 无人机 systemId
     * @return true 表示当前处于故障状态
     */
    public boolean isFailed(int sysid) {
        return failureStatus.getOrDefault(sysid, false);
    }

    /**
     * 获取所有处于故障状态的无人机 sysid 列表。
     *
     * @return 故障无人机 sysid 列表
     */
    public List<Integer> getFailedDrones() {
        List<Integer> failed = new ArrayList<>();
        for (Map.Entry<Integer, Boolean> entry : failureStatus.entrySet()) {
            if (entry.getValue()) {
                failed.add(entry.getKey());
            }
        }
        return failed;
    }

    /**
     * 记录故障切换历史。
     */
    private void recordFailover(int sysid, String recordId,
                                LinkQuality.LinkType fromLink,
                                LinkQuality.LinkType toLink,
                                long triggerTime, long completeTime,
                                FailoverResult.Status status, String reason) {
        FailoverRecord record = new FailoverRecord(recordId, sysid, fromLink, toLink,
                triggerTime, completeTime, status, reason);
        // P1-fix: 使用 CopyOnWriteArrayList 替代 ArrayList，保证多线程安全
        failoverHistory.computeIfAbsent(sysid, k -> new CopyOnWriteArrayList<>()).add(record);
    }
}
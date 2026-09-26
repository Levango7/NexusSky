package io.aerofleet.cloud.api.service;

import io.aerofleet.cloud.gateway.BoundedHistory;
import io.aerofleet.cloud.gateway.MavlinkMessageEvent;
import io.aerofleet.mavlink.messages.HierarchicalRouteDecisionMsg;
import io.aerofleet.mavlink.messages.SatLinkStatusMsg;
import io.aerofleet.mavlink.messages.SatPassScheduleMsg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 卫星链路监控服务（M7 星-空-地多层级中继，FR-5.4/5.3）。
 * <p>
 * 接收各节点上报的 SatLinkStatus(459) / SatPassSchedule(460) / HierarchicalRouteDecision(461)，
 * 维护全局链路状态快照、过境计划与路由决策历史，提供只读查询。
 * <p>
 * 内部 ConcurrentHashMap，线程安全。version 递增标记状态变化。
 */
@Service
public class SatLinkMonitorService {

    private static final Logger log = LoggerFactory.getLogger(SatLinkMonitorService.class);

    /** 卫星链路状态快照：satId → snapshot。 */
    private final ConcurrentHashMap<Integer, SatLinkSnapshot> linkSnapshots = new ConcurrentHashMap<>();
    /** 过境计划：satId → schedule 列表。 */
    private final ConcurrentHashMap<Integer, List<SatPassSnapshot>> passSchedules = new ConcurrentHashMap<>();
    /** 路由决策历史（有界环形缓冲，最近 100 条）。 */
    private final BoundedHistory<RouteDecisionSnapshot> routeDecisions = new BoundedHistory<>(100);
    /** 状态版本号（每次变化递增）。 */
    private final AtomicLong version = new AtomicLong(0);
    /** 当前切换策略。 */
    private volatile String currentStrategy = "NEAR_FIRST";

    /** 卫星链路状态快照。 */
    public record SatLinkSnapshot(int satId, int visible, int elevationDeg, int azimuthDeg,
                                  int delayMs, int bandwidthMbps, long windowEndMs,
                                  int sharedUsers, long timestamp, boolean simulated) {}

    /** 过境计划快照。 */
    public record SatPassSnapshot(int satId, long passStartMs, long passEndMs,
                                  int maxElevationDeg, int groundPointId, long timestamp) {}

    /** 路由决策快照。 */
    public record RouteDecisionSnapshot(int sourceLayer, int targetLayer, int chosenLayer,
                                        int estimatedDelayMs, List<Integer> pathNodes,
                                        int strategy, String decisionReason, long timestamp) {}

    /**
     * 接收卫星链路状态上报（由 TelemetryIngestService 调用）。
     */
    public void onSatLinkStatus(int sysid, SatLinkStatusMsg msg) {
        SatLinkSnapshot snapshot = new SatLinkSnapshot(
                msg.satId, msg.visible, msg.elevationDeg, msg.azimuthDeg,
                msg.delayMs, msg.bandwidthMbps, msg.windowEndMs,
                msg.sharedUsers, msg.timestamp, msg.isSimulated());
        linkSnapshots.put(msg.satId, snapshot);
        version.incrementAndGet();
        log.debug("sat-link status updated: satId={} visible={} delay={}ms bw={}Mbps",
                msg.satId, msg.visible, msg.delayMs, msg.bandwidthMbps);
    }

    /**
     * 接收过境计划上报（由 TelemetryIngestService 调用）。
     */
    public void onSatPassSchedule(int sysid, SatPassScheduleMsg msg) {
        SatPassSnapshot snapshot = new SatPassSnapshot(
                msg.satId, msg.passStartMs, msg.passEndMs,
                msg.maxElevationDeg, msg.groundPointId, msg.timestamp);
        passSchedules.compute(msg.satId, (k, existing) -> {
            List<SatPassSnapshot> list = existing != null
                    ? new ArrayList<>(existing) : new ArrayList<>();
            list.add(snapshot);
            // 保留最近 20 条过境计划
            if (list.size() > 20) {
                list = list.subList(list.size() - 20, list.size());
            }
            return Collections.unmodifiableList(list);
        });
        version.incrementAndGet();
        log.debug("sat-link pass schedule updated: satId={} pass=[{}-{}]",
                msg.satId, msg.passStartMs, msg.passEndMs);
    }

    /**
     * 接收路由决策上报（由 TelemetryIngestService 调用）。
     */
    public void onHierarchicalRouteDecision(int sysid, HierarchicalRouteDecisionMsg msg) {
        RouteDecisionSnapshot snapshot = new RouteDecisionSnapshot(
                msg.sourceLayer, msg.targetLayer, msg.chosenLayer,
                msg.estimatedDelayMs, msg.pathNodes,
                msg.strategy, msg.decisionReason, msg.timestamp);
        routeDecisions.add(snapshot);
        version.incrementAndGet();
        log.debug("sat-link route decision: chosenLayer={} delay={}ms reason={}",
                msg.chosenLayer, msg.estimatedDelayMs, msg.decisionReason);
    }

    /** 获取单星链路状态；不存在返回 null。 */
    public SatLinkSnapshot getLinkStatus(int satId) {
        return linkSnapshots.get(satId);
    }

    /** 获取所有卫星链路状态（按 satId 排序）。 */
    public Map<Integer, SatLinkSnapshot> getAllLinkStatuses() {
        return new TreeMap<>(linkSnapshots);
    }

    /** 获取单星过境计划；不存在返回空列表。 */
    public List<SatPassSnapshot> getPassSchedules(int satId) {
        return passSchedules.getOrDefault(satId, Collections.emptyList());
    }

    /** 获取所有过境计划（按 satId 排序）。 */
    public Map<Integer, List<SatPassSnapshot>> getAllPassSchedules() {
        return new TreeMap<>(passSchedules);
    }

    /** 获取路由决策历史（最近 100 条）。 */
    public List<RouteDecisionSnapshot> getRouteDecisions() {
        return routeDecisions.toList();
    }

    /** 当前状态版本号。 */
    public long currentVersion() {
        return version.get();
    }

    /** 获取当前切换策略。 */
    public String getCurrentStrategy() {
        return currentStrategy;
    }

    /**
     * 设置切换策略（运行时热更新，FR-4.4.2）。
     *
     * @param strategy 策略名称（NEAR_FIRST/DELAY_OPTIMAL/BANDWIDTH_OPTIMAL/RELIABILITY_OPTIMAL）
     * @return 是否设置成功
     */
    public boolean setStrategy(String strategy) {
        if (strategy == null || strategy.isBlank()) {
            return false;
        }
        String upper = strategy.toUpperCase();
        if (!upper.equals("NEAR_FIRST") && !upper.equals("DELAY_OPTIMAL")
                && !upper.equals("BANDWIDTH_OPTIMAL") && !upper.equals("RELIABILITY_OPTIMAL")) {
            return false;
        }
        this.currentStrategy = upper;
        version.incrementAndGet();
        log.info("sat-link strategy set to: {}", upper);
        return true;
    }

    // =====================================================================
    // @EventListener：监听 MavlinkMessageEvent 自行处理卫星链路消息
    // =====================================================================

    @EventListener(condition = "#event.msgId == T(io.aerofleet.mavlink.messages.SatLinkStatusMsg).ID")
    public void onSatLinkStatusEvent(MavlinkMessageEvent event) {
        onSatLinkStatus(event.getSysid(), (SatLinkStatusMsg) event.getMessage());
    }

    @EventListener(condition = "#event.msgId == T(io.aerofleet.mavlink.messages.SatPassScheduleMsg).ID")
    public void onSatPassScheduleEvent(MavlinkMessageEvent event) {
        onSatPassSchedule(event.getSysid(), (SatPassScheduleMsg) event.getMessage());
    }

    @EventListener(condition = "#event.msgId == T(io.aerofleet.mavlink.messages.HierarchicalRouteDecisionMsg).ID")
    public void onHierarchicalRouteDecisionEvent(MavlinkMessageEvent event) {
        onHierarchicalRouteDecision(event.getSysid(), (HierarchicalRouteDecisionMsg) event.getMessage());
    }
}
package io.aerofleet.cloud.api.service;

import io.aerofleet.cloud.api.pusher.DisasterCommPusher;
import io.aerofleet.cloud.gateway.MavlinkMessageEvent;
import io.aerofleet.mavlink.messages.ClusterFormationMsg;
import io.aerofleet.mavlink.messages.DisasterModeStatusMsg;
import io.aerofleet.mavlink.messages.QoSRouteDecisionMsg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 灾害通信监控服务（P2 灾害应急通讯组网扩展）。
 * <p>
 * 管理灾害模式状态（active/inactive）、分簇拓扑信息、QoS 优先级队列状态、
 * 异构链路桥接状态。通过 {@code @EventListener} 接收 MAVLink msgId 30060-30062 消息。
 * <p>
 * 线程安全：内部使用 {@link ConcurrentHashMap} + {@link AtomicLong}。
 */
@Service
public class DisasterCommService {

    private static final Logger log = LoggerFactory.getLogger(DisasterCommService.class);

    /**
     * 允许的触发原因集合（手动激活接口 {@code POST /api/v1/disaster/activate} 的取值域）。
     * <p>
     * = {@link #triggerReasonName(int)} 的 MAVLink 序数词汇（manual / heartbeat_timeout /
     * terrain_change / auto）∪ 前端下拉框提供的灾害类型（earthquake / mudslide）。
     * <p>
     * 取值域不能只取其一：MAVLink 入向消息（{@code DisasterModeStatusMsg}）只能表达序数
     * 0..3，而 REST 手动激活由人在界面上选，选到的是灾害类型。两边都认，才能让下拉框
     * 的声明与后端行为一致（此前该下拉框是装饰性的——值被静默丢弃）。
     */
    public static final Set<String> ALLOWED_TRIGGER_REASONS = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList(
                    "manual", "heartbeat_timeout", "terrain_change", "auto",
                    "earthquake", "mudslide")));

    /** 默认触发原因（未指定 reason 时）。 */
    public static final String DEFAULT_TRIGGER_REASON = "manual";

    /** 灾害模式状态：active / inactive。 */
    private volatile boolean disasterModeActive = false;
    /** 灾害模式触发原因。 */
    private volatile String triggerReason = "";
    /** 受影响节点数。 */
    private volatile int affectedNodes = 0;
    /** 恢复率（0-100%）。 */
    private volatile int recoveryRate = 0;
    /** 灾害模式最后更新时间戳。 */
    private volatile long disasterModeTimestamp = 0;

    /** 分簇拓扑：clusterId → 簇信息。 */
    private final ConcurrentHashMap<Integer, ClusterInfo> clusters = new ConcurrentHashMap<>();
    /** QoS 优先级队列状态：priorityClass → 队列信息。 */
    private final ConcurrentHashMap<Integer, QoSQueueInfo> qosQueues = new ConcurrentHashMap<>();
    /** 异构链路桥接状态：bridgeId → 桥接信息。 */
    private final ConcurrentHashMap<Integer, LinkBridgeInfo> linkBridges = new ConcurrentHashMap<>();

    /** 状态版本号（每次变化递增）。 */
    private final AtomicLong version = new AtomicLong(0);

    /** WebSocket 推送器（可为 null，测试场景）。 */
    private final DisasterCommPusher pusher;

    public DisasterCommService(DisasterCommPusher pusher) {
        this.pusher = pusher;
    }

    // =====================================================================
    // 灾害模式状态管理
    // =====================================================================

    /**
     * 获取灾害模式状态。
     *
     * @return 状态 map，包含 mode/triggerReason/affectedNodes/recoveryRate/timestamp
     */
    public Map<String, Object> getDisasterStatus() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", disasterModeActive ? "active" : "inactive");
        result.put("triggerReason", triggerReason);
        result.put("affectedNodes", affectedNodes);
        result.put("recoveryRate", recoveryRate);
        result.put("timestamp", disasterModeTimestamp);
        return result;
    }

    /**
     * 手动激活灾害模式（未指定触发原因，等价于 {@code activateDisasterMode("manual")}）。
     *
     * @return 更新后的状态 map
     */
    public Map<String, Object> activateDisasterMode() {
        return activateDisasterMode(DEFAULT_TRIGGER_REASON);
    }

    /**
     * 手动激活灾害模式，并记录调用方声明的触发原因。
     * <p>
     * 此前本方法把 {@code triggerReason} 硬编码成 {@code "manual"}，而前端下拉框让用户从
     * 5 个选项里选、控制器又没有 {@code @RequestBody} 所以把请求体整个丢弃 —— 面板上显示的
     * 「触发原因」因此永远是 {@code manual}，下拉框是装饰性的。
     *
     * @param reason 触发原因；{@code null} / 空白按 {@link #DEFAULT_TRIGGER_REASON} 处理。
     *               非法取值由调用方（控制器，API 边界）先行校验，本方法只做空白归一化，
     *               不做静默改写 —— 否则界面会显示一个调用方并未声明过的原因。
     * @return 更新后的状态 map
     */
    public Map<String, Object> activateDisasterMode(String reason) {
        boolean wasActive = disasterModeActive;
        disasterModeActive = true;
        triggerReason = (reason == null || reason.isBlank())
                ? DEFAULT_TRIGGER_REASON
                : reason.trim();
        disasterModeTimestamp = System.currentTimeMillis();
        version.incrementAndGet();
        log.info("灾害模式手动激活 (wasActive={}, reason={})", wasActive, triggerReason);
        Map<String, Object> status = getDisasterStatus();
        pushDisasterStatus(status);
        return status;
    }

    /**
     * 手动退出灾害模式。
     *
     * @return 更新后的状态 map
     */
    public Map<String, Object> deactivateDisasterMode() {
        boolean wasActive = disasterModeActive;
        disasterModeActive = false;
        triggerReason = "";
        affectedNodes = 0;
        recoveryRate = 100;
        disasterModeTimestamp = System.currentTimeMillis();
        version.incrementAndGet();
        log.info("灾害模式手动退出 (wasActive={})", wasActive);
        Map<String, Object> status = getDisasterStatus();
        pushDisasterStatus(status);
        return status;
    }

    // =====================================================================
    // 分簇拓扑管理
    // =====================================================================

    /**
     * 获取分簇拓扑信息。
     *
     * @return 状态 map，包含 clusterCount/clusters 列表
     */
    public Map<String, Object> getClusterTopology() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("clusterCount", clusters.size());
        List<Map<String, Object>> clusterList = new ArrayList<>();
        for (ClusterInfo c : clusters.values()) {
            Map<String, Object> cv = new LinkedHashMap<>();
            cv.put("clusterId", c.clusterId);
            cv.put("clusterHead", c.clusterHead);
            cv.put("members", c.members);
            cv.put("memberCount", c.memberCount);
            cv.put("clusterRadius", c.clusterRadius);
            cv.put("timestamp", c.timestamp);
            clusterList.add(cv);
        }
        result.put("clusters", clusterList);
        return result;
    }

    // =====================================================================
    // QoS 优先级队列管理
    // =====================================================================

    /**
     * 获取 QoS 优先级队列状态。
     *
     * @return 状态 map，包含 queueCount/queues 列表
     */
    public Map<String, Object> getQoSStatus() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("queueCount", qosQueues.size());
        List<Map<String, Object>> queueList = new ArrayList<>();
        for (QoSQueueInfo q : qosQueues.values()) {
            Map<String, Object> qv = new LinkedHashMap<>();
            qv.put("priorityClass", priorityClassName(q.priorityClass));
            qv.put("routeId", q.routeId);
            qv.put("bandwidthAlloc", q.bandwidthAlloc);
            qv.put("sourceSysid", q.sourceSysid);
            qv.put("targetSysid", q.targetSysid);
            qv.put("timestamp", q.timestamp);
            queueList.add(qv);
        }
        result.put("queues", queueList);
        return result;
    }

    // =====================================================================
    // 异构链路桥接管理
    // =====================================================================

    /**
     * 获取异构链路桥接状态。
     *
     * @return 状态 map，包含 bridgeCount/bridges 列表
     */
    public Map<String, Object> getLinkBridgeStatus() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("bridgeCount", linkBridges.size());
        List<Map<String, Object>> bridgeList = new ArrayList<>();
        for (LinkBridgeInfo b : linkBridges.values()) {
            Map<String, Object> bv = new LinkedHashMap<>();
            bv.put("bridgeId", b.bridgeId);
            bv.put("sourceSysid", b.sourceSysid);
            bv.put("targetSysid", b.targetSysid);
            bv.put("active", b.active);
            bv.put("timestamp", b.timestamp);
            bridgeList.add(bv);
        }
        result.put("bridges", bridgeList);
        return result;
    }

    /** 当前状态版本号。 */
    public long currentVersion() {
        return version.get();
    }

    // =====================================================================
    // @EventListener：监听 MavlinkMessageEvent 处理 msgId 30060-30062
    // =====================================================================

    /**
     * QoS_ROUTE_DECISION (msgId=30060)：更新 QoS 优先级队列状态。
     */
    @EventListener(condition = "#event.msgId == T(io.aerofleet.mavlink.messages.QoSRouteDecisionMsg).ID")
    public void onQoSRouteDecision(MavlinkMessageEvent event) {
        QoSRouteDecisionMsg msg = (QoSRouteDecisionMsg) event.getMessage();
        QoSQueueInfo info = new QoSQueueInfo(
                msg.priorityClass, msg.routeId, msg.bandwidthAlloc,
                msg.sourceSysid, msg.targetSysid, msg.timestamp);
        qosQueues.put(msg.priorityClass, info);
        version.incrementAndGet();
        log.debug("QoS 路由决策更新: priority={} routeId={} bw={}kbps",
                priorityClassName(msg.priorityClass), msg.routeId, msg.bandwidthAlloc);
        pushQoSStatus(getQoSStatus());
    }

    /**
     * CLUSTER_FORMATION (msgId=30061)：更新分簇拓扑信息。
     */
    @EventListener(condition = "#event.msgId == T(io.aerofleet.mavlink.messages.ClusterFormationMsg).ID")
    public void onClusterFormation(MavlinkMessageEvent event) {
        ClusterFormationMsg msg = (ClusterFormationMsg) event.getMessage();
        List<Integer> memberList = new ArrayList<>();
        for (int i = 0; i < msg.memberCount; i++) {
            if (msg.members[i] != 0) {
                memberList.add(msg.members[i]);
            }
        }
        ClusterInfo info = new ClusterInfo(
                msg.clusterId, msg.clusterHead, memberList,
                msg.memberCount, msg.clusterRadius, msg.timestamp);
        clusters.put(msg.clusterId, info);
        version.incrementAndGet();
        log.debug("分簇拓扑更新: clusterId={} head={} members={}",
                msg.clusterId, msg.clusterHead, msg.memberCount);
        pushClusterTopology(getClusterTopology());
    }

    /**
     * DISASTER_MODE_STATUS (msgId=30062)：更新灾害模式状态。
     */
    @EventListener(condition = "#event.msgId == T(io.aerofleet.mavlink.messages.DisasterModeStatusMsg).ID")
    public void onDisasterModeStatus(MavlinkMessageEvent event) {
        DisasterModeStatusMsg msg = (DisasterModeStatusMsg) event.getMessage();
        boolean wasActive = disasterModeActive;
        disasterModeActive = (msg.mode == DisasterModeStatusMsg.MODE_ACTIVE);
        triggerReason = triggerReasonName(msg.triggerReason);
        affectedNodes = msg.affectedNodes;
        recoveryRate = msg.recoveryRate;
        disasterModeTimestamp = msg.timestamp;
        version.incrementAndGet();
        log.info("灾害模式状态更新: mode={} reason={} affected={} recovery={}%",
                disasterModeActive ? "ACTIVE" : "INACTIVE", triggerReason,
                msg.affectedNodes, msg.recoveryRate);
        if (wasActive != disasterModeActive) {
            pushDisasterStatus(getDisasterStatus());
        }
    }

    // =====================================================================
    // 推送辅助
    // =====================================================================

    /** 推送灾害模式状态变更。 */
    private void pushDisasterStatus(Map<String, Object> status) {
        if (pusher != null) {
            pusher.pushDisasterStatus(status);
        }
    }

    /** 推送分簇拓扑更新。 */
    private void pushClusterTopology(Map<String, Object> topology) {
        if (pusher != null) {
            pusher.pushClusterTopology(topology);
        }
    }

    /** 推送 QoS 优先级队列状态。 */
    private void pushQoSStatus(Map<String, Object> qosStatus) {
        if (pusher != null) {
            pusher.pushQoSStatus(qosStatus);
        }
    }

    // =====================================================================
    // 辅助方法
    // =====================================================================

    /** 优先级类别序数 → 名称。 */
    private static String priorityClassName(int ordinal) {
        return switch (ordinal) {
            case 0 -> "EMERGENCY";
            case 1 -> "COMMAND";
            case 2 -> "MAPPING";
            case 3 -> "ROUTINE";
            default -> "UNKNOWN(" + ordinal + ")";
        };
    }

    /** 触发原因序数 → 名称。 */
    private static String triggerReasonName(int ordinal) {
        return switch (ordinal) {
            case 0 -> "manual";
            case 1 -> "heartbeat_timeout";
            case 2 -> "terrain_change";
            case 3 -> "auto";
            default -> "unknown(" + ordinal + ")";
        };
    }

    // =====================================================================
    // 内部数据结构
    // =====================================================================

    /** 分簇信息。 */
    static final class ClusterInfo {
        final int clusterId;
        final int clusterHead;
        final List<Integer> members;
        final int memberCount;
        final int clusterRadius;
        final long timestamp;

        ClusterInfo(int clusterId, int clusterHead, List<Integer> members,
                    int memberCount, int clusterRadius, long timestamp) {
            this.clusterId = clusterId;
            this.clusterHead = clusterHead;
            this.members = Collections.unmodifiableList(members);
            this.memberCount = memberCount;
            this.clusterRadius = clusterRadius;
            this.timestamp = timestamp;
        }
    }

    /** QoS 优先级队列信息。 */
    static final class QoSQueueInfo {
        final int priorityClass;
        final int routeId;
        final int bandwidthAlloc;
        final int sourceSysid;
        final int targetSysid;
        final long timestamp;

        QoSQueueInfo(int priorityClass, int routeId, int bandwidthAlloc,
                     int sourceSysid, int targetSysid, long timestamp) {
            this.priorityClass = priorityClass;
            this.routeId = routeId;
            this.bandwidthAlloc = bandwidthAlloc;
            this.sourceSysid = sourceSysid;
            this.targetSysid = targetSysid;
            this.timestamp = timestamp;
        }
    }

    /** 异构链路桥接信息。 */
    static final class LinkBridgeInfo {
        final int bridgeId;
        final int sourceSysid;
        final int targetSysid;
        final boolean active;
        final long timestamp;

        LinkBridgeInfo(int bridgeId, int sourceSysid, int targetSysid,
                       boolean active, long timestamp) {
            this.bridgeId = bridgeId;
            this.sourceSysid = sourceSysid;
            this.targetSysid = targetSysid;
            this.active = active;
            this.timestamp = timestamp;
        }
    }
}
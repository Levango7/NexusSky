package io.aerofleet.cloud.alarm;

import io.aerofleet.cloud.autodispatch.AutoDispatchService;
import io.aerofleet.cloud.autodispatch.DispatchResult;
import io.aerofleet.cloud.autodispatch.VoiceIntercomService;
import io.aerofleet.cloud.gateway.MavlinkMessageEvent;
import io.aerofleet.mavlink.messages.AlarmAckMsg;
import io.aerofleet.mavlink.messages.AlarmTriggerMsg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 报警联动引擎（M10 报警联动编排，FR-31）。
 * <p>
 * 维护联动规则集（{@link ConcurrentHashMap}，线程安全），处理报警事件时
 * 遍历所有启用规则，对匹配的规则执行联动动作（部署无人机/通知/录像）。
 * <p>
 * 处理流程：
 * <ol>
 *   <li>事件存入 {@link AlarmEventStore}</li>
 *   <li>遍历启用规则，调用 {@link AlarmLinkageRule#matches}</li>
 *   <li>匹配规则委托 {@link AlarmToOrchBridge} 执行联动动作</li>
 *   <li>记录处理日志与执行结果</li>
 * </ol>
 * <p>
 * WS 帧生产（2026-10-05 边界清零）：事件摄取发布 AlarmTriggerMsg(30057)
 * （显式租户 = 事件所属租户，帧 sysid = 设备 ID 哈希）；AUTO_DISPATCH 联动
 * 派遣成功后逐机发布 AlarmAckMsg(30058, ackResult=1 已开始响应，含真实 ETA）。
 * DEPLOY_DRONE 走云内编排无确定机载 sysid，不发 ack——见 README 边界口径。
 *
 * @see AlarmLinkageRule
 * @see AlarmToOrchBridge
 * @see AlarmEventStore
 */
@Service
public class AlarmLinkageEngine {

    private static final Logger log = LoggerFactory.getLogger(AlarmLinkageEngine.class);

    /** 规则存储：ruleId → rule，线程安全。 */
    private final ConcurrentHashMap<String, AlarmLinkageRule> rules = new ConcurrentHashMap<>();
    /** 报警事件存储。 */
    private final AlarmEventStore eventStore;
    /** 报警→应急编排桥接。 */
    private final AlarmToOrchBridge bridge;
    /** 自动出警服务（@Lazy 避免循环依赖，可能未注入）。 */
    private final Optional<AutoDispatchService> autoDispatchService;
    /** 语音对讲服务（@Lazy 避免循环依赖，可能未注入）。 */
    private final Optional<VoiceIntercomService> voiceIntercomService;
    /** WS 帧发布器（旧构造器/测试场景为 null，发布跳过）。 */
    private final ApplicationEventPublisher eventPublisher;
    /** 联动执行日志（最近 N 条，线程安全的有界队列）。 */
    private final ConcurrentLinkedDeque<LinkageLog> linkageLogs = new ConcurrentLinkedDeque<>();
    /** 联动日志容量上限。 */
    private static final int LINKAGE_LOG_CAPACITY = 500;

    @Autowired
    public AlarmLinkageEngine(AlarmEventStore eventStore, AlarmToOrchBridge bridge,
                              @Lazy AutoDispatchService autoDispatchService,
                              @Lazy VoiceIntercomService voiceIntercomService,
                              ApplicationEventPublisher eventPublisher) {
        this.eventStore = eventStore;
        this.bridge = bridge;
        this.autoDispatchService = Optional.ofNullable(autoDispatchService);
        this.voiceIntercomService = Optional.ofNullable(voiceIntercomService);
        this.eventPublisher = eventPublisher;
    }

    /**
     * 兼容旧构造器：不注入自动出警/语音对讲服务与帧发布器（用于测试与向后兼容，
     * WS 帧发布跳过）。
     */
    public AlarmLinkageEngine(AlarmEventStore eventStore, AlarmToOrchBridge bridge) {
        this.eventStore = eventStore;
        this.bridge = bridge;
        this.autoDispatchService = Optional.empty();
        this.voiceIntercomService = Optional.empty();
        this.eventPublisher = null;
    }

    /**
     * 添加联动规则。
     * <p>
     * 若规则 ID 已存在，覆盖旧规则。
     *
     * @param rule 联动规则
     */
    public void addRule(AlarmLinkageRule rule) {
        rules.put(rule.getId(), rule);
        log.info("alarm linkage rule added: id={} name={} enabled={}",
                rule.getId(), rule.getName(), rule.isEnabled());
    }

    /**
     * 移除联动规则。
     *
     * @param ruleId 规则 ID
     * @return 被移除的规则，不存在返回 null
     */
    public AlarmLinkageRule removeRule(String ruleId) {
        AlarmLinkageRule removed = rules.remove(ruleId);
        if (removed != null) {
            log.info("alarm linkage rule removed: id={}", ruleId);
        }
        return removed;
    }

    /**
     * 按 ID 获取规则。
     *
     * @param ruleId 规则 ID
     * @return 规则，不存在返回 null
     */
    public AlarmLinkageRule getRule(String ruleId) {
        return rules.get(ruleId);
    }

    /**
     * 获取全部规则（不可变快照）。
     */
    public List<AlarmLinkageRule> getAllRules() {
        return Collections.unmodifiableList(new ArrayList<>(rules.values()));
    }

    /**
     * 获取启用的规则列表（不可变快照）。
     */
    public List<AlarmLinkageRule> getActiveRules() {
        List<AlarmLinkageRule> active = new ArrayList<>();
        for (AlarmLinkageRule r : rules.values()) {
            if (r.isEnabled()) {
                active.add(r);
            }
        }
        return Collections.unmodifiableList(active);
    }

    /**
     * 处理报警事件。
     * <p>
     * 流程：存储事件→遍历启用规则→匹配则执行联动动作→记录结果。
     * <p>
     * 线程安全：{@code rules} 与 {@code eventStore} 均为并发结构，
     * 单个事件的处理过程中规则集变更不会导致 ConcurrentModificationException。
     *
     * @param event 报警事件
     * @return 处理结果（含匹配规则数与执行结果列表）
     */
    public ProcessResult processEvent(AlarmEvent event) {
        // 1. 存储事件
        eventStore.store(event);
        log.info("alarm event received: id={} type={} severity={} device={}",
                event.getId(), event.getEventType(), event.getSeverity(),
                event.getSourceDeviceId());
        publishAlarmTrigger(event);

        // 2. 遍历启用规则，匹配并执行动作
        List<ActionExecution> executions = new ArrayList<>();
        List<AlarmLinkageRule> snapshot = new ArrayList<>(rules.values());
        for (AlarmLinkageRule rule : snapshot) {
            if (!rule.matches(event)) {
                continue;
            }
            ActionExecution exec = executeAction(event, rule);
            executions.add(exec);
        }

        log.info("alarm event processed: id={} matched={} actions={}",
                event.getId(), executions.size(),
                executions.stream().map(ActionExecution::getActionType).toList());

        // 3. 记录联动日志
        if (!executions.isEmpty()) {
            for (ActionExecution exec : executions) {
                LinkageLog logEntry = new LinkageLog(
                        event.getId(), exec.getRuleId(), exec.getActionType(),
                        exec.getStatus(), exec.getPlanId(), exec.getError(),
                        System.currentTimeMillis());
                linkageLogs.addFirst(logEntry);
            }
            // 驱逐超容量日志
            while (linkageLogs.size() > LINKAGE_LOG_CAPACITY) {
                linkageLogs.pollLast();
            }
        }

        return new ProcessResult(event.getId(), executions.size(), executions);
    }

    /**
     * 执行联动动作。
     * <p>
     * DEPLOY_DRONE 委托 {@link AlarmToOrchBridge} 启动无人机编排；
     * AUTO_DISPATCH 委托 {@link AutoDispatchService} 自动出警（P0-1）；
     * VOICE_BROADCAST 委托 {@link VoiceIntercomService} 广播喊话（P0-1）；
     * NOTIFY_ONLY/RECORD_VIDEO 仅记录日志（后续可扩展为通知服务/录像服务）。
     */
    private ActionExecution executeAction(AlarmEvent event, AlarmLinkageRule rule) {
        try {
            switch (rule.getActionType()) {
                case DEPLOY_DRONE -> {
                    AlarmToOrchBridge.BridgeResult result = bridge.bridge(event, rule);
                    return new ActionExecution(
                            rule.getId(),
                            rule.getActionType().name(),
                            result.getStatus(),
                            result.getPlanId(),
                            result.getTaskTemplate(),
                            null);
                }
                case AUTO_DISPATCH -> {
                    return executeAutoDispatch(event, rule);
                }
                case VOICE_BROADCAST -> {
                    return executeVoiceBroadcast(event, rule);
                }
                case NOTIFY_ONLY -> {
                    log.info("notify-only action: eventId={} ruleId={}",
                            event.getId(), rule.getId());
                    return new ActionExecution(
                            rule.getId(),
                            rule.getActionType().name(),
                            "NOTIFIED",
                            -1L,
                            null,
                            null);
                }
                case RECORD_VIDEO -> {
                    log.info("record-video action: eventId={} ruleId={}",
                            event.getId(), rule.getId());
                    return new ActionExecution(
                            rule.getId(),
                            rule.getActionType().name(),
                            "RECORDING",
                            -1L,
                            null,
                            null);
                }
                default -> {
                    return new ActionExecution(
                            rule.getId(),
                            rule.getActionType().name(),
                            "UNKNOWN",
                            -1L,
                            null,
                            "unknown action type");
                }
            }
        } catch (Exception e) {
            log.error("action execution failed: eventId={} ruleId={} action={}",
                    event.getId(), rule.getId(), rule.getActionType(), e);
            return new ActionExecution(
                    rule.getId(),
                    rule.getActionType().name(),
                    "FAILED",
                    -1L,
                    null,
                    e.getMessage());
        }
    }

    /**
     * 执行 AUTO_DISPATCH 动作：调用 {@link AutoDispatchService#dispatchDrone} 派遣无人机。
     * <p>
     * 目标位置：规则指定优先（{@link AlarmLinkageRule#getTargetLat()} 非 NaN），
     * 否则使用事件位置。派遣数量使用 {@link AlarmLinkageRule#getDroneCount()}。
     */
    private ActionExecution executeAutoDispatch(AlarmEvent event, AlarmLinkageRule rule) {
        if (autoDispatchService.isEmpty()) {
            log.warn("auto dispatch service not available: eventId={} ruleId={}",
                    event.getId(), rule.getId());
            return new ActionExecution(
                    rule.getId(), rule.getActionType().name(),
                    "SKIPPED", -1L, null, "auto dispatch service not available");
        }
        double targetLat = Double.isNaN(rule.getTargetLat()) ? event.getLat() : rule.getTargetLat();
        double targetLon = Double.isNaN(rule.getTargetLon()) ? event.getLon() : rule.getTargetLon();
        int droneCount = rule.getDroneCount() > 0 ? rule.getDroneCount() : 1;

        DispatchResult result = autoDispatchService.get()
                .dispatchDrone(targetLat, targetLon, event.getId(), droneCount);
        log.info("auto dispatch action: eventId={} ruleId={} status={} dispatched={}",
                event.getId(), rule.getId(), result.getStatus(),
                result.getDispatchedDrones().size());
        // 派遣成功（已分配任务）= 无人机开始响应：逐机发布机载确认帧（30058）
        for (DispatchResult.DispatchedDrone drone : result.getDispatchedDrones()) {
            if (drone.isTaskAssigned()) {
                publishAlarmAck(event, drone);
            }
        }

        Map<String, Object> template = new java.util.LinkedHashMap<>();
        template.put("dispatchId", result.getDispatchId());
        template.put("status", result.getStatus().name());
        template.put("dispatchedDrones", result.getDispatchedDrones().size());
        template.put("message", result.getMessage());

        return new ActionExecution(
                rule.getId(), rule.getActionType().name(),
                result.getStatus().name(), -1L, template, null);
    }

    /**
     * 执行 VOICE_BROADCAST 动作：调用 {@link VoiceIntercomService#broadcast} 广播喊话。
     * <p>
     * 广播文本：优先使用规则 taskTemplate（透传字段），否则使用事件描述。
     * 音量默认使用 {@link VoiceIntercomService#DEFAULT_VOLUME}。
     */
    private ActionExecution executeVoiceBroadcast(AlarmEvent event, AlarmLinkageRule rule) {
        if (voiceIntercomService.isEmpty()) {
            log.warn("voice intercom service not available: eventId={} ruleId={}",
                    event.getId(), rule.getId());
            return new ActionExecution(
                    rule.getId(), rule.getActionType().name(),
                    "SKIPPED", -1L, null, "voice intercom service not available");
        }
        String text = (rule.getTaskTemplate() != null && !rule.getTaskTemplate().isBlank())
                ? rule.getTaskTemplate()
                : event.getDescription();
        if (text == null || text.isBlank()) {
            text = "请立即注意现场情况";
        }

        // 选取首个在线无人机作为广播目标（简化策略）
        int targetSysid = 1;
        VoiceIntercomService.BroadcastResult result = voiceIntercomService.get()
                .broadcast(targetSysid, text, VoiceIntercomService.DEFAULT_VOLUME);
        log.info("voice broadcast action: eventId={} ruleId={} sysid={} status={}",
                event.getId(), rule.getId(), targetSysid, result.getStatus());

        Map<String, Object> template = new java.util.LinkedHashMap<>();
        template.put("sysid", result.getSysid());
        template.put("status", result.getStatus());
        template.put("message", result.getMessage());
        template.put("volume", result.getVolume());

        return new ActionExecution(
                rule.getId(), rule.getActionType().name(),
                result.getStatus(), -1L, template,
                "SENT".equals(result.getStatus()) ? null : result.getMessage());
    }

    /** 当前规则总数。 */
    public int ruleCount() {
        return rules.size();
    }

    /**
     * 获取联动执行日志（最近 N 条，最新在前）。
     *
     * @param limit 最多返回条数，<=0 表示返回全部
     * @return 联动日志列表（不可变快照）
     */
    public List<LinkageLog> getLinkageLogs(int limit) {
        List<LinkageLog> snapshot = new ArrayList<>(linkageLogs);
        if (limit > 0 && snapshot.size() > limit) {
            return Collections.unmodifiableList(snapshot.subList(0, limit));
        }
        return Collections.unmodifiableList(snapshot);
    }

    /** 当前联动日志总数。 */
    public int linkageLogCount() {
        return linkageLogs.size();
    }

    // =====================================================================
    // WS 帧发布（30057 AlarmTrigger / 30058 AlarmAck）
    // =====================================================================

    /** 机载确认结果常量：1 = 已开始响应（联动派遣成功语义）。 */
    private static final int ACK_RESULT_RESPONDING = 1;

    /**
     * 字符串 ID → u32 稳定哈希（事件 ID / 设备 ID 的协议承载方式）。
     * REST 面 ID 为字符串，u32 字段以 {@code String.hashCode()} 无符号化映射。
     */
    static long idToU32(String id) {
        return id == null ? 0L : id.hashCode() & 0xFFFFFFFFL;
    }

    /** 设备 ID → u16 稳定哈希（sourceDeviceId 协议字段，碰撞可能，REST 面以字符串为准）。 */
    static int deviceIdToU16(String deviceId) {
        return deviceId == null ? 0 : deviceId.hashCode() & 0xFFFF;
    }

    /** 报警海拔（m）→ i16 毫米钳位（协议 i16 范围 ±32.767m，超出截断）。 */
    private static int altMmClampI16(double altM) {
        long mm = Math.round(altM * 1000.0);
        if (mm > Short.MAX_VALUE) {
            return Short.MAX_VALUE;
        }
        if (mm < Short.MIN_VALUE) {
            return Short.MIN_VALUE;
        }
        return (int) mm;
    }

    /**
     * 发布 AlarmTriggerMsg(30057)：报警事件摄取即通知（安防设备→无人机通知语义，
     * 云端代发）。显式租户 = 事件所属租户；帧 sysid = 设备 ID 哈希（非无人机 sysid）。
     * 发布失败仅记日志，不影响事件处理主流程。
     */
    private void publishAlarmTrigger(AlarmEvent event) {
        if (eventPublisher == null) {
            return;
        }
        try {
            AlarmTriggerMsg msg = new AlarmTriggerMsg(
                    event.getTimestampMs(),
                    (int) Math.round(event.getLat() * 1e7),
                    (int) Math.round(event.getLon() * 1e7),
                    deviceIdToU16(event.getSourceDeviceId()),
                    altMmClampI16(event.getAlt()),
                    event.getEventType().ordinal(),
                    event.getSeverity().level(),
                    event.getDescription() == null ? "" : event.getDescription(),
                    // 与 publishAlarmAck 的 alarmId 同一个来源（idToU32(event.getId())），
                    // 否则 trigger 帧与 ack 帧对不上号，消费侧无法关联。
                    idToU32(event.getId()));
            eventPublisher.publishEvent(new MavlinkMessageEvent(
                    this, deviceIdToU16(event.getSourceDeviceId()),
                    AlarmTriggerMsg.ID, msg, event.getTimestampMs(),
                    event.getTenantId()));
        } catch (Exception e) {
            log.warn("alarm trigger frame publish failed: eventId={}: {}", event.getId(), e.getMessage());
        }
    }

    /**
     * 发布 AlarmAckMsg(30058)：AUTO_DISPATCH 派遣成功（无人机已分配任务）视为
     * 机载开始响应，承载真实无人机 sysid 与预计到达时间。显式租户 = 事件所属租户；
     * 事件 sysid = 被派遣无人机（真实机载 sysid）。
     */
    private void publishAlarmAck(AlarmEvent event, DispatchResult.DispatchedDrone drone) {
        if (eventPublisher == null) {
            return;
        }
        try {
            long now = System.currentTimeMillis();
            AlarmAckMsg msg = new AlarmAckMsg(
                    idToU32(event.getId()),
                    now,
                    drone.getEstimatedArrivalSec(),
                    drone.getSysid(),
                    ACK_RESULT_RESPONDING);
            eventPublisher.publishEvent(new MavlinkMessageEvent(
                    this, drone.getSysid(), AlarmAckMsg.ID, msg, now,
                    event.getTenantId()));
        } catch (Exception e) {
            log.warn("alarm ack frame publish failed: eventId={} sysid={}: {}",
                    event.getId(), drone.getSysid(), e.getMessage());
        }
    }

    // =====================================================================
    // 处理结果
    // =====================================================================

    /** 事件处理结果。 */
    public static class ProcessResult {
        /** 事件 ID。 */
        private final String eventId;
        /** 匹配的规则数。 */
        private final int matchedCount;
        /** 各规则执行结果。 */
        private final List<ActionExecution> executions;

        public ProcessResult(String eventId, int matchedCount, List<ActionExecution> executions) {
            this.eventId = eventId;
            this.matchedCount = matchedCount;
            this.executions = Collections.unmodifiableList(executions);
        }

        public String getEventId() {
            return eventId;
        }

        public int getMatchedCount() {
            return matchedCount;
        }

        public List<ActionExecution> getExecutions() {
            return executions;
        }
    }

    /** 单个规则执行结果。 */
    public static class ActionExecution {
        /** 规则 ID。 */
        private final String ruleId;
        /** 动作类型。 */
        private final String actionType;
        /** 执行状态（DEPLOYED/SKIP/NOTIFIED/RECORDING/FAILED）。 */
        private final String status;
        /** 编排计划 ID（-1 表示未启动）。 */
        private final long planId;
        /** 任务模板（DEPLOY_DRONE 时填充）。 */
        private final Map<String, Object> taskTemplate;
        /** 错误信息（失败时填充）。 */
        private final String error;

        public ActionExecution(String ruleId, String actionType, String status,
                               long planId, Map<String, Object> taskTemplate, String error) {
            this.ruleId = ruleId;
            this.actionType = actionType;
            this.status = status;
            this.planId = planId;
            this.taskTemplate = taskTemplate;
            this.error = error;
        }

        public String getRuleId() {
            return ruleId;
        }

        public String getActionType() {
            return actionType;
        }

        public String getStatus() {
            return status;
        }

        public long getPlanId() {
            return planId;
        }

        public Map<String, Object> getTaskTemplate() {
            return taskTemplate;
        }

        public String getError() {
            return error;
        }
    }

    /** 联动执行日志条目。 */
    public static class LinkageLog {
        /** 报警事件 ID。 */
        private final String eventId;
        /** 联动规则 ID。 */
        private final String ruleId;
        /** 动作类型。 */
        private final String actionType;
        /** 执行状态。 */
        private final String status;
        /** 编排计划 ID（-1 表示未启动）。 */
        private final long planId;
        /** 错误信息（失败时填充，null 表示成功）。 */
        private final String error;
        /** 日志记录时间戳（毫秒）。 */
        private final long timestampMs;

        public LinkageLog(String eventId, String ruleId, String actionType,
                          String status, long planId, String error, long timestampMs) {
            this.eventId = eventId;
            this.ruleId = ruleId;
            this.actionType = actionType;
            this.status = status;
            this.planId = planId;
            this.error = error;
            this.timestampMs = timestampMs;
        }

        public String getEventId() {
            return eventId;
        }

        public String getRuleId() {
            return ruleId;
        }

        public String getActionType() {
            return actionType;
        }

        public String getStatus() {
            return status;
        }

        public long getPlanId() {
            return planId;
        }

        public String getError() {
            return error;
        }

        public long getTimestampMs() {
            return timestampMs;
        }
    }
}
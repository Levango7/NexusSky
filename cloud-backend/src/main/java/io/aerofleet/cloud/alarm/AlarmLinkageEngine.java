package io.aerofleet.cloud.alarm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
    /** 联动执行日志（最近 N 条，线程安全的有界队列）。 */
    private final ConcurrentLinkedDeque<LinkageLog> linkageLogs = new ConcurrentLinkedDeque<>();
    /** 联动日志容量上限。 */
    private static final int LINKAGE_LOG_CAPACITY = 500;

    public AlarmLinkageEngine(AlarmEventStore eventStore, AlarmToOrchBridge bridge) {
        this.eventStore = eventStore;
        this.bridge = bridge;
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
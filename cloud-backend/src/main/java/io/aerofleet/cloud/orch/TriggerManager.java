package io.aerofleet.cloud.orch;

import io.aerofleet.cloud.orch.entity.ConditionTriggerEntity;
import io.aerofleet.cloud.orch.entity.OrchestrationPlanEntity;
import io.aerofleet.cloud.orch.enums.PauseReason;
import io.aerofleet.cloud.orch.enums.PlanStatus;
import io.aerofleet.cloud.orch.enums.TriggerAction;
import io.aerofleet.cloud.orch.enums.TriggerType;
import io.aerofleet.cloud.orch.event.EmergencyEndEvent;
import io.aerofleet.cloud.orch.event.EmergencyStartEvent;
import io.aerofleet.cloud.orch.event.PausePlanEvent;
import io.aerofleet.cloud.orch.event.ResumePlanEvent;
import io.aerofleet.cloud.orch.event.StepCompleteEvent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 条件触发器管理器，负责监听事件并评估触发条件、执行触发动作。
 * <p>
 * 核心职责：
 * <ul>
 *   <li>监听应急启动事件，自动暂停资源池有交集的运行中计划</li>
 *   <li>监听应急结束事件，推送恢复通知（不自动恢复，由操作员手动恢复）</li>
 *   <li>监听步骤完成事件，检查 on-complete 触发器</li>
 *   <li>定时检查 TIMER 类型触发器</li>
 * </ul>
 * <p>
 * 线程安全：事件监听方法由 Spring 的事件分发器调度，定时任务由 Spring 的调度器调度。
 * 使用 {@link ConcurrentHashMap} 缓存定时触发器的上次触发时间，避免并发重复触发。
 *
 * @see TriggerType
 * @see TriggerAction
 * @see ConditionTriggerEntity
 */
@Component
public class TriggerManager {

    private static final Logger log = LoggerFactory.getLogger(TriggerManager.class);

    private static final String TIMER_CONDITION_KEY = "triggerTime";

    private final EntityManager entityManager;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    /** 定时触发器上次触发时间缓存：triggerId → 上次触发的毫秒时间戳 */
    private final ConcurrentHashMap<String, Long> timerLastFired = new ConcurrentHashMap<>();

    public TriggerManager(EntityManager entityManager,
                          ApplicationEventPublisher eventPublisher,
                          ObjectMapper objectMapper) {
        this.entityManager = entityManager;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    /**
     * 监听应急启动事件，暂停与应急无人机资源有交集的运行中编排计划。
     * <p>
     * 遍历所有 RUNNING 状态的计划，检查其资源池与应急 droneIds 是否有交集。
     * 有交集的计划通过发布 {@link PausePlanEvent} 自动暂停。
     *
     * @param event 应急启动事件
     */
    @EventListener
    @Transactional
    public void onEmergencyStart(EmergencyStartEvent event) {
        log.info("onEmergencyStart: planId={}, droneIds={}", event.getPlanId(), event.getDroneIds());

        Set<Integer> emergencyDroneIds = new HashSet<>(event.getDroneIds());
        if (emergencyDroneIds.isEmpty()) {
            log.warn("onEmergencyStart: empty droneIds, skipping");
            return;
        }

        List<OrchestrationPlanEntity> runningPlans = findPlansByStatus(PlanStatus.RUNNING);
        for (OrchestrationPlanEntity plan : runningPlans) {
            Set<Integer> planDrones = parseResourcePool(plan.getResourcePool());
            Set<Integer> intersection = new HashSet<>(planDrones);
            intersection.retainAll(emergencyDroneIds);

            if (!intersection.isEmpty()) {
                log.info("onEmergencyStart: pausing plan {} due to resource overlap {} with emergency",
                        plan.getPlanId(), intersection);
                eventPublisher.publishEvent(
                        new PausePlanEvent(this, plan.getPlanId(),
                                "Emergency start: resource overlap with emergency plan " + event.getPlanId()));
            }
        }
    }

    /**
     * 监听应急结束事件，推送恢复通知。
     * <p>
     * 不自动恢复暂停的计划，由操作员根据通知手动恢复。
     * 暂时通过发布 {@link ResumePlanEvent} 通知恢复。
     *
     * @param event 应急结束事件
     */
    @EventListener
    @Transactional
    public void onEmergencyEnd(EmergencyEndEvent event) {
        log.info("onEmergencyEnd: planId={}, pushing resume notification", event.getPlanId());

        // 只恢复因应急暂停的计划（pauseReason=EMERGENCY），手动暂停的计划不受影响
        List<OrchestrationPlanEntity> pausedPlans = findPlansByStatus(PlanStatus.PAUSED);
        for (OrchestrationPlanEntity plan : pausedPlans) {
            if (plan.getPauseReason() == PauseReason.EMERGENCY) {
                log.info("onEmergencyEnd: notifying resume availability for paused plan {}", plan.getPlanId());
                eventPublisher.publishEvent(new ResumePlanEvent(this, plan.getPlanId()));
            }
        }
    }

    /**
     * 监听步骤完成事件，检查并执行 on-complete 触发器。
     * <p>
     * 查询当前计划中 type=STEP_COMPLETE 的触发器，评估条件是否满足，
     * 满足则执行对应的触发动作。
     *
     * @param event 步骤完成事件
     */
    @EventListener
    @Transactional
    public void onStepComplete(StepCompleteEvent event) {
        log.info("onStepComplete: planId={}, stepId={}", event.getPlanId(), event.getStepId());

        List<ConditionTriggerEntity> triggers = findTriggersByPlanAndType(
                event.getPlanId(), TriggerType.STEP_COMPLETE);

        Map<String, Object> context = new ConcurrentHashMap<>();
        context.put("planId", event.getPlanId());
        context.put("stepId", event.getStepId());
        context.put("moduleTaskId", event.getModuleTaskId());

        for (ConditionTriggerEntity trigger : triggers) {
            if (evaluateCondition(trigger, context)) {
                log.info("onStepComplete: trigger {} condition met for plan {}",
                        trigger.getTriggerId(), event.getPlanId());
                executeAction(trigger);
            }
        }
    }

    /**
     * 定时检查 TIMER 类型触发器，每分钟执行一次。
     * <p>
     * 查询所有 type=TIMER 的触发器，检查是否到达触发时间。
     * 到达则执行触发动作，并记录上次触发时间避免重复触发。
     */
    @Scheduled(fixedRate = 60000)
    @Transactional
    public void checkTimerTriggers() {
        List<ConditionTriggerEntity> timerTriggers = findAllTriggersByType(TriggerType.TIMER);
        long now = System.currentTimeMillis();

        for (ConditionTriggerEntity trigger : timerTriggers) {
            Long triggerTime = extractTriggerTime(trigger);
            if (triggerTime == null) {
                continue;
            }

            Long lastFired = timerLastFired.get(trigger.getTriggerId());
            if (lastFired != null && lastFired >= triggerTime) {
                continue;
            }

            if (now >= triggerTime) {
                log.info("checkTimerTriggers: timer trigger {} fired at {}", trigger.getTriggerId(), now);
                executeAction(trigger);
                timerLastFired.put(trigger.getTriggerId(), now);
            }
        }
    }

    /**
     * 评估触发条件是否满足。
     * <p>
     * 条件以 JSON 格式存储，支持：
     * <ul>
     *   <li>简单键值匹配：{"key": "value"} → context 中 key 的值须等于 value</li>
     *   <li>比较运算符：{"key": {"$lt": value}} → context 中 key 的值须小于 value</li>
     * </ul>
     * 支持的运算符：$lt (&lt;), $gt (&gt;), $lte (&lt;=), $gte (&gt;=), $ne (!=)
     * <p>
     * 若条件为空或解析失败，默认返回 true（无条件触发）。
     *
     * @param trigger 触发器实体
     * @param context 上下文变量
     * @return true 表示条件满足
     */
    private boolean evaluateCondition(ConditionTriggerEntity trigger, Map<String, Object> context) {
        String condition = trigger.getCondition();
        if (condition == null || condition.isBlank()) {
            return true;
        }

        try {
            Map<String, Object> conditionMap = objectMapper.readValue(condition,
                    new TypeReference<Map<String, Object>>() {});

            for (Map.Entry<String, Object> entry : conditionMap.entrySet()) {
                String key = entry.getKey();
                Object conditionValue = entry.getValue();
                Object contextValue = context.get(key);

                if (conditionValue instanceof Map) {
                    // 比较运算符模式：{"key": {"$lt": value, "$gte": value2}}
                    if (!evaluateOperatorCondition(contextValue, (Map<String, Object>) conditionValue)) {
                        return false;
                    }
                } else {
                    // 简单等值匹配模式：{"key": "value"}
                    if (contextValue == null || !contextValue.toString().equals(conditionValue.toString())) {
                        return false;
                    }
                }
            }
            return true;
        } catch (Exception e) {
            log.warn("evaluateCondition: failed to parse condition for trigger {}: {}",
                    trigger.getTriggerId(), e.getMessage());
            return true;
        }
    }

    /**
     * 评估比较运算符条件。
     * <p>
     * 支持的运算符：$lt (&lt;), $gt (&gt;), $lte (&lt;=), $gte (&gt;=), $ne (!=)
     * 多个运算符之间为 AND 关系，全部满足才返回 true。
     *
     * @param contextValue 上下文中的值
     * @param operators    运算符映射
     * @return true 表示所有运算符条件都满足
     */
    @SuppressWarnings("unchecked")
    private boolean evaluateOperatorCondition(Object contextValue, Map<String, Object> operators) {
        if (contextValue == null) {
            return false;
        }

        double contextNum = toDouble(contextValue);

        for (Map.Entry<String, Object> op : operators.entrySet()) {
            String operator = op.getKey();
            double targetNum = toDouble(op.getValue());

            switch (operator) {
                case "$lt":
                    if (!(contextNum < targetNum)) return false;
                    break;
                case "$gt":
                    if (!(contextNum > targetNum)) return false;
                    break;
                case "$lte":
                    if (!(contextNum <= targetNum)) return false;
                    break;
                case "$gte":
                    if (!(contextNum >= targetNum)) return false;
                    break;
                case "$ne":
                    if (!contextValue.toString().equals(op.getValue().toString())) return false;
                    break;
                default:
                    log.warn("evaluateOperatorCondition: unknown operator {}", operator);
                    return false;
            }
        }
        return true;
    }

    /**
     * 将值转换为 double 用于数值比较。
     *
     * @param value 待转换的值
     * @return double 值
     */
    private double toDouble(Object value) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return Double.parseDouble(value.toString());
    }

    /**
     * 执行触发动作。
     * <p>
     * 根据触发器的 action 类型执行对应操作：
     * <ul>
     *   <li>PAUSE_PLAN → 发布 PausePlanEvent</li>
     *   <li>RESUME_PLAN → 发布 ResumePlanEvent</li>
     *   <li>START_PLAN → 记录日志（启动逻辑由计划管理服务处理）</li>
     *   <li>NOTIFY → 记录日志（通知推送由推送服务处理）</li>
     * </ul>
     *
     * @param trigger 触发器实体
     */
    private void executeAction(ConditionTriggerEntity trigger) {
        TriggerAction action = trigger.getAction();
        Long targetPlanId = trigger.getTargetPlanId() != null ? trigger.getTargetPlanId() : trigger.getPlanId();

        switch (action) {
            case PAUSE_PLAN:
                log.info("executeAction: PAUSE_PLAN for plan {}", targetPlanId);
                eventPublisher.publishEvent(
                        new PausePlanEvent(this, targetPlanId, "Trigger " + trigger.getTriggerId()));
                break;
            case RESUME_PLAN:
                log.info("executeAction: RESUME_PLAN for plan {}", targetPlanId);
                eventPublisher.publishEvent(new ResumePlanEvent(this, targetPlanId));
                break;
            case START_PLAN:
                log.info("executeAction: START_PLAN for plan {}", targetPlanId);
                break;
            case NOTIFY:
                log.info("executeAction: NOTIFY for plan {}", targetPlanId);
                break;
            default:
                log.warn("executeAction: unknown action {} for trigger {}", action, trigger.getTriggerId());
        }
    }

    /**
     * 从 TIMER 触发器的条件 JSON 中提取触发时间（毫秒时间戳）。
     *
     * @param trigger 定时触发器
     * @return 触发时间戳；解析失败时返回 null
     */
    private Long extractTriggerTime(ConditionTriggerEntity trigger) {
        String condition = trigger.getCondition();
        if (condition == null || condition.isBlank()) {
            return null;
        }

        try {
            Map<String, Object> conditionMap = objectMapper.readValue(condition,
                    new TypeReference<Map<String, Object>>() {});
            Object value = conditionMap.get(TIMER_CONDITION_KEY);
            if (value instanceof Number) {
                return ((Number) value).longValue();
            }
            if (value instanceof String) {
                return Long.parseLong((String) value);
            }
        } catch (Exception e) {
            log.warn("extractTriggerTime: failed to parse condition for trigger {}: {}",
                    trigger.getTriggerId(), e.getMessage());
        }
        return null;
    }

    /**
     * 解析资源池 JSON 字符串为无人机 ID 集合。
     *
     * @param resourcePool JSON 格式的资源池字符串
     * @return 无人机 ID 集合；解析失败时返回空集合
     */
    private Set<Integer> parseResourcePool(String resourcePool) {
        if (resourcePool == null || resourcePool.isBlank()) {
            return Collections.emptySet();
        }

        try {
            List<Integer> drones = objectMapper.readValue(resourcePool,
                    new TypeReference<List<Integer>>() {});
            return new HashSet<>(drones);
        } catch (Exception e) {
            log.warn("parseResourcePool: failed to parse resourcePool: {}", e.getMessage());
            return Collections.emptySet();
        }
    }

    /**
     * 查询指定状态的编排计划列表。
     *
     * @param status 计划状态
     * @return 匹配状态的计划列表
     */
    private List<OrchestrationPlanEntity> findPlansByStatus(PlanStatus status) {
        TypedQuery<OrchestrationPlanEntity> query = entityManager.createQuery(
                "SELECT p FROM OrchestrationPlanEntity p WHERE p.status = :status",
                OrchestrationPlanEntity.class);
        query.setParameter("status", status);
        return query.getResultList();
    }

    /**
     * 查询指定计划和类型的触发器列表。
     *
     * @param planId 计划 ID
     * @param type   触发器类型
     * @return 匹配的触发器列表
     */
    private List<ConditionTriggerEntity> findTriggersByPlanAndType(Long planId, TriggerType type) {
        TypedQuery<ConditionTriggerEntity> query = entityManager.createQuery(
                "SELECT t FROM ConditionTriggerEntity t WHERE t.planId = :planId AND t.type = :type",
                ConditionTriggerEntity.class);
        query.setParameter("planId", planId);
        query.setParameter("type", type);
        return query.getResultList();
    }

    /**
     * 查询指定类型的所有触发器列表。
     *
     * @param type 触发器类型
     * @return 匹配的触发器列表
     */
    private List<ConditionTriggerEntity> findAllTriggersByType(TriggerType type) {
        TypedQuery<ConditionTriggerEntity> query = entityManager.createQuery(
                "SELECT t FROM ConditionTriggerEntity t WHERE t.type = :type",
                ConditionTriggerEntity.class);
        query.setParameter("type", type);
        return query.getResultList();
    }
}
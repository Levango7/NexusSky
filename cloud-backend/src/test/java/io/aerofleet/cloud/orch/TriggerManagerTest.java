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

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 条件触发器管理器单测。
 * <p>
 * 覆盖 onEmergencyStart / onEmergencyEnd / onStepComplete /
 * checkTimerTriggers / evaluateCondition 等核心逻辑。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TriggerManager 条件触发器管理器")
class TriggerManagerTest {

    @Mock
    private EntityManager entityManager;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    private TriggerManager triggerManager;

    @BeforeEach
    void setUp() {
        triggerManager = new TriggerManager(entityManager, eventPublisher, objectMapper);
    }

    // ==================== onEmergencyStart ====================

    @Test
    @DisplayName("onEmergencyStart 暂停与应急资源有交集的 RUNNING 计划")
    void onEmergencyStartPausesOverlappingPlans() {
        // Arrange
        OrchestrationPlanEntity plan1 = buildPlan(1L, PlanStatus.RUNNING, "[1,2,3]");
        OrchestrationPlanEntity plan2 = buildPlan(2L, PlanStatus.RUNNING, "[4,5,6]");
        List<OrchestrationPlanEntity> runningPlans = Arrays.asList(plan1, plan2);

        TypedQuery<OrchestrationPlanEntity> mockQuery = mock(TypedQuery.class);
        when(entityManager.createQuery(anyString(), eq(OrchestrationPlanEntity.class)))
                .thenReturn(mockQuery);
        when(mockQuery.setParameter("status", PlanStatus.RUNNING)).thenReturn(mockQuery);
        when(mockQuery.getResultList()).thenReturn(runningPlans);

        // Act: 应急使用 drone 1，与 plan1 有交集
        triggerManager.onEmergencyStart(new EmergencyStartEvent(this, 100L, Arrays.asList(1)));

        // Assert: plan1 被暂停，plan2 不受影响
        ArgumentCaptor<PausePlanEvent> captor = ArgumentCaptor.forClass(PausePlanEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        PausePlanEvent event = captor.getValue();
        assertEquals(1L, event.getPlanId());
        assertNotNull(event.getReason());
    }

    @Test
    @DisplayName("onEmergencyStart 无交集的 RUNNING 计划不受影响")
    void onEmergencyStartNoOverlapDoesNotPause() {
        // Arrange
        OrchestrationPlanEntity plan1 = buildPlan(1L, PlanStatus.RUNNING, "[4,5,6]");

        TypedQuery<OrchestrationPlanEntity> mockQuery = mock(TypedQuery.class);
        when(entityManager.createQuery(anyString(), eq(OrchestrationPlanEntity.class)))
                .thenReturn(mockQuery);
        when(mockQuery.setParameter("status", PlanStatus.RUNNING)).thenReturn(mockQuery);
        when(mockQuery.getResultList()).thenReturn(Collections.singletonList(plan1));

        // Act: 应急使用 drone 1，与 plan1 无交集
        triggerManager.onEmergencyStart(new EmergencyStartEvent(this, 100L, Arrays.asList(1)));

        // Assert
        verify(eventPublisher, never()).publishEvent(any(PausePlanEvent.class));
    }

    @Test
    @DisplayName("onEmergencyStart 空 droneIds 不操作")
    void onEmergencyStartEmptyDroneIdsDoesNothing() {
        // Act
        triggerManager.onEmergencyStart(new EmergencyStartEvent(this, 100L, Collections.emptyList()));

        // Assert
        verify(entityManager, never()).createQuery(anyString(), any(Class.class));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("onEmergencyStart 多个计划有交集时全部暂停")
    void onEmergencyStartMultipleOverlappingPlans() {
        // Arrange
        OrchestrationPlanEntity plan1 = buildPlan(1L, PlanStatus.RUNNING, "[1,2]");
        OrchestrationPlanEntity plan2 = buildPlan(2L, PlanStatus.RUNNING, "[3,4]");
        OrchestrationPlanEntity plan3 = buildPlan(3L, PlanStatus.RUNNING, "[1,5]");

        TypedQuery<OrchestrationPlanEntity> mockQuery = mock(TypedQuery.class);
        when(entityManager.createQuery(anyString(), eq(OrchestrationPlanEntity.class)))
                .thenReturn(mockQuery);
        when(mockQuery.setParameter("status", PlanStatus.RUNNING)).thenReturn(mockQuery);
        when(mockQuery.getResultList()).thenReturn(Arrays.asList(plan1, plan2, plan3));

        // Act: 应急使用 drone 1 和 3，与 plan1(1) 和 plan2(3) 和 plan3(1) 都有交集
        triggerManager.onEmergencyStart(new EmergencyStartEvent(this, 100L, Arrays.asList(1, 3)));

        // Assert: plan1、plan2、plan3 都被暂停（3 个 PausePlanEvent）
        verify(eventPublisher, times(3)).publishEvent(any(PausePlanEvent.class));
    }

    // ==================== onEmergencyEnd ====================

    @Test
    @DisplayName("onEmergencyEnd 向所有 PAUSED 计划推送恢复通知")
    void onEmergencyEndPushesResumeNotifications() {
        // Arrange
        OrchestrationPlanEntity pausedPlan1 = buildPlan(1L, PlanStatus.PAUSED, "[1]");
        pausedPlan1.setPauseReason(PauseReason.EMERGENCY);
        OrchestrationPlanEntity pausedPlan2 = buildPlan(2L, PlanStatus.PAUSED, "[2]");
        pausedPlan2.setPauseReason(PauseReason.EMERGENCY);

        TypedQuery<OrchestrationPlanEntity> mockQuery = mock(TypedQuery.class);
        when(entityManager.createQuery(anyString(), eq(OrchestrationPlanEntity.class)))
                .thenReturn(mockQuery);
        when(mockQuery.setParameter("status", PlanStatus.PAUSED)).thenReturn(mockQuery);
        when(mockQuery.getResultList()).thenReturn(Arrays.asList(pausedPlan1, pausedPlan2));

        // Act
        triggerManager.onEmergencyEnd(new EmergencyEndEvent(this, 100L));

        // Assert
        verify(eventPublisher, times(2)).publishEvent(any(ResumePlanEvent.class));
    }

    @Test
    @DisplayName("onEmergencyEnd 无 PAUSED 计划时不发布事件")
    void onEmergencyEndNoPausedPlans() {
        // Arrange
        TypedQuery<OrchestrationPlanEntity> mockQuery = mock(TypedQuery.class);
        when(entityManager.createQuery(anyString(), eq(OrchestrationPlanEntity.class)))
                .thenReturn(mockQuery);
        when(mockQuery.setParameter("status", PlanStatus.PAUSED)).thenReturn(mockQuery);
        when(mockQuery.getResultList()).thenReturn(Collections.emptyList());

        // Act
        triggerManager.onEmergencyEnd(new EmergencyEndEvent(this, 100L));

        // Assert
        verify(eventPublisher, never()).publishEvent(any(ResumePlanEvent.class));
    }

    // ==================== onStepComplete ====================

    @Test
    @DisplayName("onStepComplete 条件满足时执行触发动作")
    void onStepCompleteConditionMetExecutesAction() {
        // Arrange
        ConditionTriggerEntity trigger = buildTrigger("trig-1", TriggerType.STEP_COMPLETE,
                TriggerAction.PAUSE_PLAN, "{\"stepId\":\"step-1\"}");

        TypedQuery<ConditionTriggerEntity> mockQuery = mock(TypedQuery.class);
        when(entityManager.createQuery(anyString(), eq(ConditionTriggerEntity.class)))
                .thenReturn(mockQuery);
        when(mockQuery.setParameter(eq("planId"), eq(1L))).thenReturn(mockQuery);
        when(mockQuery.setParameter(eq("type"), eq(TriggerType.STEP_COMPLETE))).thenReturn(mockQuery);
        when(mockQuery.getResultList()).thenReturn(Collections.singletonList(trigger));

        // Act
        triggerManager.onStepComplete(new StepCompleteEvent(this, 1L, "step-1", "task-001"));

        // Assert: PAUSE_PLAN 动作发布 PausePlanEvent
        verify(eventPublisher).publishEvent(any(PausePlanEvent.class));
    }

    @Test
    @DisplayName("onStepComplete 条件不满足时不执行触发动作")
    void onStepCompleteConditionNotMetDoesNotExecute() {
        // Arrange
        ConditionTriggerEntity trigger = buildTrigger("trig-1", TriggerType.STEP_COMPLETE,
                TriggerAction.PAUSE_PLAN, "{\"stepId\":\"step-2\"}");

        TypedQuery<ConditionTriggerEntity> mockQuery = mock(TypedQuery.class);
        when(entityManager.createQuery(anyString(), eq(ConditionTriggerEntity.class)))
                .thenReturn(mockQuery);
        when(mockQuery.setParameter(eq("planId"), eq(1L))).thenReturn(mockQuery);
        when(mockQuery.setParameter(eq("type"), eq(TriggerType.STEP_COMPLETE))).thenReturn(mockQuery);
        when(mockQuery.getResultList()).thenReturn(Collections.singletonList(trigger));

        // Act: 事件中 stepId=step-1，但条件要求 stepId=step-2
        triggerManager.onStepComplete(new StepCompleteEvent(this, 1L, "step-1", "task-001"));

        // Assert
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("onStepComplete 无触发器时不执行动作")
    void onStepCompleteNoTriggers() {
        // Arrange
        TypedQuery<ConditionTriggerEntity> mockQuery = mock(TypedQuery.class);
        when(entityManager.createQuery(anyString(), eq(ConditionTriggerEntity.class)))
                .thenReturn(mockQuery);
        when(mockQuery.setParameter(eq("planId"), eq(1L))).thenReturn(mockQuery);
        when(mockQuery.setParameter(eq("type"), eq(TriggerType.STEP_COMPLETE))).thenReturn(mockQuery);
        when(mockQuery.getResultList()).thenReturn(Collections.emptyList());

        // Act
        triggerManager.onStepComplete(new StepCompleteEvent(this, 1L, "step-1", "task-001"));

        // Assert
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("onStepComplete RESUME_PLAN 动作发布 ResumePlanEvent")
    void onStepCompleteResumePlanAction() {
        // Arrange
        ConditionTriggerEntity trigger = buildTrigger("trig-1", TriggerType.STEP_COMPLETE,
                TriggerAction.RESUME_PLAN, null);

        TypedQuery<ConditionTriggerEntity> mockQuery = mock(TypedQuery.class);
        when(entityManager.createQuery(anyString(), eq(ConditionTriggerEntity.class)))
                .thenReturn(mockQuery);
        when(mockQuery.setParameter(eq("planId"), eq(1L))).thenReturn(mockQuery);
        when(mockQuery.setParameter(eq("type"), eq(TriggerType.STEP_COMPLETE))).thenReturn(mockQuery);
        when(mockQuery.getResultList()).thenReturn(Collections.singletonList(trigger));

        // Act
        triggerManager.onStepComplete(new StepCompleteEvent(this, 1L, "step-1", "task-001"));

        // Assert
        verify(eventPublisher).publishEvent(any(ResumePlanEvent.class));
    }

    @Test
    @DisplayName("onStepComplete 空条件触发器无条件执行")
    void onStepCompleteEmptyConditionAlwaysTriggers() {
        // Arrange
        ConditionTriggerEntity trigger = buildTrigger("trig-1", TriggerType.STEP_COMPLETE,
                TriggerAction.NOTIFY, "");

        TypedQuery<ConditionTriggerEntity> mockQuery = mock(TypedQuery.class);
        when(entityManager.createQuery(anyString(), eq(ConditionTriggerEntity.class)))
                .thenReturn(mockQuery);
        when(mockQuery.setParameter(eq("planId"), eq(1L))).thenReturn(mockQuery);
        when(mockQuery.setParameter(eq("type"), eq(TriggerType.STEP_COMPLETE))).thenReturn(mockQuery);
        when(mockQuery.getResultList()).thenReturn(Collections.singletonList(trigger));

        // Act
        triggerManager.onStepComplete(new StepCompleteEvent(this, 1L, "step-1", "task-001"));

        // Assert: 空条件默认满足，NOTIFY 动作不发布事件但执行了日志
        verify(eventPublisher, never()).publishEvent(any());
    }

    // ==================== checkTimerTriggers ====================

    @Test
    @DisplayName("checkTimerTriggers 到达触发时间时执行动作")
    void checkTimerTriggersFiresWhenTimeReached() {
        // Arrange
        long pastTime = System.currentTimeMillis() - 1000;
        ConditionTriggerEntity timerTrigger = buildTrigger("timer-1", TriggerType.TIMER,
                TriggerAction.PAUSE_PLAN, "{\"triggerTime\":" + pastTime + "}");

        TypedQuery<ConditionTriggerEntity> mockQuery = mock(TypedQuery.class);
        when(entityManager.createQuery(anyString(), eq(ConditionTriggerEntity.class)))
                .thenReturn(mockQuery);
        when(mockQuery.setParameter(eq("type"), eq(TriggerType.TIMER))).thenReturn(mockQuery);
        when(mockQuery.getResultList()).thenReturn(Collections.singletonList(timerTrigger));

        // Act
        triggerManager.checkTimerTriggers();

        // Assert
        verify(eventPublisher).publishEvent(any(PausePlanEvent.class));
    }

    @Test
    @DisplayName("checkTimerTriggers 未到达触发时间时不执行")
    void checkTimerTriggersDoesNotFireBeforeTime() {
        // Arrange
        long futureTime = System.currentTimeMillis() + 60000;
        ConditionTriggerEntity timerTrigger = buildTrigger("timer-1", TriggerType.TIMER,
                TriggerAction.PAUSE_PLAN, "{\"triggerTime\":" + futureTime + "}");

        TypedQuery<ConditionTriggerEntity> mockQuery = mock(TypedQuery.class);
        when(entityManager.createQuery(anyString(), eq(ConditionTriggerEntity.class)))
                .thenReturn(mockQuery);
        when(mockQuery.setParameter(eq("type"), eq(TriggerType.TIMER))).thenReturn(mockQuery);
        when(mockQuery.getResultList()).thenReturn(Collections.singletonList(timerTrigger));

        // Act
        triggerManager.checkTimerTriggers();

        // Assert
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("checkTimerTriggers 无定时触发器时不操作")
    void checkTimerTriggersNoTimerTriggers() {
        // Arrange
        TypedQuery<ConditionTriggerEntity> mockQuery = mock(TypedQuery.class);
        when(entityManager.createQuery(anyString(), eq(ConditionTriggerEntity.class)))
                .thenReturn(mockQuery);
        when(mockQuery.setParameter(eq("type"), eq(TriggerType.TIMER))).thenReturn(mockQuery);
        when(mockQuery.getResultList()).thenReturn(Collections.emptyList());

        // Act
        triggerManager.checkTimerTriggers();

        // Assert
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("checkTimerTriggers 已触发过的不再重复触发")
    void checkTimerTriggersDoesNotRepeatFire() {
        // Arrange
        long pastTime = System.currentTimeMillis() - 1000;
        ConditionTriggerEntity timerTrigger = buildTrigger("timer-1", TriggerType.TIMER,
                TriggerAction.PAUSE_PLAN, "{\"triggerTime\":" + pastTime + "}");

        TypedQuery<ConditionTriggerEntity> mockQuery = mock(TypedQuery.class);
        when(entityManager.createQuery(anyString(), eq(ConditionTriggerEntity.class)))
                .thenReturn(mockQuery);
        when(mockQuery.setParameter(eq("type"), eq(TriggerType.TIMER))).thenReturn(mockQuery);
        when(mockQuery.getResultList()).thenReturn(Collections.singletonList(timerTrigger));

        // Act: 第一次触发
        triggerManager.checkTimerTriggers();
        // 第二次调用不应重复触发
        triggerManager.checkTimerTriggers();

        // Assert: 只触发一次
        verify(eventPublisher, times(1)).publishEvent(any(PausePlanEvent.class));
    }

    @Test
    @DisplayName("checkTimerTriggers 无 triggerTime 条件时跳过")
    void checkTimerTriggersNoTriggerTimeSkips() {
        // Arrange
        ConditionTriggerEntity timerTrigger = buildTrigger("timer-1", TriggerType.TIMER,
                TriggerAction.PAUSE_PLAN, "{}");

        TypedQuery<ConditionTriggerEntity> mockQuery = mock(TypedQuery.class);
        when(entityManager.createQuery(anyString(), eq(ConditionTriggerEntity.class)))
                .thenReturn(mockQuery);
        when(mockQuery.setParameter(eq("type"), eq(TriggerType.TIMER))).thenReturn(mockQuery);
        when(mockQuery.getResultList()).thenReturn(Collections.singletonList(timerTrigger));

        // Act
        triggerManager.checkTimerTriggers();

        // Assert
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("checkTimerTriggers triggerTime 为字符串格式也能解析")
    void checkTimerTriggersStringTriggerTime() {
        // Arrange
        long pastTime = System.currentTimeMillis() - 1000;
        ConditionTriggerEntity timerTrigger = buildTrigger("timer-1", TriggerType.TIMER,
                TriggerAction.PAUSE_PLAN, "{\"triggerTime\":\"" + pastTime + "\"}");

        TypedQuery<ConditionTriggerEntity> mockQuery = mock(TypedQuery.class);
        when(entityManager.createQuery(anyString(), eq(ConditionTriggerEntity.class)))
                .thenReturn(mockQuery);
        when(mockQuery.setParameter(eq("type"), eq(TriggerType.TIMER))).thenReturn(mockQuery);
        when(mockQuery.getResultList()).thenReturn(Collections.singletonList(timerTrigger));

        // Act
        triggerManager.checkTimerTriggers();

        // Assert
        verify(eventPublisher).publishEvent(any(PausePlanEvent.class));
    }

    // ==================== evaluateCondition (通过 onStepComplete 间接测试) ====================

    @Test
    @DisplayName("evaluateCondition 条件 JSON 解析失败时默认满足")
    void evaluateConditionParseFailureDefaultsTrue() {
        // Arrange
        ConditionTriggerEntity trigger = buildTrigger("trig-1", TriggerType.STEP_COMPLETE,
                TriggerAction.PAUSE_PLAN, "invalid-json");

        TypedQuery<ConditionTriggerEntity> mockQuery = mock(TypedQuery.class);
        when(entityManager.createQuery(anyString(), eq(ConditionTriggerEntity.class)))
                .thenReturn(mockQuery);
        when(mockQuery.setParameter(eq("planId"), eq(1L))).thenReturn(mockQuery);
        when(mockQuery.setParameter(eq("type"), eq(TriggerType.STEP_COMPLETE))).thenReturn(mockQuery);
        when(mockQuery.getResultList()).thenReturn(Collections.singletonList(trigger));

        // Act: 无效 JSON 条件应默认满足
        triggerManager.onStepComplete(new StepCompleteEvent(this, 1L, "step-1", "task-001"));

        // Assert: 条件解析失败默认 true，应执行 PAUSE_PLAN
        verify(eventPublisher).publishEvent(any(PausePlanEvent.class));
    }

    @Test
    @DisplayName("evaluateCondition 多键条件全部匹配时满足")
    void evaluateConditionMultipleKeysAllMatch() {
        // Arrange
        ConditionTriggerEntity trigger = buildTrigger("trig-1", TriggerType.STEP_COMPLETE,
                TriggerAction.PAUSE_PLAN, "{\"stepId\":\"step-1\",\"planId\":1}");

        TypedQuery<ConditionTriggerEntity> mockQuery = mock(TypedQuery.class);
        when(entityManager.createQuery(anyString(), eq(ConditionTriggerEntity.class)))
                .thenReturn(mockQuery);
        when(mockQuery.setParameter(eq("planId"), eq(1L))).thenReturn(mockQuery);
        when(mockQuery.setParameter(eq("type"), eq(TriggerType.STEP_COMPLETE))).thenReturn(mockQuery);
        when(mockQuery.getResultList()).thenReturn(Collections.singletonList(trigger));

        // Act: stepId=step-1 且 planId=1 都匹配
        triggerManager.onStepComplete(new StepCompleteEvent(this, 1L, "step-1", "task-001"));

        // Assert
        verify(eventPublisher).publishEvent(any(PausePlanEvent.class));
    }

    @Test
    @DisplayName("evaluateCondition 多键条件部分匹配时不满足")
    void evaluateConditionMultipleKeysPartialMatch() {
        // Arrange
        ConditionTriggerEntity trigger = buildTrigger("trig-1", TriggerType.STEP_COMPLETE,
                TriggerAction.PAUSE_PLAN, "{\"stepId\":\"step-1\",\"planId\":2}");

        TypedQuery<ConditionTriggerEntity> mockQuery = mock(TypedQuery.class);
        when(entityManager.createQuery(anyString(), eq(ConditionTriggerEntity.class)))
                .thenReturn(mockQuery);
        when(mockQuery.setParameter(eq("planId"), eq(1L))).thenReturn(mockQuery);
        when(mockQuery.setParameter(eq("type"), eq(TriggerType.STEP_COMPLETE))).thenReturn(mockQuery);
        when(mockQuery.getResultList()).thenReturn(Collections.singletonList(trigger));

        // Act: stepId 匹配但 planId 不匹配（事件 planId=1，条件要求 planId=2）
        triggerManager.onStepComplete(new StepCompleteEvent(this, 1L, "step-1", "task-001"));

        // Assert
        verify(eventPublisher, never()).publishEvent(any());
    }

    // ==================== executeAction - targetPlanId ====================

    @Test
    @DisplayName("onStepComplete 使用 targetPlanId 而非 planId")
    void onStepCompleteUsesTargetPlanId() {
        // Arrange
        ConditionTriggerEntity trigger = buildTrigger("trig-1", TriggerType.STEP_COMPLETE,
                TriggerAction.PAUSE_PLAN, null);
        trigger.setTargetPlanId(99L);

        TypedQuery<ConditionTriggerEntity> mockQuery = mock(TypedQuery.class);
        when(entityManager.createQuery(anyString(), eq(ConditionTriggerEntity.class)))
                .thenReturn(mockQuery);
        when(mockQuery.setParameter(eq("planId"), eq(1L))).thenReturn(mockQuery);
        when(mockQuery.setParameter(eq("type"), eq(TriggerType.STEP_COMPLETE))).thenReturn(mockQuery);
        when(mockQuery.getResultList()).thenReturn(Collections.singletonList(trigger));

        // Act
        triggerManager.onStepComplete(new StepCompleteEvent(this, 1L, "step-1", "task-001"));

        // Assert: PausePlanEvent 应使用 targetPlanId=99
        ArgumentCaptor<PausePlanEvent> captor = ArgumentCaptor.forClass(PausePlanEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertEquals(99L, captor.getValue().getPlanId());
    }

    // ==================== 辅助方法 ====================

    private OrchestrationPlanEntity buildPlan(Long planId, PlanStatus status, String resourcePool) {
        OrchestrationPlanEntity plan = new OrchestrationPlanEntity();
        plan.setPlanId(planId);
        plan.setName("test-plan-" + planId);
        plan.setStatus(status);
        plan.setResourcePool(resourcePool);
        plan.setCreateTime(System.currentTimeMillis());
        return plan;
    }

    private ConditionTriggerEntity buildTrigger(String triggerId, TriggerType type,
                                                 TriggerAction action, String condition) {
        ConditionTriggerEntity trigger = new ConditionTriggerEntity();
        trigger.setTriggerId(triggerId);
        trigger.setType(type);
        trigger.setAction(action);
        trigger.setCondition(condition);
        trigger.setPlanId(1L);
        return trigger;
    }
}
package io.aerofleet.cloud.orch;

import io.aerofleet.cloud.orch.entity.ConditionTriggerEntity;
import io.aerofleet.cloud.orch.entity.OrchestrationPlanEntity;
import io.aerofleet.cloud.orch.entity.TaskStepEntity;
import io.aerofleet.cloud.orch.enums.ModuleType;
import io.aerofleet.cloud.orch.enums.PlanStatus;
import io.aerofleet.cloud.orch.enums.StepAction;
import io.aerofleet.cloud.orch.enums.StepStatus;
import io.aerofleet.cloud.orch.enums.TriggerAction;
import io.aerofleet.cloud.orch.enums.TriggerType;
import io.aerofleet.cloud.orch.event.PausePlanEvent;
import io.aerofleet.cloud.orch.event.ResumePlanEvent;
import io.aerofleet.cloud.orch.event.StepCompleteEvent;
import io.aerofleet.cloud.orch.event.StepFailEvent;
import io.aerofleet.cloud.orch.repository.ConditionTriggerRepository;
import io.aerofleet.cloud.orch.repository.OrchestrationPlanRepository;
import io.aerofleet.cloud.orch.repository.TaskStepRepository;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * 编排计划服务核心单测。
 * <p>
 * 覆盖 createPlan / startPlan / pausePlan / resumePlan / abortPlan /
 * onStepComplete / onStepFail / checkPlanCompletion / hasCircularDependency /
 * getReadySteps / markSkippedSteps 等核心逻辑。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OrchestrationPlanService 编排计划服务")
class OrchestrationPlanServiceTest {

    @Mock
    private OrchestrationPlanRepository planRepository;

    @Mock
    private TaskStepRepository stepRepository;

    @Mock
    private ConditionTriggerRepository triggerRepository;

    @Mock
    private ResourceManager resourceManager;

    @Mock
    private StepExecutor stepExecutor;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private OrchestrationPlanService service;

    // 内存存储模拟，用于需要 save/findById 交互的场景
    private Map<Long, OrchestrationPlanEntity> planStore;
    private AtomicLong planIdGenerator;

    @BeforeEach
    void setUp() {
        planStore = new HashMap<>();
        planIdGenerator = new AtomicLong(1L);
    }

    // ==================== createPlan ====================

    @Test
    @DisplayName("createPlan 正常创建计划返回 planId")
    void createPlanReturnsPlanId() {
        // Arrange
        TaskStepEntity step = buildStep("step-1", null, "[]", null);
        when(planRepository.save(any(OrchestrationPlanEntity.class))).thenAnswer(inv -> {
            OrchestrationPlanEntity p = inv.getArgument(0);
            p.setPlanId(planIdGenerator.getAndIncrement());
            planStore.put(p.getPlanId(), p);
            return p;
        });
        when(stepRepository.save(any(TaskStepEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        // Act
        Long planId = service.createPlan("test-plan", Arrays.asList(1, 2),
                Collections.singletonList(step), null);

        // Assert
        assertNotNull(planId);
        assertEquals(1L, planId);
        verify(planRepository).save(any(OrchestrationPlanEntity.class));
        verify(stepRepository).save(any(TaskStepEntity.class));
    }

    @Test
    @DisplayName("createPlan 名称为空抛出 IllegalArgumentException")
    void createPlanEmptyNameThrows() {
        // Arrange & Act & Assert
        assertThrows(IllegalArgumentException.class,
                () -> service.createPlan("", Arrays.asList(1), Collections.singletonList(buildStep("s1", null, "[]", null)), null));
        assertThrows(IllegalArgumentException.class,
                () -> service.createPlan(null, Arrays.asList(1), Collections.singletonList(buildStep("s1", null, "[]", null)), null));
    }

    @Test
    @DisplayName("createPlan 资源池为空抛出 IllegalArgumentException")
    void createPlanEmptyResourcePoolThrows() {
        // Arrange & Act & Assert
        assertThrows(IllegalArgumentException.class,
                () -> service.createPlan("plan", Collections.emptyList(),
                        Collections.singletonList(buildStep("s1", null, "[]", null)), null));
        assertThrows(IllegalArgumentException.class,
                () -> service.createPlan("plan", null,
                        Collections.singletonList(buildStep("s1", null, "[]", null)), null));
    }

    @Test
    @DisplayName("createPlan 步骤列表为空抛出 IllegalArgumentException")
    void createPlanEmptyStepsThrows() {
        // Arrange & Act & Assert
        assertThrows(IllegalArgumentException.class,
                () -> service.createPlan("plan", Arrays.asList(1), Collections.emptyList(), null));
        assertThrows(IllegalArgumentException.class,
                () -> service.createPlan("plan", Arrays.asList(1), null, null));
    }

    @Test
    @DisplayName("createPlan stepId 为空抛出 IllegalArgumentException")
    void createPlanBlankStepIdThrows() {
        // Arrange
        TaskStepEntity step = buildStep("", null, "[]", null);

        // Act & Assert
        assertThrows(IllegalArgumentException.class,
                () -> service.createPlan("plan", Arrays.asList(1), Collections.singletonList(step), null));
    }

    @Test
    @DisplayName("createPlan stepId 重复抛出 IllegalArgumentException")
    void createPlanDuplicateStepIdThrows() {
        // Arrange
        TaskStepEntity step1 = buildStep("step-1", null, "[]", null);
        TaskStepEntity step2 = buildStep("step-1", null, "[]", null);

        // Act & Assert
        assertThrows(IllegalArgumentException.class,
                () -> service.createPlan("plan", Arrays.asList(1), Arrays.asList(step1, step2), null));
    }

    @Test
    @DisplayName("createPlan 循环依赖检测抛出 IllegalArgumentException")
    void createPlanCircularDependencyThrows() {
        // Arrange: step-1 → step-2 → step-1
        TaskStepEntity step1 = buildStep("step-1", "[\"step-2\"]", null, null);
        TaskStepEntity step2 = buildStep("step-2", "[\"step-1\"]", null, null);

        // Act & Assert
        assertThrows(IllegalArgumentException.class,
                () -> service.createPlan("plan", Arrays.asList(1), Arrays.asList(step1, step2), null));
    }

    @Test
    @DisplayName("createPlan 自循环依赖检测")
    void createPlanSelfCircularDependencyThrows() {
        // Arrange: step-1 → step-1
        TaskStepEntity step1 = buildStep("step-1", "[\"step-1\"]", null, null);

        // Act & Assert
        assertThrows(IllegalArgumentException.class,
                () -> service.createPlan("plan", Arrays.asList(1), Collections.singletonList(step1), null));
    }

    @Test
    @DisplayName("createPlan 三节点循环依赖检测")
    void createPlanThreeNodeCycleThrows() {
        // Arrange: step-1 → step-2 → step-3 → step-1
        TaskStepEntity step1 = buildStep("step-1", "[\"step-2\"]", null, null);
        TaskStepEntity step2 = buildStep("step-2", "[\"step-3\"]", null, null);
        TaskStepEntity step3 = buildStep("step-3", "[\"step-1\"]", null, null);

        // Act & Assert
        assertThrows(IllegalArgumentException.class,
                () -> service.createPlan("plan", Arrays.asList(1), Arrays.asList(step1, step2, step3), null));
    }

    @Test
    @DisplayName("createPlan 无循环依赖的正常 DAG 通过校验")
    void createPlanValidDagPasses() {
        // Arrange: step-1 → step-2, step-1 → step-3 (无环)
        TaskStepEntity step1 = buildStep("step-1", null, "[]", null);
        TaskStepEntity step2 = buildStep("step-2", null, "[\"step-1\"]", null);
        TaskStepEntity step3 = buildStep("step-3", null, "[\"step-1\"]", null);

        when(planRepository.save(any(OrchestrationPlanEntity.class))).thenAnswer(inv -> {
            OrchestrationPlanEntity p = inv.getArgument(0);
            p.setPlanId(1L);
            return p;
        });
        when(stepRepository.save(any(TaskStepEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        // Act & Assert
        Long planId = service.createPlan("plan", Arrays.asList(1, 2),
                Arrays.asList(step1, step2, step3), null);
        assertNotNull(planId);
    }

    @Test
    @DisplayName("createPlan 保存触发器")
    void createPlanSavesTriggers() {
        // Arrange
        TaskStepEntity step = buildStep("step-1", null, "[]", null);
        ConditionTriggerEntity trigger = buildTrigger("trig-1", TriggerType.STEP_COMPLETE,
                TriggerAction.NOTIFY, null);

        when(planRepository.save(any(OrchestrationPlanEntity.class))).thenAnswer(inv -> {
            OrchestrationPlanEntity p = inv.getArgument(0);
            p.setPlanId(1L);
            return p;
        });
        when(stepRepository.save(any(TaskStepEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(triggerRepository.save(any(ConditionTriggerEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        // Act
        Long planId = service.createPlan("plan", Arrays.asList(1),
                Collections.singletonList(step), Collections.singletonList(trigger));

        // Assert
        assertNotNull(planId);
        verify(triggerRepository).save(any(ConditionTriggerEntity.class));
    }

    // ==================== startPlan ====================

    @Test
    @DisplayName("startPlan 正常启动 DRAFT→RUNNING")
    void startPlanTransitionsToRunning() {
        // Arrange
        OrchestrationPlanEntity plan = buildPlan(1L, PlanStatus.DRAFT, "[1,2]");
        TaskStepEntity pendingStep = buildStep("step-1", null, "[]", null);
        pendingStep.setStatus(StepStatus.PENDING);
        when(planRepository.findById(1L)).thenReturn(Optional.of(plan));
        when(planRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(resourceManager.allocate(1L, Arrays.asList(1, 2))).thenReturn(true);
        when(stepRepository.findByPlanId(1L)).thenReturn(Collections.singletonList(pendingStep));

        // Act
        service.startPlan(1L);

        // Assert
        assertEquals(PlanStatus.RUNNING, plan.getStatus());
        assertNotNull(plan.getStartTime());
        verify(resourceManager).allocate(1L, Arrays.asList(1, 2));
    }

    @Test
    @DisplayName("startPlan 计划不存在抛出 IllegalArgumentException")
    void startPlanNotFoundThrows() {
        // Arrange
        when(planRepository.findById(999L)).thenReturn(Optional.empty());

        // Act & Assert
        assertThrows(IllegalArgumentException.class, () -> service.startPlan(999L));
    }

    @Test
    @DisplayName("startPlan 非 DRAFT 状态抛出 IllegalStateException")
    void startPlanNotDraftThrows() {
        // Arrange
        OrchestrationPlanEntity plan = buildPlan(1L, PlanStatus.RUNNING, "[1]");
        when(planRepository.findById(1L)).thenReturn(Optional.of(plan));

        // Act & Assert
        assertThrows(IllegalStateException.class, () -> service.startPlan(1L));
    }

    @Test
    @DisplayName("startPlan 资源分配失败抛出 IllegalStateException")
    void startPlanResourceAllocationFails() {
        // Arrange
        OrchestrationPlanEntity plan = buildPlan(1L, PlanStatus.DRAFT, "[1,2]");
        when(planRepository.findById(1L)).thenReturn(Optional.of(plan));
        when(resourceManager.allocate(1L, Arrays.asList(1, 2))).thenReturn(false);

        // Act & Assert
        assertThrows(IllegalStateException.class, () -> service.startPlan(1L));
    }

    @Test
    @DisplayName("startPlan 启动后推进无依赖的 PENDING 步骤")
    void startPlanAdvancesReadySteps() {
        // Arrange
        OrchestrationPlanEntity plan = buildPlan(1L, PlanStatus.DRAFT, "[1]");
        TaskStepEntity step = buildStep("step-1", null, "[]", null);
        step.setStatus(StepStatus.PENDING);

        when(planRepository.findById(1L)).thenReturn(Optional.of(plan));
        when(planRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(resourceManager.allocate(1L, Arrays.asList(1))).thenReturn(true);
        when(stepRepository.findByPlanId(1L)).thenReturn(Collections.singletonList(step));

        // Act
        service.startPlan(1L);

        // Assert
        verify(stepExecutor).executeStep(step, 1L);
    }

    // ==================== pausePlan / resumePlan ====================

    @Test
    @DisplayName("pausePlan RUNNING→PAUSED 状态转移")
    void pausePlanTransitionsToPaused() {
        // Arrange
        OrchestrationPlanEntity plan = buildPlan(1L, PlanStatus.RUNNING, "[1]");
        when(planRepository.findById(1L)).thenReturn(Optional.of(plan));
        when(planRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Act
        service.pausePlan(1L);

        // Assert
        assertEquals(PlanStatus.PAUSED, plan.getStatus());
    }

    @Test
    @DisplayName("pausePlan 非 RUNNING 状态抛出 IllegalStateException")
    void pausePlanNotRunningThrows() {
        // Arrange
        OrchestrationPlanEntity plan = buildPlan(1L, PlanStatus.DRAFT, "[1]");
        when(planRepository.findById(1L)).thenReturn(Optional.of(plan));

        // Act & Assert
        assertThrows(IllegalStateException.class, () -> service.pausePlan(1L));
    }

    @Test
    @DisplayName("pausePlan 计划不存在抛出 IllegalArgumentException")
    void pausePlanNotFoundThrows() {
        when(planRepository.findById(999L)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.pausePlan(999L));
    }

    @Test
    @DisplayName("resumePlan PAUSED→RUNNING 状态转移")
    void resumePlanTransitionsToRunning() {
        // Arrange
        OrchestrationPlanEntity plan = buildPlan(1L, PlanStatus.PAUSED, "[1]");
        TaskStepEntity pendingStep = buildStep("step-1", null, "[]", null);
        pendingStep.setStatus(StepStatus.PENDING);
        when(planRepository.findById(1L)).thenReturn(Optional.of(plan));
        when(planRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(stepRepository.findByPlanId(1L)).thenReturn(Collections.singletonList(pendingStep));

        // Act
        service.resumePlan(1L);

        // Assert
        assertEquals(PlanStatus.RUNNING, plan.getStatus());
    }

    @Test
    @DisplayName("resumePlan 非 PAUSED 状态抛出 IllegalStateException")
    void resumePlanNotPausedThrows() {
        // Arrange
        OrchestrationPlanEntity plan = buildPlan(1L, PlanStatus.RUNNING, "[1]");
        when(planRepository.findById(1L)).thenReturn(Optional.of(plan));

        // Act & Assert
        assertThrows(IllegalStateException.class, () -> service.resumePlan(1L));
    }

    @Test
    @DisplayName("resumePlan 恢复后推进可执行步骤")
    void resumePlanAdvancesSteps() {
        // Arrange
        OrchestrationPlanEntity plan = buildPlan(1L, PlanStatus.PAUSED, "[1]");
        TaskStepEntity step = buildStep("step-1", null, "[]", null);
        step.setStatus(StepStatus.PENDING);

        when(planRepository.findById(1L)).thenReturn(Optional.of(plan));
        when(planRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(stepRepository.findByPlanId(1L)).thenReturn(Collections.singletonList(step));

        // Act
        service.resumePlan(1L);

        // Assert
        verify(stepExecutor).executeStep(step, 1L);
    }

    // ==================== abortPlan ====================

    @Test
    @DisplayName("abortPlan 正常中止计划")
    void abortPlanTransitionsToAborted() {
        // Arrange
        OrchestrationPlanEntity plan = buildPlan(1L, PlanStatus.RUNNING, "[1,2]");
        when(planRepository.findById(1L)).thenReturn(Optional.of(plan));
        when(planRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(stepRepository.findByPlanIdAndStatus(1L, StepStatus.EXECUTING))
                .thenReturn(Collections.emptyList());
        when(resourceManager.getPlanAllocations(1L)).thenReturn(Arrays.asList(1, 2));

        // Act
        service.abortPlan(1L);

        // Assert
        assertEquals(PlanStatus.ABORTED, plan.getStatus());
        assertNotNull(plan.getEndTime());
        verify(resourceManager).release(1L, Arrays.asList(1, 2));
    }

    @Test
    @DisplayName("abortPlan 中止执行中的步骤")
    void abortPlanAbortsExecutingSteps() {
        // Arrange
        OrchestrationPlanEntity plan = buildPlan(1L, PlanStatus.RUNNING, "[1]");
        TaskStepEntity executingStep = buildStep("step-1", null, "[]", null);
        executingStep.setStatus(StepStatus.EXECUTING);

        when(planRepository.findById(1L)).thenReturn(Optional.of(plan));
        when(planRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(stepRepository.findByPlanIdAndStatus(1L, StepStatus.EXECUTING))
                .thenReturn(Collections.singletonList(executingStep));
        when(resourceManager.getPlanAllocations(1L)).thenReturn(Collections.emptyList());

        // Act
        service.abortPlan(1L);

        // Assert
        verify(stepExecutor).abortStep(executingStep);
    }

    @Test
    @DisplayName("abortPlan COMPLETED 状态抛出 IllegalStateException")
    void abortPlanCompletedThrows() {
        // Arrange
        OrchestrationPlanEntity plan = buildPlan(1L, PlanStatus.COMPLETED, "[1]");
        when(planRepository.findById(1L)).thenReturn(Optional.of(plan));

        // Act & Assert
        assertThrows(IllegalStateException.class, () -> service.abortPlan(1L));
    }

    @Test
    @DisplayName("abortPlan ABORTED 状态抛出 IllegalStateException")
    void abortPlanAlreadyAbortedThrows() {
        // Arrange
        OrchestrationPlanEntity plan = buildPlan(1L, PlanStatus.ABORTED, "[1]");
        when(planRepository.findById(1L)).thenReturn(Optional.of(plan));

        // Act & Assert
        assertThrows(IllegalStateException.class, () -> service.abortPlan(1L));
    }

    @Test
    @DisplayName("abortPlan 计划不存在抛出 IllegalArgumentException")
    void abortPlanNotFoundThrows() {
        when(planRepository.findById(999L)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.abortPlan(999L));
    }

    // ==================== onStepComplete ====================

    @Test
    @DisplayName("onStepComplete 推进后续步骤")
    void onStepCompleteAdvancesSteps() {
        // Arrange
        OrchestrationPlanEntity plan = buildPlan(1L, PlanStatus.RUNNING, "[1]");
        TaskStepEntity doneStep = buildStep("step-1", null, "[]", null);
        doneStep.setStatus(StepStatus.DONE);
        TaskStepEntity nextStep = buildStep("step-2", null, "[\"step-1\"]", null);
        nextStep.setStatus(StepStatus.PENDING);

        when(planRepository.findById(1L)).thenReturn(Optional.of(plan));
        when(stepRepository.findByPlanId(1L)).thenReturn(Arrays.asList(doneStep, nextStep));

        // Act
        service.onStepComplete(new StepCompleteEvent(this, 1L, "step-1", "task-001"));

        // Assert
        verify(stepExecutor).executeStep(nextStep, 1L);
    }

    @Test
    @DisplayName("onStepComplete 计划非 RUNNING 时不推进")
    void onStepCompleteNotRunningDoesNotAdvance() {
        // Arrange
        OrchestrationPlanEntity plan = buildPlan(1L, PlanStatus.PAUSED, "[1]");

        when(planRepository.findById(1L)).thenReturn(Optional.of(plan));

        // Act
        service.onStepComplete(new StepCompleteEvent(this, 1L, "step-1", "task-001"));

        // Assert
        verify(stepExecutor, never()).executeStep(any(), anyLong());
    }

    // ==================== onStepFail ====================

    @Test
    @DisplayName("onStepFail continueOnFailure=false 标记后续步骤为 SKIPPED")
    void onStepFailMarksSkippedWhenContinueOnFailureFalse() {
        // Arrange
        OrchestrationPlanEntity plan = buildPlan(1L, PlanStatus.RUNNING, "[1]");
        TaskStepEntity failedStep = buildStep("step-1", null, "[]", null);
        failedStep.setStatus(StepStatus.FAILED);
        TaskStepEntity dependentStep = buildStep("step-2", "[\"step-1\"]", null, null);
        dependentStep.setStatus(StepStatus.PENDING);
        dependentStep.setContinueOnFailure(false);

        when(planRepository.findById(1L)).thenReturn(Optional.of(plan));
        when(stepRepository.findByPlanId(1L)).thenReturn(Arrays.asList(failedStep, dependentStep));
        when(stepRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Act
        service.onStepFail(new StepFailEvent(this, 1L, "step-1", "执行失败"));

        // Assert
        assertEquals(StepStatus.SKIPPED, dependentStep.getStatus());
        verify(stepRepository).save(dependentStep);
    }

    @Test
    @DisplayName("onStepFail continueOnFailure=true 不标记 SKIPPED，步骤仍可推进")
    void onStepFailDoesNotSkipWhenContinueOnFailureTrue() {
        // Arrange
        OrchestrationPlanEntity plan = buildPlan(1L, PlanStatus.RUNNING, "[1]");
        TaskStepEntity failedStep = buildStep("step-1", null, "[]", null);
        failedStep.setStatus(StepStatus.FAILED);
        TaskStepEntity dependentStep = buildStep("step-2", "[\"step-1\"]", null, null);
        dependentStep.setStatus(StepStatus.PENDING);
        dependentStep.setContinueOnFailure(true);

        when(planRepository.findById(1L)).thenReturn(Optional.of(plan));
        when(stepRepository.findByPlanId(1L)).thenReturn(Arrays.asList(failedStep, dependentStep));

        // Act
        service.onStepFail(new StepFailEvent(this, 1L, "step-1", "执行失败"));

        // Assert
        assertEquals(StepStatus.PENDING, dependentStep.getStatus());
        verify(stepExecutor).executeStep(dependentStep, 1L);
    }

    @Test
    @DisplayName("onStepFail 递归跳过后续步骤")
    void onStepFailRecursivelySkips() {
        // Arrange
        OrchestrationPlanEntity plan = buildPlan(1L, PlanStatus.RUNNING, "[1]");
        TaskStepEntity failedStep = buildStep("step-1", null, "[]", null);
        failedStep.setStatus(StepStatus.FAILED);
        TaskStepEntity step2 = buildStep("step-2", "[\"step-1\"]", null, null);
        step2.setStatus(StepStatus.PENDING);
        step2.setContinueOnFailure(false);
        TaskStepEntity step3 = buildStep("step-3", "[\"step-2\"]", null, null);
        step3.setStatus(StepStatus.PENDING);
        step3.setContinueOnFailure(false);

        when(planRepository.findById(1L)).thenReturn(Optional.of(plan));
        when(stepRepository.findByPlanId(1L)).thenReturn(Arrays.asList(failedStep, step2, step3));
        when(stepRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Act
        service.onStepFail(new StepFailEvent(this, 1L, "step-1", "执行失败"));

        // Assert
        assertEquals(StepStatus.SKIPPED, step2.getStatus());
        assertEquals(StepStatus.SKIPPED, step3.getStatus());
    }

    // ==================== onPausePlan / onResumePlan 事件 ====================

    @Test
    @DisplayName("onPausePlan RUNNING 计划暂停")
    void onPausePlanRunningTransitionsToPaused() {
        // Arrange
        OrchestrationPlanEntity plan = buildPlan(1L, PlanStatus.RUNNING, "[1]");
        when(planRepository.findById(1L)).thenReturn(Optional.of(plan));
        when(planRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Act
        service.onPausePlan(new PausePlanEvent(this, 1L, "应急暂停"));

        // Assert
        assertEquals(PlanStatus.PAUSED, plan.getStatus());
    }

    @Test
    @DisplayName("onPausePlan 非 RUNNING 计划不暂停")
    void onPausePlanNotRunningDoesNotPause() {
        // Arrange
        OrchestrationPlanEntity plan = buildPlan(1L, PlanStatus.DRAFT, "[1]");
        when(planRepository.findById(1L)).thenReturn(Optional.of(plan));

        // Act
        service.onPausePlan(new PausePlanEvent(this, 1L, "应急暂停"));

        // Assert
        assertEquals(PlanStatus.DRAFT, plan.getStatus());
    }

    @Test
    @DisplayName("onResumePlan PAUSED 计划恢复")
    void onResumePlanPausedTransitionsToRunning() {
        // Arrange
        OrchestrationPlanEntity plan = buildPlan(1L, PlanStatus.PAUSED, "[1]");
        TaskStepEntity pendingStep = buildStep("step-1", null, "[]", null);
        pendingStep.setStatus(StepStatus.PENDING);
        when(planRepository.findById(1L)).thenReturn(Optional.of(plan));
        when(planRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(stepRepository.findByPlanId(1L)).thenReturn(Collections.singletonList(pendingStep));

        // Act
        service.onResumePlan(new ResumePlanEvent(this, 1L));

        // Assert
        assertEquals(PlanStatus.RUNNING, plan.getStatus());
    }

    @Test
    @DisplayName("onResumePlan 非 PAUSED 计划不恢复")
    void onResumePlanNotPausedDoesNotResume() {
        // Arrange
        OrchestrationPlanEntity plan = buildPlan(1L, PlanStatus.RUNNING, "[1]");
        when(planRepository.findById(1L)).thenReturn(Optional.of(plan));

        // Act
        service.onResumePlan(new ResumePlanEvent(this, 1L));

        // Assert
        assertEquals(PlanStatus.RUNNING, plan.getStatus());
    }

    // ==================== checkPlanCompletion (通过 advanceSteps 间接触发) ====================

    @Test
    @DisplayName("所有步骤终态时计划自动标记为 COMPLETED")
    void planCompletedWhenAllStepsTerminal() {
        // Arrange
        OrchestrationPlanEntity plan = buildPlan(1L, PlanStatus.RUNNING, "[1]");
        TaskStepEntity doneStep = buildStep("step-1", null, "[]", null);
        doneStep.setStatus(StepStatus.DONE);

        when(planRepository.findById(1L)).thenReturn(Optional.of(plan));
        when(planRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(stepRepository.findByPlanId(1L)).thenReturn(Collections.singletonList(doneStep));
        when(resourceManager.getPlanAllocations(1L)).thenReturn(Arrays.asList(1));

        // Act - 通过 onStepComplete 触发 advanceSteps → checkPlanCompletion
        service.onStepComplete(new StepCompleteEvent(this, 1L, "step-1", "task-001"));

        // Assert
        assertEquals(PlanStatus.COMPLETED, plan.getStatus());
        assertNotNull(plan.getEndTime());
        verify(resourceManager).release(eq(1L), any());
    }

    @Test
    @DisplayName("部分步骤未完成时计划不标记为 COMPLETED")
    void planNotCompletedWhenStepsPending() {
        // Arrange
        OrchestrationPlanEntity plan = buildPlan(1L, PlanStatus.RUNNING, "[1]");
        TaskStepEntity doneStep = buildStep("step-1", null, "[]", null);
        doneStep.setStatus(StepStatus.DONE);
        TaskStepEntity pendingStep = buildStep("step-2", null, "[\"step-1\"]", null);
        pendingStep.setStatus(StepStatus.PENDING);

        when(planRepository.findById(1L)).thenReturn(Optional.of(plan));
        when(stepRepository.findByPlanId(1L)).thenReturn(Arrays.asList(doneStep, pendingStep));

        // Act
        service.onStepComplete(new StepCompleteEvent(this, 1L, "step-1", "task-001"));

        // Assert
        assertEquals(PlanStatus.RUNNING, plan.getStatus());
    }

    // ==================== getPlan / listPlans / getProgress ====================

    @Test
    @DisplayName("getPlan 返回计划实体")
    void getPlanReturnsEntity() {
        // Arrange
        OrchestrationPlanEntity plan = buildPlan(1L, PlanStatus.DRAFT, "[1]");
        when(planRepository.findById(1L)).thenReturn(Optional.of(plan));

        // Act
        OrchestrationPlanEntity result = service.getPlan(1L);

        // Assert
        assertNotNull(result);
        assertEquals(1L, result.getPlanId());
    }

    @Test
    @DisplayName("getPlan 不存在返回 null")
    void getPlanReturnsNullForUnknown() {
        when(planRepository.findById(999L)).thenReturn(Optional.empty());
        assertNull(service.getPlan(999L));
    }

    @Test
    @DisplayName("listPlans 返回所有计划列表")
    void listPlansReturnsAll() {
        // Arrange
        List<OrchestrationPlanEntity> plans = Arrays.asList(
                buildPlan(1L, PlanStatus.DRAFT, "[1]"),
                buildPlan(2L, PlanStatus.RUNNING, "[2]"));
        when(planRepository.findAll()).thenReturn(plans);

        // Act
        List<OrchestrationPlanEntity> result = service.listPlans();

        // Assert
        assertEquals(2, result.size());
    }

    @Test
    @DisplayName("getProgress 返回步骤列表")
    void getProgressReturnsSteps() {
        // Arrange
        TaskStepEntity step = buildStep("step-1", null, "[]", null);
        when(stepRepository.findByPlanId(1L)).thenReturn(Collections.singletonList(step));

        // Act
        List<TaskStepEntity> result = service.getProgress(1L);

        // Assert
        assertEquals(1, result.size());
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

    private TaskStepEntity buildStep(String stepId, String dependsOn, String requiredResources, String params) {
        TaskStepEntity step = new TaskStepEntity();
        step.setStepId(stepId);
        step.setModule(ModuleType.DELIVERY);
        step.setAction(StepAction.CREATE_TASK);
        step.setDependsOn(dependsOn);
        step.setRequiredResources(requiredResources);
        step.setParams(params);
        step.setStatus(StepStatus.PENDING);
        step.setContinueOnFailure(false);
        return step;
    }

    private ConditionTriggerEntity buildTrigger(String triggerId, TriggerType type,
                                                 TriggerAction action, String condition) {
        ConditionTriggerEntity trigger = new ConditionTriggerEntity();
        trigger.setTriggerId(triggerId);
        trigger.setType(type);
        trigger.setAction(action);
        trigger.setCondition(condition);
        return trigger;
    }
}
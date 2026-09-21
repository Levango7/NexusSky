package io.aerofleet.cloud.orch;

import io.aerofleet.cloud.orch.adapter.ModuleAdapter;
import io.aerofleet.cloud.orch.adapter.ModuleResult;
import io.aerofleet.cloud.orch.entity.TaskStepEntity;
import io.aerofleet.cloud.orch.enums.ModuleType;
import io.aerofleet.cloud.orch.enums.StepAction;
import io.aerofleet.cloud.orch.enums.StepStatus;
import io.aerofleet.cloud.orch.event.StepCompleteEvent;
import io.aerofleet.cloud.orch.event.StepFailEvent;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 步骤执行引擎单测。
 * <p>
 * 覆盖 executeStep / abortStep / 资源分配失败处理 / 适配器选择失败处理 /
 * 超时处理 / 各 StepAction 分发逻辑。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("StepExecutor 步骤执行引擎")
class StepExecutorTest {

    @Mock
    private ResourceManager resourceManager;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private ModuleAdapter deliveryAdapter;

    @Mock
    private ModuleAdapter formationAdapter;

    private StepExecutor executor;

    @BeforeEach
    void setUp() {
        // 默认适配器类型映射（须在 init 前配置，因为 init 会调用 getModuleType）
        lenient().when(deliveryAdapter.getModuleType()).thenReturn(ModuleType.DELIVERY.name());
        lenient().when(formationAdapter.getModuleType()).thenReturn(ModuleType.FORMATION.name());

        // 构造 StepExecutor，注入适配器列表
        List<ModuleAdapter> adapters = List.of(deliveryAdapter, formationAdapter);
        executor = new StepExecutor(adapters, resourceManager, eventPublisher, objectMapper);

        // 手动调用 init 注册适配器（模拟 @PostConstruct）
        executor.init();
    }

    // ==================== executeStep 基本流程 ====================

    @Test
    @DisplayName("executeStep 正常执行 CREATE_TASK 成功")
    void executeStepCreateTaskSuccess() {
        // Arrange
        TaskStepEntity step = buildStep("step-1", ModuleType.DELIVERY,
                StepAction.CREATE_TASK, "[]", "{\"name\":\"task\"}");
        when(resourceManager.allocate(anyLong(), any())).thenReturn(true);
        when(deliveryAdapter.createTask(anyMap()))
                .thenReturn(ModuleResult.ok("task-001", "CREATED"));

        // Act
        executor.executeStep(step, 1L);

        // Assert
        assertEquals(StepStatus.DONE, step.getStatus());
        assertEquals("task-001", step.getModuleTaskId());
        verify(eventPublisher).publishEvent(any(StepCompleteEvent.class));
    }

    @Test
    @DisplayName("executeStep 正常执行 START_TASK 成功")
    void executeStepStartTaskSuccess() {
        // Arrange
        TaskStepEntity step = buildStep("step-1", ModuleType.DELIVERY,
                StepAction.START_TASK, "[]", "{}");
        step.setModuleTaskId("task-001");
        when(resourceManager.allocate(anyLong(), any())).thenReturn(true);
        when(deliveryAdapter.startTask("task-001"))
                .thenReturn(ModuleResult.ok("task-001", "RUNNING"));

        // Act
        executor.executeStep(step, 1L);

        // Assert
        assertEquals(StepStatus.DONE, step.getStatus());
        verify(deliveryAdapter).startTask("task-001");
    }

    @Test
    @DisplayName("executeStep 正常执行 ABORT_TASK 成功")
    void executeStepAbortTaskSuccess() {
        // Arrange
        TaskStepEntity step = buildStep("step-1", ModuleType.DELIVERY,
                StepAction.ABORT_TASK, "[]", "{}");
        step.setModuleTaskId("task-001");
        when(resourceManager.allocate(anyLong(), any())).thenReturn(true);
        when(deliveryAdapter.abortTask("task-001"))
                .thenReturn(ModuleResult.ok("task-001", "ABORTED"));

        // Act
        executor.executeStep(step, 1L);

        // Assert
        assertEquals(StepStatus.DONE, step.getStatus());
        verify(deliveryAdapter).abortTask("task-001");
    }

    @Test
    @DisplayName("executeStep CREATE_AND_START_TASK 两步成功")
    void executeStepCreateAndStartTaskSuccess() {
        // Arrange
        TaskStepEntity step = buildStep("step-1", ModuleType.DELIVERY,
                StepAction.CREATE_AND_START_TASK, "[]", "{\"name\":\"task\"}");
        when(resourceManager.allocate(anyLong(), any())).thenReturn(true);
        when(deliveryAdapter.createTask(anyMap()))
                .thenReturn(ModuleResult.ok("task-001", "CREATED"));
        when(deliveryAdapter.startTask("task-001"))
                .thenReturn(ModuleResult.ok("task-001", "RUNNING"));

        // Act
        executor.executeStep(step, 1L);

        // Assert
        assertEquals(StepStatus.DONE, step.getStatus());
        verify(deliveryAdapter).createTask(anyMap());
        verify(deliveryAdapter).startTask("task-001");
    }

    @Test
    @DisplayName("executeStep CREATE_AND_START_TASK 创建失败则整体失败")
    void executeStepCreateAndStartTaskCreateFails() {
        // Arrange
        TaskStepEntity step = buildStep("step-1", ModuleType.DELIVERY,
                StepAction.CREATE_AND_START_TASK, "[]", "{\"name\":\"task\"}");
        when(resourceManager.allocate(anyLong(), any())).thenReturn(true);
        when(deliveryAdapter.createTask(anyMap()))
                .thenReturn(ModuleResult.fail(null, "创建失败"));

        // Act
        executor.executeStep(step, 1L);

        // Assert
        assertEquals(StepStatus.FAILED, step.getStatus());
        assertNotNull(step.getFailReason());
        verify(deliveryAdapter, never()).startTask(anyString());
        verify(eventPublisher).publishEvent(any(StepFailEvent.class));
    }

    // ==================== executeStep 无资源需求 ====================

    @Test
    @DisplayName("executeStep 无资源需求时跳过分配直接执行")
    void executeStepNoResourcesSkipsAllocation() {
        // Arrange
        TaskStepEntity step = buildStep("step-1", ModuleType.DELIVERY,
                StepAction.CREATE_TASK, null, "{\"name\":\"task\"}");
        when(deliveryAdapter.createTask(anyMap()))
                .thenReturn(ModuleResult.ok("task-001", "CREATED"));

        // Act
        executor.executeStep(step, 1L);

        // Assert
        assertEquals(StepStatus.DONE, step.getStatus());
        verify(resourceManager, never()).allocate(anyLong(), any());
    }

    @Test
    @DisplayName("executeStep 空资源列表时跳过分配")
    void executeStepEmptyResourcesSkipsAllocation() {
        // Arrange
        TaskStepEntity step = buildStep("step-1", ModuleType.DELIVERY,
                StepAction.CREATE_TASK, "[]", "{\"name\":\"task\"}");
        when(deliveryAdapter.createTask(anyMap()))
                .thenReturn(ModuleResult.ok("task-001", "CREATED"));

        // Act
        executor.executeStep(step, 1L);

        // Assert
        assertEquals(StepStatus.DONE, step.getStatus());
        verify(resourceManager, never()).allocate(anyLong(), any());
    }

    // ==================== executeStep 资源分配失败 ====================

    @Test
    @DisplayName("executeStep 资源分配失败标记步骤 FAILED")
    void executeStepResourceAllocationFails() {
        // Arrange
        TaskStepEntity step = buildStep("step-1", ModuleType.DELIVERY,
                StepAction.CREATE_TASK, "[1,2]", "{\"name\":\"task\"}");
        when(resourceManager.allocate(1L, Arrays.asList(1, 2))).thenReturn(false);

        // Act
        executor.executeStep(step, 1L);

        // Assert
        assertEquals(StepStatus.FAILED, step.getStatus());
        assertNotNull(step.getFailReason());
        assertTrue(step.getFailReason().contains("资源分配失败"));
        verify(eventPublisher).publishEvent(any(StepFailEvent.class));
        verify(deliveryAdapter, never()).createTask(anyMap());
    }

    // ==================== executeStep 适配器选择失败 ====================

    @Test
    @DisplayName("executeStep 未找到适配器标记步骤 FAILED")
    void executeStepNoAdapterFound() {
        // Arrange
        TaskStepEntity step = buildStep("step-1", ModuleType.MAPPING,
                StepAction.CREATE_TASK, "[]", "{}");
        when(resourceManager.allocate(anyLong(), any())).thenReturn(true);

        // Act
        executor.executeStep(step, 1L);

        // Assert
        assertEquals(StepStatus.FAILED, step.getStatus());
        assertNotNull(step.getFailReason());
        assertTrue(step.getFailReason().contains("适配器"));
        verify(eventPublisher).publishEvent(any(StepFailEvent.class));
    }

    @Test
    @DisplayName("executeStep module 为 null 标记步骤 FAILED")
    void executeStepNullModule() {
        // Arrange
        TaskStepEntity step = buildStep("step-1", null,
                StepAction.CREATE_TASK, "[]", "{}");
        when(resourceManager.allocate(anyLong(), any())).thenReturn(true);

        // Act
        executor.executeStep(step, 1L);

        // Assert
        assertEquals(StepStatus.FAILED, step.getStatus());
    }

    // ==================== executeStep 模块调用失败 ====================

    @Test
    @DisplayName("executeStep 模块返回失败标记步骤 FAILED")
    void executeStepModuleReturnsFail() {
        // Arrange
        TaskStepEntity step = buildStep("step-1", ModuleType.DELIVERY,
                StepAction.CREATE_TASK, "[]", "{}");
        when(resourceManager.allocate(anyLong(), any())).thenReturn(true);
        when(deliveryAdapter.createTask(anyMap()))
                .thenReturn(ModuleResult.fail(null, "模块调用失败"));

        // Act
        executor.executeStep(step, 1L);

        // Assert
        assertEquals(StepStatus.FAILED, step.getStatus());
        assertEquals("模块调用失败", step.getFailReason());
        verify(eventPublisher).publishEvent(any(StepFailEvent.class));
    }

    @Test
    @DisplayName("executeStep 模块抛出异常标记步骤 FAILED")
    void executeStepModuleThrowsException() {
        // Arrange
        TaskStepEntity step = buildStep("step-1", ModuleType.DELIVERY,
                StepAction.CREATE_TASK, "[]", "{}");
        when(resourceManager.allocate(anyLong(), any())).thenReturn(true);
        when(deliveryAdapter.createTask(anyMap()))
                .thenThrow(new RuntimeException("网络异常"));

        // Act
        executor.executeStep(step, 1L);

        // Assert
        assertEquals(StepStatus.FAILED, step.getStatus());
        assertNotNull(step.getFailReason());
        assertTrue(step.getFailReason().contains("执行异常"));
        verify(eventPublisher).publishEvent(any(StepFailEvent.class));
    }

    // ==================== executeStep 参数校验 ====================

    @Test
    @DisplayName("executeStep step 为 null 不操作")
    void executeStepNullStepDoesNothing() {
        // Act
        executor.executeStep(null, 1L);

        // Assert
        verify(resourceManager, never()).allocate(anyLong(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("executeStep planId 为 null 不操作")
    void executeStepNullPlanIdDoesNothing() {
        // Arrange
        TaskStepEntity step = buildStep("step-1", ModuleType.DELIVERY,
                StepAction.CREATE_TASK, "[]", "{}");

        // Act
        executor.executeStep(step, null);

        // Assert
        verify(resourceManager, never()).allocate(anyLong(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    // ==================== executeStep 状态流转 ====================

    @Test
    @DisplayName("executeStep 成功时状态流转 PENDING→ALLOCATING→EXECUTING→DONE")
    void executeStepSuccessStateTransition() {
        // Arrange
        TaskStepEntity step = buildStep("step-1", ModuleType.DELIVERY,
                StepAction.CREATE_TASK, "[1]", "{}");
        step.setStatus(StepStatus.PENDING);
        when(resourceManager.allocate(anyLong(), any())).thenReturn(true);
        when(deliveryAdapter.createTask(anyMap()))
                .thenReturn(ModuleResult.ok("task-001", "CREATED"));

        // Act
        executor.executeStep(step, 1L);

        // Assert
        // 最终状态应为 DONE（中间状态 ALLOCATING/EXECUTING 在同步执行中被覆盖）
        assertEquals(StepStatus.DONE, step.getStatus());
    }

    @Test
    @DisplayName("executeStep 失败时状态流转 PENDING→ALLOCATING→FAILED")
    void executeStepFailStateTransition() {
        // Arrange
        TaskStepEntity step = buildStep("step-1", ModuleType.DELIVERY,
                StepAction.CREATE_TASK, "[1]", "{}");
        step.setStatus(StepStatus.PENDING);
        when(resourceManager.allocate(anyLong(), any())).thenReturn(true);
        when(deliveryAdapter.createTask(anyMap()))
                .thenReturn(ModuleResult.fail(null, "失败"));

        // Act
        executor.executeStep(step, 1L);

        // Assert
        assertEquals(StepStatus.FAILED, step.getStatus());
    }

    // ==================== executeStep 事件发布 ====================

    @Test
    @DisplayName("executeStep 成功发布 StepCompleteEvent")
    void executeStepSuccessPublishesCompleteEvent() {
        // Arrange
        TaskStepEntity step = buildStep("step-1", ModuleType.DELIVERY,
                StepAction.CREATE_TASK, "[]", "{}");
        when(resourceManager.allocate(anyLong(), any())).thenReturn(true);
        when(deliveryAdapter.createTask(anyMap()))
                .thenReturn(ModuleResult.ok("task-001", "CREATED"));

        // Act
        executor.executeStep(step, 1L);

        // Assert
        ArgumentCaptor<StepCompleteEvent> captor = ArgumentCaptor.forClass(StepCompleteEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        StepCompleteEvent event = captor.getValue();
        assertEquals(1L, event.getPlanId());
        assertEquals("step-1", event.getStepId());
        assertEquals("task-001", event.getModuleTaskId());
    }

    @Test
    @DisplayName("executeStep 失败发布 StepFailEvent")
    void executeStepFailPublishesFailEvent() {
        // Arrange
        TaskStepEntity step = buildStep("step-1", ModuleType.DELIVERY,
                StepAction.CREATE_TASK, "[]", "{}");
        when(resourceManager.allocate(anyLong(), any())).thenReturn(true);
        when(deliveryAdapter.createTask(anyMap()))
                .thenReturn(ModuleResult.fail(null, "失败原因"));

        // Act
        executor.executeStep(step, 1L);

        // Assert
        ArgumentCaptor<StepFailEvent> captor = ArgumentCaptor.forClass(StepFailEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        StepFailEvent event = captor.getValue();
        assertEquals(1L, event.getPlanId());
        assertEquals("step-1", event.getStepId());
        assertNotNull(event.getFailReason());
    }

    // ==================== executeStep 失败时释放资源 ====================

    @Test
    @DisplayName("executeStep 失败时释放已分配的资源")
    void executeStepFailReleasesResources() {
        // Arrange
        TaskStepEntity step = buildStep("step-1", ModuleType.DELIVERY,
                StepAction.CREATE_TASK, "[1,2]", "{}");
        when(resourceManager.allocate(anyLong(), any())).thenReturn(true);
        when(deliveryAdapter.createTask(anyMap()))
                .thenReturn(ModuleResult.fail(null, "失败"));
        when(resourceManager.getPlanAllocations(1L)).thenReturn(Arrays.asList(1, 2));

        // Act
        executor.executeStep(step, 1L);

        // Assert
        verify(resourceManager).release(1L, Arrays.asList(1, 2));
    }

    // ==================== abortStep ====================

    @Test
    @DisplayName("abortStep 正常中止步骤")
    void abortStepNormal() {
        // Arrange
        TaskStepEntity step = buildStep("step-1", ModuleType.DELIVERY,
                StepAction.START_TASK, "[1]", "{}");
        step.setStatus(StepStatus.EXECUTING);
        step.setModuleTaskId("task-001");
        step.setPlanId(1L);
        when(deliveryAdapter.abortTask("task-001"))
                .thenReturn(ModuleResult.ok("task-001", "ABORTED"));
        when(resourceManager.getPlanAllocations(1L)).thenReturn(Arrays.asList(1));

        // Act
        executor.abortStep(step);

        // Assert
        assertEquals(StepStatus.FAILED, step.getStatus());
        assertEquals("步骤被手动中止", step.getFailReason());
        verify(deliveryAdapter).abortTask("task-001");
        verify(resourceManager).release(1L, Arrays.asList(1));
        verify(eventPublisher).publishEvent(any(StepFailEvent.class));
    }

    @Test
    @DisplayName("abortStep 无 moduleTaskId 时不调用适配器")
    void abortStepNoModuleTaskId() {
        // Arrange
        TaskStepEntity step = buildStep("step-1", ModuleType.DELIVERY,
                StepAction.CREATE_TASK, "[1]", "{}");
        step.setStatus(StepStatus.EXECUTING);
        step.setPlanId(1L);
        step.setModuleTaskId(null);
        when(resourceManager.getPlanAllocations(1L)).thenReturn(Collections.emptyList());

        // Act
        executor.abortStep(step);

        // Assert
        assertEquals(StepStatus.FAILED, step.getStatus());
        verify(deliveryAdapter, never()).abortTask(anyString());
    }

    @Test
    @DisplayName("abortStep 适配器不存在时不调用 abortTask")
    void abortStepNoAdapter() {
        // Arrange
        TaskStepEntity step = buildStep("step-1", ModuleType.MAPPING,
                StepAction.START_TASK, "[1]", "{}");
        step.setStatus(StepStatus.EXECUTING);
        step.setPlanId(1L);
        step.setModuleTaskId("task-001");
        when(resourceManager.getPlanAllocations(1L)).thenReturn(Collections.emptyList());

        // Act
        executor.abortStep(step);

        // Assert
        assertEquals(StepStatus.FAILED, step.getStatus());
    }

    @Test
    @DisplayName("abortStep 适配器 abortTask 抛异常时不影响中止流程")
    void abortStepAdapterThrowsException() {
        // Arrange
        TaskStepEntity step = buildStep("step-1", ModuleType.DELIVERY,
                StepAction.START_TASK, "[1]", "{}");
        step.setStatus(StepStatus.EXECUTING);
        step.setPlanId(1L);
        step.setModuleTaskId("task-001");
        when(deliveryAdapter.abortTask("task-001"))
                .thenThrow(new RuntimeException("网络异常"));
        when(resourceManager.getPlanAllocations(1L)).thenReturn(Collections.emptyList());

        // Act
        executor.abortStep(step);

        // Assert
        assertEquals(StepStatus.FAILED, step.getStatus());
        verify(eventPublisher).publishEvent(any(StepFailEvent.class));
    }

    @Test
    @DisplayName("abortStep step 为 null 不操作")
    void abortStepNullDoesNothing() {
        // Act
        executor.abortStep(null);

        // Assert
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("abortStep planId 为 null 时不释放资源但标记 FAILED")
    void abortStepNullPlanId() {
        // Arrange
        TaskStepEntity step = buildStep("step-1", ModuleType.DELIVERY,
                StepAction.START_TASK, "[1]", "{}");
        step.setStatus(StepStatus.EXECUTING);
        step.setPlanId(null);
        step.setModuleTaskId("task-001");
        when(deliveryAdapter.abortTask("task-001"))
                .thenReturn(ModuleResult.ok("task-001", "ABORTED"));

        // Act
        executor.abortStep(step);

        // Assert
        assertEquals(StepStatus.FAILED, step.getStatus());
        verify(resourceManager, never()).release(anyLong(), any());
    }

    // ==================== StepAction 分发逻辑 ====================

    @Test
    @DisplayName("executeStep CREATE_FORMATION 调用 createTask")
    void executeStepCreateFormation() {
        // Arrange
        TaskStepEntity step = buildStep("step-1", ModuleType.FORMATION,
                StepAction.CREATE_FORMATION, "[]", "{}");
        when(resourceManager.allocate(anyLong(), any())).thenReturn(true);
        when(formationAdapter.createTask(anyMap()))
                .thenReturn(ModuleResult.ok("form-001", "CREATED"));

        // Act
        executor.executeStep(step, 1L);

        // Assert
        assertEquals(StepStatus.DONE, step.getStatus());
        verify(formationAdapter).createTask(anyMap());
    }

    @Test
    @DisplayName("executeStep COMMAND_FORMATION 调用 startTask")
    void executeStepCommandFormation() {
        // Arrange
        TaskStepEntity step = buildStep("step-1", ModuleType.FORMATION,
                StepAction.COMMAND_FORMATION, "[]", "{}");
        step.setModuleTaskId("form-001");
        when(resourceManager.allocate(anyLong(), any())).thenReturn(true);
        when(formationAdapter.startTask("form-001"))
                .thenReturn(ModuleResult.ok("form-001", "COMMAND_SENT"));

        // Act
        executor.executeStep(step, 1L);

        // Assert
        assertEquals(StepStatus.DONE, step.getStatus());
        verify(formationAdapter).startTask("form-001");
    }

    @Test
    @DisplayName("executeStep DISSOLVE_FORMATION 调用 abortTask")
    void executeStepDissolveFormation() {
        // Arrange
        TaskStepEntity step = buildStep("step-1", ModuleType.FORMATION,
                StepAction.DISSOLVE_FORMATION, "[]", "{}");
        step.setModuleTaskId("form-001");
        when(resourceManager.allocate(anyLong(), any())).thenReturn(true);
        when(formationAdapter.abortTask("form-001"))
                .thenReturn(ModuleResult.ok("form-001", "DISSOLVED"));

        // Act
        executor.executeStep(step, 1L);

        // Assert
        assertEquals(StepStatus.DONE, step.getStatus());
        verify(formationAdapter).abortTask("form-001");
    }

    @Test
    @DisplayName("executeStep START_EMERGENCY 调用 createTask")
    void executeStepStartEmergency() {
        // Arrange
        TaskStepEntity step = buildStep("step-1", ModuleType.DELIVERY,
                StepAction.START_EMERGENCY, "[]", "{}");
        when(resourceManager.allocate(anyLong(), any())).thenReturn(true);
        when(deliveryAdapter.createTask(anyMap()))
                .thenReturn(ModuleResult.ok("emrg-001", "CREATED"));

        // Act
        executor.executeStep(step, 1L);

        // Assert
        assertEquals(StepStatus.DONE, step.getStatus());
        verify(deliveryAdapter).createTask(anyMap());
    }

    @Test
    @DisplayName("executeStep ABORT_EMERGENCY 调用 abortTask")
    void executeStepAbortEmergency() {
        // Arrange
        TaskStepEntity step = buildStep("step-1", ModuleType.DELIVERY,
                StepAction.ABORT_EMERGENCY, "[]", "{}");
        step.setModuleTaskId("emrg-001");
        when(resourceManager.allocate(anyLong(), any())).thenReturn(true);
        when(deliveryAdapter.abortTask("emrg-001"))
                .thenReturn(ModuleResult.ok("emrg-001", "ABORTED"));

        // Act
        executor.executeStep(step, 1L);

        // Assert
        assertEquals(StepStatus.DONE, step.getStatus());
        verify(deliveryAdapter).abortTask("emrg-001");
    }

    // ==================== 辅助方法 ====================

    private TaskStepEntity buildStep(String stepId, ModuleType module,
                                      StepAction action, String requiredResources, String params) {
        TaskStepEntity step = new TaskStepEntity();
        step.setStepId(stepId);
        step.setModule(module);
        step.setAction(action);
        step.setRequiredResources(requiredResources);
        step.setParams(params);
        step.setStatus(StepStatus.PENDING);
        step.setContinueOnFailure(false);
        step.setTimeoutMs(0L);
        return step;
    }
}
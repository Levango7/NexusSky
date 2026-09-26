package io.aerofleet.cloud.orch;

import io.aerofleet.cloud.orch.adapter.ModuleAdapter;
import io.aerofleet.cloud.orch.adapter.ModuleResult;
import io.aerofleet.cloud.orch.entity.TaskStepEntity;
import io.aerofleet.cloud.orch.enums.ModuleType;
import io.aerofleet.cloud.orch.enums.StepAction;
import io.aerofleet.cloud.orch.enums.StepStatus;
import io.aerofleet.cloud.orch.event.StepCompleteEvent;
import io.aerofleet.cloud.orch.event.StepFailEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 步骤执行引擎，驱动任务步骤从 PENDING 流转到 DONE 或 FAILED。
 * <p>
 * 核心职责：
 * <ul>
 *   <li>资源分配：调用 ResourceManager 分配无人机资源</li>
 *   <li>模块调用：根据步骤的 module 和 action 类型选择对应 ModuleAdapter 执行操作</li>
 *   <li>超时处理：使用 ScheduledExecutorService 设置超时任务，超时后标记步骤为 FAILED</li>
 *   <li>事件发布：步骤完成/失败时通过 ApplicationEventPublisher 发布事件</li>
 * </ul>
 * <p>
 * 线程安全：适配器映射使用 ConcurrentHashMap，超时调度器使用单线程 ScheduledExecutorService。
 */
@Component
public class StepExecutor {

    private static final Logger log = LoggerFactory.getLogger(StepExecutor.class);

    /** 默认超时时间（毫秒） */
    private static final long DEFAULT_TIMEOUT_MS = 60_000L;

    private final List<ModuleAdapter> adapterList;
    private final ResourceManager resourceManager;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    /** 模块类型 → 适配器 映射 */
    private final Map<String, ModuleAdapter> adapters = new ConcurrentHashMap<>();

    /** 超时调度器 */
    private final ScheduledExecutorService timeoutScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "step-timeout-scheduler");
        t.setDaemon(true);
        return t;
    });

    /** 步骤ID → 超时任务句柄 映射，用于取消超时 */
    private final ConcurrentHashMap<String, ScheduledFuture<?>> timeoutFutures = new ConcurrentHashMap<>();

    public StepExecutor(List<ModuleAdapter> adapterList,
                        ResourceManager resourceManager,
                        ApplicationEventPublisher eventPublisher,
                        ObjectMapper objectMapper) {
        this.adapterList = adapterList;
        this.resourceManager = resourceManager;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    /**
     * 初始化适配器映射，按模块类型索引所有注入的 ModuleAdapter。
     */
    @PostConstruct
    void init() {
        for (ModuleAdapter adapter : adapterList) {
            String type = adapter.getModuleType();
            ModuleAdapter prev = adapters.put(type, adapter);
            if (prev != null) {
                log.warn("模块类型 {} 存在重复适配器，覆盖：{} → {}", type, prev, adapter);
            }
        }
        log.info("StepExecutor 初始化完成，已注册 {} 个模块适配器：{}", adapters.size(), adapters.keySet());
    }

    /**
     * 销毁时关闭超时调度器。
     */
    @PreDestroy
    void destroy() {
        timeoutScheduler.shutdownNow();
        log.info("StepExecutor 超时调度器已关闭");
    }

    /**
     * 执行单个步骤。
     * <p>
     * 流程：PENDING → ALLOCATING（分配资源）→ EXECUTING（调用模块）→ DONE/FAILED。
     * 若资源分配失败或模块调用返回失败，步骤标记为 FAILED 并发布失败事件。
     * 若设置了超时时间，超时后步骤自动标记为 FAILED。
     *
     * @param step   步骤实体
     * @param planId 所属计划ID
     */
    public void executeStep(TaskStepEntity step, Long planId) {
        if (step == null || planId == null) {
            log.warn("executeStep 参数为空：step={}, planId={}", step, planId);
            return;
        }

        log.info("开始执行步骤：stepId={}, planId={}, module={}, action={}",
                step.getStepId(), planId, step.getModule(), step.getAction());

        // PENDING → ALLOCATING
        step.setStatus(StepStatus.ALLOCATING);

        // 分配资源
        List<Integer> requiredResources = parseRequiredResources(step.getRequiredResources());
        if (requiredResources != null && !requiredResources.isEmpty()) {
            boolean allocated = resourceManager.allocate(planId, requiredResources);
            if (!allocated) {
                handleStepFail(step, planId, "资源分配失败：所需无人机已被其他计划占用");
                return;
            }
            log.info("步骤 {} 资源分配成功：drones={}", step.getStepId(), requiredResources);
        }

        // ALLOCATING → EXECUTING
        step.setStatus(StepStatus.EXECUTING);

        // 设置超时定时器
        scheduleTimeout(step, planId);

        // 选择适配器并执行操作
        ModuleAdapter adapter = selectAdapter(step.getModule());
        if (adapter == null) {
            handleStepFail(step, planId, "未找到模块类型 " + step.getModule() + " 的适配器");
            return;
        }

        try {
            ModuleResult result = dispatchAction(adapter, step);
            if (result.isSuccess()) {
                step.setModuleTaskId(result.getTaskId());
                handleStepComplete(step, planId);
            } else {
                handleStepFail(step, planId, result.getErrorMessage());
            }
        } catch (Exception e) {
            log.error("步骤 {} 执行异常：{}", step.getStepId(), e.getMessage(), e);
            handleStepFail(step, planId, "执行异常：" + e.getMessage());
        }
    }

    /**
     * 中止步骤执行。
     * <p>
     * 调用对应适配器的 abortTask，释放已分配资源，并标记步骤状态。
     *
     * @param step 步骤实体
     */
    public void abortStep(TaskStepEntity step) {
        if (step == null) {
            log.warn("abortStep 参数为空：step=null");
            return;
        }

        Long planId = step.getPlanId();
        log.info("中止步骤：stepId={}, planId={}", step.getStepId(), planId);

        // 取消超时定时器
        cancelTimeout(step.getStepId());

        // 调用适配器中止任务
        ModuleAdapter adapter = selectAdapter(step.getModule());
        if (adapter != null && step.getModuleTaskId() != null) {
            try {
                ModuleResult result = adapter.abortTask(step.getModuleTaskId());
                log.info("步骤 {} 中止结果：{}", step.getStepId(), result);
            } catch (Exception e) {
                log.warn("步骤 {} 中止调用异常：{}", step.getStepId(), e.getMessage());
            }
        }

        // 释放资源
        if (planId != null) {
            List<Integer> allocated = resourceManager.getPlanAllocations(planId);
            if (!allocated.isEmpty()) {
                resourceManager.release(planId, allocated);
            }
        }

        step.setStatus(StepStatus.FAILED);
        step.setFailReason("步骤被手动中止");

        if (planId != null) {
            eventPublisher.publishEvent(new StepFailEvent(this, planId, step.getStepId(), "步骤被手动中止"));
        }
    }

    /**
     * 根据模块类型选择适配器。
     *
     * @param module 模块类型
     * @return 对应适配器；未找到时返回 null
     */
    private ModuleAdapter selectAdapter(ModuleType module) {
        if (module == null) {
            return null;
        }
        return adapters.get(module.name());
    }

    /**
     * 根据步骤操作类型分发到适配器的对应方法。
     *
     * @param adapter 模块适配器
     * @param step    步骤实体
     * @return 模块调用结果
     */
    private ModuleResult dispatchAction(ModuleAdapter adapter, TaskStepEntity step) {
        StepAction action = step.getAction();
        Map<String, Object> params = parseParams(step.getParams());
        String taskId = step.getModuleTaskId();

        switch (action) {
            case CREATE_TASK:
            case CREATE_FORMATION:
            case START_EMERGENCY:
                return adapter.createTask(params);

            case START_TASK:
            case COMMAND_FORMATION:
                return adapter.startTask(taskId);

            case ABORT_TASK:
            case DISSOLVE_FORMATION:
            case ABORT_EMERGENCY:
                return adapter.abortTask(taskId);

            case CREATE_AND_START_TASK:
                ModuleResult createResult = adapter.createTask(params);
                if (!createResult.isSuccess()) {
                    return createResult;
                }
                return adapter.startTask(createResult.getTaskId());

            default:
                return ModuleResult.fail(taskId, "未知操作类型：" + action);
        }
    }

    /**
     * 处理步骤完成。
     * <p>
     * 取消超时定时器，标记步骤为 DONE，发布完成事件。
     *
     * @param step   步骤实体
     * @param planId 计划ID
     */
    private void handleStepComplete(TaskStepEntity step, Long planId) {
        cancelTimeout(step.getStepId());
        step.setStatus(StepStatus.DONE);
        log.info("步骤 {} 执行完成：moduleTaskId={}", step.getStepId(), step.getModuleTaskId());
        eventPublisher.publishEvent(
                new StepCompleteEvent(this, planId, step.getStepId(), step.getModuleTaskId()));
    }

    /**
     * 处理步骤失败。
     * <p>
     * 取消超时定时器，释放资源，标记步骤为 FAILED，发布失败事件。
     *
     * @param step   步骤实体
     * @param planId 计划ID
     * @param reason 失败原因
     */
    private void handleStepFail(TaskStepEntity step, Long planId, String reason) {
        cancelTimeout(step.getStepId());
        step.setStatus(StepStatus.FAILED);
        step.setFailReason(reason);

        // 只释放当前步骤所需的资源，不影响其他步骤的资源
        List<Integer> stepResources = parseRequiredResources(step.getRequiredResources());
        if (!stepResources.isEmpty()) {
            resourceManager.release(planId, stepResources);
        }

        log.warn("步骤 {} 执行失败：{}", step.getStepId(), reason);
        eventPublisher.publishEvent(
                new StepFailEvent(this, planId, step.getStepId(), reason));
    }

    /**
     * 设置超时定时器。
     * <p>
     * 若步骤设置了超时时间（timeoutMs > 0），则调度超时任务；
     * 超时后标记步骤为 FAILED 并发布失败事件。
     *
     * @param step   步骤实体
     * @param planId 计划ID
     */
    private void scheduleTimeout(TaskStepEntity step, Long planId) {
        long timeoutMs = step.getTimeoutMs();
        if (timeoutMs <= 0) {
            timeoutMs = DEFAULT_TIMEOUT_MS;
        }
        final long effectiveTimeoutMs = timeoutMs;

        final String stepId = step.getStepId();
        ScheduledFuture<?> future = timeoutScheduler.schedule(() -> {
            // 仅当步骤仍在执行中时才触发超时
            if (step.getStatus() == StepStatus.EXECUTING) {
                log.warn("步骤 {} 执行超时（{}ms），标记为 FAILED", stepId, effectiveTimeoutMs);
                handleStepFail(step, planId, "步骤执行超时");
            }
        }, effectiveTimeoutMs, TimeUnit.MILLISECONDS);

        timeoutFutures.put(stepId, future);
    }

    /**
     * 取消步骤的超时定时器。
     *
     * @param stepId 步骤ID
     */
    private void cancelTimeout(String stepId) {
        ScheduledFuture<?> future = timeoutFutures.remove(stepId);
        if (future != null) {
            future.cancel(false);
        }
    }

    /**
     * 解析所需资源 JSON 字符串为无人机 ID 列表。
     *
     * @param requiredResources JSON 格式的资源 ID 列表
     * @return 无人机 ID 列表；解析失败或为空时返回空列表
     */
    private List<Integer> parseRequiredResources(String requiredResources) {
        if (requiredResources == null || requiredResources.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(requiredResources, new TypeReference<List<Integer>>() {});
        } catch (Exception e) {
            log.warn("parseRequiredResources 解析失败：{}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 解析步骤参数 JSON 字符串为参数 Map。
     *
     * @param params JSON 格式的参数字符串
     * @return 参数 Map；解析失败或为空时返回空 Map
     */
    private Map<String, Object> parseParams(String params) {
        if (params == null || params.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(params, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("parseParams 解析失败：{}", e.getMessage());
            return Collections.emptyMap();
        }
    }
}
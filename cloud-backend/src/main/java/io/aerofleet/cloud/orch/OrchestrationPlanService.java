package io.aerofleet.cloud.orch;

import io.aerofleet.cloud.orch.entity.ConditionTriggerEntity;
import io.aerofleet.cloud.orch.entity.OrchestrationPlanEntity;
import io.aerofleet.cloud.orch.entity.TaskStepEntity;
import io.aerofleet.cloud.orch.enums.PauseReason;
import io.aerofleet.cloud.orch.enums.PlanStatus;
import io.aerofleet.cloud.orch.enums.StepStatus;
import io.aerofleet.cloud.orch.event.PausePlanEvent;
import io.aerofleet.cloud.orch.event.ResumePlanEvent;
import io.aerofleet.cloud.orch.event.StepCompleteEvent;
import io.aerofleet.cloud.orch.event.StepFailEvent;
import io.aerofleet.cloud.orch.repository.ConditionTriggerRepository;
import io.aerofleet.cloud.orch.repository.OrchestrationPlanRepository;
import io.aerofleet.cloud.orch.repository.TaskStepRepository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 编排计划服务，负责计划生命周期管理与步骤推进。
 * <p>
 * 核心职责：
 * <ul>
 *   <li>计划 CRUD：创建、查询、列表</li>
 *   <li>生命周期管理：DRAFT → RUNNING → PAUSED → COMPLETED → ABORTED</li>
 *   <li>计划创建校验：循环依赖检测、资源池非空、步骤非空</li>
 *   <li>步骤推进：监听步骤完成/失败事件，自动推进后续步骤</li>
 *   <li>重启恢复：从数据库恢复 RUNNING 状态的计划</li>
 * </ul>
 */
@Service
public class OrchestrationPlanService {

    private static final Logger log = LoggerFactory.getLogger(OrchestrationPlanService.class);

    private final OrchestrationPlanRepository planRepository;
    private final TaskStepRepository stepRepository;
    private final ConditionTriggerRepository triggerRepository;
    private final ResourceManager resourceManager;
    private final StepExecutor stepExecutor;
    private final ObjectMapper objectMapper;

    public OrchestrationPlanService(OrchestrationPlanRepository planRepository,
                                     TaskStepRepository stepRepository,
                                     ConditionTriggerRepository triggerRepository,
                                     ResourceManager resourceManager,
                                     StepExecutor stepExecutor,
                                     ObjectMapper objectMapper) {
        this.planRepository = planRepository;
        this.stepRepository = stepRepository;
        this.triggerRepository = triggerRepository;
        this.resourceManager = resourceManager;
        this.stepExecutor = stepExecutor;
        this.objectMapper = objectMapper;
    }

    // ==================== 计划 CRUD ====================

    /**
     * 创建编排计划。
     * <p>
     * 校验资源池非空、步骤非空、无循环依赖后，创建状态为 DRAFT 的计划。
     *
     * @param name         计划名称
     * @param resourcePool 资源池（无人机 ID 列表）
     * @param steps        步骤列表
     * @param triggers     触发器列表（可为 null）
     * @return 计划 ID
     * @throws IllegalArgumentException 校验失败时抛出
     */
    @Transactional
    public Long createPlan(String name, List<Integer> resourcePool,
                           List<TaskStepEntity> steps, List<ConditionTriggerEntity> triggers) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("计划名称不能为空");
        }
        if (resourcePool == null || resourcePool.isEmpty()) {
            throw new IllegalArgumentException("资源池不能为空");
        }
        if (steps == null || steps.isEmpty()) {
            throw new IllegalArgumentException("步骤列表不能为空");
        }
        // 校验步骤 stepId 非空且唯一
        Set<String> stepIdSet = new HashSet<>();
        for (TaskStepEntity step : steps) {
            if (step.getStepId() == null || step.getStepId().isBlank()) {
                throw new IllegalArgumentException("步骤 stepId 不能为空");
            }
            if (!stepIdSet.add(step.getStepId())) {
                throw new IllegalArgumentException("步骤 stepId 重复：" + step.getStepId());
            }
        }
        if (hasCircularDependency(steps)) {
            throw new IllegalArgumentException("步骤依赖图中存在循环依赖");
        }

        OrchestrationPlanEntity plan = new OrchestrationPlanEntity();
        plan.setName(name);
        plan.setStatus(PlanStatus.DRAFT);
        plan.setResourcePool(toJson(resourcePool));
        plan.setCreateTime(System.currentTimeMillis());
        plan = planRepository.save(plan);

        // 保存步骤，设置 planId 和初始状态
        for (TaskStepEntity step : steps) {
            step.setPlanId(plan.getPlanId());
            step.setStatus(StepStatus.PENDING);
            stepRepository.save(step);
        }

        // 保存触发器
        if (triggers != null) {
            for (ConditionTriggerEntity trigger : triggers) {
                trigger.setPlanId(plan.getPlanId());
                triggerRepository.save(trigger);
            }
        }

        log.info("创建编排计划：planId={}, name={}, steps={}", plan.getPlanId(), name, steps.size());
        return plan.getPlanId();
    }

    /**
     * 查询指定计划。
     *
     * @param planId 计划 ID
     * @return 计划实体；不存在时返回 null
     */
    @Transactional(readOnly = true)
    public OrchestrationPlanEntity getPlan(Long planId) {
        return planRepository.findById(planId).orElse(null);
    }

    /**
     * 查询所有计划列表。
     *
     * @return 计划列表
     */
    @Transactional(readOnly = true)
    public List<OrchestrationPlanEntity> listPlans() {
        return planRepository.findAll();
    }

    // ==================== 生命周期管理 ====================

    /**
     * 启动计划。
     * <p>
     * 将计划状态从 DRAFT 变为 RUNNING，分配资源池，并启动第一个无依赖的步骤。
     *
     * @param planId 计划 ID
     * @throws IllegalArgumentException 计划不存在时抛出
     * @throws IllegalStateException    计划状态不为 DRAFT 或资源分配失败时抛出
     */
    @Transactional
    public void startPlan(Long planId) {
        OrchestrationPlanEntity plan = planRepository.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("计划不存在：" + planId));

        if (plan.getStatus() != PlanStatus.DRAFT) {
            throw new IllegalStateException("计划状态不为 DRAFT，无法启动：" + plan.getStatus());
        }

        // 分配资源池
        List<Integer> pool = parseResourcePool(plan.getResourcePool());
        boolean allocated = resourceManager.allocate(planId, pool);
        if (!allocated) {
            throw new IllegalStateException("资源池分配失败：所需无人机已被其他计划占用");
        }

        plan.setStatus(PlanStatus.RUNNING);
        plan.setStartTime(System.currentTimeMillis());
        planRepository.save(plan);

        log.info("启动编排计划：planId={}", planId);

        advanceSteps(planId);
    }

    /**
     * 暂停计划。
     * <p>
     * 将计划状态从 RUNNING 变为 PAUSED。暂停后不启动新步骤，正在执行的步骤不受影响。
     *
     * @param planId 计划 ID
     * @throws IllegalArgumentException 计划不存在时抛出
     * @throws IllegalStateException    计划状态不为 RUNNING 时抛出
     */
    @Transactional
    public void pausePlan(Long planId) {
        OrchestrationPlanEntity plan = planRepository.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("计划不存在：" + planId));

        if (plan.getStatus() != PlanStatus.RUNNING) {
            throw new IllegalStateException("计划状态不为 RUNNING，无法暂停：" + plan.getStatus());
        }

        plan.setStatus(PlanStatus.PAUSED);
        plan.setPauseReason(PauseReason.MANUAL);
        planRepository.save(plan);

        log.info("暂停编排计划：planId={}", planId);
    }

    /**
     * 恢复计划。
     * <p>
     * 将计划状态从 PAUSED 变为 RUNNING，并推进可执行的步骤。
     *
     * @param planId 计划 ID
     * @throws IllegalArgumentException 计划不存在时抛出
     * @throws IllegalStateException    计划状态不为 PAUSED 时抛出
     */
    @Transactional
    public void resumePlan(Long planId) {
        OrchestrationPlanEntity plan = planRepository.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("计划不存在：" + planId));

        if (plan.getStatus() != PlanStatus.PAUSED) {
            throw new IllegalStateException("计划状态不为 PAUSED，无法恢复：" + plan.getStatus());
        }

        plan.setStatus(PlanStatus.RUNNING);
        planRepository.save(plan);

        log.info("恢复编排计划：planId={}", planId);

        advanceSteps(planId);
    }

    /**
     * 中止计划。
     * <p>
     * 将计划状态变为 ABORTED，向所有执行中的步骤发送中止指令，释放资源。
     *
     * @param planId 计划 ID
     * @throws IllegalArgumentException 计划不存在时抛出
     * @throws IllegalStateException    计划已结束（COMPLETED/ABORTED）时抛出
     */
    @Transactional
    public void abortPlan(Long planId) {
        OrchestrationPlanEntity plan = planRepository.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("计划不存在：" + planId));

        if (plan.getStatus() == PlanStatus.COMPLETED || plan.getStatus() == PlanStatus.ABORTED) {
            throw new IllegalStateException("计划已结束，无法中止：" + plan.getStatus());
        }

        plan.setStatus(PlanStatus.ABORTED);
        plan.setEndTime(System.currentTimeMillis());
        planRepository.save(plan);

        // 中止所有执行中的步骤
        List<TaskStepEntity> executingSteps = stepRepository.findByPlanIdAndStatus(planId, StepStatus.EXECUTING);
        for (TaskStepEntity step : executingSteps) {
            stepExecutor.abortStep(step);
        }

        // 释放资源
        List<Integer> allocated = resourceManager.getPlanAllocations(planId);
        if (!allocated.isEmpty()) {
            resourceManager.release(planId, allocated);
        }

        log.info("中止编排计划：planId={}, 中止步骤数={}", planId, executingSteps.size());
    }

    // ==================== 步骤进度查询 ====================

    /**
     * 查询指定计划的步骤进度。
     *
     * @param planId 计划 ID
     * @return 步骤列表
     */
    @Transactional(readOnly = true)
    public List<TaskStepEntity> getProgress(Long planId) {
        return stepRepository.findByPlanId(planId);
    }

    // ==================== 事件监听 ====================

    /**
     * 监听步骤完成事件，推进后续步骤。
     *
     * @param event 步骤完成事件
     */
    @EventListener
    @Transactional
    public void onStepComplete(StepCompleteEvent event) {
        log.info("onStepComplete: planId={}, stepId={}", event.getPlanId(), event.getStepId());
        advanceSteps(event.getPlanId());
    }

    /**
     * 监听步骤失败事件，按依赖规则处理后续步骤。
     * <p>
     * 对于依赖失败步骤的后续步骤：
     * <ul>
     *   <li>continueOnFailure=false → 标记为 SKIPPED，并递归跳过其后续步骤</li>
     *   <li>continueOnFailure=true → 仍可启动</li>
     * </ul>
     *
     * @param event 步骤失败事件
     */
    @EventListener
    @Transactional
    public void onStepFail(StepFailEvent event) {
        log.info("onStepFail: planId={}, stepId={}, reason={}",
                event.getPlanId(), event.getStepId(), event.getFailReason());

        String failedStepId = event.getStepId();
        List<TaskStepEntity> allSteps = stepRepository.findByPlanId(event.getPlanId());

        // 递归标记 SKIPPED 步骤
        markSkippedSteps(failedStepId, allSteps, new HashSet<>());

        // 尝试推进可执行的步骤（continueOnFailure=true 的步骤可能可以启动）
        advanceSteps(event.getPlanId());
    }

    /**
     * 监听暂停计划事件。
     *
     * @param event 暂停计划事件
     */
    @EventListener
    @Transactional
    public void onPausePlan(PausePlanEvent event) {
        log.info("onPausePlan: planId={}, reason={}", event.getPlanId(), event.getReason());
        OrchestrationPlanEntity plan = planRepository.findById(event.getPlanId()).orElse(null);
        if (plan != null && plan.getStatus() == PlanStatus.RUNNING) {
            plan.setStatus(PlanStatus.PAUSED);
            plan.setPauseReason(PauseReason.EMERGENCY);
            planRepository.save(plan);
            log.info("计划 {} 已暂停（应急）", event.getPlanId());
        }
    }

    /**
     * 监听恢复计划事件。
     *
     * @param event 恢复计划事件
     */
    @EventListener
    @Transactional
    public void onResumePlan(ResumePlanEvent event) {
        log.info("onResumePlan: planId={}", event.getPlanId());
        OrchestrationPlanEntity plan = planRepository.findById(event.getPlanId()).orElse(null);
        if (plan != null && plan.getStatus() == PlanStatus.PAUSED) {
            plan.setStatus(PlanStatus.RUNNING);
            planRepository.save(plan);
            log.info("计划 {} 已恢复", event.getPlanId());
            advanceSteps(event.getPlanId());
        }
    }

    // ==================== 内部方法 ====================

    /**
     * 推进可执行的步骤。
     * <p>
     * 查找所有前置依赖已满足的 PENDING 步骤，并启动执行。
     * 若所有步骤都达到终态，则将计划标记为 COMPLETED。
     *
     * @param planId 计划 ID
     */
    private void advanceSteps(Long planId) {
        OrchestrationPlanEntity plan = planRepository.findById(planId).orElse(null);
        if (plan == null || plan.getStatus() != PlanStatus.RUNNING) {
            return;
        }

        List<TaskStepEntity> readySteps = getReadySteps(planId);
        for (TaskStepEntity step : readySteps) {
            // 检查步骤状态是否仍为 PENDING，避免递归调用导致重复启动
            if (step.getStatus() == StepStatus.PENDING) {
                log.info("推进步骤：planId={}, stepId={}", planId, step.getStepId());
                // 在事务提交后执行步骤，避免长事务阻塞；非事务上下文中同步执行
                executeStepAfterCommit(step, planId);
            }
        }

        checkPlanCompletion(planId);
    }

    /**
     * 在事务提交后执行步骤，若不在事务上下文中则同步执行。
     *
     * @param step   步骤实体
     * @param planId 计划 ID
     */
    private void executeStepAfterCommit(TaskStepEntity step, Long planId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    stepExecutor.executeStep(step, planId);
                }
            });
        } else {
            stepExecutor.executeStep(step, planId);
        }
    }

    /**
     * 检测步骤依赖图中是否存在循环依赖。
     * <p>
     * 使用 DFS 遍历依赖图，检测是否存在环。
     *
     * @param steps 步骤列表
     * @return true 表示存在循环依赖
     */
    private boolean hasCircularDependency(List<TaskStepEntity> steps) {
        Map<String, List<String>> dependencyGraph = new HashMap<>();
        Set<String> allStepIds = new HashSet<>();

        for (TaskStepEntity step : steps) {
            allStepIds.add(step.getStepId());
            List<String> deps = parseDependsOn(step.getDependsOn());
            dependencyGraph.put(step.getStepId(), deps);
        }

        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();

        for (String stepId : allStepIds) {
            if (dfsDetectCycle(stepId, dependencyGraph, visiting, visited)) {
                return true;
            }
        }
        return false;
    }

    /**
     * DFS 递归检测环。
     *
     * @param stepId          当前步骤 ID
     * @param dependencyGraph 依赖图
     * @param visiting        正在访问中的节点集合（用于检测环）
     * @param visited         已完成访问的节点集合
     * @return true 表示检测到环
     */
    private boolean dfsDetectCycle(String stepId, Map<String, List<String>> dependencyGraph,
                                    Set<String> visiting, Set<String> visited) {
        if (visiting.contains(stepId)) {
            return true;
        }
        if (visited.contains(stepId)) {
            return false;
        }

        visiting.add(stepId);
        List<String> deps = dependencyGraph.getOrDefault(stepId, Collections.emptyList());
        for (String dep : deps) {
            if (dfsDetectCycle(dep, dependencyGraph, visiting, visited)) {
                return true;
            }
        }
        visiting.remove(stepId);
        visited.add(stepId);
        return false;
    }

    /**
     * 获取可启动的步骤列表。
     * <p>
     * 条件：步骤状态为 PENDING，且所有前置依赖步骤的状态为 DONE
     * 或（FAILED 且当前步骤 continueOnFailure=true）。
     *
     * @param planId 计划 ID
     * @return 可启动的步骤列表
     */
    private List<TaskStepEntity> getReadySteps(Long planId) {
        List<TaskStepEntity> allSteps = stepRepository.findByPlanId(planId);
        Map<String, StepStatus> statusMap = new HashMap<>();
        for (TaskStepEntity step : allSteps) {
            statusMap.put(step.getStepId(), step.getStatus());
        }

        List<TaskStepEntity> readySteps = new ArrayList<>();
        for (TaskStepEntity step : allSteps) {
            if (step.getStatus() != StepStatus.PENDING) {
                continue;
            }

            List<String> deps = parseDependsOn(step.getDependsOn());
            boolean allDepsSatisfied = true;
            for (String depId : deps) {
                StepStatus depStatus = statusMap.get(depId);
                if (depStatus == StepStatus.DONE) {
                    // 前置步骤完成，满足
                } else if (depStatus == StepStatus.FAILED
                        && Boolean.TRUE.equals(step.getContinueOnFailure())) {
                    // 前置步骤失败但允许继续，满足
                } else {
                    allDepsSatisfied = false;
                    break;
                }
            }

            if (allDepsSatisfied) {
                readySteps.add(step);
            }
        }
        return readySteps;
    }

    /**
     * 递归标记因前置步骤失败而应跳过的步骤。
     * <p>
     * 对于依赖失败步骤的后续步骤，若 continueOnFailure=false，则标记为 SKIPPED。
     * SKIPPED 步骤的后续步骤也应递归标记为 SKIPPED。
     *
     * @param failedStepId 失败步骤 ID
     * @param allSteps     计划中的所有步骤
     * @param processed    已处理的步骤 ID 集合（避免重复处理）
     */
    private void markSkippedSteps(String failedStepId, List<TaskStepEntity> allSteps,
                                   Set<String> processed) {
        if (processed.contains(failedStepId)) {
            return;
        }
        processed.add(failedStepId);

        for (TaskStepEntity step : allSteps) {
            if (step.getStatus() != StepStatus.PENDING) {
                continue;
            }

            List<String> deps = parseDependsOn(step.getDependsOn());
            if (!deps.contains(failedStepId)) {
                continue;
            }

            if (!Boolean.TRUE.equals(step.getContinueOnFailure())) {
                step.setStatus(StepStatus.SKIPPED);
                stepRepository.save(step);
                log.info("步骤 {} 因前置步骤 {} 失败而跳过", step.getStepId(), failedStepId);
                markSkippedSteps(step.getStepId(), allSteps, processed);
            }
        }
    }

    /**
     * 检查计划是否已完成。
     * <p>
     * 若所有步骤都达到终态（DONE/FAILED/SKIPPED），则将计划状态变为 COMPLETED 并释放资源。
     *
     * @param planId 计划 ID
     */
    private void checkPlanCompletion(Long planId) {
        List<TaskStepEntity> allSteps = stepRepository.findByPlanId(planId);
        boolean allTerminal = true;
        for (TaskStepEntity step : allSteps) {
            StepStatus status = step.getStatus();
            if (status != StepStatus.DONE
                    && status != StepStatus.FAILED
                    && status != StepStatus.SKIPPED) {
                allTerminal = false;
                break;
            }
        }

        if (allTerminal) {
            OrchestrationPlanEntity plan = planRepository.findById(planId).orElse(null);
            if (plan != null && plan.getStatus() == PlanStatus.RUNNING) {
                plan.setStatus(PlanStatus.COMPLETED);
                plan.setEndTime(System.currentTimeMillis());
                planRepository.save(plan);

                List<Integer> allocated = resourceManager.getPlanAllocations(planId);
                if (!allocated.isEmpty()) {
                    resourceManager.release(planId, allocated);
                }

                log.info("编排计划已完成：planId={}", planId);
            }
        }
    }

    // ==================== 重启恢复 ====================

    /**
     * 重启后恢复 RUNNING 状态的计划。
     * <p>
     * 查询所有 RUNNING 状态的计划，重新推进可执行的步骤。
     */
    @PostConstruct
    public void recoverRunningPlans() {
        List<OrchestrationPlanEntity> runningPlans = planRepository.findByStatus(PlanStatus.RUNNING);
        if (runningPlans.isEmpty()) {
            log.info("重启恢复：没有 RUNNING 状态的计划需要恢复");
            return;
        }

        log.info("重启恢复：发现 {} 个 RUNNING 状态的计划", runningPlans.size());
        for (OrchestrationPlanEntity plan : runningPlans) {
            log.info("恢复计划：planId={}", plan.getPlanId());

            // 重建资源所有权映射，确保 ResourceManager 知道该计划占用的无人机
            List<Integer> resources = parseResourcePool(plan.getResourcePool());
            if (!resources.isEmpty()) {
                resourceManager.allocate(plan.getPlanId(), resources);
                log.info("恢复计划 {} 资源所有权：drones={}", plan.getPlanId(), resources);
            }

            advanceSteps(plan.getPlanId());
        }
    }

    // ==================== JSON 解析工具 ====================

    /**
     * 将对象序列化为 JSON 字符串。
     *
     * @param obj 对象
     * @return JSON 字符串；序列化失败时返回 "[]"
     */
    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.warn("toJson 序列化失败：{}", e.getMessage());
            return "[]";
        }
    }

    /**
     * 解析资源池 JSON 字符串为无人机 ID 列表。
     *
     * @param resourcePool JSON 格式的资源池字符串
     * @return 无人机 ID 列表；解析失败时返回空列表
     */
    private List<Integer> parseResourcePool(String resourcePool) {
        if (resourcePool == null || resourcePool.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(resourcePool, new TypeReference<List<Integer>>() {});
        } catch (Exception e) {
            log.warn("parseResourcePool 解析失败：{}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 解析步骤依赖 JSON 字符串为步骤 ID 列表。
     *
     * @param dependsOn JSON 格式的依赖步骤 ID 列表
     * @return 步骤 ID 列表；解析失败时返回空列表
     */
    private List<String> parseDependsOn(String dependsOn) {
        if (dependsOn == null || dependsOn.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(dependsOn, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("parseDependsOn 解析失败：{}", e.getMessage());
            return Collections.emptyList();
        }
    }
}
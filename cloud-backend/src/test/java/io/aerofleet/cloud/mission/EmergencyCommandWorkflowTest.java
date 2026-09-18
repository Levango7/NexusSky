package io.aerofleet.cloud.mission;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link EmergencyCommandWorkflow} 工作流引擎单测。
 * <p>
 * 覆盖创建/研判/部署/执行/评估/关闭/非法转移/并发安全/历史记录/阶段筛选。
 */
@DisplayName("EmergencyCommandWorkflow 工作流引擎")
class EmergencyCommandWorkflowTest {

    private EmergencyCommandWorkflow workflow;

    @BeforeEach
    void setUp() {
        workflow = new EmergencyCommandWorkflow();
    }

    private EmergencyCommand newCommand() {
        return new EmergencyCommand(
                null, EmergencyCommand.IncidentType.FIRE, EmergencyCommand.Severity.CRITICAL,
                new EmergencyCommand.Location(39.9, 116.3, 50.0),
                "火灾", "张三", "13800000000", System.currentTimeMillis());
    }

    private EmergencyCommand newCommandWithId(String id) {
        return new EmergencyCommand(
                id, EmergencyCommand.IncidentType.FIRE, EmergencyCommand.Severity.CRITICAL,
                new EmergencyCommand.Location(39.9, 116.3, 50.0),
                "火灾", "张三", "13800000000", System.currentTimeMillis());
    }

    // =====================================================================
    // 创建
    // =====================================================================

    @Test
    @DisplayName("createCommand 创建命令并自动生成 ID")
    void createCommandGeneratesId() {
        EmergencyCommand cmd = newCommand();
        EmergencyCommand created = workflow.createCommand(cmd);
        assertThat(created.getId()).isNotNull().isNotEmpty();
        assertThat(created.getCurrentPhase()).isEqualTo(EmergencyCommandPhase.RECEIVED);
        assertThat(workflow.getCommand(created.getId())).isSameAs(created);
    }

    @Test
    @DisplayName("createCommand 保留指定 ID")
    void createCommandKeepsId() {
        EmergencyCommand cmd = newCommandWithId("CMD-FIXED");
        EmergencyCommand created = workflow.createCommand(cmd);
        assertThat(created.getId()).isEqualTo("CMD-FIXED");
    }

    @Test
    @DisplayName("createCommand 多次创建返回不同 ID")
    void createCommandUniqueIds() {
        String id1 = workflow.createCommand(newCommand()).getId();
        String id2 = workflow.createCommand(newCommand()).getId();
        assertThat(id1).isNotEqualTo(id2);
    }

    // =====================================================================
    // 研判
    // =====================================================================

    @Test
    @DisplayName("assess 研判成功 RECEIVED→ASSESSED")
    void assessSuccess() {
        EmergencyCommand cmd = workflow.createCommand(newCommand());
        EmergencyCommand assessed = workflow.assess(cmd.getId(), "需要 6 架无人机", "operator1");
        assertThat(assessed).isNotNull();
        assertThat(assessed.getCurrentPhase()).isEqualTo(EmergencyCommandPhase.ASSESSED);
        assertThat(assessed.getAssessmentResult()).isEqualTo("需要 6 架无人机");
    }

    @Test
    @DisplayName("assess 命令不存在返回 null")
    void assessNotFound() {
        assertThat(workflow.assess("nonexistent", "result", "op")).isNull();
    }

    @Test
    @DisplayName("assess 在非 RECEIVED 阶段返回 null（非法转移）")
    void assessIllegalPhase() {
        EmergencyCommand cmd = workflow.createCommand(newCommand());
        workflow.assess(cmd.getId(), "result", "op");
        // 已在 ASSESSED，再次 assess 非法
        assertThat(workflow.assess(cmd.getId(), "again", "op")).isNull();
    }

    // =====================================================================
    // 部署
    // =====================================================================

    @Test
    @DisplayName("deploy 部署成功 ASSESSED→DEPLOYED")
    void deploySuccess() {
        EmergencyCommand cmd = workflow.createCommand(newCommand());
        workflow.assess(cmd.getId(), "result", "op");
        EmergencyCommand.DeploymentPlan plan = new EmergencyCommand.DeploymentPlan(
                "plan1", "strategy1", 120, "mesh");
        EmergencyCommand deployed = workflow.deploy(cmd.getId(), plan, "operator1");
        assertThat(deployed).isNotNull();
        assertThat(deployed.getCurrentPhase()).isEqualTo(EmergencyCommandPhase.DEPLOYED);
        assertThat(deployed.getDeploymentPlan().getPlanName()).isEqualTo("plan1");
    }

    @Test
    @DisplayName("deploy 未研判直接部署返回 null（非法转移）")
    void deployWithoutAssess() {
        EmergencyCommand cmd = workflow.createCommand(newCommand());
        EmergencyCommand.DeploymentPlan plan = new EmergencyCommand.DeploymentPlan(
                "plan1", "strategy1", 120, "mesh");
        assertThat(workflow.deploy(cmd.getId(), plan, "op")).isNull();
    }

    // =====================================================================
    // 执行
    // =====================================================================

    @Test
    @DisplayName("startExecution 执行成功 DEPLOYED→EXECUTING")
    void startExecutionSuccess() {
        EmergencyCommand cmd = workflow.createCommand(newCommand());
        workflow.assess(cmd.getId(), "r", "op");
        workflow.deploy(cmd.getId(), new EmergencyCommand.DeploymentPlan("p", "s", 60, "m"), "op");
        EmergencyCommand executing = workflow.startExecution(cmd.getId(), "operator1");
        assertThat(executing).isNotNull();
        assertThat(executing.getCurrentPhase()).isEqualTo(EmergencyCommandPhase.EXECUTING);
        assertThat(executing.getExecutionLog()).isNotEmpty();
    }

    @Test
    @DisplayName("startExecution 未部署直接执行返回 null")
    void startExecutionWithoutDeploy() {
        EmergencyCommand cmd = workflow.createCommand(newCommand());
        workflow.assess(cmd.getId(), "r", "op");
        assertThat(workflow.startExecution(cmd.getId(), "op")).isNull();
    }

    // =====================================================================
    // 评估
    // =====================================================================

    @Test
    @DisplayName("evaluate 评估成功 EXECUTING→EVALUATED")
    void evaluateSuccess() {
        EmergencyCommand cmd = workflow.createCommand(newCommand());
        workflow.assess(cmd.getId(), "r", "op");
        workflow.deploy(cmd.getId(), new EmergencyCommand.DeploymentPlan("p", "s", 60, "m"), "op");
        workflow.startExecution(cmd.getId(), "op");
        EmergencyCommand evaluated = workflow.evaluate(cmd.getId(), "覆盖率 95%", "operator1");
        assertThat(evaluated).isNotNull();
        assertThat(evaluated.getCurrentPhase()).isEqualTo(EmergencyCommandPhase.EVALUATED);
        assertThat(evaluated.getEvaluationResult()).isEqualTo("覆盖率 95%");
    }

    // =====================================================================
    // 关闭
    // =====================================================================

    @Test
    @DisplayName("close 关闭成功 EVALUATED→CLOSED")
    void closeSuccess() {
        EmergencyCommand cmd = workflow.createCommand(newCommand());
        workflow.assess(cmd.getId(), "r", "op");
        workflow.deploy(cmd.getId(), new EmergencyCommand.DeploymentPlan("p", "s", 60, "m"), "op");
        workflow.startExecution(cmd.getId(), "op");
        workflow.evaluate(cmd.getId(), "good", "op");
        EmergencyCommand closed = workflow.close(cmd.getId(), "任务完成", "operator1");
        assertThat(closed).isNotNull();
        assertThat(closed.getCurrentPhase()).isEqualTo(EmergencyCommandPhase.CLOSED);
        assertThat(closed.getSummary()).isEqualTo("任务完成");
        assertThat(closed.getClosedTimeMs()).isPositive();
        assertThat(closed.isClosed()).isTrue();
    }

    @Test
    @DisplayName("close 未评估直接关闭返回 null")
    void closeWithoutEvaluate() {
        EmergencyCommand cmd = workflow.createCommand(newCommand());
        workflow.assess(cmd.getId(), "r", "op");
        workflow.deploy(cmd.getId(), new EmergencyCommand.DeploymentPlan("p", "s", 60, "m"), "op");
        workflow.startExecution(cmd.getId(), "op");
        assertThat(workflow.close(cmd.getId(), "summary", "op")).isNull();
    }

    // =====================================================================
    // 完整流程 + 历史记录
    // =====================================================================

    @Test
    @DisplayName("完整流程 6 阶段转移历史记录正确")
    void fullWorkflowHistory() {
        EmergencyCommand cmd = workflow.createCommand(newCommand());
        String id = cmd.getId();
        workflow.assess(id, "r1", "op1");
        workflow.deploy(id, new EmergencyCommand.DeploymentPlan("p", "s", 60, "m"), "op2");
        workflow.startExecution(id, "op3");
        workflow.evaluate(id, "r2", "op4");
        workflow.close(id, "done", "op5");

        EmergencyCommand finalCmd = workflow.getCommand(id);
        List<EmergencyCommand.PhaseTransition> history = finalCmd.getPhaseHistory();
        assertThat(history).hasSize(5);
        assertThat(history.get(0).getFromPhase()).isEqualTo(EmergencyCommandPhase.RECEIVED);
        assertThat(history.get(0).getToPhase()).isEqualTo(EmergencyCommandPhase.ASSESSED);
        assertThat(history.get(4).getFromPhase()).isEqualTo(EmergencyCommandPhase.EVALUATED);
        assertThat(history.get(4).getToPhase()).isEqualTo(EmergencyCommandPhase.CLOSED);
        assertThat(history.get(0).getOperatorName()).isEqualTo("op1");
        assertThat(history.get(4).getOperatorName()).isEqualTo("op5");
    }

    // =====================================================================
    // 阶段筛选
    // =====================================================================

    @Test
    @DisplayName("listCommands 按阶段筛选")
    void listCommandsByPhase() {
        EmergencyCommand cmd1 = workflow.createCommand(newCommand());
        EmergencyCommand cmd2 = workflow.createCommand(newCommand());
        workflow.assess(cmd1.getId(), "r", "op"); // cmd1 → ASSESSED

        List<EmergencyCommand> received = workflow.listCommands(EmergencyCommandPhase.RECEIVED);
        List<EmergencyCommand> assessed = workflow.listCommands(EmergencyCommandPhase.ASSESSED);
        assertThat(received).hasSize(1);
        assertThat(assessed).hasSize(1);
        assertThat(received.get(0).getId()).isEqualTo(cmd2.getId());
        assertThat(assessed.get(0).getId()).isEqualTo(cmd1.getId());
    }

    @Test
    @DisplayName("listCommands null 过滤返回全部")
    void listCommandsAll() {
        workflow.createCommand(newCommand());
        workflow.createCommand(newCommand());
        assertThat(workflow.listCommands(null)).hasSize(2);
    }

    // =====================================================================
    // 通用转移
    // =====================================================================

    @Test
    @DisplayName("transitionPhase 通用转移合法")
    void transitionPhaseLegal() {
        EmergencyCommand cmd = workflow.createCommand(newCommand());
        EmergencyCommand result = workflow.transitionPhase(
                cmd.getId(), EmergencyCommandPhase.ASSESSED, "op", "manual assess");
        assertThat(result).isNotNull();
        assertThat(result.getCurrentPhase()).isEqualTo(EmergencyCommandPhase.ASSESSED);
    }

    @Test
    @DisplayName("transitionPhase 非法转移返回 null")
    void transitionPhaseIllegal() {
        EmergencyCommand cmd = workflow.createCommand(newCommand());
        // RECEIVED → DEPLOYED 非法
        assertThat(workflow.transitionPhase(
                cmd.getId(), EmergencyCommandPhase.DEPLOYED, "op", "jump")).isNull();
    }

    // =====================================================================
    // 并发安全
    // =====================================================================

    @Test
    @DisplayName("并发 assess 同一命令只有一个成功")
    void concurrentAssess() throws InterruptedException {
        EmergencyCommand cmd = workflow.createCommand(newCommand());
        int threadCount = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                try {
                    EmergencyCommand result = workflow.assess(cmd.getId(), "concurrent", "op");
                    if (result != null) {
                        successCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        pool.shutdown();

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(workflow.getCommand(cmd.getId()).getCurrentPhase())
                .isEqualTo(EmergencyCommandPhase.ASSESSED);
    }

    @Test
    @DisplayName("并发创建多个命令线程安全")
    void concurrentCreate() throws InterruptedException {
        int threadCount = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        List<String> ids = java.util.Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                try {
                    EmergencyCommand created = workflow.createCommand(newCommand());
                    ids.add(created.getId());
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        pool.shutdown();

        assertThat(ids).hasSize(threadCount);
        assertThat(new java.util.HashSet<>(ids)).hasSize(threadCount); // ID 唯一
        assertThat(workflow.size()).isEqualTo(threadCount);
    }
}
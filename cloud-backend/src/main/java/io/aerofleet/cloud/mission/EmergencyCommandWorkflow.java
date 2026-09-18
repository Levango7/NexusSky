package io.aerofleet.cloud.mission;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 应急指挥工作流引擎（接报→研判→部署→执行→评估→总结）。
 * <p>
 * 维护应急指挥命令的生命周期状态机，提供阶段转移校验、历史记录、
 * 并发安全的命令存储。基于怀业信息科技应急救援业务标准流程设计。
 * <p>
 * 线程安全：使用 {@link ConcurrentHashMap} 存储命令，每个命令的阶段转移
 * 通过 synchronized(cmd) 串行化，避免并发下阶段跳跃或重复转移。
 * <p>
 * 阶段转移图：
 * <pre>
 * RECEIVED → ASSESSED → DEPLOYED → EXECUTING → EVALUATED → CLOSED
 *   接报       研判       部署        执行        评估        总结
 * </pre>
 */
@Service
public class EmergencyCommandWorkflow {

    private static final Logger log = LoggerFactory.getLogger(EmergencyCommandWorkflow.class);

    /** 命令存储：cmdId → EmergencyCommand。 */
    private final ConcurrentHashMap<String, EmergencyCommand> commands = new ConcurrentHashMap<>();
    /** 命令 ID 序号生成器（用于生成有序 ID）。 */
    private final AtomicLong idSequence = new AtomicLong(0);

    /**
     * 创建新指挥命令（初始阶段 RECEIVED）。
     * <p>
     * 若 cmd.id 为 null 或空，自动生成 UUID 前缀的有序 ID。
     *
     * @param cmd 命令模板（id 可为 null）
     * @return 创建后的命令（含最终 ID）
     */
    public EmergencyCommand createCommand(EmergencyCommand cmd) {
        EmergencyCommand finalCmd = cmd;
        if (cmd.getId() == null || cmd.getId().isEmpty()) {
            String generatedId = generateId();
            // 重建带 ID 的命令
            finalCmd = new EmergencyCommand(generatedId, cmd.getIncidentType(), cmd.getSeverity(),
                    cmd.getLocation(), cmd.getDescription(), cmd.getReporterName(),
                    cmd.getReporterContact(), cmd.getReceiveTimeMs());
            // 复制已分配无人机
            finalCmd.assignDrones(cmd.getAssignedDrones());
        }
        commands.put(finalCmd.getId(), finalCmd);
        log.info("emergency command created: id={} type={} severity={} location={}",
                finalCmd.getId(), finalCmd.getIncidentType(), finalCmd.getSeverity(),
                finalCmd.getLocation());
        return finalCmd;
    }

    /**
     * 研判（RECEIVED→ASSESSED）。
     *
     * @param cmdId           命令 ID
     * @param assessmentResult 研判结果
     * @param operator        操作人
     * @return 更新后的命令，若命令不存在或阶段非法返回 null
     */
    public EmergencyCommand assess(String cmdId, String assessmentResult, String operator) {
        EmergencyCommand cmd = commands.get(cmdId);
        if (cmd == null) {
            return null;
        }
        synchronized (cmd) {
            if (!cmd.getCurrentPhase().canTransitionTo(EmergencyCommandPhase.ASSESSED)) {
                log.warn("assess rejected: cmdId={} currentPhase={}", cmdId, cmd.getCurrentPhase());
                return null;
            }
            recordTransition(cmd, EmergencyCommandPhase.ASSESSED, operator, "assess");
            cmd.setAssessmentResult(assessmentResult);
            log.info("emergency command assessed: id={} operator={}", cmdId, operator);
            return cmd;
        }
    }

    /**
     * 部署（ASSESSED→DEPLOYED）。
     *
     * @param cmdId   命令 ID
     * @param plan    部署计划
     * @param operator 操作人
     * @return 更新后的命令，若命令不存在或阶段非法返回 null
     */
    public EmergencyCommand deploy(String cmdId, EmergencyCommand.DeploymentPlan plan,
                                   String operator) {
        EmergencyCommand cmd = commands.get(cmdId);
        if (cmd == null) {
            return null;
        }
        synchronized (cmd) {
            if (!cmd.getCurrentPhase().canTransitionTo(EmergencyCommandPhase.DEPLOYED)) {
                log.warn("deploy rejected: cmdId={} currentPhase={}", cmdId, cmd.getCurrentPhase());
                return null;
            }
            recordTransition(cmd, EmergencyCommandPhase.DEPLOYED, operator, "deploy");
            cmd.setDeploymentPlan(plan);
            log.info("emergency command deployed: id={} plan={} operator={}", cmdId, plan, operator);
            return cmd;
        }
    }

    /**
     * 开始执行（DEPLOYED→EXECUTING）。
     *
     * @param cmdId    命令 ID
     * @param operator 操作人
     * @return 更新后的命令，若命令不存在或阶段非法返回 null
     */
    public EmergencyCommand startExecution(String cmdId, String operator) {
        EmergencyCommand cmd = commands.get(cmdId);
        if (cmd == null) {
            return null;
        }
        synchronized (cmd) {
            if (!cmd.getCurrentPhase().canTransitionTo(EmergencyCommandPhase.EXECUTING)) {
                log.warn("startExecution rejected: cmdId={} currentPhase={}", cmdId, cmd.getCurrentPhase());
                return null;
            }
            recordTransition(cmd, EmergencyCommandPhase.EXECUTING, operator, "start execution");
            cmd.appendExecutionLog("execution started by " + operator);
            log.info("emergency command executing: id={} operator={}", cmdId, operator);
            return cmd;
        }
    }

    /**
     * 评估（EXECUTING→EVALUATED）。
     *
     * @param cmdId           命令 ID
     * @param evaluationResult 评估结果
     * @param operator        操作人
     * @return 更新后的命令，若命令不存在或阶段非法返回 null
     */
    public EmergencyCommand evaluate(String cmdId, String evaluationResult, String operator) {
        EmergencyCommand cmd = commands.get(cmdId);
        if (cmd == null) {
            return null;
        }
        synchronized (cmd) {
            if (!cmd.getCurrentPhase().canTransitionTo(EmergencyCommandPhase.EVALUATED)) {
                log.warn("evaluate rejected: cmdId={} currentPhase={}", cmdId, cmd.getCurrentPhase());
                return null;
            }
            recordTransition(cmd, EmergencyCommandPhase.EVALUATED, operator, "evaluate");
            cmd.setEvaluationResult(evaluationResult);
            log.info("emergency command evaluated: id={} operator={}", cmdId, operator);
            return cmd;
        }
    }

    /**
     * 总结关闭（EVALUATED→CLOSED）。
     *
     * @param cmdId    命令 ID
     * @param summary  总结内容
     * @param operator 操作人
     * @return 更新后的命令，若命令不存在或阶段非法返回 null
     */
    public EmergencyCommand close(String cmdId, String summary, String operator) {
        EmergencyCommand cmd = commands.get(cmdId);
        if (cmd == null) {
            return null;
        }
        synchronized (cmd) {
            if (!cmd.getCurrentPhase().canTransitionTo(EmergencyCommandPhase.CLOSED)) {
                log.warn("close rejected: cmdId={} currentPhase={}", cmdId, cmd.getCurrentPhase());
                return null;
            }
            recordTransition(cmd, EmergencyCommandPhase.CLOSED, operator, "close");
            cmd.setSummary(summary);
            cmd.setClosedTimeMs(System.currentTimeMillis());
            log.info("emergency command closed: id={} operator={}", cmdId, operator);
            return cmd;
        }
    }

    /**
     * 获取命令。
     *
     * @param cmdId 命令 ID
     * @return 命令对象，不存在返回 null
     */
    public EmergencyCommand getCommand(String cmdId) {
        return commands.get(cmdId);
    }

    /**
     * 按阶段筛选命令列表。
     *
     * @param phaseFilter 阶段过滤，null 表示全部
     * @return 命令列表（防御性拷贝）
     */
    public List<EmergencyCommand> listCommands(EmergencyCommandPhase phaseFilter) {
        if (phaseFilter == null) {
            return new ArrayList<>(commands.values());
        }
        return commands.values().stream()
                .filter(cmd -> cmd.getCurrentPhase() == phaseFilter)
                .collect(Collectors.toList());
    }

    /**
     * 通用阶段转移（验证合法性）。
     * <p>
     * 供一键响应等场景使用，校验当前阶段能否转移到目标阶段。
     *
     * @param cmdId    命令 ID
     * @param target   目标阶段
     * @param operator 操作人
     * @param notes    转移备注
     * @return 更新后的命令，若命令不存在或转移非法返回 null
     */
    public EmergencyCommand transitionPhase(String cmdId, EmergencyCommandPhase target,
                                            String operator, String notes) {
        EmergencyCommand cmd = commands.get(cmdId);
        if (cmd == null) {
            return null;
        }
        synchronized (cmd) {
            if (!cmd.getCurrentPhase().canTransitionTo(target)) {
                log.warn("transition rejected: cmdId={} from={} to={}", cmdId, cmd.getCurrentPhase(), target);
                return null;
            }
            recordTransition(cmd, target, operator, notes);
            log.info("emergency command transition: id={} -> {} operator={}", cmdId, target, operator);
            return cmd;
        }
    }

    /**
     * 返回所有命令总数。
     *
     * @return 命令数量
     */
    public int size() {
        return commands.size();
    }

    // =====================================================================
    // 内部辅助
    // =====================================================================

    /**
     * 记录阶段转移（调用方需已持有 cmd 锁）。
     */
    private void recordTransition(EmergencyCommand cmd, EmergencyCommandPhase target,
                                  String operator, String notes) {
        EmergencyCommandPhase from = cmd.getCurrentPhase();
        long now = System.currentTimeMillis();
        EmergencyCommand.PhaseTransition transition =
                new EmergencyCommand.PhaseTransition(from, target, now, operator, notes);
        cmd.addPhaseTransition(transition);
        cmd.setCurrentPhase(target);
    }

    /**
     * 生成有序命令 ID：EC + 时间戳后 6 位 + 递增序号。
     */
    private String generateId() {
        long seq = idSequence.incrementAndGet();
        return "EC-" + System.currentTimeMillis() + "-" + seq;
    }
}
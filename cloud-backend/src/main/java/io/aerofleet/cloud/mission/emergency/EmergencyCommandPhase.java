package io.aerofleet.cloud.mission.emergency;

import java.util.EnumSet;
import java.util.Set;

/**
 * 应急指挥工作流阶段枚举（接报→研判→部署→执行→评估→总结）。
 * <p>
 * 标准应急救援流程的 6 个阶段，每个阶段定义合法的后继阶段，
 * 通过 {@link #canTransitionTo(EmergencyCommandPhase)} 校验状态转移合法性。
 * <p>
 * 阶段顺序：
 * <pre>
 * RECEIVED（接报）→ ASSESSED（研判）→ DEPLOYED（部署）
 *   → EXECUTING（执行）→ EVALUATED（评估）→ CLOSED（总结）
 * </pre>
 * <p>
 * 设计参考应急救援行业标准流程：
 * <ol>
 *   <li>接报：接收报警/上报，记录事件基本信息</li>
 *   <li>研判：分析事件级别、影响范围、所需资源</li>
 *   <li>部署：分配无人机、制定飞行计划、部署通信中继</li>
 *   <li>执行：无人机起飞执行搜救/测绘/中继任务</li>
 *   <li>评估：任务完成度评估、覆盖效果评估、资源使用评估</li>
 *   <li>总结：归档总结、经验沉淀、报告生成</li>
 * </ol>
 */
public enum EmergencyCommandPhase {

    /** 接报：接收报警/上报，记录事件基本信息。初始阶段。 */
    RECEIVED,
    /** 研判：分析事件级别、影响范围、所需资源。 */
    ASSESSED,
    /** 部署：分配无人机、制定飞行计划、部署通信中继。 */
    DEPLOYED,
    /** 执行：无人机起飞执行搜救/测绘/中继任务。 */
    EXECUTING,
    /** 评估：任务完成度评估、覆盖效果评估、资源使用评估。 */
    EVALUATED,
    /** 总结：归档总结、经验沉淀、报告生成。终态阶段。 */
    CLOSED;

    /**
     * 校验从当前阶段转移到目标阶段是否合法。
     * <p>
     * 合法转移规则（严格顺序推进，不允许跳跃或回退）：
     * <pre>
     * RECEIVED  → ASSESSED
     * ASSESSED  → DEPLOYED
     * DEPLOYED  → EXECUTING
     * EXECUTING → EVALUATED
     * EVALUATED → CLOSED
     * </pre>
     * 同阶段转移（自环）视为非法，避免重复操作污染历史。
     *
     * @param next 目标阶段
     * @return true 若转移合法
     */
    public boolean canTransitionTo(EmergencyCommandPhase next) {
        if (next == null || next == this) {
            return false;
        }
        return validNextPhases().contains(next);
    }

    /**
     * 返回当前阶段的合法后继阶段集合。
     * <p>
     * 终态 {@link #CLOSED} 无后继，返回空集合。
     *
     * @return 合法后继阶段集合（不可变）
     */
    public Set<EmergencyCommandPhase> validNextPhases() {
        return switch (this) {
            case RECEIVED -> EnumSet.of(ASSESSED);
            case ASSESSED -> EnumSet.of(DEPLOYED);
            case DEPLOYED -> EnumSet.of(EXECUTING);
            case EXECUTING -> EnumSet.of(EVALUATED);
            case EVALUATED -> EnumSet.of(CLOSED);
            case CLOSED -> EnumSet.noneOf(EmergencyCommandPhase.class);
        };
    }

    /**
     * 判断当前阶段是否为终态（不可再转移）。
     *
     * @return true 若为终态
     */
    public boolean isTerminal() {
        return this == CLOSED;
    }

    /**
     * 判断当前阶段是否为初始阶段。
     *
     * @return true 若为初始阶段
     */
    public boolean isInitial() {
        return this == RECEIVED;
    }

    /**
     * 返回阶段的序号（用于排序/展示）。
     *
     * @return 序号 0~5
     */
    public int order() {
        return ordinal();
    }

    /**
     * 返回阶段的中文显示名称。
     *
     * @return 中文名称
     */
    public String displayName() {
        return switch (this) {
            case RECEIVED -> "接报";
            case ASSESSED -> "研判";
            case DEPLOYED -> "部署";
            case EXECUTING -> "执行";
            case EVALUATED -> "评估";
            case CLOSED -> "总结";
        };
    }
}
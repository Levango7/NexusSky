package io.aerofleet.cloud.show;

/**
 * 表演任务状态。
 * <p>
 * 编队表演任务的生命周期状态流转：
 * <pre>
 *   CREATED → DEPLOYING → PERFORMING → COMPLETED
 *                         ↘ → ABORTED
 * </pre>
 */
public enum ShowStatus {
    /** 已创建，尚未启动。 */
    CREATED,
    /** 正在部署（无人机起飞并移动到初始队形位置）。 */
    DEPLOYING,
    /** 正在表演（执行动作序列）。 */
    PERFORMING,
    /** 表演完成（所有动作执行完毕，无人机降落）。 */
    COMPLETED,
    /** 表演中止（异常或手动中止）。 */
    ABORTED
}
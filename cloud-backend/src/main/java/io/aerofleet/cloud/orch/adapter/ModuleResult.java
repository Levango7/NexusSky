package io.aerofleet.cloud.orch.adapter;

/**
 * 模块调用结果封装，统一各模块的返回格式。
 * <p>
 * 包含操作是否成功、任务 ID、当前状态和错误信息，
 * 供编排层判断任务流转结果。
 */
public class ModuleResult {

    /** 操作是否成功。 */
    private final boolean success;

    /** 任务 ID。 */
    private final String taskId;

    /** 任务当前状态。 */
    private final String status;

    /** 错误信息（操作失败时填充）。 */
    private final String errorMessage;

    public ModuleResult(boolean success, String taskId, String status, String errorMessage) {
        this.success = success;
        this.taskId = taskId;
        this.status = status;
        this.errorMessage = errorMessage;
    }

    /** 构建成功结果。 */
    public static ModuleResult ok(String taskId, String status) {
        return new ModuleResult(true, taskId, status, null);
    }

    /** 构建失败结果。 */
    public static ModuleResult fail(String taskId, String errorMessage) {
        return new ModuleResult(false, taskId, null, errorMessage);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getTaskId() {
        return taskId;
    }

    public String getStatus() {
        return status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    @Override
    public String toString() {
        return "ModuleResult{success=" + success
                + ", taskId='" + taskId + '\''
                + ", status='" + status + '\''
                + ", errorMessage='" + errorMessage + '\'' + '}';
    }
}
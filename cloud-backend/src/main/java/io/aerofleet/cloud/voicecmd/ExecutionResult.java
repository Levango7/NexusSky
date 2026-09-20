package io.aerofleet.cloud.voicecmd;

/**
 * 指令执行结果。
 * <p>
 * 描述语音指令经过 {@link VoiceCommandExecutor} 处理后的状态，
 * 包括执行状态、消息说明和实际执行的动作描述。
 */
public class ExecutionResult {

    /** 执行状态枚举 */
    public enum Status {
        EXECUTED,           // 已执行
        PENDING_CONFIRMATION, // 待确认（高优先级指令需二次确认）
        REJECTED,           // 已拒绝（无人机不存在等）
        FAILED              // 执行失败
    }

    private String commandId;
    private Status status;
    private String message;
    private String executedAction;

    public ExecutionResult() {
    }

    public ExecutionResult(String commandId, Status status, String message, String executedAction) {
        this.commandId = commandId;
        this.status = status;
        this.message = message;
        this.executedAction = executedAction;
    }

    // --- getters / setters ---

    public String getCommandId() {
        return commandId;
    }

    public void setCommandId(String commandId) {
        this.commandId = commandId;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getExecutedAction() {
        return executedAction;
    }

    public void setExecutedAction(String executedAction) {
        this.executedAction = executedAction;
    }

    @Override
    public String toString() {
        return "ExecutionResult{commandId='" + commandId + "'"
                + ", status=" + status
                + ", message='" + message + "'"
                + ", executedAction='" + executedAction + "'}";
    }
}
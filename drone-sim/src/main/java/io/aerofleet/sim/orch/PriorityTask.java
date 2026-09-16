package io.aerofleet.sim.orch;

/**
 * 优先级任务（M9 应急任务编排，T4 优先级调度）。
 * <p>
 * 描述一个可调度的应急任务：所属计划、优先级、描述、提交时间、上下文，
 * 以及运行时状态（状态、起止时间、分配无人机、被抢占的任务 id）。
 * <p>
 * 优先级可运行时调整（{@link #adjustPriority}），其余标识字段 final 不可变。
 * 状态、起止时间、分配无人机、被抢占任务 id 用 volatile 保证可见性。
 * <p>
 * 状态机：
 * <pre>
 * PENDING → RUNNING → COMPLETED
 *                ↘ PREEMPTED（被高优先级抢占，可重新调度）
 * PENDING/RUNNING/PREEMPTED → CANCELLED
 * </pre>
 */
public class PriorityTask {

    /** 任务状态。 */
    public enum Status {
        /** 待调度（在优先级队列中）。 */
        PENDING,
        /** 运行中（已分配无人机）。 */
        RUNNING,
        /** 被抢占（被高优先级任务抢占，可重新入队调度）。 */
        PREEMPTED,
        /** 已完成。 */
        COMPLETED,
        /** 已取消。 */
        CANCELLED
    }

    /** 任务 id（全局唯一）。 */
    private final long taskId;
    /** 所属计划 id。 */
    private final long planId;
    /** 优先级（可调整）。 */
    private PriorityLevel priority;
    /** 任务描述。 */
    private final String description;
    /** 提交时间（ms）。 */
    private final long submitTimeMs;
    /** 任务状态（volatile 保证可见性）。 */
    private volatile Status status;
    /** 开始时间（ms，0 表示未开始）。 */
    private volatile long startTimeMs;
    /** 结束时间（ms，0 表示未结束）。 */
    private volatile long endTimeMs;
    /** 分配的无人机 id（0 表示未分配）。 */
    private volatile int assignedDroneId;
    /** 被本任务抢占的前任务 id（0 表示无）。 */
    private volatile long preemptedTaskId;
    /** 任务上下文（JSON 或自由文本，供执行方解析）。 */
    private final String context;

    /**
     * 构造优先级任务，初始状态 PENDING。
     *
     * @param taskId        任务 id
     * @param planId        所属计划 id
     * @param priority      优先级
     * @param description   任务描述
     * @param submitTimeMs  提交时间（ms）
     * @param context       任务上下文
     */
    public PriorityTask(long taskId, long planId, PriorityLevel priority,
                        String description, long submitTimeMs, String context) {
        this.taskId = taskId;
        this.planId = planId;
        this.priority = priority;
        this.description = description;
        this.submitTimeMs = submitTimeMs;
        this.context = context;
        this.status = Status.PENDING;
        this.startTimeMs = 0L;
        this.endTimeMs = 0L;
        this.assignedDroneId = 0;
        this.preemptedTaskId = 0L;
    }

    /**
     * 启动任务：分配无人机，状态转为 RUNNING，记录开始时间。
     *
     * @param droneId 分配的无人机 id
     */
    public void start(int droneId) {
        this.assignedDroneId = droneId;
        this.startTimeMs = System.currentTimeMillis();
        this.status = Status.RUNNING;
    }

    /** 完成任务：状态转为 COMPLETED，记录结束时间。 */
    public void complete() {
        this.endTimeMs = System.currentTimeMillis();
        this.status = Status.COMPLETED;
    }

    /** 取消任务：状态转为 CANCELLED，记录结束时间。 */
    public void cancel() {
        this.endTimeMs = System.currentTimeMillis();
        this.status = Status.CANCELLED;
    }

    /**
     * 抢占任务：状态转为 PREEMPTED，记录被抢占的前任务 id。
     *
     * @param preemptedTaskId 被本任务抢占的前任务 id（0 表示无）
     */
    public void preempt(long preemptedTaskId) {
        this.preemptedTaskId = preemptedTaskId;
        this.status = Status.PREEMPTED;
    }

    /**
     * 调整优先级（运行时动态调整）。
     *
     * @param newPriority 新优先级
     */
    public void adjustPriority(PriorityLevel newPriority) {
        this.priority = newPriority;
    }

    /** @return 任务 id。 */
    public long getTaskId() {
        return taskId;
    }

    /** @return 所属计划 id。 */
    public long getPlanId() {
        return planId;
    }

    /** @return 优先级。 */
    public PriorityLevel getPriority() {
        return priority;
    }

    /** @return 任务描述。 */
    public String getDescription() {
        return description;
    }

    /** @return 提交时间（ms）。 */
    public long getSubmitTimeMs() {
        return submitTimeMs;
    }

    /** @return 任务状态。 */
    public Status getStatus() {
        return status;
    }

    /** @return 开始时间（ms）。 */
    public long getStartTimeMs() {
        return startTimeMs;
    }

    /** @return 结束时间（ms）。 */
    public long getEndTimeMs() {
        return endTimeMs;
    }

    /** @return 分配的无人机 id。 */
    public int getAssignedDroneId() {
        return assignedDroneId;
    }

    /** @return 被本任务抢占的前任务 id。 */
    public long getPreemptedTaskId() {
        return preemptedTaskId;
    }

    /** @return 任务上下文。 */
    public String getContext() {
        return context;
    }

    @Override
    public String toString() {
        return "PriorityTask{taskId=" + taskId + ", planId=" + planId
                + ", priority=" + priority + ", status=" + status
                + ", drone=" + assignedDroneId + ", desc=\"" + description + "\"}";
    }
}
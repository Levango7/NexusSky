package io.aerofleet.sim.orch;

/**
 * 编排阶段状态（M9 应急任务编排，T2 编排引擎核心）。
 * <p>
 * 记录单个阶段的执行状态、起止时间、失败原因、重试计数。
 * 状态机转换：
 * <pre>
 * PENDING → RUNNING → COMPLETED
 *                  ↘ FAILED → (retry) RUNNING
 *                  ↘ ABORTED
 * RUNNING/FAILED/PENDING → ABORTED
 * </pre>
 * 重试上限 3 次，超过抛 IllegalStateException。
 * 本类非线程安全，由 OrchestrationPlan/OrchestrationEngine 保证并发访问安全。
 */
public class OrchestrationState {

    /** 阶段执行状态。 */
    public enum Status {
        /** 待执行。 */
        PENDING,
        /** 执行中。 */
        RUNNING,
        /** 已完成。 */
        COMPLETED,
        /** 已失败。 */
        FAILED,
        /** 已中止。 */
        ABORTED
    }

    /** 最大重试次数。 */
    public static final int MAX_RETRY = 3;

    private final OrchestrationPhase phase;
    private Status status;
    private long startTimeMs;
    private long endTimeMs;
    private String failReason;
    private int retryCount;

    /**
     * 构造器，初始化为 PENDING。
     *
     * @param phase 所属阶段
     */
    public OrchestrationState(OrchestrationPhase phase) {
        if (phase == null) {
            throw new IllegalArgumentException("phase must not be null");
        }
        this.phase = phase;
        this.status = Status.PENDING;
        this.startTimeMs = 0L;
        this.endTimeMs = 0L;
        this.failReason = null;
        this.retryCount = 0;
    }

    /** 启动阶段：PENDING/FAILED → RUNNING，记录 startTimeMs。 */
    public void start() {
        if (status == Status.ABORTED) {
            throw new IllegalStateException("Cannot start aborted phase: " + phase.label);
        }
        this.status = Status.RUNNING;
        this.startTimeMs = System.currentTimeMillis();
        this.endTimeMs = 0L;
        this.failReason = null;
    }

    /** 完成阶段：RUNNING → COMPLETED，记录 endTimeMs。 */
    public void complete() {
        if (status != Status.RUNNING) {
            throw new IllegalStateException("Cannot complete phase not in RUNNING: " + phase.label + " (current=" + status + ")");
        }
        this.status = Status.COMPLETED;
        this.endTimeMs = System.currentTimeMillis();
    }

    /** 失败阶段：RUNNING → FAILED，记录原因与 endTimeMs。 */
    public void fail(String reason) {
        if (status != Status.RUNNING) {
            throw new IllegalStateException("Cannot fail phase not in RUNNING: " + phase.label + " (current=" + status + ")");
        }
        this.status = Status.FAILED;
        this.endTimeMs = System.currentTimeMillis();
        this.failReason = reason;
    }

    /** 中止阶段：任意状态 → ABORTED。 */
    public void abort() {
        this.status = Status.ABORTED;
        if (endTimeMs == 0L) {
            this.endTimeMs = System.currentTimeMillis();
        }
    }

    /**
     * 重试阶段：FAILED → PENDING，retryCount++，超过 3 次抛 IllegalStateException。
     */
    public void retry() {
        if (status != Status.FAILED) {
            throw new IllegalStateException("Cannot retry phase not in FAILED: " + phase.label + " (current=" + status + ")");
        }
        if (retryCount >= MAX_RETRY) {
            throw new IllegalStateException("Max retry (" + MAX_RETRY + ") exceeded for phase: " + phase.label);
        }
        this.retryCount++;
        this.status = Status.PENDING;
        this.startTimeMs = 0L;
        this.endTimeMs = 0L;
        this.failReason = null;
    }

    /** 是否可重试：retryCount < 3。 */
    public boolean canRetry() {
        return retryCount < MAX_RETRY;
    }

    public OrchestrationPhase getPhase() {
        return phase;
    }

    public Status getStatus() {
        return status;
    }

    public long getStartTimeMs() {
        return startTimeMs;
    }

    public long getEndTimeMs() {
        return endTimeMs;
    }

    public String getFailReason() {
        return failReason;
    }

    public int getRetryCount() {
        return retryCount;
    }

    @Override
    public String toString() {
        return "OrchestrationState{phase=" + phase.label + ", status=" + status
                + ", retry=" + retryCount + "/" + MAX_RETRY
                + ", start=" + startTimeMs + ", end=" + endTimeMs
                + (failReason != null ? ", reason=" + failReason : "")
                + "}";
    }
}
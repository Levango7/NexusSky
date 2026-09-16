package io.aerofleet.sim.celltower;

/**
 * 漫游切换记录值类（M6 移动基站载荷抽象，FR-HO-04 / §6.4）。
 * <p>
 * 记录一次漫游切换的完整信息：终端 ID、源机、目标机、原因、起止时间戳、状态。不可变。
 */
public final class HandoverRecord {

    public final int terminalId;
    public final int fromSysid;
    public final int toSysid;
    public final HandoverReason reason;
    public final long startedAtMs;
    public final long completedAtMs;
    public final HandoverStatus status;

    public HandoverRecord(int terminalId, int fromSysid, int toSysid, HandoverReason reason,
                          long startedAtMs, long completedAtMs, HandoverStatus status) {
        this.terminalId = terminalId;
        this.fromSysid = fromSysid;
        this.toSysid = toSysid;
        this.reason = reason;
        this.startedAtMs = startedAtMs;
        this.completedAtMs = completedAtMs;
        this.status = status;
    }

    /** 发起漫游（status=INITIATED, completedAtMs=0）。 */
    public static HandoverRecord initiated(int terminalId, int fromSysid, int toSysid,
                                           HandoverReason reason, long nowMs) {
        return new HandoverRecord(terminalId, fromSysid, toSysid, reason, nowMs, 0, HandoverStatus.INITIATED);
    }

    /** 完成漫游（status=COMPLETED）。 */
    public HandoverRecord completed(long nowMs) {
        return new HandoverRecord(terminalId, fromSysid, toSysid, reason, startedAtMs, nowMs, HandoverStatus.COMPLETED);
    }

    /** 回滚（status=ROLLED_BACK）。 */
    public HandoverRecord rolledBack(long nowMs) {
        return new HandoverRecord(terminalId, fromSysid, toSysid, reason, startedAtMs, nowMs, HandoverStatus.ROLLED_BACK);
    }

    /** 超时（status=TIMEOUT）。 */
    public HandoverRecord timeout(long nowMs) {
        return new HandoverRecord(terminalId, fromSysid, toSysid, reason, startedAtMs, nowMs, HandoverStatus.TIMEOUT);
    }

    /** 切换耗时（ms）。 */
    public long durationMs() {
        return completedAtMs - startedAtMs;
    }

    @Override
    public String toString() {
        return "HandoverRecord{terminal=" + terminalId
                + ", from=" + fromSysid + ", to=" + toSysid
                + ", reason=" + reason + ", status=" + status
                + ", dur=" + durationMs() + "ms}";
    }
}
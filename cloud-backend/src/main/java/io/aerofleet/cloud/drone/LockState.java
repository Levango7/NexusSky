package io.aerofleet.cloud.drone;

import java.util.Objects;

/**
 * 无人机锁定状态。
 * <p>
 * 记录某架无人机的锁定/解锁信息：是否锁定、锁定时间、解锁时间、
 * 锁定原因、操作人/系统、锁定时执行的动作。
 * <p>
 * 不可变值对象，所有字段在构造时确定；状态转移由
 * {@link DroneLockService} 创建新实例完成。
 */
public final class LockState {

    private final int sysid;
    private final boolean locked;
    /** 锁定时间（epoch ms），0 表示未锁定。 */
    private final long lockTimeMs;
    /** 解锁时间（epoch ms），0 表示从未解锁。 */
    private final long unlockTimeMs;
    /** 锁定原因。 */
    private final String lockReason;
    /** 操作人/系统标识。 */
    private final String lockedBy;
    /** 锁定时执行的动作。 */
    private final Action action;

    /**
     * 锁定动作。
     * <ul>
     *   <li>{@link #DISARM} — 解除武装（地面锁定）</li>
     *   <li>{@link #FORCE_LAND} — 强制降落（空中锁定）</li>
     *   <li>{@link #RETURN_TO_LAUNCH} — 返航锁定（飞回起飞点后锁定）</li>
     * </ul>
     */
    public enum Action {
        DISARM,
        FORCE_LAND,
        RETURN_TO_LAUNCH
    }

    public LockState(int sysid, boolean locked, long lockTimeMs, long unlockTimeMs,
                     String lockReason, String lockedBy, Action action) {
        this.sysid = sysid;
        this.locked = locked;
        this.lockTimeMs = lockTimeMs;
        this.unlockTimeMs = unlockTimeMs;
        this.lockReason = lockReason;
        this.lockedBy = lockedBy;
        this.action = action;
    }

    /** 创建未锁定状态。 */
    public static LockState unlocked(int sysid) {
        return new LockState(sysid, false, 0L, 0L, null, null, null);
    }

    /** 创建锁定状态。 */
    public static LockState locked(int sysid, String reason, String lockedBy, Action action) {
        return new LockState(sysid, true, System.currentTimeMillis(), 0L,
                reason, lockedBy, action);
    }

    public int getSysid() {
        return sysid;
    }

    public boolean isLocked() {
        return locked;
    }

    public long getLockTimeMs() {
        return lockTimeMs;
    }

    public long getUnlockTimeMs() {
        return unlockTimeMs;
    }

    public String getLockReason() {
        return lockReason;
    }

    public String getLockedBy() {
        return lockedBy;
    }

    public Action getAction() {
        return action;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LockState)) return false;
        LockState that = (LockState) o;
        return sysid == that.sysid
                && locked == that.locked
                && lockTimeMs == that.lockTimeMs
                && unlockTimeMs == that.unlockTimeMs
                && Objects.equals(lockReason, that.lockReason)
                && Objects.equals(lockedBy, that.lockedBy)
                && action == that.action;
    }

    @Override
    public int hashCode() {
        return Objects.hash(sysid, locked, lockTimeMs, unlockTimeMs, lockReason, lockedBy, action);
    }

    @Override
    public String toString() {
        return "LockState{sysid=" + sysid
                + ", locked=" + locked
                + ", lockTime=" + lockTimeMs
                + ", unlockTime=" + unlockTimeMs
                + ", reason=" + lockReason
                + ", by=" + lockedBy
                + ", action=" + action + "}";
    }
}
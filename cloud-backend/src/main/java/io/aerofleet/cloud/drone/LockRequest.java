package io.aerofleet.cloud.drone;

/**
 * 锁定/解锁请求体 DTO。
 * <p>
 * 用于 {@code POST /api/drone-lock/{sysid}/lock} 与
 * {@code POST /api/drone-lock/{sysid}/unlock} 端点。
 * <ul>
 *   <li>锁定请求需提供 {@code reason}、{@code lockedBy}、{@code action}。</li>
 *   <li>解锁请求需提供 {@code unlockedBy}。</li>
 * </ul>
 */
public class LockRequest {

    /** 锁定原因。 */
    private String reason;
    /** 锁定操作人/系统标识。 */
    private String lockedBy;
    /** 解锁操作人/系统标识。 */
    private String unlockedBy;
    /** 锁定动作：DISARM / FORCE_LAND / RETURN_TO_LAUNCH。 */
    private String action;

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getLockedBy() {
        return lockedBy;
    }

    public void setLockedBy(String lockedBy) {
        this.lockedBy = lockedBy;
    }

    public String getUnlockedBy() {
        return unlockedBy;
    }

    public void setUnlockedBy(String unlockedBy) {
        this.unlockedBy = unlockedBy;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }
}
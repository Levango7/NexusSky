package io.aerofleet.cloud.geofence;

/**
 * 拦截判决结果：不可变值对象，由 {@link InterceptChain#check} 返回。
 * <p>
 * 包含判决结果（ALLOW/DENY）、拒绝原因（仅 DENY 时有意义）和相关围栏区域信息。
 * <p>
 * 故障安全原则（FR-10）：当拦截校验过程中发生异常时，应返回 DENY 而非放行。
 *
 * @see InterceptChain
 */
public final class InterceptVerdict {

    /** 判决结果：允许执行 / 拒绝执行。 */
    public enum Verdict { ALLOW, DENY }

    /**
     * 拒绝原因枚举。
     * <ul>
     *   <li>{@link #IN_KEEP_OUT_ZONE}：无人机当前位于 KEEP_OUT 禁飞区内。</li>
     *   <li>{@link #OUTSIDE_KEEP_IN_ZONE}：无人机当前位于 KEEP_IN 允许区外。</li>
     *   <li>{@link #INTERCEPT_ERROR}：拦截校验过程中发生异常（故障安全）。</li>
     *   <li>{@link #POSITION_UNKNOWN}：无人机位置未知（lat/lon 为 NaN）。</li>
     *   <li>{@link #NO_RESTRICTION_DATA}：无可用围栏/限飞区数据。</li>
     * </ul>
     */
    public enum DenyReason {
        IN_KEEP_OUT_ZONE,
        OUTSIDE_KEEP_IN_ZONE,
        INTERCEPT_ERROR,
        POSITION_UNKNOWN,
        NO_RESTRICTION_DATA
    }

    /**
     * 围栏区域信息 record，用于在拒绝判决中携带相关围栏的元数据。
     *
     * @param zoneId    围栏 ID
     * @param name      围栏名称
     * @param fenceType 围栏类型（KEEP_IN / KEEP_OUT）
     */
    public record ZoneInfo(int zoneId, String name, FenceType fenceType) {
        /** 是否为 KEEP_OUT（禁飞区）围栏。 */
        public boolean isKeepOut() {
            return fenceType == FenceType.KEEP_OUT;
        }
    }

    private final Verdict verdict;
    private final DenyReason denyReason;
    private final ZoneInfo zoneInfo;

    private InterceptVerdict(Verdict verdict, DenyReason denyReason, ZoneInfo zoneInfo) {
        this.verdict = verdict;
        this.denyReason = denyReason;
        this.zoneInfo = zoneInfo;
    }

    /**
     * 创建允许判决。
     *
     * @return ALLOW 判决，无拒绝原因和围栏信息
     */
    public static InterceptVerdict allow() {
        return new InterceptVerdict(Verdict.ALLOW, null, null);
    }

    /**
     * 创建拒绝判决，携带拒绝原因和相关围栏信息。
     *
     * @param reason   拒绝原因
     * @param zoneInfo 相关围栏区域信息（可为 null，如 POSITION_UNKNOWN / NO_RESTRICTION_DATA）
     * @return DENY 判决
     */
    public static InterceptVerdict deny(DenyReason reason, ZoneInfo zoneInfo) {
        return new InterceptVerdict(Verdict.DENY, reason, zoneInfo);
    }

    /** 判决结果。 */
    public Verdict getVerdict() { return verdict; }

    /** 拒绝原因（仅 DENY 时有意义，ALLOW 时为 null）。 */
    public DenyReason getDenyReason() { return denyReason; }

    /** 相关围栏区域信息（可为 null）。 */
    public ZoneInfo getZoneInfo() { return zoneInfo; }

    /** 是否为拒绝判决。 */
    public boolean isDenied() { return verdict == Verdict.DENY; }

    /** 是否为允许判决。 */
    public boolean isAllowed() { return verdict == Verdict.ALLOW; }

    @Override
    public String toString() {
        if (verdict == Verdict.ALLOW) {
            return "InterceptVerdict{ALLOW}";
        }
        return "InterceptVerdict{DENY, reason=" + denyReason
                + (zoneInfo != null ? ", zone=" + zoneInfo : "") + "}";
    }
}
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
     *   <li>{@link #IN_KEEP_OUT_ZONE}：无人机当前位于 KEEP_OUT 禁飞区内（**用户自建围栏**）。</li>
     *   <li>{@link #IN_RESTRICTION_ZONE}：无人机当前位于**限飞区数据源**给出的禁飞区内
     *       （民航/厂商口径，与用户自建围栏不是一回事）。</li>
     *   <li>{@link #OUTSIDE_KEEP_IN_ZONE}：无人机当前位于 KEEP_IN 允许区外。</li>
     *   <li>{@link #INTERCEPT_ERROR}：拦截校验过程中发生异常（故障安全）。</li>
     *   <li>{@link #POSITION_UNKNOWN}：无人机位置未知（lat/lon 为 NaN）。</li>
     *   <li>{@link #NO_RESTRICTION_DATA}：限飞区数据不可用（数据源没配/加载失败/缓存过期）
     *       且启用了 fail-closed 策略。</li>
     * </ul>
     */
    public enum DenyReason {
        IN_KEEP_OUT_ZONE,
        IN_RESTRICTION_ZONE,
        OUTSIDE_KEEP_IN_ZONE,
        INTERCEPT_ERROR,
        POSITION_UNKNOWN,
        NO_RESTRICTION_DATA
    }

    /**
     * 限飞区信息（来自限飞区数据源，不是用户自建围栏）。
     *
     * @param zoneId 限飞区 ID（数据源给的字符串 ID）
     * @param name   限飞区名称
     * @param source 数据来源标识（LOCAL_FILE / HTTP / MOCK …）
     */
    public record RestrictionInfo(String zoneId, String name, String source) {
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
    /** 命中的限飞区（数据源口径）；未命中为 null。 */
    private final RestrictionInfo restrictionInfo;
    /** 限飞区数据不可用时为 true（此时判决通常是 ALLOW，见 allowWithoutRestrictionData）。 */
    private final boolean restrictionDataUnavailable;

    private InterceptVerdict(Verdict verdict, DenyReason denyReason, ZoneInfo zoneInfo) {
        this(verdict, denyReason, zoneInfo, null, false);
    }

    private InterceptVerdict(Verdict verdict, DenyReason denyReason, ZoneInfo zoneInfo,
                              RestrictionInfo restrictionInfo, boolean restrictionDataUnavailable) {
        this.verdict = verdict;
        this.denyReason = denyReason;
        this.zoneInfo = zoneInfo;
        this.restrictionInfo = restrictionInfo;
        this.restrictionDataUnavailable = restrictionDataUnavailable;
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
     * 创建"限飞区数据不可用、但按 fail-open 放行"的判决。
     *
     * <p><b>为什么单独一种判决</b>：限飞区数据源今天还没接上真实数据（HTTP 源未实现），
     * 默认 fail-closed 会让**所有命令都发不出去**；于是默认取 fail-open，
     * 但**放行不等于"检查过"** —— 这个状态必须能被调用方看见（{@link #isRestrictionDataUnavailable()}），
     * 否则就是"没查过却装作查过了"。
     */
    public static InterceptVerdict allowWithoutRestrictionData() {
        return new InterceptVerdict(Verdict.ALLOW, null, null, null, true);
    }

    /**
     * 创建"命中限飞区"的拒绝判决。
     *
     * @param info 命中的限飞区信息
     */
    public static InterceptVerdict denyInRestriction(RestrictionInfo info) {
        return new InterceptVerdict(Verdict.DENY, DenyReason.IN_RESTRICTION_ZONE, null, info, false);
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

    /** 命中的限飞区信息（数据源口径；未命中为 null）。 */
    public RestrictionInfo getRestrictionInfo() { return restrictionInfo; }

    /**
     * 限飞区数据是否不可用（数据源没配 / 加载失败 / 缓存过期）。
     *
     * <p>ALLOW 时这个标志为 true 表示"**放行不是因为检查过，而是因为没数据可查**"。
     */
    public boolean isRestrictionDataUnavailable() { return restrictionDataUnavailable; }

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
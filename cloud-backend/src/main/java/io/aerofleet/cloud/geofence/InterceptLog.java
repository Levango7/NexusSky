package io.aerofleet.cloud.geofence;

/**
 * 拦截日志：不可变值对象，记录一次拦截校验的完整信息。
 * <p>
 * 由 {@link InterceptLogStore#record} 创建并存储，用于审计和排查。
 * <p>
 * 不可变值对象；线程安全通过不可变性保证。
 *
 * @see InterceptLogStore
 * @see InterceptVerdict
 */
public final class InterceptLog {

    private final int sysid;
    private final String command;
    private final double lat;
    private final double lon;
    private final InterceptVerdict.Verdict verdict;
    private final InterceptVerdict.DenyReason reason;
    /** 围栏区域信息的 JSON 表示（ALLOW 时为 null）。 */
    private final String zoneInfo;
    private final long timestampMs;

    /**
     * 创建拦截日志。
     *
     * @param sysid       无人机 systemId
     * @param command     命令名称（如 "ARM"、"TAKEOFF"）
     * @param lat         无人机纬度（NaN 表示位置未知）
     * @param lon         无人机经度（NaN 表示位置未知）
     * @param verdict     判决结果（ALLOW / DENY）
     * @param reason      拒绝原因（仅 DENY 时有意义，ALLOW 时为 null）
     * @param zoneInfo    围栏区域信息 JSON（ALLOW 时为 null）
     * @param timestampMs 时间戳（epoch ms）
     */
    public InterceptLog(int sysid, String command, double lat, double lon,
                        InterceptVerdict.Verdict verdict,
                        InterceptVerdict.DenyReason reason,
                        String zoneInfo, long timestampMs) {
        this.sysid = sysid;
        this.command = command;
        this.lat = lat;
        this.lon = lon;
        this.verdict = verdict;
        this.reason = reason;
        this.zoneInfo = zoneInfo;
        this.timestampMs = timestampMs;
    }

    /**
     * 从 {@link InterceptVerdict} 创建拦截日志。
     *
     * @param sysid   无人机 systemId
     * @param command 命令名称
     * @param lat     纬度
     * @param lon     经度
     * @param verdict 拦截判决结果
     * @return 拦截日志实例
     */
    public static InterceptLog fromVerdict(int sysid, String command,
                                           double lat, double lon,
                                           InterceptVerdict verdict) {
        String zoneInfoJson = null;
        if (verdict.getZoneInfo() != null) {
            InterceptVerdict.ZoneInfo zi = verdict.getZoneInfo();
            zoneInfoJson = "{\"zoneId\":" + zi.zoneId()
                    + ",\"name\":\"" + zi.name() + "\""
                    + ",\"fenceType\":\"" + zi.fenceType() + "\"}";
        }
        return new InterceptLog(sysid, command, lat, lon,
                verdict.getVerdict(), verdict.getDenyReason(),
                zoneInfoJson, System.currentTimeMillis());
    }

    public int getSysid() { return sysid; }
    public String getCommand() { return command; }
    public double getLat() { return lat; }
    public double getLon() { return lon; }
    public InterceptVerdict.Verdict getVerdict() { return verdict; }
    public InterceptVerdict.DenyReason getReason() { return reason; }
    public String getZoneInfo() { return zoneInfo; }
    public long getTimestampMs() { return timestampMs; }

    @Override
    public String toString() {
        return "InterceptLog{sysid=" + sysid
                + ", command='" + command + "'"
                + ", lat=" + lat + ", lon=" + lon
                + ", verdict=" + verdict
                + (reason != null ? ", reason=" + reason : "")
                + (zoneInfo != null ? ", zoneInfo=" + zoneInfo : "")
                + ", ts=" + timestampMs + "}";
    }
}
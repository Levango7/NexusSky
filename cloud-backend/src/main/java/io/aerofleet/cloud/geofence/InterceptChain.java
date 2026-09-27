package io.aerofleet.cloud.geofence;

/**
 * 拦截链接口：在无人机执行关键命令（ARM/TAKEOFF）前进行围栏拦截校验。
 * <p>
 * 实现方（如 {@code GeofenceInterceptService}）根据无人机当前位置与围栏/限飞区数据，
 * 判定命令是否允许执行。拦截链设计为可扩展（FR-11），未来可插入更多拦截规则。
 * <p>
 * 故障安全原则（FR-10）：当拦截校验过程中发生异常时，实现方应返回 {@link InterceptVerdict.Verdict#DENY DENY}
 * 而非放行。
 *
 * @see InterceptVerdict
 */
public interface InterceptChain {

    /**
     * 执行拦截校验。
     *
     * @param sysid   无人机 systemId
     * @param command 命令名称（如 "ARM"、"TAKEOFF"）
     * @param lat     无人机当前纬度（NaN 表示位置未知）
     * @param lon     无人机当前经度（NaN 表示位置未知）
     * @return 拦截判决结果：{@link InterceptVerdict.Verdict#ALLOW ALLOW} 放行，
     *         {@link InterceptVerdict.Verdict#DENY DENY} 拦截
     */
    InterceptVerdict check(int sysid, String command, double lat, double lon);
}
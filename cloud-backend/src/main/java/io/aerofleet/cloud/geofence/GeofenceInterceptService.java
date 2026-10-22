package io.aerofleet.cloud.geofence;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 围栏拦截服务：在无人机执行关键命令（ARM/TAKEOFF）前进行围栏拦截校验。
 * <p>
 * 实现 {@link InterceptChain} 接口，根据无人机当前位置与围栏区域数据，
 * 判定命令是否允许执行。
 * <p>
 * 拦截逻辑：
 * <ul>
 *   <li>位置未知（lat/lon 为 NaN）→ {@link InterceptVerdict.DenyReason#POSITION_UNKNOWN DENY}</li>
 *   <li>KEEP_OUT 围栏 + 无人机在围栏内 → {@link InterceptVerdict.DenyReason#IN_KEEP_OUT_ZONE DENY}</li>
 *   <li>KEEP_IN 围栏 + 无人机在围栏外 → {@link InterceptVerdict.DenyReason#OUTSIDE_KEEP_IN_ZONE DENY}</li>
 *   <li><b>限飞区（数据源口径）+ 无人机在限飞区内 →
 *       {@link InterceptVerdict.DenyReason#IN_RESTRICTION_ZONE DENY}</b></li>
 *   <li>限飞区数据不可用 → 按 {@code aerofleet.geofence.restriction.fail-closed} 决定
 *       DENY({@link InterceptVerdict.DenyReason#NO_RESTRICTION_DATA}) 或
 *       ALLOW + {@link InterceptVerdict#isRestrictionDataUnavailable()} 标记</li>
 *   <li>无命中 → {@link InterceptVerdict.Verdict#ALLOW ALLOW}</li>
 *   <li>异常 → {@link InterceptVerdict.DenyReason#INTERCEPT_ERROR DENY}（故障安全原则 FR-10）</li>
 * </ul>
 *
 * <p><b>为什么 2026-10-21 之前限飞区不参与拦截</b>：限飞区数据（{@link RestrictionCacheManager}）
 * 早就有了（本地文件源可用），但拦截链只遍历**用户自建围栏**
 * （{@link GeofenceStore#getAllZones()}）⇒ <b>平台可以指挥无人机飞进民航禁飞区</b>。
 * 更糟的是当时有个测试叫 {@code testRestrictionZoneHit}，声称验证的是
 * "限飞区缓存中的限飞区命中"，实际却把区域加进了**用户围栏表** ——
 * <b>限飞区缓存从头到尾没被碰过</b>（测试名字在骗人）。
 *
 * @see InterceptChain
 * @see InterceptVerdict
 */
@Service
public class GeofenceInterceptService implements InterceptChain {

    private static final Logger log = LoggerFactory.getLogger(GeofenceInterceptService.class);

    private final GeofenceStore store;
    private final GeofenceMonitor monitor;
    private final DeviceRegistry registry;
    /** 限飞区数据缓存；为 null 表示未装配（等同"无数据"）。 */
    private final RestrictionCacheManager restrictionCache;
    /** 是否让限飞区参与拦截（可整体关掉，默认开）。 */
    private final boolean restrictionEnabled;
    /** 限飞区数据不可用时是否 fail-closed（默认 fail-open）。 */
    private final boolean restrictionFailClosed;

    @Autowired
    public GeofenceInterceptService(GeofenceStore store,
                                    GeofenceMonitor monitor,
                                    DeviceRegistry registry,
                                    RestrictionCacheManager restrictionCache,
                                    @Value("${aerofleet.geofence.restriction.intercept-enabled:true}") boolean restrictionEnabled,
                                    @Value("${aerofleet.geofence.restriction.intercept-fail-closed:false}") boolean restrictionFailClosed) {
        this.store = store;
        this.monitor = monitor;
        this.registry = registry;
        this.restrictionCache = restrictionCache;
        this.restrictionEnabled = restrictionEnabled;
        this.restrictionFailClosed = restrictionFailClosed;
    }

    /** 便捷构造：没有限飞区缓存（等同"数据不可用"）。 */
    public GeofenceInterceptService(GeofenceStore store,
                                    GeofenceMonitor monitor,
                                    DeviceRegistry registry) {
        this(store, monitor, registry, null, true, false);
    }

    @Override
    public InterceptVerdict check(int sysid, String command, double lat, double lon) {
        try {
            // 位置未知 → DENY(POSITION_UNKNOWN)
            if (Double.isNaN(lat) || Double.isNaN(lon)) {
                log.warn("Intercept DENY: sysid={} command={} reason=POSITION_UNKNOWN", sysid, command);
                return InterceptVerdict.deny(InterceptVerdict.DenyReason.POSITION_UNKNOWN, null);
            }

            // 遍历所有启用的围栏区域
            List<GeofenceZone> zones = store.getAllZones();
            for (GeofenceZone zone : zones) {
                if (!zone.isEnabled()) {
                    continue;
                }

                boolean inside = monitor.isInsideZone(lat, lon, zone);

                if (zone.getFenceType() == FenceType.KEEP_OUT && inside) {
                    // KEEP_OUT + 在禁飞区内 → DENY(IN_KEEP_OUT_ZONE)
                    InterceptVerdict.ZoneInfo zoneInfo = new InterceptVerdict.ZoneInfo(
                            zone.getId(), zone.getName(), zone.getFenceType());
                    log.warn("Intercept DENY: sysid={} command={} reason=IN_KEEP_OUT_ZONE zone={}",
                            sysid, command, zone.getName());
                    return InterceptVerdict.deny(InterceptVerdict.DenyReason.IN_KEEP_OUT_ZONE, zoneInfo);
                }

                if (zone.getFenceType() == FenceType.KEEP_IN && !inside) {
                    // KEEP_IN + 在允许区外 → DENY(OUTSIDE_KEEP_IN_ZONE)
                    InterceptVerdict.ZoneInfo zoneInfo = new InterceptVerdict.ZoneInfo(
                            zone.getId(), zone.getName(), zone.getFenceType());
                    log.warn("Intercept DENY: sysid={} command={} reason=OUTSIDE_KEEP_IN_ZONE zone={}",
                            sysid, command, zone.getName());
                    return InterceptVerdict.deny(InterceptVerdict.DenyReason.OUTSIDE_KEEP_IN_ZONE, zoneInfo);
                }
            }

            // 限飞区（数据源口径）：2026-10-21 才真正接进拦截链
            InterceptVerdict restrictionVerdict = checkRestrictionZones(sysid, command, lat, lon);
            if (restrictionVerdict != null) {
                return restrictionVerdict;
            }

            // 无命中 → ALLOW
            log.debug("Intercept ALLOW: sysid={} command={} lat={} lon={}", sysid, command, lat, lon);
            return InterceptVerdict.allow();

        } catch (Exception e) {
            // 异常 → DENY(INTERCEPT_ERROR)（故障安全原则）
            log.error("Intercept DENY: sysid={} command={} reason=INTERCEPT_ERROR err={}",
                    sysid, command, e.getMessage(), e);
            return InterceptVerdict.deny(InterceptVerdict.DenyReason.INTERCEPT_ERROR, null);
        }
    }

    /**
     * 限飞区判定。
     *
     * @return 需要立刻返回的判决（命中限飞区 / 数据不可用且 fail-closed）；否则 null 表示"继续往下走"
     */
    private InterceptVerdict checkRestrictionZones(int sysid, String command, double lat, double lon) {
        if (!restrictionEnabled) {
            log.debug("Intercept: 限飞区拦截已关闭（aerofleet.geofence.restriction.enabled=false）");
            return null;
        }

        List<RestrictionZone> zones = (restrictionCache == null)
                ? List.of()
                : restrictionCache.getRestrictionZones();

        if (zones.isEmpty()) {
            // 没有可用的限飞区数据 —— 姿态必须显式，不能"当作检查过"
            if (restrictionFailClosed) {
                log.warn("Intercept DENY: sysid={} command={} reason=NO_RESTRICTION_DATA"
                        + "（限飞区数据不可用且配置为 fail-closed）", sysid, command);
                return InterceptVerdict.deny(InterceptVerdict.DenyReason.NO_RESTRICTION_DATA, null);
            }
            log.warn("Intercept ALLOW（**未检查限飞区**）：sysid={} command={}"
                    + " 限飞区数据不可用（数据源未接/加载失败），当前为 fail-open；"
                    + "若要 fail-closed 请设 aerofleet.geofence.restriction.fail-closed=true", sysid, command);
            return InterceptVerdict.allowWithoutRestrictionData();
        }

        if (restrictionCache != null && restrictionCache.isStale()) {
            // 数据过期但还有内容：按最后一次成功加载的数据判定（过期的禁飞区仍然是禁飞区）
            log.warn("Intercept: 限飞区数据已过期，按最后一次成功加载的 {} 个区域判定", zones.size());
        }

        for (RestrictionZone zone : zones) {
            if (!isInsideRestriction(lat, lon, zone)) {
                continue;
            }
            log.warn("Intercept DENY: sysid={} command={} reason=IN_RESTRICTION_ZONE zone={} source={}",
                    sysid, command, zone.getName(), zone.getSource());
            return InterceptVerdict.denyInRestriction(new InterceptVerdict.RestrictionInfo(
                    zone.getZoneId(), zone.getName(), zone.getSource()));
        }
        return null;
    }

    /** 点是否在限飞区内（几何与用户围栏同口径，直接复用监控器的实现，不重复写一遍数学）。 */
    private boolean isInsideRestriction(double lat, double lon, RestrictionZone zone) {
        if (zone.getType() == GeofenceZone.Type.CIRCLE) {
            return monitor.isInsideCircle(lat, lon, zone.getCenterLat(), zone.getCenterLon(), zone.getRadiusM());
        }
        return monitor.isInsidePolygon(lat, lon, zone.getPoints());
    }
}

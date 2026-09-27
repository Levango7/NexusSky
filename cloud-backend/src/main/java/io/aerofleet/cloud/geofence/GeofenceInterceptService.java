package io.aerofleet.cloud.geofence;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 *   <li>无命中 → {@link InterceptVerdict.Verdict#ALLOW ALLOW}</li>
 *   <li>异常 → {@link InterceptVerdict.DenyReason#INTERCEPT_ERROR DENY}（故障安全原则 FR-10）</li>
 * </ul>
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

    public GeofenceInterceptService(GeofenceStore store,
                                     GeofenceMonitor monitor,
                                     DeviceRegistry registry) {
        this.store = store;
        this.monitor = monitor;
        this.registry = registry;
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
}
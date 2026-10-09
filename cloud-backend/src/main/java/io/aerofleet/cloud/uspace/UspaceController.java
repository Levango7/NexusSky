package io.aerofleet.cloud.uspace;

import io.aerofleet.cloud.gateway.DeviceRegistry;
import io.aerofleet.cloud.gateway.DroneSnapshot;
import io.aerofleet.cloud.geofence.RestrictionCacheManager;
import io.aerofleet.cloud.geofence.RestrictionZone;
import io.aerofleet.cloud.rid.RidStateManager;
import io.aerofleet.cloud.rid.model.RidSnapshot;
import io.aerofleet.cloud.security.RequireRole;
import io.aerofleet.cloud.security.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * U-space 四服务（E3，spec D2）——本仓以 USSP 服务接口形状实现：
 * <ul>
 *   <li>网络识别 net-rid：RID 快照按三套标准映射输出；</li>
 *   <li>地理感知 geo-awareness：限飞区数据源（C3）的 UAVolume 形状；</li>
 *   <li>飞行授权 flight-authorization：与限飞区求交的几何判定（三态）；</li>
 *   <li>交通信息 traffic：设备注册表指定半径机清单（统一脱敏）。</li>
 * </ul>
 * 与真实 U-space 网络互联（CISP 交换/EASA 认证）属生产阶段（诚实边界 spec §4）。
 */
@RestController
@RequestMapping("/api/v1/uspace")
public class UspaceController {

    private static final Logger log = LoggerFactory.getLogger(UspaceController.class);

    private final RidStateManager ridState;
    private final RestrictionCacheManager restrictionCache;
    private final DeviceRegistry registry;

    public UspaceController(RidStateManager ridState,
                            RestrictionCacheManager restrictionCache,
                            DeviceRegistry registry) {
        this.ridState = ridState;
        this.restrictionCache = restrictionCache;
        this.registry = registry;
    }

    // ------------------------------------------------------------------
    // D2-1 网络识别
    // ------------------------------------------------------------------

    @GetMapping("/net-rid")
    @RequireRole(Role.OBSERVER)
    public ResponseEntity<Map<String, Object>> netRid(
            @RequestParam(value = "format", defaultValue = "astm") String format) {
        if (!format.equals("gb46750") && !format.equals("astm") && !format.equals("eu")) {
            return ResponseEntity.badRequest().body(err(
                    "format must be gb46750|astm|eu: " + format));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (RidSnapshot s : ridState.getAll()) {
            out.add(RemoteIdMapper.map(s, format));
        }
        return ResponseEntity.ok(Map.of(
                "format", format,
                "count", out.size(),
                "uasCount", out.size(),
                "snapshots", out));
    }

    // ------------------------------------------------------------------
    // D2-2 地理感知
    // ------------------------------------------------------------------

    @GetMapping("/geo-awareness")
    @RequireRole(Role.OBSERVER)
    public ResponseEntity<Map<String, Object>> geoAwareness(
            @RequestParam("lat") double lat,
            @RequestParam("lon") double lon,
            @RequestParam(value = "radiusM", defaultValue = "5000") double radiusM) {
        if (lat < -90 || lat > 90 || lon < -180 || lon > 180) {
            return ResponseEntity.badRequest().body(err("lat/lon out of range"));
        }
        List<Map<String, Object>> volumes = new ArrayList<>();
        for (RestrictionZone z : restrictionCache.getRestrictionZones()) {
            double dist = distanceToZoneM(z, lat, lon);
            if (dist <= radiusM) {
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("id", z.getZoneId());
                v.put("name", z.getName());
                v.put("type", z.getType().name());
                v.put("distanceM", Math.round(dist));
                if (z.getType() == io.aerofleet.cloud.geofence.GeofenceZone.Type.CIRCLE) {
                    v.put("centerLat", z.getCenterLat());
                    v.put("centerLon", z.getCenterLon());
                    v.put("radiusM", z.getRadiusM());
                } else {
                    v.put("points", z.getPoints().stream()
                            .map(p -> List.of(p.lat(), p.lon())).toList());
                }
                volumes.add(v);
            }
        }
        return ResponseEntity.ok(Map.of(
                "query", Map.of("lat", lat, "lon", lon, "radiusM", radiusM),
                "volumes", volumes));
    }

    // ------------------------------------------------------------------
    // D2-3 飞行授权（三态）
    // ------------------------------------------------------------------

    @PostMapping("/flight-authorization")
    @RequireRole(Role.OPERATOR)
    public ResponseEntity<Map<String, Object>> flightAuthorization(
            @RequestBody Map<String, Object> body) {
        Object latO = body.get("lat");
        Object lonO = body.get("lon");
        if (!(latO instanceof Number latN) || !(lonO instanceof Number lonN)) {
            return ResponseEntity.badRequest().body(err("missing fields: lat, lon"));
        }
        double lat = latN.doubleValue();
        double lon = lonN.doubleValue();
        if (lat < -90 || lat > 90 || lon < -180 || lon > 180) {
            return ResponseEntity.badRequest().body(err("lat/lon out of range"));
        }
        String uasId = body.get("uasId") instanceof String s ? s : "";

        // 与限飞区求交（本仓限飞区恒为 KEEP_OUT/禁飞语义）：
        // 未相交=AUTHORIZED；相交=DENIED（附区名）。三态之 CONDITIONAL 留给
        // 未来"限制区（可申请）"语义——当前数据源只有禁飞区，不硬造中间态。
        List<String> hitZones = new ArrayList<>();
        for (RestrictionZone z : restrictionCache.getRestrictionZones()) {
            if (containsPoint(z, lat, lon)) {
                hitZones.add(z.getName());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("uasId", uasId);
        out.put("query", Map.of("lat", lat, "lon", lon));
        if (hitZones.isEmpty()) {
            out.put("decision", "AUTHORIZED");
            out.put("volumes", List.of());
            log.info("U-space authorization AUTHORIZED uasId={} ({},{})", uasId, lat, lon);
            return ResponseEntity.ok(out);
        }
        out.put("decision", "DENIED");
        out.put("reason", "intersects restricted zone(s): " + hitZones);
        out.put("volumes", hitZones);
        log.info("U-space authorization DENIED uasId={} zones={}", uasId, hitZones);
        return ResponseEntity.ok(out);
    }

    // ------------------------------------------------------------------
    // D2-4 交通信息
    // ------------------------------------------------------------------

    @GetMapping("/traffic")
    @RequireRole(Role.OBSERVER)
    public ResponseEntity<Map<String, Object>> traffic(
            @RequestParam("lat") double lat,
            @RequestParam("lon") double lon,
            @RequestParam(value = "radiusM", defaultValue = "5000") double radiusM) {
        if (lat < -90 || lat > 90 || lon < -180 || lon > 180) {
            return ResponseEntity.badRequest().body(err("lat/lon out of range"));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (DroneSnapshot s : registry.all()) {
            if (!s.online || Double.isNaN(s.lat) || Double.isNaN(s.lon)) {
                continue;
            }
            double d = haversineM(lat, lon, s.lat, s.lon);
            if (d > radiusM) {
                continue;
            }
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("sysid", s.sysid);
            t.put("lat", s.lat);
            t.put("lon", s.lon);
            t.put("altM", Double.isNaN(s.relativeAlt) ? null : s.relativeAlt);
            t.put("headingDeg", Double.isNaN(s.heading) ? null : s.heading);
            t.put("speedMps", Double.isNaN(s.groundspeed) ? null : s.groundspeed);
            t.put("distanceM", Math.round(d));
            // 不暴露 operatorId（跨标准统一脱敏口径与 RidController 一致）
            out.add(t);
        }
        out.sort((a, b) -> Double.compare((Double) a.get("distanceM"), (Double) b.get("distanceM")));
        return ResponseEntity.ok(Map.of(
                "query", Map.of("lat", lat, "lon", lon, "radiusM", radiusM),
                "count", out.size(),
                "traffic", out));
    }

    // ------------------------------------------------------------------
    // 几何
    // ------------------------------------------------------------------

    /** 点到限飞区边缘距离（圆：环距；多边形：顶点点集最小距——转场判定用，非精确内距）。 */
    static double distanceToZoneM(RestrictionZone z, double lat, double lon) {
        if (z.getType() == io.aerofleet.cloud.geofence.GeofenceZone.Type.CIRCLE) {
            double centerDist = haversineM(lat, lon, z.getCenterLat(), z.getCenterLon());
            return Math.max(0, centerDist - z.getRadiusM());
        }
        double min = Double.MAX_VALUE;
        for (var p : z.getPoints()) {
            min = Math.min(min, haversineM(lat, lon, p.lat(), p.lon()));
        }
        return min == Double.MAX_VALUE ? Double.MAX_VALUE : min;
    }

    /** 点是否在区内（圆：半径内；多边形：射线法）。 */
    static boolean containsPoint(RestrictionZone z, double lat, double lon) {
        if (z.getType() == io.aerofleet.cloud.geofence.GeofenceZone.Type.CIRCLE) {
            return haversineM(lat, lon, z.getCenterLat(), z.getCenterLon()) <= z.getRadiusM();
        }
        // 射线法（经度作 x、纬度作 y；小尺度区域足够）
        List<io.aerofleet.cloud.geofence.GeofenceZone.GeoPoint> pts = z.getPoints();
        boolean inside = false;
        for (int i = 0, j = pts.size() - 1; i < pts.size(); j = i++) {
            double xi = pts.get(i).lon();
            double yi = pts.get(i).lat();
            double xj = pts.get(j).lon();
            double yj = pts.get(j).lat();
            if (((yi > lat) != (yj > lat)) && (lon < (xj - xi) * (lat - yi) / (yj - yi) + xi)) {
                inside = !inside;
            }
        }
        return inside;
    }

    static double haversineM(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 6_371_000.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private static Map<String, Object> err(String message) {
        return Map.of("status", "error", "result", message);
    }
}

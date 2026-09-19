package io.aerofleet.cloud.geofence;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.aerofleet.cloud.api.ApiExceptionHandler.BadRequestException;
import static io.aerofleet.cloud.api.ApiExceptionHandler.NotFoundException;

/**
 * 电子围栏 REST API：围栏区域 CRUD + 越界历史查询 + 手动检查。
 * <p>
 * 端点清单：
 * <pre>
 * POST   /api/geofence/zones        创建围栏区域
 * GET    /api/geofence/zones        列出所有围栏
 * GET    /api/geofence/zones/{id}   获取单个围栏
 * PUT    /api/geofence/zones/{id}   更新围栏
 * DELETE /api/geofence/zones/{id}   删除围栏
 * GET    /api/geofence/breaches     获取越界历史（支持 sysid/zoneId 过滤）
 * POST   /api/geofence/check        手动触发一次全量检查
 * </pre>
 * <p>
 * 围栏 JSON 格式：
 * <pre>
 * 圆形：{"id":1,"name":"base","type":"CIRCLE","centerLat":22.5,"centerLon":113.9,"radiusM":500,"action":"WARN"}
 * 多边形：{"id":2,"name":"area","type":"POLYGON","points":[{"lat":22.0,"lon":113.0},...],"action":"LOCK_RTH"}
 * </pre>
 */
@RestController
@RequestMapping("/api/geofence")
@Tag(name = "Geofence", description = "电子围栏 REST API：围栏区域 CRUD、越界历史查询、手动检查")
public class GeofenceController {

    private static final Logger log = LoggerFactory.getLogger(GeofenceController.class);

    private final GeofenceStore store;
    private final GeofenceMonitor monitor;

    public GeofenceController(GeofenceStore store, GeofenceMonitor monitor) {
        this.store = store;
        this.monitor = monitor;
    }

    // =====================================================================
    // 围栏区域 CRUD
    // =====================================================================

    @Operation(summary = "创建围栏区域", description = "支持圆形（CIRCLE）和多边形（POLYGON）两种类型")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "创建成功"),
        @ApiResponse(responseCode = "400", description = "请求体格式错误")
    })
    @PostMapping("/zones")
    public ResponseEntity<Map<String, Object>> createZone(@RequestBody Map<String, Object> body) {
        GeofenceZone zone = parseZone(body);
        store.addZone(zone);
        log.info("Geofence zone created via API: id={} name='{}'", zone.getId(), zone.getName());
        return ResponseEntity.ok(zoneToMap(zone));
    }

    @Operation(summary = "列出所有围栏区域")
    @ApiResponse(responseCode = "200", description = "围栏列表")
    @GetMapping("/zones")
    public ResponseEntity<Map<String, Object>> listZones() {
        List<GeofenceZone> zones = store.getAllZones();
        List<Map<String, Object>> items = new ArrayList<>();
        for (GeofenceZone z : zones) {
            items.add(zoneToMap(z));
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("items", items);
        resp.put("total", items.size());
        return ResponseEntity.ok(resp);
    }

    @Operation(summary = "获取单个围栏区域")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "围栏详情"),
        @ApiResponse(responseCode = "404", description = "围栏不存在")
    })
    @GetMapping("/zones/{id}")
    public ResponseEntity<Map<String, Object>> getZone(@PathVariable("id") int id) {
        GeofenceZone zone = store.getZone(id);
        if (zone == null) {
            throw new NotFoundException("geofence zone not found: " + id);
        }
        return ResponseEntity.ok(zoneToMap(zone));
    }

    @Operation(summary = "更新围栏区域", description = "更新指定 ID 的围栏区域属性")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "更新成功"),
        @ApiResponse(responseCode = "404", description = "围栏不存在"),
        @ApiResponse(responseCode = "400", description = "请求体格式错误")
    })
    @PutMapping("/zones/{id}")
    public ResponseEntity<Map<String, Object>> updateZone(@PathVariable("id") int id,
                                                          @RequestBody Map<String, Object> body) {
        if (store.getZone(id) == null) {
            throw new NotFoundException("geofence zone not found: " + id);
        }
        // 强制 body 中的 id 与路径一致
        body.put("id", id);
        GeofenceZone zone = parseZone(body);
        store.updateZone(zone);
        log.info("Geofence zone updated via API: id={} name='{}'", zone.getId(), zone.getName());
        return ResponseEntity.ok(zoneToMap(zone));
    }

    @Operation(summary = "删除围栏区域")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "删除成功"),
        @ApiResponse(responseCode = "404", description = "围栏不存在")
    })
    @DeleteMapping("/zones/{id}")
    public ResponseEntity<Map<String, Object>> deleteZone(@PathVariable("id") int id) {
        GeofenceZone removed = store.removeZone(id);
        if (removed == null) {
            throw new NotFoundException("geofence zone not found: " + id);
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("deleted", true);
        resp.put("id", id);
        return ResponseEntity.ok(resp);
    }

    // =====================================================================
    // 越界历史
    // =====================================================================

    @Operation(summary = "获取越界历史", description = "支持按 sysid（无人机）和 zoneId（围栏）过滤")
    @ApiResponse(responseCode = "200", description = "越界事件列表")
    @GetMapping("/breaches")
    public ResponseEntity<Map<String, Object>> getBreaches(
            @RequestParam(value = "sysid", required = false) Integer sysid,
            @RequestParam(value = "zoneId", required = false) Integer zoneId) {
        List<GeofenceBreachEvent> events;
        if (sysid != null && zoneId != null) {
            // 同时过滤：先按无人机，再按围栏
            events = new ArrayList<>();
            for (GeofenceBreachEvent e : store.getBreachesForDrone(sysid)) {
                if (e.getZoneId() == zoneId) {
                    events.add(e);
                }
            }
        } else if (sysid != null) {
            events = store.getBreachesForDrone(sysid);
        } else if (zoneId != null) {
            events = store.getBreachesForZone(zoneId);
        } else {
            events = store.getBreachHistory();
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (GeofenceBreachEvent e : events) {
            items.add(breachToMap(e));
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("items", items);
        resp.put("total", items.size());
        return ResponseEntity.ok(resp);
    }

    // =====================================================================
    // 手动检查
    // =====================================================================

    @Operation(summary = "手动触发一次全量围栏检查", description = "检查所有在线无人机位置是否越界，返回新生成的越界事件")
    @ApiResponse(responseCode = "200", description = "检查结果（新生成的越界事件列表）")
    @PostMapping("/check")
    public ResponseEntity<Map<String, Object>> manualCheck() {
        List<GeofenceBreachEvent> events = monitor.checkAllDrones();
        List<Map<String, Object>> items = new ArrayList<>();
        for (GeofenceBreachEvent e : events) {
            items.add(breachToMap(e));
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("newEvents", items);
        resp.put("count", items.size());
        resp.put("timestamp", System.currentTimeMillis());
        log.info("Manual geofence check: {} new event(s)", items.size());
        return ResponseEntity.ok(resp);
    }

    // =====================================================================
    // JSON 解析 / 序列化
    // =====================================================================

    /** 从请求 body 解析围栏区域。 */
    private GeofenceZone parseZone(Map<String, Object> body) {
        Object idObj = body.get("id");
        if (idObj == null) {
            throw new BadRequestException("field 'id' is required");
        }
        int id = toInt(idObj);
        String name = toStr(body.get("name"));
        if (name == null || name.isEmpty()) {
            throw new BadRequestException("field 'name' is required");
        }
        String typeStr = toStr(body.get("type"));
        if (typeStr == null) {
            throw new BadRequestException("field 'type' is required (CIRCLE or POLYGON)");
        }
        GeofenceZone.Action action = parseAction(body.get("action"));
        boolean enabled = body.containsKey("enabled") ? toBool(body.get("enabled")) : true;

        GeofenceZone zone;
        if ("CIRCLE".equalsIgnoreCase(typeStr)) {
            double centerLat = toDouble(body.get("centerLat"), "centerLat");
            double centerLon = toDouble(body.get("centerLon"), "centerLon");
            double radiusM = toDouble(body.get("radiusM"), "radiusM");
            zone = GeofenceZone.circleZone(id, name, centerLat, centerLon, radiusM, action);
        } else if ("POLYGON".equalsIgnoreCase(typeStr)) {
            Object pointsObj = body.get("points");
            if (!(pointsObj instanceof List)) {
                throw new BadRequestException("field 'points' must be a list of {lat, lon} for POLYGON");
            }
            @SuppressWarnings("unchecked")
            List<Object> rawPoints = (List<Object>) pointsObj;
            if (rawPoints.size() < 3) {
                throw new BadRequestException("polygon requires at least 3 points, got " + rawPoints.size());
            }
            List<GeofenceZone.GeoPoint> points = new ArrayList<>();
            for (Object p : rawPoints) {
                if (!(p instanceof Map)) {
                    throw new BadRequestException("each point must be {lat, lon}");
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> pm = (Map<String, Object>) p;
                double plat = toDouble(pm.get("lat"), "lat");
                double plon = toDouble(pm.get("lon"), "lon");
                points.add(new GeofenceZone.GeoPoint(plat, plon));
            }
            zone = GeofenceZone.polygonZone(id, name, points, action);
        } else {
            throw new BadRequestException("field 'type' must be CIRCLE or POLYGON, got " + typeStr);
        }
        return enabled ? zone : zone.withEnabled(false);
    }

    private GeofenceZone.Action parseAction(Object obj) {
        if (obj == null) {
            return GeofenceZone.Action.WARN;
        }
        String s = String.valueOf(obj);
        if ("LOCK_RTH".equalsIgnoreCase(s)) {
            return GeofenceZone.Action.LOCK_RTH;
        }
        if ("WARN".equalsIgnoreCase(s)) {
            return GeofenceZone.Action.WARN;
        }
        throw new BadRequestException("field 'action' must be WARN or LOCK_RTH, got " + s);
    }

    /** 围栏区域 → JSON Map。 */
    private static Map<String, Object> zoneToMap(GeofenceZone z) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", z.getId());
        m.put("name", z.getName());
        m.put("type", z.getType().name());
        if (z.getType() == GeofenceZone.Type.CIRCLE) {
            m.put("centerLat", z.getCenterLat());
            m.put("centerLon", z.getCenterLon());
            m.put("radiusM", z.getRadiusM());
        } else {
            List<Map<String, Object>> pts = new ArrayList<>();
            for (GeofenceZone.GeoPoint p : z.getPoints()) {
                Map<String, Object> pm = new LinkedHashMap<>();
                pm.put("lat", p.lat());
                pm.put("lon", p.lon());
                pts.add(pm);
            }
            m.put("points", pts);
        }
        m.put("action", z.getAction().name());
        m.put("enabled", z.isEnabled());
        m.put("createdAtMs", z.getCreatedAtMs());
        return m;
    }

    /** 越界事件 → JSON Map。 */
    private static Map<String, Object> breachToMap(GeofenceBreachEvent e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sysid", e.getSysid());
        m.put("zoneId", e.getZoneId());
        m.put("zoneName", e.getZoneName());
        m.put("breachType", e.getBreachType().name());
        m.put("lat", e.getLat());
        m.put("lon", e.getLon());
        m.put("timestampMs", e.getTimestampMs());
        return m;
    }

    // ------------------------------------------------------------------
    // 类型转换辅助
    // ------------------------------------------------------------------

    private static int toInt(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        return Integer.parseInt(String.valueOf(o));
    }

    private static String toStr(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static boolean toBool(Object o) {
        if (o instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(String.valueOf(o));
    }

    private static double toDouble(Object o, String fieldName) {
        if (o == null) {
            throw new BadRequestException("field '" + fieldName + "' is required");
        }
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        return Double.parseDouble(String.valueOf(o));
    }
}
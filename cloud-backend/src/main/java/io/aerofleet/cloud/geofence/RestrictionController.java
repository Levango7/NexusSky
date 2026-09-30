package io.aerofleet.cloud.geofence;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import io.aerofleet.cloud.security.RequireRole;
import io.aerofleet.cloud.security.Role;

/**
 * 限飞区 REST API：限飞区列表查询、缓存状态、手动刷新、拦截日志查询。
 * <p>
 * 端点清单：
 * <pre>
 * GET   /api/v1/geofence/restriction/zones    获取缓存的限飞区列表
 * GET   /api/v1/geofence/restriction/status   获取缓存状态
 * POST  /api/v1/geofence/restriction/refresh  手动刷新限飞区缓存
 * GET   /api/v1/geofence/intercept/logs       获取拦截日志列表（支持 sysid 过滤）
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/geofence")
@Tag(name = "Restriction", description = "限飞区 REST API：限飞区列表、缓存状态、手动刷新、拦截日志查询")
@RequireRole(Role.OBSERVER)
public class RestrictionController {

    private static final Logger log = LoggerFactory.getLogger(RestrictionController.class);

    private final RestrictionCacheManager cacheManager;
    private final InterceptLogStore logStore;

    public RestrictionController(RestrictionCacheManager cacheManager, InterceptLogStore logStore) {
        this.cacheManager = cacheManager;
        this.logStore = logStore;
    }

    // =====================================================================
    // 限飞区列表
    // =====================================================================

    @Operation(summary = "获取限飞区列表", description = "返回当前缓存的限飞区列表，包含 zoneId/name/type/几何数据/source/fetchedAtMs")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "限飞区列表")
    })
    @GetMapping("/restriction/zones")
    public ResponseEntity<Map<String, Object>> getRestrictionZones() {
        List<RestrictionZone> zones = cacheManager.getRestrictionZones();
        List<Map<String, Object>> items = new ArrayList<>();
        for (RestrictionZone z : zones) {
            items.add(restrictionZoneToMap(z));
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("items", items);
        resp.put("total", items.size());
        resp.put("stale", cacheManager.isStale());
        return ResponseEntity.ok(resp);
    }

    // =====================================================================
    // 缓存状态
    // =====================================================================

    @Operation(summary = "获取限飞区缓存状态", description = "返回缓存条数、最后刷新时间、数据源类型、是否 stale、连通状态")
    @ApiResponse(responseCode = "200", description = "缓存状态信息")
    @GetMapping("/restriction/status")
    public ResponseEntity<Map<String, Object>> getRestrictionStatus() {
        Map<String, Object> status = cacheManager.getStatus();
        // 新增连通状态字段：如果缓存非空且非 stale，则视为连通
        boolean connected = !cacheManager.isStale() && (int) status.getOrDefault("zoneCount", 0) > 0;
        status.put("connected", connected);
        return ResponseEntity.ok(status);
    }

    // =====================================================================
    // 手动刷新
    // =====================================================================

    @Operation(summary = "手动刷新限飞区缓存", description = "触发缓存立即刷新，返回刷新结果（成功/失败 + 条数 + 时间戳）")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "刷新结果")
    })
    @PostMapping("/restriction/refresh")
    @RequireRole(Role.OPERATOR)
    public ResponseEntity<Map<String, Object>> refreshRestrictionCache() {
        long beforeCount = cacheManager.getRestrictionZones().size();
        boolean wasStale = cacheManager.isStale();
        cacheManager.refresh();
        long afterCount = cacheManager.getRestrictionZones().size();
        boolean nowStale = cacheManager.isStale();

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", !nowStale);
        resp.put("zoneCount", afterCount);
        resp.put("previousCount", beforeCount);
        resp.put("wasStale", wasStale);
        resp.put("nowStale", nowStale);
        resp.put("timestamp", System.currentTimeMillis());

        if (!nowStale) {
            log.info("Restriction cache manually refreshed: {} zones", afterCount);
        } else {
            log.warn("Restriction cache manual refresh failed, cache may be stale");
        }

        return ResponseEntity.ok(resp);
    }

    // =====================================================================
    // 拦截日志查询
    // =====================================================================

    @Operation(summary = "获取拦截日志列表", description = "返回拦截日志列表，支持按 sysid（无人机）过滤")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "拦截日志列表")
    })
    @GetMapping("/intercept/logs")
    public ResponseEntity<Map<String, Object>> getInterceptLogs(
            @Parameter(description = "无人机 systemId 过滤") @RequestParam(value = "sysid", required = false) Integer sysid) {
        List<InterceptLog> logs;
        if (sysid != null) {
            logs = logStore.getLogsForDrone(sysid);
        } else {
            logs = logStore.getLogs();
        }

        List<Map<String, Object>> items = new ArrayList<>();
        for (InterceptLog entry : logs) {
            items.add(interceptLogToMap(entry));
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("items", items);
        resp.put("total", items.size());
        return ResponseEntity.ok(resp);
    }

    // =====================================================================
    // JSON 序列化辅助
    // =====================================================================

    /** 限飞区 → JSON Map。 */
    private static Map<String, Object> restrictionZoneToMap(RestrictionZone z) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("zoneId", z.getZoneId());
        m.put("name", z.getName());
        m.put("type", z.getType().name());
        m.put("fenceType", z.getFenceType().name());
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
        m.put("source", z.getSource());
        m.put("fetchedAtMs", z.getFetchedAtMs());
        return m;
    }

    /** 拦截日志 → JSON Map。 */
    private static Map<String, Object> interceptLogToMap(InterceptLog entry) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sysid", entry.getSysid());
        m.put("command", entry.getCommand());
        m.put("lat", entry.getLat());
        m.put("lon", entry.getLon());
        m.put("verdict", entry.getVerdict().name());
        if (entry.getReason() != null) {
            m.put("reason", entry.getReason().name());
        }
        if (entry.getZoneInfo() != null) {
            m.put("zoneInfo", entry.getZoneInfo());
        }
        m.put("timestampMs", entry.getTimestampMs());
        return m;
    }
}